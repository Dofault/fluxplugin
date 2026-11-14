package fr.influencraft.fluxmascot;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Where the robot can stand and how it walks from a place to another (A* over blocks, steps of one block at most). */
final class Ground {

    private static final int CLEARANCE = 2;         // free blocks needed above the floor (the robot is under 2 tall)
    private static final int MAX_VISITS = 6000;

    private Ground() {
    }

    /** Height of the floor in this column near a height (top of a block with room above), NaN when none. */
    static double floor(World w, int x, int z, double nearY) {
        int from = (int) Math.floor(nearY + 1e-6) + 1;
        for (int y = from; y >= from - 3; y--) {
            Block b = w.getBlockAt(x, y, z);
            if (b.isPassable() || b.isLiquid()) continue;
            boolean free = true;
            for (int i = 1; i <= CLEARANCE && free; i++) free = w.getBlockAt(x, y + i, z).isPassable()
                    && !w.getBlockAt(x, y + i, z).isLiquid();
            if (!free) continue;
            double top = b.getBoundingBox().getMaxY();
            if (top <= y) top = y + 1;
            if (top - y > 1.01) continue;            // fences, walls: nothing stands on them
            return top;
        }
        return Double.NaN;
    }

    /** The floor at a place, null when there is none. */
    static Location at(Location l, double nearY) {
        double y = floor(l.getWorld(), l.getBlockX(), l.getBlockZ(), nearY);
        return Double.isNaN(y) ? null : new Location(l.getWorld(), l.getX(), y, l.getZ());
    }

    private record Cell(int x, int z, int h2) {     // h2: floor height in half blocks
    }

    /** Way points (centres of columns, on the floor) from a place to another; empty when no way was found. */
    static List<Location> path(Location from, Location to) {
        World w = from.getWorld();
        double fy = floor(w, from.getBlockX(), from.getBlockZ(), from.getY());
        double ty = floor(w, to.getBlockX(), to.getBlockZ(), to.getY());
        if (Double.isNaN(fy) || Double.isNaN(ty)) return List.of();
        Cell start = new Cell(from.getBlockX(), from.getBlockZ(), (int) Math.round(fy * 2));
        int gx = to.getBlockX(), gz = to.getBlockZ();
        Map<Cell, Cell> parent = new HashMap<>();
        Map<Cell, Double> cost = new HashMap<>();
        PriorityQueue<Object[]> open = new PriorityQueue<>((a, b) -> Double.compare((double) a[0], (double) b[0]));
        cost.put(start, 0.0);
        open.add(new Object[]{0.0, start});
        Cell goal = null;
        int visits = 0;
        while (!open.isEmpty() && visits++ < MAX_VISITS) {
            Cell c = (Cell) open.poll()[1];
            if (c.x == gx && c.z == gz && Math.abs(c.h2 / 2.0 - ty) <= 1.5) {
                goal = c;
                break;
            }
            double base = cost.get(c);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Cell n = step(w, c, dx, dz);
                    if (n == null) continue;
                    if (dx != 0 && dz != 0 && (step(w, c, dx, 0) == null || step(w, c, 0, dz) == null)) continue;
                    double g = base + (dx != 0 && dz != 0 ? 1.414 : 1.0) + Math.abs(n.h2 - c.h2) * 0.25;
                    Double old = cost.get(n);
                    if (old != null && old <= g) continue;
                    cost.put(n, g);
                    parent.put(n, c);
                    int ax = Math.abs(n.x - gx), az = Math.abs(n.z - gz);
                    open.add(new Object[]{g + Math.max(ax, az) + 0.414 * Math.min(ax, az), n});
                }
            }
        }
        if (goal == null) return List.of();
        List<Location> out = new ArrayList<>();
        for (Cell c = goal; c != null && !c.equals(start); c = parent.get(c)) {
            out.add(new Location(w, c.x + 0.5, c.h2 / 2.0, c.z + 0.5));
        }
        Collections.reverse(out);
        return out;
    }

    private static Cell step(World w, Cell c, int dx, int dz) {
        double h = c.h2 / 2.0;
        double n = floor(w, c.x + dx, c.z + dz, h + 1);
        if (Double.isNaN(n) || n - h > 1.01 || h - n > 1.51) return null;
        return new Cell(c.x + dx, c.z + dz, (int) Math.round(n * 2));
    }
}
