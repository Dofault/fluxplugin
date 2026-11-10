package fr.influencraft.fluxdungeons.mob;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Effects any boss can use, never touching the map for real (everything is shown to the players only, or drawn with
 * displays and particles, and goes away by itself):
 * <ul>
 *   <li>{@link #crater}: the ground cracks (Minecraft's breaking overlay on the blocks of a disc, deepest in the
 *   middle), a share of the blocks look broken (coarse dirt, rooted dirt...), then it heals;</li>
 *   <li>{@link #shockwave}: a ring of ground blocks jumping up, running out from a point;</li>
 *   <li>{@link #debris}: pieces of a block thrown in the air;</li>
 *   <li>{@link #ring}, {@link #cone}, {@link #column}: warnings and lights drawn with particles (one frame: call them
 *   every few ticks for as long as they must show).</li>
 * </ul>
 */
final class Fx {

    private static final String TAG = "fluxdungeons_fx";
    private static final AtomicInteger IDS = new AtomicInteger(-0x40000000);
    /** Blocks the ground may look like where it broke. */
    static final Material[] BROKEN_EARTH = {Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.DIRT, Material.PACKED_MUD};

    private final Plugin plugin;
    private final Set<Entity> alive = new HashSet<>();

    Fx(Plugin plugin) {
        this.plugin = plugin;
    }

    void clear() {
        for (Entity e : alive) if (e.isValid()) e.remove();
        alive.clear();
    }

    private static List<Player> viewers(World w, Location at) {
        List<Player> out = new ArrayList<>();
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(at) < 80 * 80) out.add(p);
        return out;
    }

    /** The top solid block under (x, z), searched around y; null when there is none. */
    static Block ground(World w, int x, double y, int z) {
        for (int by = (int) Math.floor(y + 1.5); by >= (int) Math.floor(y) - 3; by--) {
            Block b = w.getBlockAt(x, by, z);
            if (b.getType().isSolid() && b.getType().isOccluding() || b.getType() == Material.FARMLAND
                    || b.getType() == Material.DIRT_PATH) return b;
        }
        return null;
    }

    // ---------------------------------------------------------------- crater

    /**
     * Cracked ground in a disc of {@code radius} blocks for {@code ticks} ticks: breaking overlay strongest in the
     * middle, a share {@code broken} of the inner blocks shown as {@code palette} (none = no change), healing over
     * the last second.
     */
    void crater(Location center, double radius, int ticks, double broken, Material... palette) {
        World w = center.getWorld();
        int r = (int) Math.ceil(radius);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Block> blocks = new ArrayList<>();
        List<Float> depth = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        List<BlockData> fake = new ArrayList<>();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > radius) continue;
                Block b = ground(w, center.getBlockX() + x, center.getY(), center.getBlockZ() + z);
                if (b == null) continue;
                float k = (float) Math.max(0.1, Math.min(0.95, 1 - d / radius + rnd.nextDouble(-0.15, 0.15)));
                blocks.add(b);
                depth.add(k);
                ids.add(IDS.getAndDecrement());
                boolean change = palette.length > 0 && d < radius * 0.65 && rnd.nextDouble() < broken
                        && !HarvestPowers.CROPS.contains(b.getRelative(0, 1, 0).getType());
                fake.add(change ? palette[rnd.nextInt(palette.length)].createBlockData() : null);
            }
        }
        List<Player> who = viewers(w, center);
        for (Player p : who) {
            for (int i = 0; i < blocks.size(); i++) {
                if (fake.get(i) != null) p.sendBlockChange(blocks.get(i).getLocation(), fake.get(i));
                p.sendBlockDamage(blocks.get(i).getLocation(), depth.get(i), ids.get(i));
            }
        }
        int fade = Math.min(20, ticks / 2);
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                t += 5;
                if (t < ticks - fade) return;
                float k = Math.max(0, (ticks - t) / (float) fade);
                for (Player p : who) {
                    if (!p.isOnline() || p.getWorld() != w) continue;
                    for (int i = 0; i < blocks.size(); i++) {
                        p.sendBlockDamage(blocks.get(i).getLocation(), depth.get(i) * k, ids.get(i));
                        if (k <= 0 && fake.get(i) != null) p.sendBlockChange(blocks.get(i).getLocation(), blocks.get(i).getBlockData());
                    }
                }
                if (k <= 0) cancel();
            }
        }.runTaskTimer(plugin, 5L, 5L);
    }

    // ---------------------------------------------------------------- shockwave

    /** Ground blocks jumping up in a ring that runs out from {@code center} to {@code radius} in {@code ticks} ticks. */
    void shockwave(Location center, double radius, int ticks, double lift) {
        World w = center.getWorld();
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (++t > ticks) {
                    cancel();
                    return;
                }
                double r = radius * t / ticks;
                int n = Math.max(6, (int) Math.round(Math.PI * 2 * r / 1.1));
                double a0 = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                Set<Block> done = new HashSet<>();
                for (int i = 0; i < n; i++) {
                    double a = a0 + Math.PI * 2 * i / n;
                    Block b = ground(w, (int) Math.floor(center.getX() + Math.cos(a) * r), center.getY(),
                            (int) Math.floor(center.getZ() + Math.sin(a) * r));
                    if (b == null || !done.add(b)) continue;
                    hop(b, lift);
                    w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 1.05, 0.5), 3, 0.3, 0.05, 0.3, 0, b.getBlockData());
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** A copy of a ground block jumps up out of it and falls back (the real block never moves). */
    private void hop(Block b, double lift) {
        BlockData data = b.getBlockData();
        BlockDisplay d = b.getWorld().spawn(b.getLocation(), BlockDisplay.class, it -> {
            it.setBlock(data);
            it.setPersistent(false);
            it.setBrightness(new Display.Brightness(13, 15));
            it.addScoreboardTag(TAG);
            it.setTransformation(new Transformation(new Vector3f(-0.005f, 0.01f, -0.005f), new AxisAngle4f(),
                    new Vector3f(1.01f, 1.0f, 1.01f), new AxisAngle4f()));
        });
        alive.add(d);
        float tilt = (float) ThreadLocalRandom.current().nextDouble(-0.25, 0.25);
        Bukkit.getScheduler().runTaskLater(plugin, () -> move(d, new Vector3f(-0.005f, (float) lift, -0.005f), tilt, 3), 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> move(d, new Vector3f(-0.005f, 0.01f, -0.005f), 0, 4), 5L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> forget(d), 10L);
    }

    private static void move(BlockDisplay d, Vector3f to, float tilt, int ticks) {
        if (!d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(new Transformation(to, new AxisAngle4f(tilt, 1, 0, 0), new Vector3f(1.01f, 1.0f, 1.01f), new AxisAngle4f()));
    }

    private void forget(Entity e) {
        if (e.isValid()) e.remove();
        alive.remove(e);
    }

    // ---------------------------------------------------------------- debris

    /** {@code count} pieces of a block thrown up and around from a place, falling back and vanishing. */
    void debris(Location at, int count, BlockData data, double power) {
        World w = at.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            float size = (float) r.nextDouble(0.18, 0.38);
            BlockDisplay d = w.spawn(at, BlockDisplay.class, it -> {
                it.setBlock(data);
                it.setPersistent(false);
                it.setTeleportDuration(1);
                it.setBrightness(new Display.Brightness(13, 15));
                it.addScoreboardTag(TAG);
                it.setTransformation(new Transformation(new Vector3f(-size / 2, -size / 2, -size / 2), new AxisAngle4f(),
                        new Vector3f(size, size, size), new AxisAngle4f()));
            });
            alive.add(d);
            Vector v = new Vector(r.nextDouble(-1, 1), 0, r.nextDouble(-1, 1)).normalize().multiply(r.nextDouble(0.1, 0.3) * power)
                    .setY(r.nextDouble(0.3, 0.6) * power);
            new BukkitRunnable() {
                int t;
                final Location pos = at.clone();

                @Override
                public void run() {
                    if (!d.isValid() || ++t > 24) {
                        cancel();
                        forget(d);
                        return;
                    }
                    v.setY(v.getY() - 0.08);
                    Location next = pos.clone().add(v);
                    if (!next.getBlock().isPassable()) {
                        if (t > 4) {
                            w.spawnParticle(Particle.BLOCK, pos, 4, 0.1, 0.05, 0.1, 0, data);
                            cancel();
                            forget(d);
                            return;
                        }
                        v.setY(Math.abs(v.getY()) * 0.3);
                        return;
                    }
                    pos.add(v);
                    pos.setYaw(pos.getYaw() + 37);
                    pos.setPitch((pos.getPitch() + 29) % 180 - 90);
                    d.teleport(pos);
                }
            }.runTaskTimer(plugin, 1L, 1L);
        }
    }

    // ---------------------------------------------------------------- particle drawings (one frame)

    /** A circle on the ground. */
    static void ring(Location center, double radius, Color color, float size, double turn) {
        World w = center.getWorld();
        int n = Math.max(8, (int) (radius * 7));
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n + turn;
            w.spawnParticle(Particle.DUST, center.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius), 1, 0, 0, 0, 0,
                    new Particle.DustOptions(color, size));
        }
    }

    /** The outline of a cone on the ground ({@code angle} degrees wide, {@code length} blocks), filled lightly. */
    static void cone(Location from, Vector dir, double angle, double length, Color edge, Color fill) {
        World w = from.getWorld();
        Vector d = dir.clone().setY(0).normalize();
        double half = Math.toRadians(angle / 2);
        Location base = from.clone();
        for (double s = 1; s <= length; s += 0.5) {
            for (int side = -1; side <= 1; side += 2) {
                Vector v = d.clone().rotateAroundY(half * side).multiply(s);
                w.spawnParticle(Particle.DUST, base.clone().add(v).add(0, 0.15, 0), 1, 0, 0, 0, 0, new Particle.DustOptions(edge, 1.3f));
            }
        }
        int arc = Math.max(6, (int) (angle / 6));
        for (int i = 0; i <= arc; i++) {
            Vector v = d.clone().rotateAroundY(-half + 2 * half * i / arc).multiply(length);
            w.spawnParticle(Particle.DUST, base.clone().add(v).add(0, 0.15, 0), 1, 0, 0, 0, 0, new Particle.DustOptions(edge, 1.3f));
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < length * angle / 12; i++) {
            Vector v = d.clone().rotateAroundY(r.nextDouble(-half, half)).multiply(r.nextDouble(1, length));
            w.spawnParticle(Particle.DUST, base.clone().add(v).add(0, 0.12, 0), 1, 0, 0, 0, 0, new Particle.DustOptions(fill, 0.9f));
        }
    }

    /** A column of light. */
    static void column(Location base, double height, Particle particle, int density) {
        World w = base.getWorld();
        for (double y = 0; y < height; y += 0.6) {
            w.spawnParticle(particle, base.clone().add(0, y, 0), density, 0.12, 0.2, 0.12, 0.01);
        }
    }
}
