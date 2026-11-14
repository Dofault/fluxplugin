package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.client.Camera;
import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.flux.api.client.Focus;
import fr.influencraft.flux.api.universe.Universes;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The class showcase ({@code /classe} in the rpg lobby): instead of pages of text, the player watches each class
 * play. He is taken to a small arena far from the lobby and sees it through a fixed camera (Flux's {@link Camera});
 * a panel drawn in front of that camera fills the left third of his screen, his mouse moves a pointer over it.
 * Pointing at a class makes an actor wearing his skin cast that class's real spells on monsters
 * ({@link CombatEngine#standIn}); a click opens the class (its spells, lit one by one as they are cast) and the
 * button that chooses it ({@link RpgPlayers#pick}, same rules as before).
 *
 * <p>One arena per viewer (a pool of {@code rpg.showcase.stages}, built once each, {@code spacing} blocks apart), so
 * nobody sees another one's show. Java clients only, in the lobby world only: {@link #open} is false otherwise and
 * the caller opens its usual menu.</p>
 */
final class Showcase implements Listener {

    /** Tag of everything the showcase spawns in the world (actor, monsters): never dropped loot, never saved. */
    static final String TAG = "fluxrpg_show";

    /** What the config says ({@code rpg.showcase}), read at each opening. */
    record Settings(boolean enabled, int stages, int spacing, int originX, int originY, int originZ,
                    double camX, double camY, double camZ, float camPitch, double actorX, double actorZ,
                    List<double[]> monsters, String monster, int level, long time,
                    double pointerSpeed, int hoverTicks, ConfigurationSection panel, ConfigurationSection scripts) {

        static Settings of(ConfigurationSection c) {
            List<double[]> spots = new ArrayList<>();
            for (Object o : c.getList("monsters", List.of())) {
                if (o instanceof List<?> l && l.size() >= 2 && l.get(0) instanceof Number x && l.get(1) instanceof Number z) {
                    spots.add(new double[]{x.doubleValue(), z.doubleValue()});
                }
            }
            if (spots.isEmpty()) spots.add(new double[]{-3, 0});
            return new Settings(c.getBoolean("enabled", true), Math.max(1, c.getInt("stages", 6)), Math.max(96, c.getInt("spacing", 320)),
                    c.getInt("origin.x", 4000), c.getInt("origin.y", 100), c.getInt("origin.z", 4000),
                    c.getDouble("camera.x", 2.25), c.getDouble("camera.y", 4.2), c.getDouble("camera.z", -9.5), (float) c.getDouble("camera.pitch", 20),
                    c.getDouble("actor.x", 4.5), c.getDouble("actor.z", 0), spots, c.getString("monster", "HUSK"),
                    Math.max(1, c.getInt("level", 1)), c.getLong("time", 14000), c.getDouble("pointer.speed", 0.02),
                    Math.max(0, c.getInt("hover-ticks", 5)), c.getConfigurationSection("panel"), c.getConfigurationSection("scripts"));
        }
    }

    private final FluxPlugin plugin;
    private final Supplier<RpgConfig.Set> config;
    private final RpgPlayers players;
    private final CombatEngine combat;
    private final Skills skills;
    private final Controls controls;
    private final RpgItems items;
    private final RpgWorld world;
    private final Map<UUID, ShowcaseSession> sessions = new HashMap<>();
    private final Map<Integer, UUID> stages = new HashMap<>();

    Showcase(FluxPlugin plugin, Supplier<RpgConfig.Set> config, RpgPlayers players, CombatEngine combat, Skills skills,
             Controls controls, RpgItems items, RpgWorld world) {
        this.plugin = plugin;
        this.config = config;
        this.players = players;
        this.combat = combat;
        this.skills = skills;
        this.controls = controls;
        this.items = items;
        this.world = world;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        // a viewer's screen is taken: no draw, no tournament bar over it
        Focus.register(plugin, p -> sessions.containsKey(p.getUniqueId()));
    }

    FluxPlugin plugin() {
        return plugin;
    }

    RpgConfig.Set set() {
        return config.get();
    }

    RpgPlayers players() {
        return players;
    }

    CombatEngine combat() {
        return combat;
    }

    Skills skills() {
        return skills;
    }

    RpgItems items() {
        return items;
    }

    boolean watching(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    /**
     * Takes the player to an arena and starts the show. False when it cannot be (switched off, Bedrock, not in the
     * lobby, every arena taken...): the caller opens its usual menu instead.
     */
    boolean open(Player p) {
        RpgConfig.Set set = config.get();
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("rpg.showcase");
        if (set == null || c == null || world == null || world.world() == null) return false;
        Settings s = Settings.of(c);
        if (!s.enabled() || !Camera.available() || Clients.bedrock(p)) return false;
        if (sessions.containsKey(p.getUniqueId())) return true;
        if (p.getWorld() != world.world() || Universes.transitioning(p) || p.isDead() || p.isInsideVehicle()) return false;
        if (RpgPlayers.visible(set).isEmpty()) return false;
        int stage = -1;
        for (int i = 0; i < s.stages(); i++) {
            if (!stages.containsKey(i)) {
                stage = i;
                break;
            }
        }
        if (stage < 0) return false;
        World w = world.world();
        int ox = s.originX() + stage * s.spacing(), oy = s.originY(), oz = s.originZ();
        build(w, ox, oy, oz);
        ShowcaseSession session = new ShowcaseSession(this, p, s, stage, new Location(w, ox + 0.5, oy + 1, oz + 0.5));
        stages.put(stage, p.getUniqueId());
        sessions.put(p.getUniqueId(), session);
        controls.take(p, true);
        session.open();
        return true;
    }

    /** Ends a player's show. {@code back}: he is brought back to where he stood in the lobby. */
    void close(Player p, boolean back) {
        ShowcaseSession s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        stages.remove(s.stage());
        controls.take(p, false);
        s.close(back);
    }

    void stop() {
        for (ShowcaseSession s : new ArrayList<>(sessions.values())) close(s.player(), true);
    }

    private void tick() {
        if (sessions.isEmpty()) return;
        for (ShowcaseSession s : new ArrayList<>(sessions.values())) {
            try {
                s.tick();
            } catch (RuntimeException e) {
                // a show must never trap a player: anything wrong ends it
                plugin.getLogger().warning("Class showcase of " + s.player().getName() + " ended on an error: " + e);
                close(s.player(), true);
            }
        }
    }

    // ------------------------------------------------------------------ the arena

    /**
     * A round floor of dark stone under the sky, lit from above (the viewer sees it at night), a few pillars with
     * lanterns at the back. Built once: the bedrock under its middle says it is there.
     */
    private static void build(World w, int ox, int oy, int oz) {
        if (w.getBlockAt(ox, oy - 1, oz).getType() == Material.BEDROCK) return;
        int radius = 17;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int d2 = x * x + z * z;
                if (d2 > radius * radius) continue;
                int h = Math.floorMod(x * 73856093 ^ z * 19349663, 20);
                Material m = d2 > (radius - 1) * (radius - 1) ? Material.POLISHED_BLACKSTONE
                        : h < 12 ? Material.POLISHED_DEEPSLATE : h < 17 ? Material.DEEPSLATE_TILES : Material.DEEPSLATE_BRICKS;
                w.getBlockAt(ox + x, oy, oz + z).setType(m, false);
                w.getBlockAt(ox + x, oy - 1, oz + z).setType(Material.DEEPSLATE, false);
                // light blocks above the floor: creatures and spells are seen clearly under a night sky
                if (Math.floorMod(x, 3) == 0 && Math.floorMod(z, 3) == 0) w.getBlockAt(ox + x, oy + 4, oz + z).setType(Material.LIGHT, false);
            }
        }
        for (int i = -2; i <= 2; i++) {
            int x = ox + i * 6, z = oz + 13 - Math.abs(i);
            int height = 3 + Math.floorMod(i * 7, 3);
            for (int y = 1; y <= height; y++) w.getBlockAt(x, oy + y, z).setType(y == height ? Material.CHISELED_DEEPSLATE : Material.DEEPSLATE_BRICKS, false);
            w.getBlockAt(x, oy + height + 1, z).setType(i % 2 == 0 ? Material.SOUL_LANTERN : Material.LANTERN, false);
        }
        w.getBlockAt(ox, oy - 1, oz).setType(Material.BEDROCK, false);
    }

    /** True inside the strip of the world the arenas are built in. */
    private boolean inArenas(Location l) {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("rpg.showcase");
        if (c == null || world == null || l.getWorld() != world.world()) return false;
        Settings s = Settings.of(c);
        return Math.abs(l.getZ() - s.originZ()) < 64 && l.getX() > s.originX() - 64 && l.getX() < s.originX() + (double) s.stages() * s.spacing();
    }

    // ------------------------------------------------------------------ what the viewer does

    /** Tick of each viewer's last dropped item: the A key swings the arm too, and is no click on the panel. */
    private final Map<UUID, Integer> dropped = new HashMap<>();

    /** A left click (his arm swings, wherever the camera is): a click on the panel. */
    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        ShowcaseSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        Integer drop = dropped.get(e.getPlayer().getUniqueId());
        if (drop != null && Bukkit.getCurrentTick() - drop <= 2) return;
        s.click();
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent e) {
        if (sessions.containsKey(e.getPlayer().getUniqueId())) dropped.put(e.getPlayer().getUniqueId(), Bukkit.getCurrentTick());
        else dropped.remove(e.getPlayer().getUniqueId());
    }

    /** Sneaking never takes him off his seat: it asks to leave the show. */
    @EventHandler(ignoreCancelled = true)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ShowcaseSession s = sessions.get(p.getUniqueId());
        if (s == null || s.closing() || e.getDismounted() != s.seat()) return;
        e.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sessions.get(p.getUniqueId()) == s) s.leave();
        });
    }

    /** Taken elsewhere by a command or another plugin: the show ends where he now is. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        ShowcaseSession s = sessions.get(p.getUniqueId());
        if (s == null || s.moving()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sessions.get(p.getUniqueId()) == s) close(p, false);
        });
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        close(e.getPlayer(), false);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        close(e.getEntity(), false);
    }

    /** Leaves from the arena: back in the lobby first, so that he comes back there. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        close(e.getPlayer(), true);
    }

    /** After a crash in the middle of a show he would wake up alone in an arena: back to the lobby's spawn. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !sessions.containsKey(p.getUniqueId()) && inArenas(p.getLocation())) {
                p.teleport(world.spawn(), PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        }, 10L);
    }

    /** The monsters of a show leave nothing behind. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onShowDeath(EntityDeathEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(TAG)) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
    }
}
