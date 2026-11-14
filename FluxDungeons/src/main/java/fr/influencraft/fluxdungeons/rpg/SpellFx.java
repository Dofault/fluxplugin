package fr.influencraft.fluxdungeons.rpg;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What the spells of the elemental classes are drawn with: block displays (never real blocks, nothing stays in the
 * map) that grow, fly and vanish, and the places spells are put at. One instance for every class: it knows every
 * display still standing and removes them when the plugin stops.
 */
final class SpellFx {

    private final Plugin plugin;
    private final Set<BlockDisplay> alive = new HashSet<>();

    SpellFx(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Removes every display still standing (plugin stopping). */
    void clear() {
        for (BlockDisplay d : alive) if (d.isValid()) d.remove();
        alive.clear();
    }

    // ------------------------------------------------------------------ displays

    /** A block display of the spells: never saved, lit as in daylight, tracked until {@link #forget}. */
    BlockDisplay display(Location at, BlockData data, Transformation tr) {
        return display(at, data, tr, 13);
    }

    /** The same, lit at a block light of its own (15: something that glows). */
    BlockDisplay display(Location at, BlockData data, Transformation tr, int light) {
        BlockDisplay d = at.getWorld().spawn(at, BlockDisplay.class, b -> {
            b.setBlock(data);
            b.setPersistent(false);
            b.setBrightness(new Display.Brightness(light, 15));
            b.setTransformation(tr);
            b.setViewRange(1.5f);
        });
        alive.add(d);
        return d;
    }

    /** A cube of {@code size} centred on its location. */
    static Transformation cube(float size) {
        return new Transformation(new Vector3f(-size / 2, -size / 2, -size / 2), new Quaternionf(), new Vector3f(size, size, size), new Quaternionf());
    }

    /** The same cube turned by {@code q} around its centre. */
    static Transformation cube(float size, Quaternionf q) {
        return new Transformation(q.transform(new Vector3f(-size / 2, -size / 2, -size / 2)), q, new Vector3f(size, size, size), new Quaternionf());
    }

    static void animate(BlockDisplay d, Transformation tr, int ticks) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(tr);
    }

    void later(BlockDisplay d, long ticks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> forget(d), ticks);
    }

    void forget(BlockDisplay d) {
        if (d == null) return;
        alive.remove(d);
        if (d.isValid()) d.remove();
    }

    // ------------------------------------------------------------------ shapes

    /** The {@code yaw} of {@link #spike} leaning towards this horizontal direction. */
    static float lean(double x, double z) {
        return (float) Math.atan2(x, z);
    }

    /**
     * Something bursting out of the ground at {@code base}: it grows from its foot along its own axis (leaning
     * {@code tilt} radians towards {@code yaw}, see {@link #lean}), overshoots a little, settles, then sinks back after
     * {@code life} ticks (never when {@code life} is negative: removed by its owner).
     */
    BlockDisplay spike(Location base, BlockData data, float width, float height, float yaw, float tilt, int life) {
        return spike(base, data, width, height, yaw, tilt, life, 13);
    }

    BlockDisplay spike(Location base, BlockData data, float width, float height, float yaw, float tilt, int life, int light) {
        Location at = base.clone();
        at.setYaw(0);
        at.setPitch(0);
        Quaternionf q = new Quaternionf().rotateY(yaw).rotateX(tilt).rotateY(rnd(1.6f));
        Vector3f offset = q.transform(new Vector3f(-width / 2, 0, -width / 2));
        BlockDisplay d = display(at, data, new Transformation(offset, q, new Vector3f(width, 0.01f, width), new Quaternionf()), light);
        at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 0.15, 0), 5, width * 0.4, 0.05, width * 0.4, 0, data);
        Bukkit.getScheduler().runTaskLater(plugin, () -> animate(d, new Transformation(offset, q, new Vector3f(width, height * 1.12f, width), new Quaternionf()), 2), 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> animate(d, new Transformation(offset, q, new Vector3f(width, height, width), new Quaternionf()), 3), 3L);
        if (life > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> animate(d, new Transformation(offset, q, new Vector3f(width * 0.8f, 0.01f, width * 0.8f), new Quaternionf()), 5), Math.max(6, life - 5L));
            later(d, life);
        }
        return d;
    }

    /** Small pieces flying out of a point, falling and spinning, then gone. */
    void debris(Location center, int count, Material[] of, int light) {
        for (int i = 0; i < count; i++) {
            float size = 0.12f + rnd(0.16f);
            BlockDisplay s = display(center, of[ThreadLocalRandom.current().nextInt(of.length)].createBlockData(), cube(size), light);
            s.setTeleportDuration(1);
            Vector v = new Vector(ThreadLocalRandom.current().nextGaussian(), 0.6 + ThreadLocalRandom.current().nextDouble(), ThreadLocalRandom.current().nextGaussian())
                    .normalize().multiply(0.35 + ThreadLocalRandom.current().nextDouble(0.3));
            new BukkitRunnable() {
                final Location at = center.clone();
                int t;

                @Override
                public void run() {
                    if (++t > 16 || !s.isValid()) {
                        cancel();
                        forget(s);
                        return;
                    }
                    at.add(v);
                    v.setY(v.getY() - 0.06);
                    if (!at.getBlock().isPassable()) {
                        v.multiply(0);
                        at.setY(Math.floor(at.getY()) + 1.02);
                    }
                    s.teleport(at);
                    animate(s, cube(size, new Quaternionf().rotateXYZ(t * 0.6f, t * 0.4f, 0)), 1);
                }
            }.runTaskTimer(plugin, 1L, 1L);
        }
    }

    /** A ring of one particle on the ground. */
    static void ring(Location at, double radius, int points, Particle particle, Object data) {
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            at.getWorld().spawnParticle(particle, at.clone().add(Math.cos(a) * radius, 0.12, Math.sin(a) * radius), 1, 0, 0, 0, 0, data);
        }
    }

    // ------------------------------------------------------------------ places

    /** Feet level on the floor under (or just above) a place, null when there is none near. */
    static Location ground(Location l) {
        Block b = l.getBlock();
        for (int dy = 2; dy >= -6; dy--) {
            Block at = b.getRelative(0, dy, 0);
            if (at.isPassable() && !at.getRelative(0, -1, 0).isPassable()) {
                return new Location(l.getWorld(), l.getX(), at.getY(), l.getZ());
            }
        }
        return null;
    }

    /**
     * Where the player aims a spell put on the ground: the floor under the creature under his crosshair, else under
     * the block looked at, else under the end of the range; null when there is no floor there.
     */
    static Location aimedGround(LivingEntity p, double range, Skills skills) {
        LivingEntity looked = skills.lookedAt(p, range);
        if (looked != null) {
            // a flying creature: the floor under it when there is one near, else where it is
            Location under = ground(looked.getLocation().add(0, 0.1, 0));
            return under != null ? under : looked.getLocation();
        }
        Location eye = p.getEyeLocation();
        RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        Location at = hit != null ? hit.getHitPosition().toLocation(p.getWorld()).subtract(eye.getDirection().multiply(0.2))
                : eye.clone().add(eye.getDirection().multiply(range));
        return ground(at);
    }

    static float rnd(float max) {
        return ThreadLocalRandom.current().nextFloat() * max;
    }
}
