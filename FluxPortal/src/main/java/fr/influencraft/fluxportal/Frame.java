package fr.influencraft.fluxportal;

import fr.influencraft.fluxportal.api.PortalEnd;
import org.bukkit.util.Vector;

/**
 * The frame of one side of a portal, without the server (tested by {@code FrameTest}): {@code a} runs across the
 * hole, {@code b} up from its middle, {@code c} out of its front. Going through is like walking through a door turned
 * to face elsewhere: a point at (a, b, c) of one side is (-a, b, -c) of the other, what was on the right stays on the
 * right, a speed keeps its size.
 */
public final class Frame {

    private final double cx, cy, cz;
    private final int facing, nx, nz, sx, sz;
    private final double halfWidth, halfHeight;

    public Frame(PortalEnd end, int width, int height) {
        this(end.x(), end.y(), end.z(), end.facing(), width, height);
    }

    Frame(double cx, double cy, double cz, int facing, int width, int height) {
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.facing = Math.floorMod(facing, 4);
        int[] n = PortalEnd.DIRECTIONS[this.facing], s = PortalEnd.DIRECTIONS[(this.facing + 1) % 4];
        nx = n[0];
        nz = n[1];
        sx = s[0];
        sz = s[1];
        halfWidth = width / 2.0;
        halfHeight = height / 2.0;
    }

    public int facing() {
        return facing;
    }

    public double halfWidth() {
        return halfWidth;
    }

    public double halfHeight() {
        return halfHeight;
    }

    public int nx() {
        return nx;
    }

    public int nz() {
        return nz;
    }

    public double a(double x, double z) {
        return (x - cx) * sx + (z - cz) * sz;
    }

    public double b(double y) {
        return y - cy;
    }

    public double c(double x, double z) {
        return (x - cx) * nx + (z - cz) * nz;
    }

    public double worldX(double a, double c) {
        return cx + a * sx + c * nx;
    }

    public double worldY(double b) {
        return cy + b;
    }

    public double worldZ(double a, double c) {
        return cz + a * sz + c * nz;
    }

    /** Height of the middle above its block (0 for an even height, 0.5 for an odd one). */
    public double heightOffset() {
        return cy - Math.floor(cy);
    }

    /** Lowest block row of the hole. */
    public int bottomY() {
        return (int) Math.floor(cy - halfHeight + 1e-6);
    }

    /** Degrees a traveller turns going from this side to {@code to} (the front of one leads out of the other's front). */
    public float turnTo(Frame to) {
        return Math.floorMod(to.facing + 2 - facing, 4) * 90f;
    }

    /** Where a point of this side is on the side {@code to}. */
    public Vector carry(Frame to, double x, double y, double z) {
        double a = a(x, z), b = b(y), c = c(x, z);
        return new Vector(to.worldX(-a, -c), to.worldY(b), to.worldZ(-a, -c));
    }

    /** A direction (a speed) of this side as the side {@code to} has it. */
    public Vector carryDirection(Frame to, Vector v) {
        double a = v.getX() * sx + v.getZ() * sz;
        double c = v.getX() * nx + v.getZ() * nz;
        return new Vector(-a * to.sx - c * to.nx, v.getY(), -a * to.sz - c * to.nz);
    }

    /**
     * Whether a move of the eyes from (x0, y0, z0) to (x1, y1, z1) goes into the hole from its front, at least
     * {@code margin} inside its edges. From behind, a portal is nothing: no window is drawn there.
     */
    public boolean entered(double x0, double y0, double z0, double x1, double y1, double z1, double margin) {
        double c0 = c(x0, z0), c1 = c(x1, z1);
        if (!(c0 > 0 && c1 <= 0)) return false;
        double s = c0 / (c0 - c1);
        double a = a(x0 + (x1 - x0) * s, z0 + (z1 - z0) * s);
        double b = b(y0 + (y1 - y0) * s);
        return Math.abs(a) <= halfWidth - margin && Math.abs(b) <= halfHeight - margin;
    }
}
