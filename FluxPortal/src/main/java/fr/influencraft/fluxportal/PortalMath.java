package fr.influencraft.fluxportal;

import fr.influencraft.fluxportal.api.PortalEnd;
import org.bukkit.util.Vector;

/**
 * Geometry of the portals, without the server (tested by {@code PortalMathTest}): when a movement goes through a ring,
 * and where, facing where and how fast the traveller comes out of the other end. Going through is like walking through
 * a door turned to face elsewhere: what was on the right stays on the right, the speed keeps its size.
 */
public final class PortalMath {

    /** How far out of the exit ring the traveller is put, along its normal (blocks). */
    public static final double STEP_OUT = 0.7;

    /** Where the traveller comes out: point, direction (yaw, pitch unchanged) and velocity. */
    public record Exit(Vector point, float yaw, Vector velocity) {
    }

    private PortalMath() {
    }

    /**
     * Whether the move {@code from} → {@code to} (the body's middle) goes through the hole of radius {@code radius} of
     * this end, from either side. The crossing point must lie within the hole.
     */
    public static boolean crosses(PortalEnd end, Vector from, Vector to, double radius) {
        Vector c = end.center(), n = end.normal();
        double d0 = from.clone().subtract(c).dot(n), d1 = to.clone().subtract(c).dot(n);
        if (d0 == 0 && d1 == 0 || Math.signum(d0) == Math.signum(d1) && d1 != 0) return false;
        double t = d0 / (d0 - d1);
        Vector hit = from.clone().add(to.clone().subtract(from).multiply(t));
        return hit.distanceSquared(c) <= radius * radius;
    }

    /** Angle (degrees) turning the entry's frame into the exit's, the traveller's way reversed (in front, out front). */
    static double turn(PortalEnd entry, PortalEnd exit) {
        return exit.yaw() - entry.yaw() + 180;
    }

    /** Rotates a vector around the vertical axis by Minecraft degrees (the way yaw grows). */
    static Vector rotate(Vector v, double degrees) {
        double r = Math.toRadians(degrees);
        double cos = Math.cos(r), sin = Math.sin(r);
        // yaw grows clockwise seen from above: x' = x cos - z sin, z' = x sin + z cos
        return new Vector(v.getX() * cos - v.getZ() * sin, v.getY(), v.getX() * sin + v.getZ() * cos);
    }

    /**
     * Where someone whose body's middle went through {@code entry} at {@code point}, facing {@code yaw}, moving at
     * {@code velocity}, comes out of {@code exit}: same place on the ring, stepped out on the side he moves to.
     */
    public static Exit travel(PortalEnd entry, PortalEnd exit, Vector point, float yaw, Vector velocity) {
        double turn = turn(entry, exit);
        Vector offset = point.clone().subtract(entry.center());
        Vector n = entry.normal();
        // the part along the ring's plane is kept, the depth is replaced by the step out
        Vector flat = offset.clone().subtract(n.clone().multiply(offset.dot(n)));
        Vector out = rotate(flat, turn);
        Vector v = rotate(velocity, turn);
        double side = v.clone().dot(exit.normal()) >= 0 ? 1 : -1;
        if (v.lengthSquared() < 1e-8) side = 1;
        Vector at = exit.center().add(out).add(exit.normal().multiply(side * STEP_OUT));
        float newYaw = (float) (((yaw + turn) % 360 + 540) % 360 - 180);
        return new Exit(at, newYaw, v);
    }
}
