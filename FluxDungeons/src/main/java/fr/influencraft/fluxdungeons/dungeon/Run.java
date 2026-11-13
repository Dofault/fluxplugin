package fr.influencraft.fluxdungeons.dungeon;

import java.util.List;

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
    Location origin;
    /** Players who entered (still in the run, alive or watching). */
    final Set<UUID> players = new LinkedHashSet<>();
    /** Players invited when the run was started, teleported in once the copy is ready. */
    final Set<UUID> expected = new LinkedHashSet<>();
    /** Players who died, watching (their gear is locked in the dungeon). */
    final Set<UUID> watching = new LinkedHashSet<>();
    final Set<UUID> mobs = new LinkedHashSet<>();
    final RoomState[] rooms;
    State state = State.PREPARING;
    int livesLeft;
    int wave;
    UUID boss;
    /** Server tick of the next sound of each ambience emitter of the map (Ambience), null before the first step. */
    long[] ambience;
    /** Boss music started for the players of the run, null before. */
    String music;
    BossBar bossBar;
    Location checkpoint;
    long started;
    int warnedMinutes = -1;
    /** World of an imported map (its own copy), null for a dungeon in the arena. */
    org.bukkit.World world;
    /** Objectives of an imported map still alive (monster ids). */
    final Set<String> objectivesLeft = new LinkedHashSet<>();
    int objectivesTotal;
    /** Difficulty level played (index in the dungeon's levels), chosen on the launch screen. */
    int level;

    public DungeonDefinition.Level levelSpec() {
        List<DungeonDefinition.Level> all = def.levels();
        return all.get(Math.max(0, Math.min(level, all.size() - 1)));
    }

    /** Room of each monster spawned from a spawn point that has one. */
    final java.util.Map<UUID, String> mobRoom = new java.util.HashMap<>();
    /** Monsters of each room killed so far. */
    final java.util.Map<String, Integer> roomKills = new java.util.HashMap<>();
    /** Doors already open. */
    final java.util.Set<String> openDoors = new java.util.HashSet<>();
    /** What each offering door received so far (counts per entry of its offering). */
    final java.util.Map<String, int[]> offerings = new java.util.HashMap<>();
    /** Scoreboard tags given to the players by completed offering doors (taken back when they leave). */
    final java.util.Set<String> grants = new java.util.HashSet<>();
    /** Counter shown on each door that opens with kills. */
    final java.util.Map<String, org.bukkit.entity.TextDisplay> doorLabels = new java.util.HashMap<>();
    /** Monsters of an imported map not spawned yet: they appear when a player comes near (memory stays low). */
    final java.util.List<DungeonDefinition.Spawn> pending = new java.util.ArrayList<>();

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

    /** Played in a copy of an imported map (true) or in the arena (false). */
    public boolean imported() {
        return def.map() != null;
    }

    public int objectivesDone() {
        return objectivesTotal - objectivesLeft.size();
    }

    public int objectivesTotal() {
        return objectivesTotal;
    }

    public int mobsLeft() {
        return mobs.size();
    }

    public long secondsLeft() {
        if (def.timeLimit() <= 0 || started == 0) return -1;
        return Math.max(0, def.timeLimit() - (System.currentTimeMillis() - started) / 1000);
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
