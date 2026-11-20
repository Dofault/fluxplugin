package fr.influencraft.fluxspawner;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One spawner, placed or carried as an item. Its data stays in the database whatever its form: the item only carries
 * its id, so a copied item cannot make a second spawner ({@code world == null}: in item form).
 */
final class Spawner {

    /** What a placed spawner is doing, shown in its hologram and menus. */
    enum Status { RUNNING, PAUSED, NO_FUEL, FULL, WAITING, BLOCKED, UNLOADED, ITEM }

    final UUID id;
    UUID owner;
    String ownerName;
    String type;
    int level;
    String world;
    int x, y, z;
    boolean active;
    /** Seconds of running left. */
    double fuel;
    /** Mobs waiting kept while the spawner is an item (put back when placed again). */
    int stock;
    /** Kills of its mobs by players, all levels together. */
    long kills;
    final long created;
    /** What players gave towards the next level, by goal key. */
    final Map<String, Long> progress = new HashMap<>();

    // ---------------------------------------------------------------- runtime only
    /** Fraction of a mob made but not spawned yet. */
    double pending;
    Status status = Status.UNLOADED;
    /** Mobs waiting at the spawner, counted at the last tick. */
    int waiting;
    UUID hologram;
    long lastAlert;
    boolean dirty;

    Spawner(UUID id, long created) {
        this.id = id;
        this.created = created;
    }

    boolean placed() {
        return world != null;
    }

    World bukkitWorld() {
        return world == null ? null : Bukkit.getWorld(world);
    }

    Location location() {
        World w = bukkitWorld();
        return w == null ? null : new Location(w, x, y, z);
    }

    /** Centre of the block. */
    Location centre() {
        Location l = location();
        return l == null ? null : l.add(0.5, 0.5, 0.5);
    }

    Block block() {
        World w = bukkitWorld();
        return w == null ? null : w.getBlockAt(x, y, z);
    }

    boolean chunkLoaded() {
        World w = bukkitWorld();
        return w != null && w.isChunkLoaded(x >> 4, z >> 4) && w.getChunkAt(x >> 4, z >> 4).isEntitiesLoaded();
    }

    long progress(String key) {
        return progress.getOrDefault(key, 0L);
    }

    static String blockKey(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    String blockKey() {
        return blockKey(world, x, y, z);
    }

    /** "x, y, z" for menus and messages. */
    String coordinates() {
        return x + ", " + y + ", " + z;
    }
}
