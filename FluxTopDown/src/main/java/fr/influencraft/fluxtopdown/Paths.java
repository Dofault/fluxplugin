package fr.influencraft.fluxtopdown;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Walking paths on the block grid for a player-sized hero: A* over standing places (feet height from the blocks'
 * collision shapes, 1.8 blocks of headroom), steps and jumps up to 1.2 blocks, drops down to 3, no corner cutting.
 * One instance per search: block shapes are cached for that search only.
 */
final class Paths {

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[] RISES = {0, 1, -1, -2, -3};
    private static final int BUDGET = 6000;

    private final World world;
    private final Map<Long, Double> tops = new HashMap<>();

    Paths(World world) {
        this.world = world;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Top of a block's collision (0 = nothing to collide with, above 1 for fences and walls, 99 for lava). */
    private double top(int x, int y, int z) {
        long k = key(x, y, z);
        Double cached = tops.get(k);
        if (cached != null) return cached;
        Block b = world.getBlockAt(x, y, z);
        double t = 0;
        if (b.getType() == Material.LAVA || b.getType() == Material.FIRE || b.getType() == Material.MAGMA_BLOCK) t = 99;
        else {
            for (BoundingBox box : b.getCollisionShape().getBoundingBoxes()) t = Math.max(t, Math.max(0.0001, box.getMaxY()));
        }
        tops.put(k, t);
        return t;
    }

    /** Feet height of a hero whose feet are in block (x, y, z), NaN when he cannot stand there. */
    double floor(int x, int y, int z) {
        double feet = top(x, y, z);
        if (feet > 0.6) return Double.NaN;
        double stand;
        if (feet > 0) stand = y + feet;
        else {
            double below = top(x, y - 1, z);
            if (below <= 0.6 || below > 1.01) return Double.NaN;
            stand = y - 1 + below;
        }
        for (int yy = y + 1; yy < stand + 1.8; yy++) if (top(x, yy, z) > 0) return Double.NaN;
        return stand;
    }

    /** Feet height near (x, y, z): the standing place of that column closest to y, NaN when none within 2 blocks. */
    double floorNear(int x, int y, int z) {
        for (int d : new int[]{0, 1, -1, 2, -2}) {
            double f = floor(x, y + d, z);
            if (!Double.isNaN(f)) return f;
        }
        return Double.NaN;
    }

    private record Node(int x, int y, int z, double stand, double f) {
    }

    /**
     * Points to walk through from {@code from} to {@code to} (feet positions, the last one as close to {@code to} as
     * can be reached), smoothed into straight walkable lines; empty when the hero cannot stand where he is.
     */
    List<Vector> find(Location from, Location to, int maxDistance) {
        int sx = from.getBlockX(), sz = from.getBlockZ();
        Node start = null;
        for (int d : new int[]{0, 1, -1}) {
            int y = (int) Math.floor(from.getY() + 0.01) + d;
            double f = floor(sx, y, sz);
            if (!Double.isNaN(f)) {
                start = new Node(sx, y, sz, f, 0);
                break;
            }
        }
        if (start == null) return List.of();
        int tx = to.getBlockX(), tz = to.getBlockZ(), ty = (int) Math.floor(to.getY() + 0.01);
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Map<Long, Double> cost = new HashMap<>();
        Map<Long, Node> came = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        open.add(start);
        cost.put(key(start.x, start.y, start.z), 0.0);
        Node best = start, goal = null;
        double bestH = h(start, tx, ty, tz);
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < BUDGET) {
            Node n = open.poll();
            long nk = key(n.x, n.y, n.z);
            if (!closed.add(nk)) continue;
            if (n.x == tx && n.z == tz && Math.abs(n.y - ty) <= 2) {
                goal = n;
                break;
            }
            double hn = h(n, tx, ty, tz);
            if (hn < bestH) {
                bestH = hn;
                best = n;
            }
            double g = cost.get(nk);
            for (int[] d : DIRS) {
                int nx = n.x + d[0], nz = n.z + d[1];
                if (Math.abs(nx - sx) > maxDistance || Math.abs(nz - sz) > maxDistance) continue;
                for (int rise : RISES) {
                    int ny = n.y + rise;
                    double f = floor(nx, ny, nz);
                    if (Double.isNaN(f)) continue;
                    double up = f - n.stand;
                    if (up > 1.2) continue;
                    if (up > 0.6 && !clear(n.x, n.y + 1, n.z, n.stand + 1.8 + up)) continue;
                    if (rise < 0 && !clear(nx, ny + 1, nz, n.stand + 1.8)) continue;
                    if (d[0] != 0 && d[1] != 0 && (!near(n.x + d[0], n.z, n.stand, f) || !near(n.x, n.z + d[1], n.stand, f))) break;
                    long k = key(nx, ny, nz);
                    if (closed.contains(k)) break;
                    double step = (d[0] != 0 && d[1] != 0 ? 1.414 : 1) + (up > 0.6 ? 0.4 : 0) + (up < -0.6 ? 0.2 * -up : 0);
                    double ng = g + step;
                    Double old = cost.get(k);
                    if (old == null || ng < old) {
                        cost.put(k, ng);
                        Node m = new Node(nx, ny, nz, f, 0);
                        came.put(k, n);
                        open.add(new Node(nx, ny, nz, f, ng + h(m, tx, ty, tz)));
                    }
                    break;
                }
            }
        }
        Node end = goal != null ? goal : best;
        List<Vector> points = new ArrayList<>();
        for (Node n = end; n != null && !(n.x == start.x && n.y == start.y && n.z == start.z); n = came.get(key(n.x, n.y, n.z))) {
            points.add(new Vector(n.x + 0.5, n.stand, n.z + 0.5));
        }
        Collections.reverse(points);
        if (goal != null && !points.isEmpty()) {
            // the clicked spot itself, not the middle of its block
            Vector last = points.get(points.size() - 1);
            points.set(points.size() - 1, new Vector(to.getX(), last.getY(), to.getZ()));
        }
        return smooth(from.toVector(), points);
    }

    private static double h(Node n, int tx, int ty, int tz) {
        int dx = Math.abs(n.x - tx), dz = Math.abs(n.z - tz);
        return Math.max(dx, dz) + 0.414 * Math.min(dx, dz) + 0.5 * Math.abs(n.y - ty);
    }

    /** Nothing to collide with in column (x, z) from block y up to height {@code upTo}. */
    private boolean clear(int x, int y, int z, double upTo) {
        for (int yy = y; yy < upTo; yy++) if (top(x, yy, z) > 0) return false;
        return true;
    }

    /** The cell beside a diagonal step can be walked at about the same height (no cutting through a corner). */
    private boolean near(int x, int z, double a, double b) {
        double f = floorNear(x, (int) Math.floor(Math.max(a, b) + 0.01), z);
        return !Double.isNaN(f) && Math.abs(f - a) <= 0.6 && Math.abs(f - b) <= 0.6;
    }

    /** Keeps the points where the way turns: a straight line is kept while it can be walked flat. */
    private List<Vector> smooth(Vector from, List<Vector> points) {
        if (points.size() <= 1) return points;
        List<Vector> out = new ArrayList<>();
        Vector at = from;
        int i = 0;
        while (i < points.size()) {
            int far = i;
            for (int j = Math.min(points.size() - 1, i + 16); j > i; j--) {
                if (straight(at, points.get(j))) {
                    far = j;
                    break;
                }
            }
            out.add(points.get(far));
            at = points.get(far);
            i = far + 1;
        }
        return out;
    }

    /** A straight line a hero can walk: every sample, and its sides, stands within 0.6 blocks of the line. */
    boolean straight(Vector a, Vector b) {
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) return true;
        double px = -dz / len * 0.3, pz = dx / len * 0.3;
        int steps = (int) Math.ceil(len / 0.25);
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            double x = a.getX() + dx * t, z = a.getZ() + dz * t, y = a.getY() + (b.getY() - a.getY()) * t;
            for (int side = -1; side <= 1; side++) {
                double cx = x + px * side, cz = z + pz * side;
                double f = floorNear((int) Math.floor(cx), (int) Math.floor(y + 0.01), (int) Math.floor(cz));
                if (Double.isNaN(f) || Math.abs(f - y) > 0.6) return false;
            }
        }
        return true;
    }
}
