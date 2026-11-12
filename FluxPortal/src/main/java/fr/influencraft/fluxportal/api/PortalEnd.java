package fr.influencraft.fluxportal.api;

import org.bukkit.util.Vector;

/**
 * One side of a portal: the middle of its hole and the way its front faces. The hole stands upright on a block
 * edge, so {@code yaw} is a multiple of 90 (Minecraft degrees: 0 = south, 90 = west, 180 = north, 270 = east) and the
 * middle lies on the face of a block along the front, in the middle of a block across it (holes are an odd number of
 * blocks wide). Someone in front of it sees the other side and walks through to the other side's front.
 */
public record PortalEnd(String world, double x, double y, double z, float yaw) {

    /** Quarter turns from south (0 south, 1 west, 2 north, 3 east). */
    public int facing() {
        return Math.floorMod(Math.round(yaw / 90f), 4);
    }

    public Vector middle() {
        return new Vector(x, y, z);
    }

    /** Unit vector the front faces. */
    public Vector normal() {
        int[] d = DIRECTIONS[facing()];
        return new Vector(d[0], 0, d[1]);
    }

    /** Unit vector along the hole, the grid's "across" axis. */
    public Vector across() {
        int[] d = DIRECTIONS[(facing() + 1) % 4];
        return new Vector(d[0], 0, d[1]);
    }

    /** x and z of each facing, in order. */
    public static final int[][] DIRECTIONS = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};

    /**
     * The side standing on {@code floor} (the block under the middle of the hole's bottom row), its front facing
     * {@code facing}, the hole {@code height} blocks high: its plane is the face of the floor's column on the front.
     */
    public static PortalEnd standing(String world, int floorX, int floorY, int floorZ, int facing, int height) {
        int[] n = DIRECTIONS[Math.floorMod(facing, 4)];
        double x = floorX + (n[0] != 0 ? (n[0] > 0 ? 1.0 : 0.0) : 0.5);
        double z = floorZ + (n[1] != 0 ? (n[1] > 0 ? 1.0 : 0.0) : 0.5);
        return new PortalEnd(world, x, floorY + 1 + height / 2.0, z, Math.floorMod(facing, 4) * 90f);
    }
}
