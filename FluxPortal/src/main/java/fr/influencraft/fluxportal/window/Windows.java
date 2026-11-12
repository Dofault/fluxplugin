package fr.influencraft.fluxportal.window;

import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.fluxportal.Frame;
import fr.influencraft.fluxportal.api.Portal;
import fr.influencraft.fluxportal.api.PortalEnd;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The 3D windows of the portals: each side of a complete portal shows the other one while a Java player is near it
 * (its maps are hung then, and the far side's chunks kept loaded), nothing otherwise. Bedrock players never see the
 * window's item frames (they get {@code BedrockView}'s blocks). The far side is read a few layers per tick for all
 * windows together. Maps are reused from a pool ({@code maps.yml}) so no map file is ever left behind.
 */
public final class Windows implements Listener {

    private final JavaPlugin plugin;
    private final Supplier<Collection<Portal>> portals;
    private final MapColours colours = new MapColours();
    private final Assets assets = new Assets();
    private final BlockLooks looks = new BlockLooks(assets);
    private final File poolFile;
    private final Deque<Integer> pool = new ArrayDeque<>();
    private final List<Integer> everMade = new ArrayList<>();
    private final Map<String, Window> windows = new HashMap<>();
    private BukkitTask task;
    private long ticks;
    private int robin;
    private final Map<String, Long> packStamps = new HashMap<>();

    private double viewDistance;
    private int margin, slabsPerTick, firstSlabsPerTick, horizonEvery, ticketRadius;

    public Windows(JavaPlugin plugin, Supplier<Collection<Portal>> portals) {
        this.plugin = plugin;
        this.portals = portals;
        this.poolFile = new File(plugin.getDataFolder(), "maps.yml");
        pool.addAll(YamlConfiguration.loadConfiguration(poolFile).getIntegerList("maps"));
        everMade.addAll(pool);
    }

    public MapColours colours() {
        return colours;
    }

    /** Settings ({@code window} section), textures, then the windows run. */
    public void start(ConfigurationSection c) {
        viewDistance = c.getDouble("view-distance", 64);
        margin = Math.max(0, c.getInt("margin", 0));
        slabsPerTick = Math.max(1, c.getInt("layers-per-tick", 2));
        firstSlabsPerTick = Math.max(1, c.getInt("first-layers-per-tick", 4));
        horizonEvery = Math.max(200, c.getInt("horizon-every", 6000));
        ticketRadius = Math.max(0, c.getInt("far-chunks-radius", 5));
        List<String> packs = c.getStringList("texture-packs");
        sweep();
        assets.load(plugin, packs, c.getStringList("texture-folders"), c.getBoolean("download-client", true), () -> {
            looks.reset();
            Window.forgetClouds();
            for (Window w : windows.values()) w.sampled = 0;
        });
        for (String p : packs) packStamps.put(p, new File(p).lastModified());
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        for (String key : new ArrayList<>(windows.keySet())) close(key);
        assets.close();
        save();
    }

    /** A portal changed (placed, moved, opened, shut, removed): its windows are hung again as needed. */
    public void refresh(Portal p) {
        for (int side = 1; side <= 2; side++) close(key(p.name(), side));
    }

    public void refreshAll() {
        for (String key : new ArrayList<>(windows.keySet())) close(key);
    }

    private static String key(String name, int side) {
        return name + "#" + side;
    }

    // ------------------------------------------------------------------ every tick

    private void tick() {
        ticks++;
        if (ticks % 20 == 0) activate();
        if (ticks % 1200 == 0) checkPacks();
        if (windows.isEmpty()) return;
        List<Window> all = new ArrayList<>(windows.values());
        // windows just hung read their whole far side first, a few layers each tick
        for (Window w : all) {
            for (int i = 0; i < firstSlabsPerTick && w.sampled < Window.MAPS; i++) {
                w.sample(w.sampled++);
            }
        }
        // then everyone in turn, a few layers per tick for all
        for (int i = 0; i < slabsPerTick; i++) {
            Window w = all.get(Math.floorMod(robin++, all.size()));
            if (w.sampled < Window.MAPS) continue;
            w.sample(w.next);
            w.next = (w.next + 1) % Window.MAPS;
        }
        for (Window w : all) {
            if (ticks % 20 == 0) w.writeClouds();
            if (!w.horizonBusy && (w.horizonAt == Long.MIN_VALUE || ticks - w.horizonAt >= horizonEvery)) w.readHorizon(ticks);
        }
    }

    /** Which windows should be up: sides of complete portals with a Java player near them. */
    private void activate() {
        Map<String, Portal> wanted = new HashMap<>();
        for (Portal p : portals.get()) {
            if (!p.complete()) continue;
            for (int side = 1; side <= 2; side++) {
                String key = key(p.name(), side);
                // a window already up stays up a little further (no flicker at the edge)
                double reach = windows.containsKey(key) ? viewDistance + 16 : viewDistance;
                if (javaPlayerNear(p.side(side), reach)) wanted.put(key, p);
            }
        }
        for (String key : new ArrayList<>(windows.keySet())) if (!wanted.containsKey(key)) close(key);
        for (Map.Entry<String, Portal> e : wanted.entrySet()) {
            Window w = windows.get(e.getKey());
            if (w == null) open(e.getKey(), e.getValue());
            else w.open(e.getValue().open());
        }
    }

    private static boolean javaPlayerNear(PortalEnd end, double reach) {
        World w = Bukkit.getWorld(end.world());
        if (w == null) return false;
        for (Player p : w.getPlayers()) {
            if (Clients.bedrock(p)) continue;
            if (p.getLocation().toVector().distanceSquared(end.middle()) <= reach * reach) return true;
        }
        return false;
    }

    private void open(String key, Portal p) {
        int side = key.endsWith("#1") ? 1 : 2;
        PortalEnd hereEnd = p.side(side), thereEnd = p.side(side == 1 ? 2 : 1);
        World hereWorld = Bukkit.getWorld(hereEnd.world()), thereWorld = Bukkit.getWorld(thereEnd.world());
        if (hereWorld == null || thereWorld == null) return;
        Window w = new Window(key, new Frame(hereEnd, p.width(), p.height()), hereWorld, new Frame(thereEnd, p.width(), p.height()), thereWorld,
                p.open(), colours, looks);
        w.hang(plugin, () -> take(hereWorld), margin);
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (Clients.bedrock(pl)) for (ItemFrame f : w.frames) pl.hideEntity(plugin, f);
        }
        hold(thereEnd, thereWorld, true);
        windows.put(key, w);
    }

    private void close(String key) {
        Window w = windows.remove(key);
        if (w == null) return;
        for (MapView map : w.unhang()) pool.add(map.getId());
        PortalEnd far = new PortalEnd(w.thereWorld.getName(), w.there.worldX(0, 0), w.there.worldY(0), w.there.worldZ(0, 0), w.there.facing() * 90f);
        hold(far, w.thereWorld, false);
    }

    /** Keeps the far side's chunks loaded while its window is up (it is read from them). */
    private void hold(PortalEnd end, World world, boolean on) {
        int cx = (int) Math.floor(end.x()) >> 4, cz = (int) Math.floor(end.z()) >> 4;
        for (int x = cx - ticketRadius; x <= cx + ticketRadius; x++) {
            for (int z = cz - ticketRadius; z <= cz + ticketRadius; z++) {
                if (on) world.addPluginChunkTicket(x, z, plugin);
                else world.removePluginChunkTicket(x, z, plugin);
            }
        }
        // another window may need some of the same chunks
        if (!on) for (Window w : windows.values()) if (w.thereWorld == world) hold(farOf(w), world, true);
    }

    private static PortalEnd farOf(Window w) {
        return new PortalEnd(w.thereWorld.getName(), w.there.worldX(0, 0), w.there.worldY(0), w.there.worldZ(0, 0), w.there.facing() * 90f);
    }

    private MapView take(World world) {
        while (!pool.isEmpty()) {
            @SuppressWarnings("deprecation") MapView map = Bukkit.getMap(pool.poll());
            if (map != null) return map;
        }
        MapView map = Bukkit.createMap(world);
        everMade.add(map.getId());
        save();
        return map;
    }

    private void save() {
        YamlConfiguration out = new YamlConfiguration();
        out.set("maps", everMade);
        try {
            out.save(poolFile);
        } catch (IOException e) {
            plugin.getLogger().warning("maps.yml not saved: " + e.getMessage());
        }
    }

    /** A pack rebuilt (/iazip): textures read again, every window redrawn. */
    private void checkPacks() {
        boolean changed = false;
        for (Map.Entry<String, Long> e : packStamps.entrySet()) {
            long now = new File(e.getKey()).lastModified();
            if (now != e.getValue()) {
                e.setValue(now);
                changed = true;
            }
        }
        if (changed) start(plugin.getConfig().getConfigurationSection("window") == null
                ? new YamlConfiguration() : plugin.getConfig().getConfigurationSection("window"));
    }

    /** Frames left by a crash (they are never saved, this is a safety net). */
    private void sweep() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntitiesByClass(ItemFrame.class)) {
                if (e.getScoreboardTags().contains(Window.TAG) && !owned(e)) e.remove();
            }
        }
    }

    private boolean owned(Entity e) {
        for (Window w : windows.values()) if (w.frames.contains(e)) return true;
        return false;
    }

    public List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Window w : windows.values()) out.add(w.key + ": " + w.frames.size() + " frames, " + w.sampled + "/" + Window.MAPS + " layers read");
        return out;
    }

    // ------------------------------------------------------------------ the frames are not for touching

    private static boolean window(Entity e) {
        return e instanceof ItemFrame && e.getScoreboardTags().contains(Window.TAG);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (window(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (window(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(HangingBreakEvent e) {
        if (window(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!Clients.bedrock(p)) return;
        for (Window w : windows.values()) for (ItemFrame f : w.frames) p.hideEntity(plugin, f);
    }
}
