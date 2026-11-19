package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.client.Focus;
import fr.influencraft.flux.api.client.Hotbar;
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
 * The class showcase ({@code /classe} in the rpg lobby): instead of a menu, the player watches each class play. He is
 * taken to a small arena far from the lobby, seated on a seat that turns around the fight, and his hotbar holds one
 * class per slot ({@link Hotbar}): the slot he holds (mouse wheel, number keys, a tap on Bedrock) is the class an
 * actor wearing his skin plays on monsters, with its real spells ({@link CombatEngine#standIn}); a click chooses it
 * ({@link RpgPlayers#pick}, same rules as before). No pointer, no window: Java and Bedrock players use it alike.
 *
 * <p>One arena per viewer (a pool of {@code rpg.showcase.stages}, built once each, {@code spacing} blocks apart), so
 * nobody sees another one's show. In the lobby world only: {@link #open} is false otherwise and the caller opens its
 * usual menu.</p>
 */
final class Showcase implements Listener {

    /** Tag of everything the showcase spawns in the world (actor, monsters): never dropped loot, never saved. */
    static final String TAG = "fluxrpg_show";

    /** What the config says ({@code rpg.showcase}), read at each opening. */
    record Settings(boolean enabled, int stages, int spacing, int originX, int originY, int originZ,
                    double camDistance, double camHeight, double camArc, int camPeriod, double lookX, double lookY,
                    double actorX, double actorZ, List<double[]> monsters, String monster, int level, long time,
                    boolean hideBars, boolean hideHeld, double textScale, int captionShade, ConfigurationSection scripts) {

        static Settings of(ConfigurationSection c) {
            List<double[]> spots = new ArrayList<>();
            for (Object o : c.getList("monsters", List.of())) {
                if (o instanceof List<?> l && l.size() >= 2 && l.get(0) instanceof Number x && l.get(1) instanceof Number z) {
                    spots.add(new double[]{x.doubleValue(), z.doubleValue()});
                }
            }
            if (spots.isEmpty()) spots.add(new double[]{-3, 0});
            int shade;
            try {
                shade = (int) Long.parseLong(c.getString("texts.shade", "99000000").replace("#", ""), 16);
            } catch (NumberFormatException e) {
                shade = 0x99000000;
            }
            return new Settings(c.getBoolean("enabled", true), Math.max(1, c.getInt("stages", 6)), Math.max(96, c.getInt("spacing", 320)),
                    c.getInt("origin.x", 4000), c.getInt("origin.y", 100), c.getInt("origin.z", 4000),
                    Math.max(3, c.getDouble("camera.distance", 10)), c.getDouble("camera.height", 4.4),
                    Math.max(0, c.getDouble("camera.arc", 80)), (int) Math.round(c.getDouble("camera.period", 24) * 20),
                    c.getDouble("camera.look.x", 0.5), c.getDouble("camera.look.y", 1.2),
                    c.getDouble("actor.x", 4.5), c.getDouble("actor.z", 0), spots, c.getString("monster", "HUSK"),
                    Math.max(1, c.getInt("level", 1)), c.getLong("time", 14000), c.getBoolean("hide-bars", true),
                    c.getBoolean("hide-held-item", true), Math.max(0.2, c.getDouble("texts.scale", 1.0)), shade,
                    c.getConfigurationSection("scripts"));
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
     * Takes the player to an arena and starts the show. False when it cannot be (switched off, not in the lobby,
     * every arena taken...): the caller opens its usual menu instead.
     */
    boolean open(Player p) {
        RpgConfig.Set set = config.get();
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("rpg.showcase");
        if (set == null || c == null || world == null || world.world() == null) return false;
        Settings s = Settings.of(c);
        if (!s.enabled() || !Hotbar.available()) return false;
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
        dropped.remove(p.getUniqueId());
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

    /** Tick of each viewer's last press of his drop key: it swings the arm too, and is no click. */
    private final Map<UUID, Integer> dropped = new HashMap<>();

    /** His drop key was pressed (Flux held it back: nothing was dropped). */
    void dropped(Player p) {
        dropped.put(p.getUniqueId(), Bukkit.getCurrentTick());
    }

    /**
     * A left click (his arm swings): the class he holds is chosen. Read two ticks later: the drop key swings the arm
     * too, and Flux only tells it on the next tick.
     */
    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        Player p = e.getPlayer();
        ShowcaseSession s = sessions.get(p.getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (sessions.get(p.getUniqueId()) != s) return;
            Integer drop = dropped.get(p.getUniqueId());
            if (drop == null || Bukkit.getCurrentTick() - drop > 4) s.click();
        }, 2L);
    }

    /** A right click chooses too (the long press of a touch screen); the item he really holds is never used. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent e) {
        ShowcaseSession s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        boolean right = e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR || e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK;
        if (right && e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) s.click();
    }

    /**
     * The slot he holds is the class he watches. Past the last class the hotbar is empty: the wheel goes round to the
     * other end, a number key of an empty slot changes nothing.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onHeld(org.bukkit.event.player.PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        ShowcaseSession s = sessions.get(p.getUniqueId());
        if (s == null || s.closing()) return;
        int count = s.count(), next = e.getNewSlot();
        if (next < count) {
            s.select(next);
            return;
        }
        e.setCancelled(true);
        int before = Math.min(e.getPreviousSlot(), count - 1);
        int target = count == 1 ? 0 : before == 0 ? count - 1 : before == count - 1 ? 0 : before;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sessions.get(p.getUniqueId()) != s || s.closing()) return;
            p.getInventory().setHeldItemSlot(target);
            s.select(target);
        });
    }

    /** What he really carries stays where it is: no drop, no swap, no click in his inventory. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent e) {
        if (!sessions.containsKey(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        dropped(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(org.bukkit.event.player.PlayerSwapHandItemsEvent e) {
        if (sessions.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (sessions.containsKey(e.getWhoClicked().getUniqueId())) e.setCancelled(true);
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
