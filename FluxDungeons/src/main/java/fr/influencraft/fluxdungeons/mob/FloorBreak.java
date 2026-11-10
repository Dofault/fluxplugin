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

    /** Ticks for the break to run from the centre to the edge. */
    private static final int SPREAD = 8;
    private static final int SHARD_LIFE = 28;

    private FloorBreak() {
    }

    /** Blocks that will change, nearest to the centre first. */
    static List<Block> blocks(Location center, MobTemplate.Flood f) {
        World w = center.getWorld();
        // the block the boss stands on: its feet are at the top of that block
        int cx = center.getBlockX(), cz = center.getBlockZ(), top = (int) Math.floor(center.getY() - 0.01);
        int r = (int) Math.ceil(f.radius());
        List<Block> out = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > f.radius() * f.radius()) continue;
                for (int dy = 0; dy < f.depth(); dy++) {
                    Block b = w.getBlockAt(cx + dx, top - dy, cz + dz);
                    if (f.from().contains(b.getType())) out.add(b);
                }
            }
        }
        out.sort(Comparator.comparingDouble(b -> flat(b, center)));
        return out;
    }

    private static double flat(Block b, Location c) {
        return Math.hypot(b.getX() + 0.5 - c.getX(), b.getZ() + 0.5 - c.getZ());
    }

    /** Cracks growing on the blocks from now until {@code ticks} later (when they break). */
    static void crack(Plugin plugin, Location center, MobTemplate.Flood f, int ticks) {
        List<Block> blocks = blocks(center, f);
        if (blocks.isEmpty()) return;
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
                    double d = flat(b, center) / Math.max(1, f.radius());
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
        World w = center.getWorld();
        BlockData into = f.to().createBlockData();
        w.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.6f);
        w.playSound(center, Sound.BLOCK_GLASS_BREAK, 3f, 0.5f);
        w.playSound(center, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 2f, 0.6f);
        // the water bursts up in the middle
        w.spawnParticle(Particle.SPLASH, center.clone().add(0, 0.5, 0), 300, 1.2, 1.5, 1.2, 0.4);
        w.spawnParticle(Particle.FALLING_WATER, center.clone().add(0, 3, 0), 120, 1.2, 2, 1.2, 0);
        w.spawnParticle(Particle.CLOUD, center.clone().add(0, 0.6, 0), 40, 1.5, 0.3, 1.5, 0.05);
        for (Block b : blocks) {
            int delay = (int) Math.round(flat(b, center) / Math.max(1, f.radius()) * SPREAD);
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
        Bukkit.getScheduler().runTaskLater(plugin, () -> w.playSound(center, Sound.AMBIENT_UNDERWATER_ENTER, 2f, 0.8f), SPREAD);
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
