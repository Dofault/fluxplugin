package fr.influencraft.flux.universe;

import fr.influencraft.flux.api.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Profiles of the universes a player is not in ({@code flux_universe_profiles}) and items taken away because they were
 * held in the wrong universe ({@code flux_universe_quarantine}). Profiles of online players are cached (loaded before
 * they join, updated after every confirmed write) so a swap never waits for a read.
 */
final class ProfileStore {

    /** A row of the quarantine. */
    record Quarantined(long id, String universe, String item) {
    }

    private final Logger log;
    private final String profiles = Database.table("universe_profiles");
    private final String quarantine = Database.table("universe_quarantine");
    /** uuid -> universe -> stored profile; a key present with no universe = loaded, nothing stored. */
    private final Map<UUID, Map<String, Transition.Stored<String>>> cache = new ConcurrentHashMap<>();

    ProfileStore(Logger log) throws SQLException {
        this.log = log;
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            Database.Dialect d = Database.dialect();
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + profiles + " (uuid " + d.key(36) + " NOT NULL, universe "
                    + d.key(32) + " NOT NULL, version BIGINT NOT NULL, data " + d.longText() + " NOT NULL, updated BIGINT NOT NULL,"
                    + " PRIMARY KEY (uuid, universe))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + quarantine + " (id " + d.autoId() + ", uuid " + d.key(36)
                    + " NOT NULL, universe " + d.key(32) + " NOT NULL, item " + d.longText() + " NOT NULL, found_in "
                    + d.key(32) + " NOT NULL, created BIGINT NOT NULL)");
            Database.index(c, quarantine.replace('.', '_') + "_owner", quarantine, "uuid, universe", false);
        }
    }

    /** Reads every profile of the player into the cache (before he joins). Throws when the database is unreadable. */
    void preload(UUID uuid) throws SQLException {
        Map<String, Transition.Stored<String>> rows = new ConcurrentHashMap<>();
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT universe, version, data FROM " + profiles + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.put(rs.getString(1), new Transition.Stored<>(rs.getLong(2), rs.getString(3)));
            }
        }
        cache.put(uuid, rows);
    }

    void forget(UUID uuid) {
        cache.remove(uuid);
    }

    /** The repository of one player, as the transition sees it. */
    Transition.Repository<String> repository(UUID uuid) {
        return new Transition.Repository<>() {
            @Override
            public Transition.Stored<String> load(String universe) {
                Map<String, Transition.Stored<String>> rows = cache.get(uuid);
                if (rows == null) {
                    try {
                        preload(uuid);
                    } catch (SQLException e) {
                        throw new IllegalStateException("profiles of " + uuid + " unreadable: " + e.getMessage(), e);
                    }
                    rows = cache.get(uuid);
                }
                return rows.get(universe);
            }

            @Override
            public boolean save(String universe, long version, String profile) {
                try (Connection c = Database.connection();
                     PreparedStatement ps = c.prepareStatement(Database.dialect().upsert(profiles,
                             List.of("uuid", "universe", "version", "data", "updated"), List.of("uuid", "universe"),
                             List.of("version", "data", "updated")))) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, universe);
                    ps.setLong(3, version);
                    ps.setString(4, profile);
                    ps.setLong(5, System.currentTimeMillis());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    log.severe("Profile " + universe + " of " + uuid + " not saved: " + e.getMessage());
                    return false;
                }
                cache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(universe, new Transition.Stored<>(version, profile));
                return true;
            }
        };
    }

    /** Stored profile of a universe from the cache only (null when unknown). */
    String cached(UUID uuid, String universe) {
        Map<String, Transition.Stored<String>> rows = cache.get(uuid);
        Transition.Stored<String> s = rows == null ? null : rows.get(universe);
        return s == null ? null : s.profile();
    }

    /**
     * Puts an item taken from a player in quarantine. Synchronous on purpose: it happens only on an anomaly, and the
     * item must be safe in the database before it leaves the player. False when it could not be written (the caller
     * then leaves the item where it was).
     */
    boolean quarantine(UUID uuid, String universe, String item, String foundIn) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO " + quarantine + " (uuid, universe, item, found_in, created) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, universe);
            ps.setString(3, item);
            ps.setString(4, foundIn);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            log.severe("Quarantine of an item of " + uuid + " failed, item left in place: " + e.getMessage());
            return false;
        }
    }

    List<Quarantined> quarantined(UUID uuid, String universe) {
        List<Quarantined> out = new ArrayList<>();
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT id, universe, item FROM " + quarantine + " WHERE uuid = ?"
                     + (universe == null ? "" : " AND universe = ?") + " ORDER BY id")) {
            ps.setString(1, uuid.toString());
            if (universe != null) ps.setString(2, universe);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Quarantined(rs.getLong(1), rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException e) {
            log.warning("Quarantine of " + uuid + " unreadable: " + e.getMessage());
        }
        return out;
    }

    /** Removes a quarantine row once its item is back with the player; false when it could not be removed. */
    boolean release(long id) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + quarantine + " WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log.warning("Quarantine row " + id + " not released: " + e.getMessage());
            return false;
        }
    }
}
