package fr.influencraft.fluxdungeons.dungeon;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.util.BoundingBox;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** One play of a dungeon by a party, in its own copy of the dungeon. */
public final class Run {

    public enum State { PREPARING, RUNNING, ENDING }

    enum RoomState { WAITING, FIGHTING, CLEARED }

    final String id;
    final DungeonDefinition def;
    final int slot;
    final Location origin;
    /** Players who entered (still in the run, alive or watching). */
    final Set<UUID> players = new LinkedHashSet<>();
    /** Players invited when the run was started, teleported in once the copy is ready. */
    final Set<UUID> expected = new LinkedHashSet<>();
    /** Players out of lives, watching. */
    final Set<UUID> watching = new LinkedHashSet<>();
    final Set<UUID> mobs = new LinkedHashSet<>();
    final RoomState[] rooms;
    State state = State.PREPARING;
    int livesLeft;
    int wave;
    UUID boss;
    BossBar bossBar;
    Location checkpoint;
    long started;
    int warnedMinutes = -1;

    Run(String id, DungeonDefinition def, int slot, Location origin) {
        this.id = id;
        this.def = def;
        this.slot = slot;
        this.origin = origin;
        this.rooms = new RoomState[def.rooms().size()];
        java.util.Arrays.fill(rooms, RoomState.WAITING);
        this.livesLeft = def.lives();
    }

    public String id() {
        return id;
    }

    public DungeonDefinition definition() {
        return def;
    }

    public State state() {
        return state;
    }

    public Set<UUID> players() {
        return Set.copyOf(players);
    }

    public int livesLeft() {
        return livesLeft;
    }

    /** Inside of a room, in world coordinates (blocks the players stand in). */
    BoundingBox room(int index) {
        BoundingBox r = def.layout().rooms().get(index);
        return new BoundingBox(origin.getBlockX() + r.getMinX(), origin.getBlockY() + r.getMinY(), origin.getBlockZ() + r.getMinZ(),
                origin.getBlockX() + r.getMaxX() + 1, origin.getBlockY() + r.getMaxY() + 1, origin.getBlockZ() + r.getMaxZ() + 1);
    }

    /** Middle of a room's floor, facing the far end (+z). */
    Location roomCenter(int index) {
        BoundingBox b = room(index);
        return new Location(origin.getWorld(), b.getCenterX(), b.getMinY(), b.getCenterZ(), 0, 0);
    }

    /** Just inside a room, by its entrance. */
    Location roomEntrance(int index) {
        BoundingBox b = room(index);
        return new Location(origin.getWorld(), b.getCenterX(), b.getMinY(), b.getMinZ() + 1.5, 0, 0);
    }

    BoundingBox box() {
        return Arena.box(origin, def.layout());
    }

    /** Index of the room being played: the first one not cleared (rooms.length once all are). */
    int currentRoom() {
        for (int i = 0; i < rooms.length; i++) if (rooms[i] != RoomState.CLEARED) return i;
        return rooms.length;
    }
}
