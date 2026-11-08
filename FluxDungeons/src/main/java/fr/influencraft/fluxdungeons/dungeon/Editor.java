package fr.influencraft.fluxdungeons.dungeon;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.text.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Edit mode of an imported dungeon: its original map is opened (a working copy, written back on save), its spawn
 * points and start shown in the world, and moved in game. Builds go back into the map folder; points go into
 * {@code edits/<id>.yml}, which the importer never touches and which overrides the dungeon file's {@code map} points.
 * A dungeon being edited cannot be started.
 */
public final class Editor {

    /** One open edit: the map's world, its points as being edited, the markers shown. */
    private static final class Session {
        final String dungeon;
        final World world;
        final List<DungeonDefinition.Spawn> spawns;
        final Set<String> objectives;
        double[] start;
        final List<TextDisplay> markers = new ArrayList<>();
        boolean saving;

        Session(String dungeon, World world, DungeonDefinition.MapSpec map) {
            this.dungeon = dungeon;
            this.world = world;
            this.spawns = new ArrayList<>(map.spawns());
            this.objectives = new LinkedHashSet<>(map.objectives());
            this.start = map.start().clone();
        }
    }

    private static final String MARKER_TAG = "fluxdungeons_marker";
    private static final double REACH = 6;

    private final FluxPlugin plugin;
    private final WorldInstances instances;
    private final Function<String, DungeonDefinition> definitions;
    private Runnable reload = () -> {
    };
    private final Map<String, Session> sessions = new HashMap<>();
    private final Set<String> opening = new java.util.HashSet<>();
    private final Map<UUID, Location> back = new HashMap<>();

    Editor(FluxPlugin plugin, WorldInstances instances, Function<String, DungeonDefinition> definitions) {
        this.plugin = plugin;
        this.instances = instances;
        this.definitions = definitions;
    }

    /** What reloads the dungeons once a map or its points changed. */
    public void onSaved(Runnable reload) {
        this.reload = reload;
    }

    /** True while the dungeon is open in edit mode (it cannot be started). */
    public boolean locked(String dungeon) {
        return sessions.containsKey(dungeon) || opening.contains(dungeon);
    }

    /** True when the world is an edit world. */
    public boolean editWorld(World w) {
        if (w == null) return false;
        for (Session s : sessions.values()) if (s.world.equals(w)) return true;
        return false;
    }

    private Session sessionOf(Player p) {
        for (Session s : sessions.values()) if (s.world.equals(p.getWorld())) return s;
        return null;
    }

    private static String worldName(String dungeon) {
        return "fde_" + dungeon.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    // ---------------------------------------------------------------- open, save, cancel

    /** Opens the dungeon in edit mode (or joins the open edit) and brings the player to its start. */
    public void open(Player p, DungeonDefinition def) {
        if (def.map() == null) {
            plugin.messages().send(p, "admin.imported-no-template", "%dungeon%", def.name());
            return;
        }
        Session open = sessions.get(def.id());
        if (open != null) {
            enter(p, open);
            return;
        }
        if (opening.contains(def.id())) return;
        File folder = new File(plugin.getDataFolder(), def.map().folder());
        if (!new File(folder, "region").isDirectory()) {
            plugin.messages().send(p, "start.failed");
            return;
        }
        opening.add(def.id());
        plugin.messages().send(p, "edit.opening", "%dungeon%", def.name());
        instances.create(folder, worldName(def.id()), world -> {
            opening.remove(def.id());
            if (world == null) {
                plugin.messages().send(p, "start.failed");
                return;
            }
            Session s = new Session(def.id(), world, def.map());
            sessions.put(def.id(), s);
            if (p.isOnline()) enter(p, s);
            draw(s);
        });
    }

    private void enter(Player p, Session s) {
        if (!s.world.equals(p.getWorld())) back.putIfAbsent(p.getUniqueId(), p.getLocation());
        p.teleport(new Location(s.world, s.start[0], s.start[1], s.start[2], (float) s.start[3], (float) s.start[4]),
                PlayerTeleportEvent.TeleportCause.PLUGIN);
        plugin.messages().send(p, "edit.opened", "%dungeon%", definitions.apply(s.dungeon).name(),
                "%spawns%", String.valueOf(s.spawns.size()));
    }

    /** Writes the points, then the builds back into the map, and closes the edit. */
    public void save(Player p) {
        Session s = sessionOf(p);
        if (s == null || s.saving) {
            plugin.messages().send(p, "edit.not-editing");
            return;
        }
        s.saving = true;
        writePoints(s);
        clearMarkers(s);
        sendBack(s);
        DungeonDefinition def = definitions.apply(s.dungeon);
        File folder = new File(plugin.getDataFolder(), def.map().folder());
        plugin.messages().send(p, "edit.saving", "%dungeon%", def.name());
        instances.saveBack(s.world, folder, () -> {
            sessions.remove(s.dungeon);
            reload.run();
            if (p.isOnline()) plugin.messages().send(p, "edit.saved", "%dungeon%", def.name());
        });
    }

    /** Closes the edit without writing the builds (points already moved stay saved). */
    public void cancel(Player p) {
        Session s = sessionOf(p);
        if (s == null || s.saving) {
            plugin.messages().send(p, "edit.not-editing");
            return;
        }
        close(s);
        reload.run();
        plugin.messages().send(p, "edit.cancelled");
    }

    private void close(Session s) {
        sessions.remove(s.dungeon);
        clearMarkers(s);
        sendBack(s);
        instances.destroy(s.world);
    }

    /** Server stopping: open edits are closed without writing their builds. */
    public void closeAll() {
        for (Session s : List.copyOf(sessions.values())) close(s);
    }

    private void sendBack(Session s) {
        for (Player q : List.copyOf(s.world.getPlayers())) {
            Location to = back.remove(q.getUniqueId());
            if (to == null || to.getWorld() == null || to.getWorld().equals(s.world)) to = Bukkit.getWorlds().get(0).getSpawnLocation();
            q.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    // ---------------------------------------------------------------- points

    /** Adds a spawn point of this monster where the player stands. */
    public void add(Player p, String mob) {
        Session s = sessionOf(p);
        if (s == null) {
            plugin.messages().send(p, "edit.not-editing");
            return;
        }
        Location l = p.getLocation();
        s.spawns.add(new DungeonDefinition.Spawn(mob, round(l.getX()), round(l.getY()), round(l.getZ()), Math.round(l.getYaw())));
        changed(s);
        plugin.messages().send(p, "edit.added", "%mob%", mob, "%spawns%", String.valueOf(s.spawns.size()));
    }

    /** Removes the spawn point nearest to the player (within a few blocks). */
    public void remove(Player p) {
        Session s = sessionOf(p);
        if (s == null) {
            plugin.messages().send(p, "edit.not-editing");
            return;
        }
        int i = nearest(s, p.getLocation());
        if (i < 0) {
            plugin.messages().send(p, "edit.none-near");
            return;
        }
        DungeonDefinition.Spawn gone = s.spawns.remove(i);
        if (s.spawns.stream().noneMatch(sp -> sp.mob().equals(gone.mob()))) s.objectives.remove(gone.mob());
        changed(s);
        plugin.messages().send(p, "edit.removed", "%mob%", gone.mob(), "%spawns%", String.valueOf(s.spawns.size()));
    }

    /** Moves the spawn point nearest to the player to where he stands. */
    public void move(Player p) {
        Session s = sessionOf(p);
        if (s == null) {
            plugin.messages().send(p, "edit.not-editing");
            return;
        }
        int i = nearest(s, p.getLocation());
        if (i < 0) {
            plugin.messages().send(p, "edit.none-near");
            return;
        }
        Location l = p.getLocation();
        DungeonDefinition.Spawn old = s.spawns.get(i);
        s.spawns.set(i, new DungeonDefinition.Spawn(old.mob(), round(l.getX()), round(l.getY()), round(l.getZ()), Math.round(l.getYaw())));
        changed(s);
        plugin.messages().send(p, "edit.moved", "%mob%", old.mob());
    }

    /** Puts the start of the dungeon where the player stands. */
    public void start(Player p) {
        Session s = sessionOf(p);
        if (s == null) {
            plugin.messages().send(p, "edit.not-editing");
            return;
        }
        Location l = p.getLocation();
        s.start = new double[]{round(l.getX()), round(l.getY()), round(l.getZ()), Math.round(l.getYaw()), Math.round(l.getPitch())};
        changed(s);
        plugin.messages().send(p, "edit.start");
    }

    /** Makes the monster of the nearest point an objective of the dungeon, or no longer one. */
    public void objective(Player p) {
        Session s = sessionOf(p);
        if (s == null) {
            plugin.messages().send(p, "edit.not-editing");
            return;
        }
        int i = nearest(s, p.getLocation());
        if (i < 0) {
            plugin.messages().send(p, "edit.none-near");
            return;
        }
        String mob = s.spawns.get(i).mob();
        boolean now = s.objectives.add(mob) || !s.objectives.remove(mob);
        changed(s);
        plugin.messages().send(p, now ? "edit.objective-on" : "edit.objective-off", "%mob%", mob,
                "%objectives%", String.valueOf(s.objectives.size()));
    }

    /** Monsters already placed in the dungeon the player edits (completion). */
    public List<String> placedMobs(Player p) {
        Session s = sessionOf(p);
        if (s == null) return List.of();
        return s.spawns.stream().map(DungeonDefinition.Spawn::mob).distinct().sorted().toList();
    }

    private int nearest(Session s, Location at) {
        int best = -1;
        double bestD = REACH * REACH;
        for (int i = 0; i < s.spawns.size(); i++) {
            DungeonDefinition.Spawn sp = s.spawns.get(i);
            double dx = sp.x() - at.getX(), dy = sp.y() - at.getY(), dz = sp.z() - at.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private void changed(Session s) {
        writePoints(s);
        markers(s);
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** {@code edits/<id>.yml}: start, spawns and objectives of the map, kept apart from the imported file. */
    private void writePoints(Session s) {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Points of the dungeon moved in game (/fluxdungeons edit): they replace those of",
                "dungeons/" + s.dungeon + ".yml. Delete this file to come back to the imported points."));
        y.set("map.start", List.of(s.start[0], s.start[1], s.start[2], s.start[3], s.start[4]));
        y.set("map.objectives", new ArrayList<>(s.objectives));
        List<Map<String, Object>> spawns = new ArrayList<>();
        for (DungeonDefinition.Spawn sp : s.spawns) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("mob", sp.mob());
            m.put("at", List.of(sp.x(), sp.y(), sp.z(), (double) sp.yaw()));
            spawns.add(m);
        }
        y.set("map.spawns", spawns);
        File f = new File(plugin.getDataFolder(), "edits/" + s.dungeon + ".yml");
        f.getParentFile().mkdirs();
        try {
            y.save(f);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save " + f + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- markers

    /** Markers are redrawn around the players (next {@link #tick}). */
    private void markers(Session s) {
        clearMarkers(s);
        draw(s);
    }

    /** Every two seconds: markers within {@link #MARKER_RANGE} blocks of a player in an edit world, none farther. */
    void tick() {
        for (Session s : sessions.values()) if (!s.saving) draw(s);
    }

    /**
     * Name of each monster above its spawn point (objectives marked), and the start, only near players: spawning a
     * marker loads its chunk, and a map may have hundreds of points. Markers are never saved in the map.
     */
    private void draw(Session s) {
        List<Location> players = s.world.getPlayers().stream().map(Player::getLocation).toList();
        s.markers.removeIf(d -> {
            if (d.isValid() && near(d.getLocation(), players)) return false;
            d.remove();
            return true;
        });
        Set<String> shown = new java.util.HashSet<>();
        for (TextDisplay d : s.markers) shown.add(key(d.getLocation()));
        for (DungeonDefinition.Spawn sp : s.spawns) {
            Location at = new Location(s.world, sp.x(), sp.y() + 2.3, sp.z());
            if (!near(at, players) || !shown.add(key(at))) continue;
            String text = plugin.messages().raw(s.objectives.contains(sp.mob()) ? "edit.marker-objective" : "edit.marker", "%mob%", sp.mob());
            s.markers.add(marker(at, text));
        }
        Location start = new Location(s.world, s.start[0], s.start[1] + 2.3, s.start[2]);
        if (near(start, players) && shown.add(key(start))) s.markers.add(marker(start, plugin.messages().raw("edit.marker-start")));
    }

    private static final double MARKER_RANGE = 48;

    private static boolean near(Location at, List<Location> players) {
        for (Location l : players) if (l.getWorld() == at.getWorld() && l.distanceSquared(at) <= MARKER_RANGE * MARKER_RANGE) return true;
        return false;
    }

    private static String key(Location l) {
        return Math.round(l.getX() * 10) + "," + Math.round(l.getY() * 10) + "," + Math.round(l.getZ() * 10);
    }

    private static TextDisplay marker(Location at, String text) {
        return at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.text(Texts.parse(text));
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setSeeThrough(true);
            d.addScoreboardTag(MARKER_TAG);
        });
    }

    private static void clearMarkers(Session s) {
        for (TextDisplay d : s.markers) d.remove();
        s.markers.clear();
        // chunks unloaded since keep their markers out of the list: never write them into the map
        for (TextDisplay d : s.world.getEntitiesByClass(TextDisplay.class)) if (d.getScoreboardTags().contains(MARKER_TAG)) d.remove();
    }
}
