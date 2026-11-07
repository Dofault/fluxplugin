package fr.influencraft.fluxdungeons.dungeon;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BoundingBox;

import java.util.List;

/**
 * Draws a dungeon's template from its definition, block by block: room shells (floor, walls, ceiling), corridors,
 * open doorways, pillars, hanging lights and decor along the walls, all in the materials of its {@code theme}. The
 * result is deterministic (same definition, same dungeon), so rebuilding never surprises; it is a starting point the
 * staff may retouch by hand in the template ({@code /fluxdungeons template}).
 */
final class Builder {

    private static final BlockData AIR = Material.AIR.createBlockData();

    private final DungeonDefinition def;
    private final DungeonDefinition.Layout layout;
    private final List<Material> floor, wall, ceiling, accent, decor, corner;
    private final BlockData pillar, light, bossLight;

    Builder(DungeonDefinition def) {
        this.def = def;
        this.layout = def.layout();
        floor = orDefault(def.materials("floor"), Material.STONE_BRICKS);
        wall = orDefault(def.materials("wall"), Material.STONE_BRICKS);
        ceiling = orDefault(def.materials("ceiling"), Material.STONE_BRICKS);
        accent = def.materials("accent");
        decor = def.materials("decor");
        corner = def.materials("corner");
        pillar = def.material("pillar", Material.POLISHED_BASALT).createBlockData();
        light = hanging(def.material("light", Material.LANTERN));
        bossLight = hanging(def.material("boss-light", Material.SOUL_LANTERN));
    }

    /** Block at a position relative to the dungeon's origin. */
    BlockData at(int x, int y, int z) {
        List<BoundingBox> rooms = layout.rooms();
        for (int i = 0; i < rooms.size(); i++) {
            BlockData b = room(i, rooms.get(i), x, y, z);
            if (b != null) return b;
        }
        for (BoundingBox c : layout.corridors()) {
            BlockData b = corridor(c, x, y, z);
            if (b != null) return b;
        }
        return AIR;
    }

    private BlockData room(int index, BoundingBox in, int x, int y, int z) {
        int minX = (int) in.getMinX(), maxX = (int) in.getMaxX(), minZ = (int) in.getMinZ(), maxZ = (int) in.getMaxZ();
        int height = (int) in.getMaxY();
        if (x < minX - 1 || x > maxX + 1 || z < minZ - 1 || z > maxZ + 1 || y < 0 || y > height + 1) return null;
        if (y == 0) return pick(floor, x, y, z, 11);
        if (y == height + 1) return pick(ceiling, x, y, z, 13);
        boolean wallX = x == minX - 1 || x == maxX + 1, wallZ = z == minZ - 1 || z == maxZ + 1;
        if (wallX || wallZ) {
            if (wallZ && !wallX && doorway(index, x, y, z == minZ - 1)) return AIR;
            if (!accent.isEmpty() && y == height - 1 && hash(x, y, z, 17) < 0.35) return pick(accent, x, y, z, 19);
            return pick(wall, x, y, z, 23);
        }
        // inside
        boolean big = maxX - minX >= 10;
        boolean pillarSpot = big && (x == minX + 1 || x == maxX - 1) && (z == minZ + 1 || z == maxZ - 1);
        if (pillarSpot) return pillar;
        boolean boss = def.rooms().get(index).boss() != null;
        if (y == height && (x - minX) % 4 == 2 && (z - minZ) % 4 == 2) return boss ? bossLight : light;
        boolean againstWall = x == minX || x == maxX || z == minZ || z == maxZ;
        boolean inFrontOfDoor = Math.abs(x - layout.centerX()) <= 2 && (z == minZ || z == maxZ);
        if (y == 1 && againstWall && !inFrontOfDoor && !decor.isEmpty() && hash(x, y, z, 29) < 0.14) return pick(decor, x, y, z, 31);
        boolean cornerSpot = (x == minX || x == maxX) && (z == minZ || z == maxZ);
        if (cornerSpot && y >= height - 1 && !corner.isEmpty()) return pick(corner, x, y, z, 37);
        return AIR;
    }

    /** Openings 3 wide and 3 high in the near wall (from the previous corridor) and the far wall (to the next). */
    private boolean doorway(int room, int x, int y, boolean nearWall) {
        if (Math.abs(x - layout.centerX()) > 1 || y > 3) return false;
        return nearWall ? room > 0 : room < def.rooms().size() - 1;
    }

    private BlockData corridor(BoundingBox in, int x, int y, int z) {
        int minX = (int) in.getMinX(), maxX = (int) in.getMaxX(), minZ = (int) in.getMinZ(), maxZ = (int) in.getMaxZ();
        if (z < minZ || z > maxZ || x < minX - 1 || x > maxX + 1 || y < 0 || y > 5) return null;
        if (y == 0) return pick(floor, x, y, z, 41);
        if (y == 5) return pick(ceiling, x, y, z, 43);
        if (x == minX - 1 || x == maxX + 1) return pick(wall, x, y, z, 47);
        if (y == 4 && x == layout.centerX() && z == (minZ + maxZ) / 2) return light;
        return AIR;
    }

    private static BlockData pick(List<Material> list, int x, int y, int z, int salt) {
        int i = (int) (hash(x, y, z, salt) * list.size());
        return list.get(Math.min(i, list.size() - 1)).createBlockData();
    }

    /** Stable pseudo-random number in [0, 1) for a position. */
    private static double hash(int x, int y, int z, int salt) {
        long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L ^ salt * 2654435761L;
        h ^= (h >>> 13);
        h *= 0x5bd1e995L;
        h ^= (h >>> 15);
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    private static BlockData hanging(Material lantern) {
        try {
            return Bukkit.createBlockData(lantern, "[hanging=true]");
        } catch (IllegalArgumentException e) {
            return lantern.createBlockData();
        }
    }

    private static List<Material> orDefault(List<Material> list, Material fallback) {
        return list.isEmpty() ? List.of(fallback) : list;
    }
}
