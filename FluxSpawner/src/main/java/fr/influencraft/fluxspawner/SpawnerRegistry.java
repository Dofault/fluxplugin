package fr.influencraft.fluxspawner;

import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Every spawner known, by id and by block (placed ones). Main thread. */
final class SpawnerRegistry {

    private final Map<UUID, Spawner> byId = new HashMap<>();
    private final Map<String, Spawner> byBlock = new HashMap<>();

    void clear() {
        byId.clear();
        byBlock.clear();
    }

    void add(Spawner s) {
        byId.put(s.id, s);
        if (s.placed()) byBlock.put(s.blockKey(), s);
    }

    void remove(Spawner s) {
        byId.remove(s.id);
        if (s.placed()) byBlock.remove(s.blockKey());
    }

    /** The spawner now sits there ({@code world} set) or left its block ({@code world} null). */
    void moved(Spawner s, String formerKey) {
        if (formerKey != null) byBlock.remove(formerKey);
        if (s.placed()) byBlock.put(s.blockKey(), s);
    }

    Spawner get(UUID id) {
        return id == null ? null : byId.get(id);
    }

    Spawner at(Block b) {
        return b == null ? null : byBlock.get(Spawner.blockKey(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
    }

    Collection<Spawner> all() {
        return byId.values();
    }

    Collection<Spawner> placed() {
        return byBlock.values();
    }

    /** Placed spawners of a player, oldest first. */
    List<Spawner> placedBy(UUID owner) {
        List<Spawner> out = new ArrayList<>();
        for (Spawner s : byBlock.values()) if (s.owner.equals(owner)) out.add(s);
        out.sort(Comparator.comparingLong(s -> s.created));
        return out;
    }

    int activeCount(UUID owner) {
        int n = 0;
        for (Spawner s : byBlock.values()) if (s.active && s.owner.equals(owner)) n++;
        return n;
    }

    /** The nearest placed spawner within {@code distance} blocks of that block (another one than {@code except}). */
    Spawner near(Block b, int distance, Spawner except) {
        String w = b.getWorld().getName();
        for (Spawner s : byBlock.values()) {
            if (s == except || !s.world.equals(w)) continue;
            if (Math.abs(s.x - b.getX()) <= distance && Math.abs(s.y - b.getY()) <= distance && Math.abs(s.z - b.getZ()) <= distance) return s;
        }
        return null;
    }
}
