package fr.influencraft.fluxdungeons.rpg;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.function.BiFunction;

/**
 * Aim assist of the rpg, the same for every class ({@code aim-assist} of FluxDungeons' config): the enemy a player
 * meant to hit when his click or his shot passes beside it. An enemy counts when the line of his aim passes within
 * {@code margin} blocks of its hit box (a near miss, whatever the distance: a baby zombie twenty blocks away is as easy
 * as a big one), or when it lies within {@code angle} degrees of his view (close up, where a block aside is a wide
 * angle). Among those, the one closest to his aim wins; never through a wall, never behind him.
 * <p>
 * Melee takes it as the creature clicked when the click hit nothing; arrows, bolts and shards fly at it (an arrow
 * aimed a little higher, for its fall).
 */
final class AimAssist {

    private boolean enabled = true;
    private double angle = 12, margin = 1.2;

    void load(org.bukkit.configuration.ConfigurationSection c) {
        enabled = c == null || c.getBoolean("enabled", true);
        angle = c == null ? 12 : Math.max(0, Math.min(60, c.getDouble("angle", 12)));
        margin = c == null ? 1.2 : Math.max(0, Math.min(4, c.getDouble("margin", 1.2)));
    }

    /**
     * The enemy the player aims at within {@code range} blocks, null when none (or the assist is off).
     * {@code enemies}: the enemies around a place within a radius (Skills#enemies).
     */
    LivingEntity target(LivingEntity p, double range, BiFunction<Location, Double, List<LivingEntity>> enemies) {
        if (!enabled) return null;
        Location eye = p.getEyeLocation();
        Vector origin = eye.toVector(), look = eye.getDirection().normalize();
        double maxAngle = Math.toRadians(angle);
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity e : enemies.apply(eye, range + 2)) {
            BoundingBox box = e.getBoundingBox();
            Vector center = box.getCenter();
            double along = center.clone().subtract(origin).dot(look);
            if (along <= 0) continue;   // behind him
            // the point of his aim nearest to it, then how far that point is from its body
            Vector onRay = origin.clone().add(look.clone().multiply(Math.min(along, range)));
            double miss = distance(box, onRay);
            Vector near = nearest(box, origin);
            if (near.distanceSquared(origin) > range * range) continue;
            double off = look.angle(center.clone().subtract(origin));
            if (miss > margin && off > maxAngle) continue;
            // closest to his aim first; a near one before a far one at equal miss
            double score = miss + off * 0.5 + along * 0.01;
            if (score >= bestScore || !p.hasLineOfSight(e)) continue;
            bestScore = score;
            best = e;
        }
        return best;
    }

    /** Where a shot from {@code from} should fly to hit this creature: the middle of its body. */
    static Vector toward(Location from, LivingEntity target) {
        return target.getBoundingBox().getCenter().subtract(from.toVector()).normalize();
    }

    /**
     * The same for an arrow ({@code speed} blocks a tick, falling 0.05 a tick per tick): aimed higher by how much it
     * falls on its way, so that it lands on the creature.
     */
    static Vector arrowToward(Location from, LivingEntity target, double speed) {
        Vector to = target.getBoundingBox().getCenter().subtract(from.toVector());
        double ticks = to.length() / Math.max(0.1, speed);
        return to.add(new Vector(0, 0.025 * ticks * ticks, 0)).normalize();
    }

    private static double distance(BoundingBox box, Vector p) {
        return nearest(box, p).distance(p);
    }

    private static Vector nearest(BoundingBox box, Vector p) {
        return new Vector(clamp(p.getX(), box.getMinX(), box.getMaxX()), clamp(p.getY(), box.getMinY(), box.getMaxY()),
                clamp(p.getZ(), box.getMinZ(), box.getMaxZ()));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
