package io.dofault.supermarket.model;

import org.bukkit.Location;
import org.bukkit.block.Block;

public class BlockPos {

    private final String world;
    private final int x, y, z;

    public BlockPos(String world, int x, int y, int z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public String getWorld() { return world; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }

    public boolean equals(Location loc) {
        return loc.getWorld().getName().equals(world)
                && loc.getBlockX() == x
                && loc.getBlockY() == y
                && loc.getBlockZ() == z;
    }

    public boolean matches(Block blockBelow) {
        return blockBelow.getWorld().getName().equals(world) &&
                blockBelow.getX() == x &&   blockBelow.getY() == y &&
                blockBelow.getZ() == z &&
                blockBelow.getY() == y;
    }
}
