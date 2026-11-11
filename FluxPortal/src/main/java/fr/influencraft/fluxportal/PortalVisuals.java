package fr.influencraft.fluxportal;

import fr.influencraft.fluxportal.api.Portal;
import fr.influencraft.fluxportal.api.PortalEnd;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * How portals look and sound, in the server's one resource pack (ItemsAdder content {@code fluxportal}, made by
 * {@code assets/gen_portal.py}): an animated pixel-art swirl filling the hole (an item display, model
 * {@code fluxportal:surface}, full bright), a ring of violet to pink dust turning slowly around it with motes drawn
 * into the middle, a low hum nearby, and a chime and whoosh when someone goes through. An end whose other end is
 * missing shows only a grey ring (for the staff placing it).
 *
 * <p>Surfaces are never saved with the world: they appear when their chunk loads and go with it.</p>
 */
final class PortalVisuals implements Listener {

    private static final String TAG = "fluxportal_surface";
    private static final Key MODEL = Key.key("fluxportal", "surface");

    private final JavaPlugin plugin;
    private final Supplier<Collection<Portal>> portals;
    /** "name|entry" or "name|exit" -> its surface. */
    private final Map<String, UUID> surfaces = new HashMap<>();
    private BukkitTask task;
    private long tick;
    /** False when a renderer draws the 3D window: no flat swirl then. */
    private boolean flatSurfaces = true;
    /** Real middle of a ring when a renderer placed it (else the saved centre). */
    private java.util.function.BiFunction<Portal, Boolean, Location> middle = (p, entry) -> null;

    void middle(java.util.function.BiFunction<Portal, Boolean, Location> middle) {
        this.middle = middle;
    }

    void flatSurfaces(boolean on) {
        flatSurfaces = on;
        if (!on) for (Portal p : portals.get()) remove(p);
    }

    PortalVisuals(JavaPlugin plugin, Supplier<Collection<Portal>> portals) {
        this.plugin = plugin;
        this.portals = portals;
    }

    void start() {
        // surfaces left by a crash (never saved, but a chunk saved during a crash may keep one)
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntitiesByClass(ItemDisplay.class)) if (e.getScoreboardTags().contains(TAG)) e.remove();
        for (Portal p : portals.get()) refresh(p);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::draw, 2L, 2L);
    }

    void stop() {
        if (task != null) task.cancel();
        for (UUID id : surfaces.values()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        surfaces.clear();
    }

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("look");
        return c == null ? plugin.getConfig().createSection("look") : c;
    }

    // ------------------------------------------------------------------ surfaces

    /** Puts the surfaces of a portal where they belong (removes them while it is incomplete). */
    void refresh(Portal p) {
        remove(p);
        if (!p.complete() || !flatSurfaces) return;
        surface(p, p.entry(), "entry");
        surface(p, p.exit(), "exit");
    }

    void remove(Portal p) {
        for (String side : new String[]{"entry", "exit"}) {
            UUID id = surfaces.remove(p.name() + "|" + side);
            Entity e = id == null ? null : Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
    }

    private void surface(Portal p, PortalEnd end, String side) {
        World w = Bukkit.getWorld(end.world());
        if (w == null || !w.isChunkLoaded((int) Math.floor(end.x()) >> 4, (int) Math.floor(end.z()) >> 4)) return;
        Location at = new Location(w, end.x(), end.y(), end.z(), end.yaw(), 0);
        ItemStack item = new ItemStack(Material.PAPER);
        item.setData(DataComponentTypes.ITEM_MODEL, MODEL);
        float d = (float) (p.holeRadius() * 2 * cfg().getDouble("surface-scale", 1.08));
        ItemDisplay display = w.spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(item);
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
            e.setBillboard(Display.Billboard.FIXED);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(4f);
            e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(d, d, d), new AxisAngle4f()));
        });
        surfaces.put(p.name() + "|" + side, display.getUniqueId());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        for (Portal p : portals.get()) {
            if (!p.complete()) continue;
            if (in(e.getChunk(), p.entry()) || in(e.getChunk(), p.exit())) refresh(p);
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        for (Entity entity : e.getChunk().getEntities()) {
            if (entity.getScoreboardTags().contains(TAG)) {
                surfaces.values().remove(entity.getUniqueId());
                entity.remove();
            }
        }
    }

    private static boolean in(Chunk c, PortalEnd end) {
        return end != null && c.getWorld().getName().equals(end.world())
                && ((int) Math.floor(end.x()) >> 4) == c.getX() && ((int) Math.floor(end.z()) >> 4) == c.getZ();
    }

    // ------------------------------------------------------------------ ring, motes, hum

    private void draw() {
        tick += 2;
        double seen = cfg().getDouble("particle-distance", 48);
        for (Portal p : portals.get()) {
            for (PortalEnd end : new PortalEnd[]{p.entry(), p.exit()}) {
                if (end == null) continue;
                World w = Bukkit.getWorld(end.world());
                if (w == null) continue;
                Location real = p.complete() ? middle.apply(p, end == p.entry()) : null;
                Location c = real != null && real.getWorld() == w ? real : new Location(w, end.x(), end.y(), end.z());
                if (!near(c, seen)) continue;
                ring(p, end, c);
                if (p.complete() && tick % cfg().getLong("hum-every", 80) == 0 && near(c, 16)) {
                    w.playSound(c, cfg().getString("hum.sound", "minecraft:block.beacon.ambient"), org.bukkit.SoundCategory.BLOCKS,
                            (float) cfg().getDouble("hum.volume", 0.5), (float) cfg().getDouble("hum.pitch", 0.6));
                }
            }
        }
    }

    private static boolean near(Location c, double distance) {
        for (Player pl : c.getWorld().getPlayers()) if (pl.getLocation().distanceSquared(c) <= distance * distance) return true;
        return false;
    }

    /**
     * The ring: points around the hole, coloured from violet to pink, a third of them lit each time and turning, so the
     * ring seems to flow; on a complete portal, motes sucked into the middle.
     */
    private void ring(Portal p, PortalEnd end, Location c) {
        World w = c.getWorld();
        Vector right = end.right(), up = new Vector(0, 1, 0);
        double radius = p.size() / 2 * 0.92;
        int points = (int) Math.max(24, Math.round(radius * 18));
        double turn = tick * 0.02;
        Color from = color("ring-from", 0xB376E5), to = color("ring-to", 0xE060A0);
        for (int i = 0; i < points; i++) {
            if ((i + tick / 2) % 3 != 0) continue;
            double a = Math.PI * 2 * i / points + turn;
            Location at = c.clone().add(right.clone().multiply(Math.cos(a) * radius)).add(up.clone().multiply(Math.sin(a) * radius));
            if (!p.complete()) {
                w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(0x8A8580), 1f));
                continue;
            }
            double t = (Math.sin(a * 2 + tick * 0.05) + 1) / 2;
            w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0, 0, 0, 0,
                    new Particle.DustTransition(mix(from, to, t), mix(to, from, t), 1.1f));
        }
        if (!p.complete()) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < cfg().getInt("motes", 2); i++) {
            double a = r.nextDouble(Math.PI * 2);
            Location at = c.clone().add(right.clone().multiply(Math.cos(a) * radius)).add(up.clone().multiply(Math.sin(a) * radius));
            Vector in = c.toVector().subtract(at.toVector()).normalize();
            w.spawnParticle(Particle.REVERSE_PORTAL, at, 0, in.getX(), in.getY(), in.getZ(), 0.06);
        }
    }

    private Color color(String key, int fallback) {
        String s = cfg().getString(key, null);
        try {
            return Color.fromRGB(s == null ? fallback : Integer.parseInt(s.replace("#", ""), 16));
        } catch (NumberFormatException e) {
            return Color.fromRGB(fallback);
        }
    }

    private static Color mix(Color a, Color b, double t) {
        return Color.fromRGB((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t), (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    // ------------------------------------------------------------------ going through

    void departure(Location at, PortalEnd end) {
        burst(at);
    }

    /** Arrival through a renderer's window: its own teleport happens right after, the effects follow it. */
    void arrivalSoon(Location at, PortalEnd end) {
        Bukkit.getScheduler().runTask(plugin, () -> arrival(at, end));
    }

    void arrival(Location at, PortalEnd end) {
        burst(at);
        World w = at.getWorld();
        w.playSound(at, cfg().getString("travel.sound", "minecraft:entity.illusioner.mirror_move"), org.bukkit.SoundCategory.PLAYERS,
                (float) cfg().getDouble("travel.volume", 0.9), (float) cfg().getDouble("travel.pitch", 1.2));
        w.playSound(at, cfg().getString("travel.chime", "minecraft:block.amethyst_block.resonate"), org.bukkit.SoundCategory.PLAYERS,
                (float) cfg().getDouble("travel.volume", 0.9), 1.4f);
    }

    private void burst(Location at) {
        at.getWorld().spawnParticle(Particle.REVERSE_PORTAL, at.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.05);
        at.getWorld().spawnParticle(Particle.DUST, at.clone().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0,
                new Particle.DustOptions(color("ring-to", 0xE060A0), 1.2f));
    }
}
