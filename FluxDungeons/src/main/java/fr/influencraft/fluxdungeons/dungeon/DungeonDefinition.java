package fr.influencraft.fluxdungeons.dungeon;

import fr.influencraft.flux.api.util.Format;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A dungeon of {@code dungeons/<id>.yml}: rooms played one after the other, joined by corridors, each closed by a
 * gate until its monsters are dead; the last room holds the boss.
 *
 * <pre>
 * name: "The Forgotten Foundry"
 * players: {min: 1, max: 4}
 * time-limit: 20m
 * lives: 3                       # deaths the party can afford, together
 * reward: {minutes: 6, xp: 150}  # money: as much as N minutes of play (FluxValue); class experience
 * loot: [{item: fluxdungeons:foundry_blade, chance: 0.3}]
 * theme: {floor: [...], wall: [...], ceiling: [...], pillar: ..., gate: IRON_BARS, light: LANTERN, decor: [...]}
 * rooms:
 *   - {id: entrance, size: [9, 6, 9]}                         # width (x), height, length (z), inside
 *   - {id: hall, size: [17, 8, 17], waves: [{forge_worker: 4}, {forge_worker: 3, ember_skeleton: 2}]}
 *   - {id: forge, size: [23, 12, 23], boss: foundry_golem}
 * </pre>
 *
 * The layout is computed from the sizes ({@link Layout}): rooms in a line along +z, centred on the same x, so a
 * dungeon is described without a single coordinate. The template built from it can then be retouched by hand.
 */
public final class DungeonDefinition {

    public record Room(String id, String title, int width, int height, int length, List<Map<String, Integer>> waves,
                       String boss) {
        public boolean fight() {
            return !waves.isEmpty() || boss != null;
        }
    }

    public record Loot(String item, double chance, int amount) {
    }

    /** Positions of everything, relative to the dungeon's origin (its lowest corner). */
    public record Layout(int sizeX, int sizeY, int sizeZ, List<BoundingBox> rooms, List<BoundingBox> corridors,
                         List<BoundingBox> gates, int centerX) {
    }

    private final String id;
    private final String name;
    private final Material icon;
    private final List<String> description;
    private final int minPlayers, maxPlayers, recommendedLevel, lives;
    private final long timeLimit;
    private final double rewardMinutes, rewardXp;
    private final List<Loot> loot = new ArrayList<>();
    private final Map<String, Object> theme = new LinkedHashMap<>();
    private final List<Room> rooms = new ArrayList<>();
    private final Layout layout;
    private final String fingerprint;
    /** Imported map (a whole world copied for each run), null for a dungeon drawn from its rooms. */
    private final MapSpec map;
    /** Console commands run on run events (enter, objective, victory, defeat, leave), one list per event. */
    private final Map<String, List<String>> commands = new LinkedHashMap<>();

    /**
     * A monster placed in a map, at the coordinates of the original world, in a {@code room} (empty: none). The kills
     * of a room's monsters open its doors.
     */
    public record Spawn(String mob, double x, double y, double z, float yaw, String room) {
        public Spawn(String mob, double x, double y, double z, float yaw) {
            this(mob, x, y, z, yaw, "");
        }

        public Spawn withRoom(String r) {
            return new Spawn(mob, x, y, z, yaw, r == null ? "" : r);
        }
    }

    /** How a door opens. */
    public enum DoorType { PROXIMITY, KILLS }

    /**
     * A door of a map: the blocks between two corners, which disappear (sinking into the ground) when it opens: as soon
     * as a player comes within {@code radius} blocks ({@link DoorType#PROXIMITY}), or once {@code kills} monsters of
     * its {@code room} died ({@link DoorType#KILLS}; 0 = all of them).
     */
    public record Door(String id, DoorType type, int x1, int y1, int z1, int x2, int y2, int z2, String room, int kills, double radius) {
        public boolean contains(int x, int y, int z) {
            return x >= Math.min(x1, x2) && x <= Math.max(x1, x2) && y >= Math.min(y1, y2) && y <= Math.max(y1, y2)
                    && z >= Math.min(z1, z2) && z <= Math.max(z1, z2);
        }

        public org.bukkit.util.BoundingBox box() {
            return new org.bukkit.util.BoundingBox(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                    Math.max(x1, x2) + 1, Math.max(y1, y2) + 1, Math.max(z1, z2) + 1);
        }

        public Door with(DoorType t, String r, int k, double rad) {
            return new Door(id, t, x1, y1, z1, x2, y2, z2, r == null ? "" : r, Math.max(0, k), Math.max(1, rad));
        }
    }

    /**
     * An imported map: {@code folder} (world folder, relative to the plugin folder), {@code start} place, monsters at
     * their spawn points, objectives (monsters whose death, last phase included, ends the dungeon), doors.
     */
    public record MapSpec(String folder, double[] start, List<Spawn> spawns, List<String> objectives, List<Door> doors) {
        /** Monsters placed in a room. */
        public int roomSize(String room) {
            int n = 0;
            for (Spawn s : spawns) if (s.room().equals(room)) n++;
            return n;
        }
    }

    public DungeonDefinition(String id, ConfigurationSection c) {
        this.id = id;
        name = c.getString("name", id);
        Material m = Material.matchMaterial(c.getString("icon", "IRON_BARS"));
        icon = m == null ? Material.IRON_BARS : m;
        description = c.getStringList("description");
        minPlayers = Math.max(1, c.getInt("players.min", 1));
        maxPlayers = Math.max(minPlayers, c.getInt("players.max", 4));
        recommendedLevel = c.getInt("recommended-level", 1);
        lives = Math.max(0, c.getInt("lives", 3));
        timeLimit = Format.parseDuration(c.getString("time-limit", "20m"));
        rewardMinutes = c.getDouble("reward.minutes", 5);
        rewardXp = c.getDouble("reward.xp", 100);
        ConfigurationSection cmds = c.getConfigurationSection("commands");
        if (cmds != null) for (String k : cmds.getKeys(false)) commands.put(k.toLowerCase(java.util.Locale.ROOT), cmds.getStringList(k));
        for (Map<?, ?> l : c.getMapList("loot")) {
            Object chance = l.get("chance"), amount = l.get("amount");
            loot.add(new Loot(String.valueOf(l.get("item")), chance instanceof Number n ? n.doubleValue() : 1,
                    amount instanceof Number n ? n.intValue() : 1));
        }
        ConfigurationSection t = c.getConfigurationSection("theme");
        if (t != null) for (String k : t.getKeys(false)) theme.put(k, t.get(k));
        for (Map<?, ?> r : c.getMapList("rooms")) {
            List<?> size = r.get("size") instanceof List<?> s ? s : List.of(9, 6, 9);
            List<Map<String, Integer>> waves = new ArrayList<>();
            if (r.get("waves") instanceof List<?> ws) {
                for (Object w : ws) {
                    if (!(w instanceof Map<?, ?> wave)) continue;
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    wave.forEach((mob, n) -> counts.put(String.valueOf(mob), n instanceof Number num ? num.intValue() : 1));
                    waves.add(counts);
                }
            }
            rooms.add(new Room(String.valueOf(r.get("id")), r.get("title") == null ? null : String.valueOf(r.get("title")),
                    odd(size, 0, 9), integer(size, 1, 6), odd(size, 2, 9), waves,
                    r.get("boss") == null ? null : String.valueOf(r.get("boss"))));
        }
        layout = computeLayout();
        map = readMap(c.getConfigurationSection("map"));
        fingerprint = Integer.toHexString((String.valueOf(c.get("rooms")) + "|" + theme).hashCode());
    }

    private static MapSpec readMap(ConfigurationSection m) {
        if (m == null) return null;
        List<Double> st = m.getDoubleList("start");
        double[] start = new double[]{0, 64, 0, 0, 0};
        for (int i = 0; i < Math.min(5, st.size()); i++) start[i] = st.get(i);
        List<Spawn> spawns = new ArrayList<>();
        for (Map<?, ?> s : m.getMapList("spawns")) {
            if (!(s.get("at") instanceof List<?> at) || at.size() < 3) continue;
            spawns.add(new Spawn(String.valueOf(s.get("mob")), d(at.get(0)), d(at.get(1)), d(at.get(2)), at.size() > 3 ? (float) d(at.get(3)) : 0,
                    s.get("room") == null ? "" : String.valueOf(s.get("room"))));
        }
        List<Door> doors = new ArrayList<>();
        for (Map<?, ?> dm : m.getMapList("doors")) {
            if (!(dm.get("from") instanceof List<?> a) || !(dm.get("to") instanceof List<?> b) || a.size() < 3 || b.size() < 3) continue;
            DoorType type = "kills".equalsIgnoreCase(String.valueOf(dm.get("type"))) ? DoorType.KILLS : DoorType.PROXIMITY;
            doors.add(new Door(String.valueOf(dm.get("id")), type, (int) d(a.get(0)), (int) d(a.get(1)), (int) d(a.get(2)),
                    (int) d(b.get(0)), (int) d(b.get(1)), (int) d(b.get(2)), dm.get("room") == null ? "" : String.valueOf(dm.get("room")),
                    dm.get("kills") instanceof Number n ? n.intValue() : 0, dm.get("radius") instanceof Number n ? n.doubleValue() : 4));
        }
        return new MapSpec(m.getString("folder", ""), start, spawns, m.getStringList("objectives"), doors);
    }

    private static double d(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    /** Commands of a run event, empty when none. */
    public List<String> commands(String event) {
        return commands.getOrDefault(event, List.of());
    }

    public MapSpec map() {
        return map;
    }

    /** Changes whenever the rooms or the theme change: the template must then be built again. */
    public String fingerprint() {
        return fingerprint;
    }

    /** Rooms in a line along +z, joined by 7-block corridors 3 wide and 4 high. */
    private Layout computeLayout() {
        int corridor = 7;
        int maxWidth = 3, maxHeight = 4, length = 0;
        for (Room r : rooms) {
            maxWidth = Math.max(maxWidth, r.width());
            maxHeight = Math.max(maxHeight, r.height());
        }
        int sizeX = maxWidth + 2, cx = sizeX / 2;
        List<BoundingBox> roomBoxes = new ArrayList<>(), corridors = new ArrayList<>(), gates = new ArrayList<>();
        int z = 0;
        for (int i = 0; i < rooms.size(); i++) {
            Room r = rooms.get(i);
            int half = r.width() / 2;
            // interior box (blocks), walls are one block around it
            roomBoxes.add(new BoundingBox(cx - half, 1, z + 1, cx + half, r.height(), z + r.length()));
            z += r.length() + 1;           // z is now the far wall of this room
            if (i < rooms.size() - 1) {
                gates.add(new BoundingBox(cx - 1, 1, z, cx + 1, 3, z));
                corridors.add(new BoundingBox(cx - 1, 1, z + 1, cx + 1, 4, z + corridor));
                z += corridor + 1;         // next room's near wall
            }
        }
        length = z + 1;
        return new Layout(sizeX, maxHeight + 2, length, roomBoxes, corridors, gates, cx);
    }

    private static int integer(List<?> list, int index, int fallback) {
        return index < list.size() && list.get(index) instanceof Number n ? Math.max(3, n.intValue()) : fallback;
    }

    /** Odd widths keep the room centred on the corridor. */
    private static int odd(List<?> list, int index, int fallback) {
        int v = integer(list, index, fallback);
        return v % 2 == 0 ? v + 1 : v;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Material icon() {
        return icon;
    }

    public List<String> description() {
        return description;
    }

    public int minPlayers() {
        return minPlayers;
    }

    public int maxPlayers() {
        return maxPlayers;
    }

    public int recommendedLevel() {
        return recommendedLevel;
    }

    public int lives() {
        return lives;
    }

    public long timeLimit() {
        return timeLimit;
    }

    public double rewardMinutes() {
        return rewardMinutes;
    }

    public double rewardXp() {
        return rewardXp;
    }

    public List<Loot> loot() {
        return loot;
    }

    public List<Room> rooms() {
        return rooms;
    }

    public Layout layout() {
        return layout;
    }

    /** A material of the theme ({@code key: STONE}), or the first of a list. */
    public Material material(String key, Material fallback) {
        List<Material> all = materials(key);
        return all.isEmpty() ? fallback : all.get(0);
    }

    /** Materials of the theme ({@code key: [A, B]} or {@code key: A}). */
    public List<Material> materials(String key) {
        Object v = theme.get(key);
        List<Material> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) add(out, String.valueOf(o));
        } else if (v != null) {
            add(out, String.valueOf(v));
        }
        return out;
    }

    private static void add(List<Material> out, String name) {
        Material m = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        if (m != null && m.isBlock()) out.add(m);
    }
}
