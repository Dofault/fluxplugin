package fr.influencraft.fluxfly;

import fr.influencraft.flux.api.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Fly time of every player, in Flux's database (table {@code fluxfly_time}): seconds left, {@link #UNLIMITED} for an
 * unlimited bank. Online players are cached; every change is written through right away (one small row).
 */
final class FlyStore {

    static final long UNLIMITED = -1;

    private final Logger log;
    private final Map<UUID, Long> cache = new ConcurrentHashMap<>();
    private String table;

    FlyStore(Logger log) {
        this.log = log;
    }

    void open() {
        table = Database.table("fluxfly_time");
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (uuid " + Database.dialect().key(36)
                    + " PRIMARY KEY, seconds BIGINT NOT NULL)");
        } catch (SQLException e) {
            log.severe("Could not create " + table + ": " + e.getMessage());
        }
    }

    /** Seconds left (cached for online players), {@code fallback} when the player has no row yet. */
    long get(UUID uuid, long fallback) {
        Long cached = cache.get(uuid);
        if (cached != null) return cached;
        Long stored = read(uuid);
        return stored == null ? fallback : stored;
    }

    boolean known(UUID uuid) {
        return cache.containsKey(uuid) || read(uuid) != null;
    }

    /** Loads an arriving player, creating his row with the starting time. */
    void load(UUID uuid, long starting) {
        Long stored = read(uuid);
        if (stored == null) {
            write(uuid, starting);
            stored = starting;
        }
        cache.put(uuid, stored);
    }

    void unload(UUID uuid) {
        Long seconds = cache.remove(uuid);
        if (seconds != null) write(uuid, seconds);
    }

    /** Changes the cache only (the flight ticker, saved every minute and on quit). */
    void spend(UUID uuid, long seconds) {
        cache.put(uuid, seconds);
    }

    void set(UUID uuid, long seconds) {
        if (cache.containsKey(uuid)) cache.put(uuid, seconds);
        write(uuid, seconds);
    }

    void saveOnline() {
        for (Map.Entry<UUID, Long> e : List.copyOf(cache.entrySet())) write(e.getKey(), e.getValue());
    }

    private Long read(UUID uuid) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT seconds FROM " + table + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (SQLException e) {
            log.warning("Could not read the fly time of " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    private void write(UUID uuid, long seconds) {
        String sql = Database.dialect().upsert(table, List.of("uuid", "seconds"), List.of("uuid"), List.of("seconds"));
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, seconds);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not save the fly time of " + uuid + ": " + e.getMessage());
        }
    }
}
