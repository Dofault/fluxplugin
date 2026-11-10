package fr.influencraft.fluxdungeons.mob;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The floor giving way under a boss in its phase transition ({@link MobTemplate.Flood}): cracks drawn on the blocks
 * from {@code crack} (the crack texture of block breaking, growing until the break), then at {@code at} the blocks
 * burst from the centre outwards, shards flying, a splash rising in the middle. Only blocks of the listed kinds on
 * the layer under the boss (and {@code depth} layers below) change; nothing else is touched.
 */
final class FloorBreak {

    /** Speed of the break running from the centre to the edge (blocks a tick), and its longest run (ticks). */
    private static final double SPEED = 1.2;
    private static final int SPREAD_MAX = 16;
    private static final int SHARD_LIFE = 28;

    private FloorBreak() {
    }

    /**
     * Blocks that will change, nearest to the centre first: a circle of {@code radius} around the spot, or with
     * {@code area: connected} every block of the listed kinds joined (side by side, on the same layer) to the one under
     * it, so a whole frozen pool breaks whatever its shape. {@code depth} layers below each are taken too.
     */
    static List<Block> blocks(Location center, MobTemplate.Flood f) {
        World w = center.getWorld();
        // the block the boss stands on: its feet are at the top of that block
        int cx = center.getBlockX(), cz = center.getBlockZ(), top = (int) Math.floor(center.getY() - 0.01);
        List<Block> surface = new ArrayList<>();
        if (f.connected()) {
            Block seed = seed(w.getBlockAt(cx, top, cz), f);
            if (seed != null) {
                java.util.Set<Block> seen = new java.util.HashSet<>();
                java.util.ArrayDeque<Block> queue = new java.util.ArrayDeque<>();
                queue.add(seed);
                seen.add(seed);
                while (!queue.isEmpty() && surface.size() < f.limit()) {
                    Block b = queue.poll();
                    surface.add(b);
                    for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.NORTH,
                            org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
                        Block n = b.getRelative(face);
                        if (f.from().contains(n.getType()) && seen.add(n)) queue.add(n);
                    }
                }
            }
        } else {
            int r = (int) Math.ceil(f.radius());
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > f.radius() * f.radius()) continue;
                    Block b = w.getBlockAt(cx + dx, top, cz + dz);
                    if (f.from().contains(b.getType())) surface.add(b);
                }
            }
        }
        List<Block> out = new ArrayList<>();
        for (Block b : surface) {
            for (int dy = 0; dy < f.depth(); dy++) {
                Block d = b.getRelative(0, -dy, 0);
                if (dy == 0 || f.from().contains(d.getType())) out.add(d);
            }
        }
        out.sort(Comparator.comparingDouble(b -> flat(b, center)));
        return out;
    }

    /** The block of the listed kinds under the boss, or the nearest one around it (it may stand on an edge). */
    private static Block seed(Block under, MobTemplate.Flood f) {
        for (int r = 0; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = 0; dy >= -1; dy--) {
                        Block b = under.getRelative(dx, dy, dz);
                        if (f.from().contains(b.getType())) return b;
                    }
                }
            }
        }
        return null;
    }

    private static double flat(Block b, Location c) {
        return Math.hypot(b.getX() + 0.5 - c.getX(), b.getZ() + 0.5 - c.getZ());
    }

    /** Cracks growing on the blocks from now until {@code ticks} later (when they break). */
    static void crack(Plugin plugin, Location center, MobTemplate.Flood f, int ticks) {
        List<Block> blocks = blocks(center, f);
        if (blocks.isEmpty()) return;
        double far = Math.max(1, flat(blocks.get(blocks.size() - 1), center));
        World w = center.getWorld();
        int source = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        w.playSound(center, Sound.BLOCK_GLASS_BREAK, 1.5f, 1.4f);
        w.playSound(center, Sound.BLOCK_POWDER_SNOW_BREAK, 2f, 0.5f);
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                t += 2;
                if (t > ticks) {
                    cancel();
                    return;
                }
                double k = (double) t / ticks;
                for (Block b : blocks) {
                    // the cracks start in the middle and reach the edge, each block a little behind the previous ones
                    double d = flat(b, center) / far;
                    float progress = (float) Math.max(0, Math.min(0.95, (k * 1.4 - d * 0.6)));
                    if (progress <= 0) continue;
                    for (Player p : w.getPlayers()) p.sendBlockDamage(b.getLocation(), progress, source + b.hashCode());
                }
                if (t % 4 == 0) {
                    Block b = blocks.get(ThreadLocalRandom.current().nextInt(blocks.size()));
                    Location l = b.getLocation().add(0.5, 1, 0.5);
                    w.spawnParticle(Particle.BLOCK, l, 8, 0.4, 0.05, 0.4, 0.05, b.getBlockData());
                    w.playSound(l, Sound.BLOCK_GLASS_HIT, 1f, 0.6f + (float) k * 0.6f);
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    /** The blocks burst into the new block from the centre out, shards flying, a splash in the middle. */
    static void burst(Plugin plugin, Location center, MobTemplate.Flood f) {
        List<Block> blocks = blocks(center, f);
        if (blocks.isEmpty()) return;
        World w = center.getWorld();
        double far = Math.max(1, flat(blocks.get(blocks.size() - 1), center));
        int spread = (int) Math.min(SPREAD_MAX, Math.ceil(far / SPEED));
        BlockData into = f.to().createBlockData();
        w.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.6f);
        w.playSound(center, Sound.BLOCK_GLASS_BREAK, 3f, 0.5f);
        w.playSound(center, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 2f, 0.6f);
        // the water bursts up in the middle
        w.spawnParticle(Particle.SPLASH, center.clone().add(0, 0.5, 0), 300, 1.2, 1.5, 1.2, 0.4);
        w.spawnParticle(Particle.FALLING_WATER, center.clone().add(0, 3, 0), 120, 1.2, 2, 1.2, 0);
        w.spawnParticle(Particle.CLOUD, center.clone().add(0, 0.6, 0), 40, 1.5, 0.3, 1.5, 0.05);
        for (Block b : blocks) {
            int delay = (int) Math.round(flat(b, center) / far * spread);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!f.from().contains(b.getType())) return;
                BlockData was = b.getBlockData();
                Location l = b.getLocation().add(0.5, 1, 0.5);
                b.setBlockData(into, false);
                w.spawnParticle(Particle.BLOCK, l, 14, 0.4, 0.1, 0.4, 0.15, was);
                w.spawnParticle(Particle.SPLASH, l, 10, 0.3, 0.2, 0.3, 0.2);
                if (ThreadLocalRandom.current().nextDouble() < 0.4) shard(plugin, l, center, was);
                if (ThreadLocalRandom.current().nextDouble() < 0.25) {
                    w.playSound(l, Sound.BLOCK_GLASS_BREAK, 1f, 0.7f + ThreadLocalRandom.current().nextFloat() * 0.5f);
                }
            }, delay);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> w.playSound(center, Sound.AMBIENT_UNDERWATER_ENTER, 2f, 0.8f), spread);
        if (f.pull() > 0) Bukkit.getScheduler().runTaskLater(plugin, () -> pull(center, f, blocks), spread / 2 + 2);
    }

    /**
     * The surge: every fighting player within {@code pull} blocks still standing on dry ground is thrown onto the
     * nearest changed block (into the water), whatever the shape of the area.
     */
    private static void pull(Location center, MobTemplate.Flood f, List<Block> changed) {
        World w = center.getWorld();
        for (Player p : w.getPlayers()) {
            if (!Mobs.fighting(p) || p.getLocation().distanceSquared(center) > f.pull() * f.pull()) continue;
            Location pl = p.getLocation();
            if (pl.getBlock().getType() == f.to() || pl.clone().add(0, -0.5, 0).getBlock().getType() == f.to()) continue;
            Block near = null;
            double best = Double.MAX_VALUE;
            for (Block b : changed) {
                double d = Math.hypot(b.getX() + 0.5 - pl.getX(), b.getZ() + 0.5 - pl.getZ());
                // a little way in, not on the very edge: aim two blocks past the nearest one, towards the centre
                if (d < best) {
                    best = d;
                    near = b;
                }
            }
            if (near == null) continue;
            Vector to = near.getLocation().add(0.5, 0, 0.5).toVector().subtract(pl.toVector()).setY(0);
            Vector in = center.toVector().subtract(near.getLocation().add(0.5, 0, 0.5).toVector()).setY(0);
            if (in.lengthSquared() > 0.01) to.add(in.normalize().multiply(Math.min(2.5, in.length())));
            double dist = to.length();
            if (dist < 0.1) continue;
            // a hop that lands there: about 14 ticks of flight with vanilla drag
            p.setVelocity(to.normalize().multiply(Math.min(2.2, dist / 9.5)).setY(0.55));
            w.spawnParticle(Particle.SPLASH, pl.clone().add(0, 0.5, 0), 40, 0.4, 0.4, 0.4, 0.3);
            w.playSound(pl, Sound.ENTITY_GENERIC_SPLASH, 1.5f, 0.7f);
            if (!f.pullTitle().isBlank()) {
                p.showTitle(net.kyori.adventure.title.Title.title(net.kyori.adventure.text.Component.empty(),
                        fr.influencraft.flux.api.text.Texts.parse(f.pullTitle()), net.kyori.adventure.title.Title.Times.times(
                                java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(1500), java.time.Duration.ofMillis(400))));
            }
        }
    }

    /** A shard of the broken block thrown up and out, spinning, falling back into the water. */
    private static void shard(Plugin plugin, Location from, Location center, BlockData data) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        float size = 0.25f + r.nextFloat() * 0.35f;
        BlockDisplay d = from.getWorld().spawn(from.clone().add(0, 0.1, 0), BlockDisplay.class, e -> {
            e.setBlock(data);
            e.setPersistent(false);
            e.setTeleportDuration(1);
            e.setInterpolationDuration(1);
            e.setTransformation(new Transformation(new Vector3f(-size / 2, -size / 2, -size / 2), new AxisAngle4f(),
                    new Vector3f(size, size, size), new AxisAngle4f()));
        });
        Vector out = from.toVector().subtract(center.toVector()).setY(0);
        if (out.lengthSquared() < 0.01) out = new Vector(r.nextDouble() - 0.5, 0, r.nextDouble() - 0.5);
        Vector v = out.normalize().multiply(0.12 + r.nextDouble() * 0.2).setY(0.45 + r.nextDouble() * 0.4);
        Vector3f axis = new Vector3f(r.nextFloat() - 0.5f, r.nextFloat() - 0.5f, r.nextFloat() - 0.5f).normalize();
        float spin = 0.25f + r.nextFloat() * 0.35f;
        new BukkitRunnable() {
            int t;
            final Location at = d.getLocation();

            @Override
            public void run() {
                if (!d.isValid() || ++t > SHARD_LIFE || at.getY() < from.getY() - 1.5) {
                    if (d.isValid()) {
                        at.getWorld().spawnParticle(Particle.SPLASH, at, 6, 0.1, 0.1, 0.1, 0.1);
                        d.remove();
                    }
                    cancel();
                    return;
                }
                at.add(v);
                v.setY(v.getY() - 0.08).multiply(0.98);
                d.teleport(at);
                Quaternionf q = new Quaternionf().rotateAxis(spin * t, axis);
                d.setTransformation(new Transformation(new Vector3f(-size / 2, -size / 2, -size / 2), q,
                        new Vector3f(size, size, size), new Quaternionf()));
                d.setInterpolationDelay(0);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
}
