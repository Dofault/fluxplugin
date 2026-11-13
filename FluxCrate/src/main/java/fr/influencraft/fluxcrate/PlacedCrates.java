package fr.influencraft.fluxcrate;

import fr.influencraft.flux.api.inventory.Items;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Crates placed in the worlds ({@code placed.yml}: {@code crates: world@x,y,z: <crate>}, and {@code facing:
 * world@x,y,z: <yaw>} where the front of a chest crate looks, toward whoever placed it): the text floating above each one
 * (a text display, not saved with the chunk, put back while the chunk is loaded), a ring of particles, and the
 * crate's {@link Crate.Effects}: its model (an item display floating and slowly turning, moved by client-side
 * interpolation between two ticks, so it stays smooth at one update per second), drifting particles, and a hop with a
 * burst when a key is used.
 */
final class PlacedCrates {

    static final NamespacedKey HOLOGRAM = new NamespacedKey("fluxcrate", "hologram");
    private static final int PERIOD = 20;        // ticks between two calls of tick()
    private static final float BOB = 0.07f;      // height of the model's float, in blocks
    private static final float TURN = (float) (Math.PI / 16); // model's turn per tick(): a full turn in 32 s

    private final FluxCrate plugin;
    private final Map<String, String> placed = new LinkedHashMap<>(); // world@x,y,z -> crate
    private final Map<String, UUID> holograms = new HashMap<>();
    private final Map<String, UUID> models = new HashMap<>();
    private final Map<String, Integer> hopping = new HashMap<>(); // world@x,y,z -> server tick the hop ends
    private final Map<String, Float> facing = new HashMap<>();    // world@x,y,z -> yaw of a chest crate's front
    private final Map<String, ChestScene> chests = new HashMap<>();
    private double angle;
    private int beat;

    PlacedCrates(FluxCrate plugin) {
        this.plugin = plugin;
    }

    private File file() {
        return new File(plugin.getDataFolder(), "placed.yml");
    }

    void load() {
        removeEntities();
        placed.clear();
        facing.clear();
        YamlConfiguration y = yaml();
        try {
            y.load(file());
        } catch (Exception e) {
            if (file().exists()) plugin.getLogger().warning("Could not read placed.yml: " + e.getMessage());
        }
        flatten(y.getConfigurationSection("crates"), "", (k, v) -> placed.put(k, String.valueOf(v)));
        flatten(y.getConfigurationSection("facing"), "", (k, v) -> {
            if (v instanceof Number n) facing.put(k, n.floatValue());
        });
    }

    /** Paths separated by « / »: world names may hold dots (SpringRoyal_1.21). */
    private static YamlConfiguration yaml() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        return y;
    }

    /**
     * Keys of a section with their values; a key split into nested sections by a dot of its world name (files
     * written before the « / » separator) is joined back.
     */
    private static void flatten(ConfigurationSection s, String prefix, java.util.function.BiConsumer<String, Object> out) {
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            Object v = s.get(k);
            if (v instanceof ConfigurationSection sub) flatten(sub, prefix + k + ".", out);
            else if (v != null) out.accept(prefix + k, v);
        }
    }

    void save() {
        YamlConfiguration y = yaml();
        y.options().setHeader(List.of("Crates placed in the worlds, written by FluxCrate (/fluxcrate place, remove)."));
        placed.forEach((k, v) -> y.set("crates/" + k, v));
        facing.forEach((k, v) -> {
            if (placed.containsKey(k)) y.set("facing/" + k, (double) v);
        });
        try {
            y.save(file());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save placed.yml: " + e.getMessage());
        }
    }

    static String key(Location l) {
        return l.getWorld().getName() + "@" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    /** Crate placed on this block, null when none. */
    String at(Block b) {
        return placed.get(key(b.getLocation()));
    }

    /** Places a crate on a block, its front (for a chest crate) looking toward {@code yaw} degrees. */
    void place(Location l, String crate, float yaw) {
        String k = key(l);
        ChestScene old = chests.remove(k);
        if (old != null) old.remove();
        remove(models.remove(k));
        placed.put(k, crate);
        facing.put(k, yaw);
        save();
        Crate c = plugin.crate(crate);
        if (c != null) solidify(l.getBlock(), c);
    }

    /**
     * A crate shown by a model stands on a barrier: players do not walk through it and can click it. Put back when its
     * block is empty (air, grass, snow layer...); a crate without model keeps its own block (furniture of vote crates).
     */
    static void solidify(Block b, Crate crate) {
        if (crate.effects().model() != null && b.getType() != Material.BARRIER && (b.isEmpty() || b.isPassable())) {
            b.setType(Material.BARRIER, false);
        }
    }

    /** Removes the crate of this block and, for a crate shown by a model, the barrier under it. */
    boolean removeWithBlock(Block b) {
        Crate crate = plugin.crate(at(b));
        if (!remove(b.getLocation())) return false;
        if (crate != null && crate.effects().model() != null && b.getType() == Material.BARRIER) b.setType(Material.AIR, false);
        return true;
    }

    boolean remove(Location l) {
        String k = key(l);
        boolean had = placed.remove(k) != null;
        facing.remove(k);
        remove(holograms.remove(k));
        remove(models.remove(k));
        ChestScene scene = chests.remove(k);
        if (scene != null) scene.remove();
        if (had) save();
        return had;
    }

    /** Yaw (degrees) a player placing a crate on this block makes it face: toward him, on the nearest side. */
    static float facingToward(Location block, Location player) {
        double dx = player.getX() - (block.getBlockX() + 0.5), dz = player.getZ() - (block.getBlockZ() + 0.5);
        double deg = Math.toDegrees(Math.atan2(dx, dz));
        return (float) (Math.round(deg / 90) * 90);
    }

    /** The chest scene of a chest crate on this block, null when none is shown (not a chest crate, chunk unloaded). */
    ChestScene chest(Block b) {
        return chests.get(key(b.getLocation()));
    }

    /** Shows or hides the text above a crate (hidden while a chest opens). */
    void hologram(String key, boolean visible) {
        UUID id = holograms.get(key);
        Entity e = id == null ? null : Bukkit.getEntity(id);
        if (e != null) e.setVisibleByDefault(visible);
    }

    /** The opener of a chest crate leaves: his reward now. */
    void quit(UUID player) {
        for (ChestScene s : chests.values()) if (player.equals(s.opener())) s.flush();
    }

    int size() {
        return placed.size();
    }

    private static Location location(String key) {
        int at = key.lastIndexOf('@');
        if (at < 0) return null;
        World w = Bukkit.getWorld(key.substring(0, at));
        if (w == null) {
            for (World any : Bukkit.getWorlds()) if (any.getName().equalsIgnoreCase(key.substring(0, at))) w = any;
        }
        String[] p = key.substring(at + 1).split(",");
        if (w == null || p.length != 3) return null;
        try {
            return new Location(w, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Every second: text and model back on the crates of loaded chunks, model moved, particles for players around. */
    void tick() {
        angle += Math.PI / 8;
        beat++;
        for (Map.Entry<String, String> e : placed.entrySet()) {
            Location l = location(e.getKey());
            Crate crate = plugin.crate(e.getValue());
            if (l == null || crate == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            solidify(l.getBlock(), crate);
            if (crate.effects().chest() != null) {
                ChestScene scene = chests.get(e.getKey());
                if (scene == null) {
                    scene = new ChestScene(plugin, this, e.getKey(), l, crate, facing.getOrDefault(e.getKey(), 0f));
                    chests.put(e.getKey(), scene);
                }
                if (!alive(holograms.get(e.getKey()))) {
                    TextDisplay text = spawn(l, crate);
                    if (scene.busy()) text.setVisibleByDefault(false);
                    holograms.put(e.getKey(), text.getUniqueId());
                }
                scene.ensure();
                if (!scene.busy()) scene.idle(beat);
                if (!crate.ring().isEmpty()) particles(l, crate);
                continue;
            }
            if (!alive(holograms.get(e.getKey()))) holograms.put(e.getKey(), spawn(l, crate).getUniqueId());
            ItemDisplay model = model(e.getKey(), l, crate);
            Integer hopEnd = hopping.get(e.getKey());
            if (hopEnd != null && Bukkit.getCurrentTick() < hopEnd) continue;
            hopping.remove(e.getKey());
            if (model != null) move(model, crate, idle(crate, beat), PERIOD);
            particles(l, crate);
        }
    }

    private static boolean alive(UUID id) {
        Entity entity = id == null ? null : Bukkit.getEntity(id);
        return entity != null && entity.isValid();
    }

    private static void remove(UUID id) {
        Entity entity = id == null ? null : Bukkit.getEntity(id);
        if (entity != null) entity.remove();
    }

    // ------------------------------------------------------------------ model

    /** The model shown on this crate (spawned when missing), null when the crate has none. */
    private ItemDisplay model(String key, Location l, Crate crate) {
        String spec = crate.effects().model();
        if (spec == null) {
            remove(models.remove(key));
            return null;
        }
        UUID id = models.get(key);
        if (alive(id) && Bukkit.getEntity(id) instanceof ItemDisplay d) return d;
        ItemStack item = Items.of(spec, null);
        if (item == null) return null;
        ItemDisplay d = l.getWorld().spawn(l.clone().add(0.5, 0.5, 0.5), ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setItemStack(item);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setBrightness(new Display.Brightness(15, 15)); // lit by night too: a crate is a landmark
            display.setShadowRadius(0.45f * crate.effects().scale());
            display.setShadowStrength(0.6f);
            display.setTransformation(idle(crate, beat));
            display.getPersistentDataContainer().set(HOLOGRAM, PersistentDataType.BYTE, (byte) 1);
        });
        models.put(key, d.getUniqueId());
        return d;
    }

    /** Pose of the model at a beat: up and down every other second, a little more turned each time. */
    private static Transformation idle(Crate crate, int beat) {
        return pose(crate, beat % 2 == 0 ? 0 : BOB, beat * TURN, 1f);
    }

    /** The model on the floor of the block (whatever its scale), lifted by {@code lift}, turned by {@code yaw}. */
    private static Transformation pose(Crate crate, float lift, float yaw, float grow) {
        float scale = crate.effects().scale() * grow;
        return new Transformation(new Vector3f(0, (scale - 1) / 2 + lift, 0), new AxisAngle4f(yaw, 0, 1, 0),
                new Vector3f(scale, scale, scale), new AxisAngle4f());
    }

    /** Moves the model to a pose, the client sliding it there in {@code ticks}. */
    private static void move(ItemDisplay model, Crate crate, Transformation to, int ticks) {
        model.setInterpolationDelay(0);
        model.setInterpolationDuration(ticks);
        model.setTransformation(to);
    }

    /**
     * A key was used on this crate: the model hops, its effects burst out. Players around see it while the opener
     * watches the roll.
     */
    void opened(Block block) {
        String key = key(block.getLocation());
        Crate crate = plugin.crate(placed.get(key));
        if (crate == null) return;
        Crate.Effects fx = crate.effects();
        Location top = block.getLocation().add(0.5, 1.0, 0.5);
        UUID id = models.get(key);
        if (alive(id) && Bukkit.getEntity(id) instanceof ItemDisplay model) {
            hopping.put(key, Bukkit.getCurrentTick() + 14);
            float yaw = beat * TURN;
            move(model, crate, pose(crate, 0.35f, yaw + (float) Math.PI / 4, 1.12f), 3);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (model.isValid()) move(model, crate, pose(crate, 0, yaw, 1f), 8);
            }, 4L);
        }
        if (fx.burst() != null) top.getWorld().spawnParticle(fx.burst(), top, 40, 0.3, 0.25, 0.3, 0.12);
        for (Color c : crate.ring()) {
            top.getWorld().spawnParticle(Particle.DUST, top, 8, 0.45, 0.3, 0.45, 0, new Particle.DustOptions(c, 1.2f));
        }
        if (fx.burstSound() != null && !fx.burstSound().isBlank()) {
            String[] p = fx.burstSound().trim().split("\\s+");
            top.getWorld().playSound(top, p[0], SoundCategory.BLOCKS, p.length > 1 ? parse(p[1]) : 1f, p.length > 2 ? parse(p[2]) : 1f);
        }
    }

    private static float parse(String s) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return 1f;
        }
    }

    private TextDisplay spawn(Location l, Crate crate) {
        // above the model's hop when the crate shows one; just above the top of a chest
        double scale = crate.effects().scale();
        double height = crate.effects().model() == null ? 1.6 : crate.effects().chest() != null ? 0.35 + scale : 1.5 + 0.4 * scale;
        Location at = l.clone().add(0.5, height, 0.5);
        return l.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.text(fr.influencraft.flux.api.text.Texts.parse(String.join("\n", crate.hologram())));
            d.getPersistentDataContainer().set(HOLOGRAM, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** Ring of 4 particles turning around the crate (its colours in turn), and its drifting particle. */
    private void particles(Location l, Crate crate) {
        List<Color> ring = crate.ring();
        if (ring.isEmpty()) return;
        double radius = crate.effects().model() == null ? 0.6 : 0.85; // around the corners of a turning model
        for (int i = 0; i < 4; i++) {
            double a = angle + i * Math.PI / 2;
            Particle.DustOptions dust = new Particle.DustOptions(ring.get(i % ring.size()), 1.0f);
            l.getWorld().spawnParticle(Particle.DUST, l.getX() + 0.5 + radius * Math.cos(a), l.getY() + 1.1,
                    l.getZ() + 0.5 + radius * Math.sin(a), 1, 0, 0, 0, 0, dust);
        }
        Particle ambient = crate.effects().ambient();
        if (ambient != null) {
            l.getWorld().spawnParticle(ambient, l.getX() + 0.5, l.getY() + 1.8, l.getZ() + 0.5, 3, 0.8, 0.3, 0.8, 0.01);
        }
    }

    /** On disable and reload: texts and models (they would vanish with their chunk anyway). */
    void removeEntities() {
        chests.values().forEach(ChestScene::remove);
        chests.clear();
        holograms.values().forEach(PlacedCrates::remove);
        models.values().forEach(PlacedCrates::remove);
        holograms.clear();
        models.clear();
        hopping.clear();
    }
}
