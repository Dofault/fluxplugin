package fr.influencraft.fluxspawner;

import fr.influencraft.flux.api.data.Database;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Spawners in Flux's database: {@code flux_spawner_spawners} (one row per spawner, placed or in item form) and
 * {@code flux_spawner_progress} (what was given towards the next level, one row per goal). Every spawner is loaded at
 * start-up (a few hundred rows at most); writes go off the main thread, in order, on one thread of their own, with a
 * copy of the values.
 */
final class SpawnerStore {

    private static final List<String> COLUMNS = List.of("id", "owner", "owner_name", "type", "level", "world", "x", "y",
            "z", "active", "fuel", "stock", "kills", "created");

    private final Plugin plugin;
    private final Logger log;
    private String spawners;
    private String progress;
    /** One thread: writes keep their order (a later state never lost under an earlier one). */
    private ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "FluxSpawner-db"));

    SpawnerStore(Plugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    void open() {
        spawners = Database.table("flux_spawner_spawners");
        progress = Database.table("flux_spawner_progress");
        Database.Dialect d = Database.dialect();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + spawners + " (id " + d.key(36) + " PRIMARY KEY, owner "
                    + d.key(36) + " NOT NULL, owner_name " + d.key(16) + ", type " + d.key(48) + " NOT NULL, level INT NOT NULL,"
                    + " world " + d.key(64) + ", x INT, y INT, z INT, active INT NOT NULL, fuel DOUBLE NOT NULL,"
                    + " stock INT NOT NULL, kills BIGINT NOT NULL, created BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + progress + " (spawner " + d.key(36) + " NOT NULL, goal "
                    + d.key(96) + " NOT NULL, amount BIGINT NOT NULL, PRIMARY KEY (spawner, goal))");
            Database.index(c, "flux_spawner_owner", spawners, "owner", false);
        } catch (SQLException e) {
            log.severe("Could not create the spawner tables: " + e.getMessage());
        }
    }

    /** Every spawner, with its progress. */
    List<Spawner> loadAll() {
        Map<UUID, Spawner> out = new HashMap<>();
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT " + String.join(", ", COLUMNS) + " FROM " + spawners);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString("id"));
                Spawner s = new Spawner(id, rs.getLong("created"));
                s.owner = UUID.fromString(rs.getString("owner"));
                s.ownerName = rs.getString("owner_name");
                s.type = rs.getString("type");
                s.level = rs.getInt("level");
                s.world = rs.getString("world");
                s.x = rs.getInt("x");
                s.y = rs.getInt("y");
                s.z = rs.getInt("z");
                s.active = rs.getInt("active") != 0;
                s.fuel = rs.getDouble("fuel");
                s.stock = rs.getInt("stock");
                s.kills = rs.getLong("kills");
                out.put(id, s);
            }
        } catch (SQLException | IllegalArgumentException e) {
            log.severe("Could not load the spawners: " + e.getMessage());
            return List.of();
        }
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT spawner, goal, amount FROM " + progress);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Spawner s = out.get(UUID.fromString(rs.getString(1)));
                if (s != null) s.progress.put(rs.getString(2), rs.getLong(3));
            }
        } catch (SQLException | IllegalArgumentException e) {
            log.warning("Could not load the spawner progress: " + e.getMessage());
        }
        return new ArrayList<>(out.values());
    }

    private record Row(String id, String owner, String ownerName, String type, int level, String world, int x, int y,
                       int z, boolean active, double fuel, int stock, long kills, long created, Map<String, Long> progress) {
    }

    private static Row copy(Spawner s) {
        return new Row(s.id.toString(), s.owner.toString(), s.ownerName, s.type, s.level, s.world, s.x, s.y, s.z,
                s.active, s.fuel, s.stock, s.kills, s.created, new HashMap<>(s.progress));
    }

    /** Saves the spawner and its progress (progress rows replaced), off the main thread. */
    void save(Spawner s) {
        s.dirty = false;
        Row row = copy(s);
        writer.execute(() -> write(row));
    }

    /** Waits for the writes in flight (plugin stopping). */
    void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) log.warning("Some spawner writes did not finish in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void write(Row r) {
        String sql = Database.dialect().upsert(spawners, COLUMNS, List.of("id"), COLUMNS.subList(1, COLUMNS.size()));
        try (Connection c = Database.connection()) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, r.id());
                ps.setString(2, r.owner());
                ps.setString(3, r.ownerName());
                ps.setString(4, r.type());
                ps.setInt(5, r.level());
                if (r.world() == null) ps.setNull(6, Types.VARCHAR);
                else ps.setString(6, r.world());
                ps.setInt(7, r.x());
                ps.setInt(8, r.y());
                ps.setInt(9, r.z());
                ps.setInt(10, r.active() ? 1 : 0);
                ps.setDouble(11, r.fuel());
                ps.setInt(12, r.stock());
                ps.setLong(13, r.kills());
                ps.setLong(14, r.created());
                ps.executeUpdate();
            }
            try (PreparedStatement del = c.prepareStatement("DELETE FROM " + progress + " WHERE spawner = ?")) {
                del.setString(1, r.id());
                del.executeUpdate();
            }
            if (!r.progress().isEmpty()) {
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO " + progress + " (spawner, goal, amount) VALUES (?, ?, ?)")) {
                    for (Map.Entry<String, Long> e : r.progress().entrySet()) {
                        if (e.getValue() <= 0) continue;
                        ins.setString(1, r.id());
                        ins.setString(2, e.getKey());
                        ins.setLong(3, e.getValue());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
            }
        } catch (SQLException e) {
            log.warning("Could not save spawner " + r.id() + ": " + e.getMessage());
        }
    }

    /** Removes a spawner for good (staff). */
    void delete(UUID id) {
        Runnable task = () -> {
            try (Connection c = Database.connection();
                 PreparedStatement a = c.prepareStatement("DELETE FROM " + spawners + " WHERE id = ?");
                 PreparedStatement b = c.prepareStatement("DELETE FROM " + progress + " WHERE spawner = ?")) {
                a.setString(1, id.toString());
                a.executeUpdate();
                b.setString(1, id.toString());
                b.executeUpdate();
            } catch (SQLException e) {
                log.warning("Could not delete spawner " + id + ": " + e.getMessage());
            }
        };
        writer.execute(task);
    }
}
