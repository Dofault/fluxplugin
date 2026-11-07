package fr.influencraft.fluxdungeons.script;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code Zone} of a script: a shape placed on one or more origins. Shapes and keys follow the EliteScript
 * format so dungeons made for EliteMobs run unchanged:
 * <ul>
 *   <li>SPHERE, DOME: {@code radius}, inner {@code borderRadius} (the border is between the two);</li>
 *   <li>CYLINDER: origin at the centre of the bottom face, {@code radius}, {@code height} upwards, {@code borderRadius};</li>
 *   <li>CUBOID: full lengths {@code x} (and {@code z}, defaults to x) centred on the origin, {@code y} upwards; inner
 *   {@code xBorder}, {@code yBorder}, {@code zBorder};</li>
 *   <li>STATIC_RAY, TRANSLATING_RAY, ROTATING_RAY: a segment from {@code Target} to {@code Target2},
 *   {@code pointRadius} thick; translating rays move to {@code FinalTarget}/{@code FinalTarget2}, rotating rays turn by
 *   {@code yawRotation}/{@code pitchRotation} degrees, both over {@code animationDuration} ticks.</li>
 * </ul>
 * {@code filter}: PLAYER (default), ELITE (dungeon monsters), LIVING.
 */
public final class Zone {

    final String shape;
    final double radius, borderRadius, height, x, y, z, xBorder, yBorder, zBorder, pointRadius;
    final double yawRotation, pitchRotation, yawPre, pitchPre;
    final int animationDuration;
    final String filter;
    final Map<?, ?> target, target2, finalTarget, finalTarget2;

    Zone(Map<?, ?> m) {
        shape = Script.text(m, "shape", "CYLINDER").toUpperCase(Locale.ROOT);
        radius = Script.num(m, "radius", 5);
        borderRadius = Script.num(m, "borderRadius", 1);
        height = Script.num(m, "height", 1);
        x = Script.num(m, "x", 1);
        y = Script.num(m, "y", 1);
        z = Script.num(m, "z", x);
        xBorder = Script.num(m, "xBorder", 0);
        yBorder = Script.num(m, "yBorder", 0);
        double zb = Script.num(m, "zBorder", 0);
        zBorder = zb == 0 ? xBorder : zb;
        pointRadius = Math.max(0.2, Script.num(m, "pointRadius", 0.5));
        yawRotation = Script.num(m, "yawRotation", 0);
        pitchRotation = Script.num(m, "pitchRotation", 0);
        yawPre = Script.num(m, "yawPreRotation", 0);
        pitchPre = Script.num(m, "pitchPreRotation", 0);
        animationDuration = (int) Script.num(m, "animationDuration", 0);
        filter = Script.text(m, "filter", "PLAYER").toUpperCase(Locale.ROOT);
        target = Script.map(m, "Target");
        target2 = Script.map(m, "Target2");
        finalTarget = Script.map(m, "FinalTarget");
        finalTarget2 = Script.map(m, "FinalTarget2");
    }

    boolean ray() {
        return shape.endsWith("_RAY");
    }

    /** The zone placed on one origin (and second point for rays), at a moment of its animation. */
    public final class Placed {
        final Location a;
        final Location b;

        Placed(Location a, Location b) {
            this.a = a;
            this.b = b;
        }

        boolean contains(Location l, boolean border) {
            if (l.getWorld() != a.getWorld()) return false;
            double dx = l.getX() - a.getX(), dy = l.getY() - a.getY(), dz = l.getZ() - a.getZ();
            switch (shape) {
                case "SPHERE", "DOME" -> {
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (shape.equals("DOME") && dy < -0.5) return false;
                    return d <= radius && (!border || d >= borderRadius);
                }
                case "CUBOID" -> {
                    boolean in = Math.abs(dx) <= x / 2 && dy >= 0 && dy < y && Math.abs(dz) <= z / 2;
                    if (!in || !border) return in;
                    boolean inner = Math.abs(dx) < xBorder / 2 && dy >= 0 && dy < yBorder && Math.abs(dz) < zBorder / 2;
                    return !inner;
                }
                default -> {
                    if (ray()) return segmentDistance(l) <= pointRadius;
                    double h = Math.sqrt(dx * dx + dz * dz);
                    return h <= radius && dy >= -0.01 && dy <= height && (!border || h >= borderRadius);
                }
            }
        }

        private double segmentDistance(Location l) {
            if (b == null) return l.distance(a);
            Vector p = l.toVector(), s = a.toVector(), e = b.toVector();
            Vector se = e.clone().subtract(s);
            double len2 = se.lengthSquared();
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, p.clone().subtract(s).dot(se) / len2));
            return p.distance(s.add(se.multiply(t)));
        }

        /** Block-centre points of the zone (or of its border). */
        List<Location> points(boolean border) {
            World w = a.getWorld();
            List<Location> out = new ArrayList<>();
            if (ray()) {
                Location end = b == null ? a : b;
                double len = a.distance(end);
                int steps = (int) Math.min(500, Math.max(1, len / pointRadius));
                Vector step = end.toVector().subtract(a.toVector()).multiply(1.0 / steps);
                Location cur = a.clone();
                for (int i = 0; i <= steps; i++) {
                    out.add(cur.clone());
                    cur.add(step);
                }
                return out;
            }
            if (shape.equals("CUBOID")) {
                int ix = (int) x, iy = (int) y, iz = (int) z;
                int sx = -(int) Math.floor(ix / 2.0), sz = -(int) Math.floor(iz / 2.0);
                int bx = a.getBlockX(), by = a.getBlockY(), bz = a.getBlockZ();
                for (int i = sx; i < sx + ix; i++) {
                    for (int j = 0; j < iy; j++) {
                        for (int k = sz; k < sz + iz; k++) {
                            if (border && Math.abs(i) < xBorder / 2 && j < yBorder && Math.abs(k) < zBorder / 2) continue;
                            out.add(new Location(w, bx + i + 0.5, by + j, bz + k + 0.5));
                        }
                    }
                }
                return out;
            }
            int r = (int) Math.ceil(radius);
            int top = shape.equals("CYLINDER") ? (int) Math.ceil(height) : r;
            int bottom = shape.equals("SPHERE") ? -r : 0;
            int bx = a.getBlockX(), by = a.getBlockY(), bz = a.getBlockZ();
            for (int i = -r; i <= r; i++) {
                for (int j = bottom; j <= top; j++) {
                    for (int k = -r; k <= r; k++) {
                        Location l = new Location(w, bx + i + 0.5, by + j, bz + k + 0.5);
                        if (contains(l, border)) out.add(l);
                    }
                }
            }
            return out;
        }

        /** Entities of the filter inside the zone (or its border). */
        List<Entity> entities(boolean border, java.util.function.Predicate<Entity> elite) {
            BoundingBox box = bounds();
            List<Entity> out = new ArrayList<>();
            for (Entity e : a.getWorld().getNearbyEntities(box)) {
                if (!(e instanceof org.bukkit.entity.LivingEntity)) continue;
                boolean ok = switch (filter) {
                    case "ELITE" -> elite.test(e);
                    case "LIVING" -> true;
                    default -> e instanceof org.bukkit.entity.Player;
                };
                if (!ok) continue;
                Location l = e.getLocation();
                if (contains(l, border) || contains(l.clone().add(0, 1, 0), border)) out.add(e);
            }
            return out;
        }

        private BoundingBox bounds() {
            if (ray()) {
                Location end = b == null ? a : b;
                return BoundingBox.of(a.toVector(), end.toVector()).expand(pointRadius + 1);
            }
            double r = shape.equals("CUBOID") ? Math.max(x, z) / 2 + 1 : radius + 1;
            double up = shape.equals("CUBOID") ? y + 1 : shape.equals("CYLINDER") ? height + 1 : radius + 1;
            double down = shape.equals("SPHERE") || shape.equals("DOME") ? radius + 1 : 1;
            return new BoundingBox(a.getX() - r, a.getY() - down, a.getZ() - r, a.getX() + r, a.getY() + up, a.getZ() + r);
        }
    }

    /** Animated rays: where the ray is {@code elapsed} ticks after the script started. */
    Placed place(Location a, Location b, Location finalA, Location finalB, long elapsed) {
        if (animationDuration > 0 && shape.equals("TRANSLATING_RAY") && finalA != null) {
            double t = Math.min(1, elapsed / (double) animationDuration);
            Location na = lerp(a, finalA, t);
            Location nb = b == null ? null : lerp(b, finalB == null ? b : finalB, t);
            return new Placed(na, nb);
        }
        if (shape.equals("ROTATING_RAY") && b != null) {
            double t = animationDuration > 0 ? Math.min(1, elapsed / (double) animationDuration) : 0;
            Vector v = b.toVector().subtract(a.toVector());
            v.rotateAroundY(-Math.toRadians(yawPre + yawRotation * t));
            return new Placed(a, a.clone().add(v));
        }
        return new Placed(a, b);
    }

    private static Location lerp(Location from, Location to, double t) {
        return from.clone().add(to.toVector().subtract(from.toVector()).multiply(t));
    }
}
