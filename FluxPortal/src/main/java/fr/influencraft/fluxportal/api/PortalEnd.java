package fr.influencraft.fluxportal.api;

import org.bukkit.util.Vector;

/**
 * One end of a portal: the centre of its ring and the way its front faces ({@code yaw}, Minecraft degrees: 0 =
 * south, 90 = west). The ring stands upright; a player crossing it from the front comes out of the other end's front.
 */
public record PortalEnd(String world, double x, double y, double z, float yaw) {

    public Vector center() {
        return new Vector(x, y, z);
    }

    /** Unit vector the front faces (horizontal). */
    public Vector normal() {
        return direction(yaw);
    }

    /** Unit vector to the right of someone looking at the front from outside. */
    public Vector right() {
        Vector n = normal();
        return new Vector(n.getZ(), 0, -n.getX());
    }

    public static Vector direction(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vector(-Math.sin(r), 0, Math.cos(r));
    }
}
