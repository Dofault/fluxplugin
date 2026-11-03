package fr.influencraft.fluxafk;

import fr.influencraft.flux.api.data.Database;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

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
 * FluxAFK's two tables in Flux's database:
 * <ul>
 *   <li>{@code flux_afk_return}: where a player in the zone must go back. Written before he is taken to the zone and
 *   deleted once he is back, so a player who disconnects (or a server that crashes) while AFK is put back on his next
 *   join.</li>
 *   <li>{@code flux_afk_daily}: rounds of rewards of the current day (daily limit), one row per player.</li>
 * </ul>
 */
final class AfkStore {

    /** A place to go back to, with the flight state the player had there. */
    record Return(Location location, boolean flying) {
    }

    private record Day(String day, int rounds) {
    }

    private final Logger log;
    private final Map<UUID, Day> days = new ConcurrentHashMap<>();
    private String returns, daily;

    AfkStore(Logger log) {
        this.log = log;
    }

    void open() {
        returns = Database.table("flux_afk_return");
        daily = Database.table("flux_afk_daily");
        String key = Database.dialect().key(36);
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + returns + " (uuid " + key + " PRIMARY KEY, world VARCHAR(64) NOT NULL,"
                    + " x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL,"
                    + " flying INT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + daily + " (uuid " + key + " PRIMARY KEY, day VARCHAR(10) NOT NULL,"
                    + " rounds INT NOT NULL)");
        } catch (SQLException e) {
            log.severe("Could not create the FluxAFK tables: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- return places

    void saveReturn(UUID uuid, Location at, boolean flying) {
        String sql = Database.dialect().upsert(returns, List.of("uuid", "world", "x", "y", "z", "yaw", "pitch", "flying"),
                List.of("uuid"), List.of("world", "x", "y", "z", "yaw", "pitch", "flying"));
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, at.getWorld().getName());
            ps.setDouble(3, at.getX());
            ps.setDouble(4, at.getY());
            ps.setDouble(5, at.getZ());
            ps.setFloat(6, at.getYaw());
            ps.setFloat(7, at.getPitch());
            ps.setInt(8, flying ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not save the return place of " + uuid + ": " + e.getMessage());
        }
    }

    /** The place a player must go back to, or null (none, or its world is not loaded). */
    Return loadReturn(UUID uuid) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT world, x, y, z, yaw, pitch, flying FROM " + returns + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                World world = Bukkit.getWorld(rs.getString(1));
                if (world == null) {
                    log.warning("Return place of " + uuid + " is in the unloaded world " + rs.getString(1) + ", kept for later.");
                    return null;
                }
                return new Return(new Location(world, rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getFloat(5), rs.getFloat(6)),
                        rs.getInt(7) == 1);
            }
        } catch (SQLException e) {
            log.warning("Could not read the return place of " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    void clearReturn(UUID uuid) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("DELETE FROM " + returns + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not clear the return place of " + uuid + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- daily rounds

    /** Rounds of rewards of the player on this day (cached for online players). */
    int rounds(UUID uuid, String day) {
        Day d = days.computeIfAbsent(uuid, this::readDay);
        return d.day().equals(day) ? d.rounds() : 0;
    }

    void addRound(UUID uuid, String day) {
        int rounds = rounds(uuid, day) + 1;
        days.put(uuid, new Day(day, rounds));
        String sql = Database.dialect().upsert(daily, List.of("uuid", "day", "rounds"), List.of("uuid"), List.of("day", "rounds"));
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, day);
            ps.setInt(3, rounds);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not save the AFK rounds of " + uuid + ": " + e.getMessage());
        }
    }

    void unload(UUID uuid) {
        days.remove(uuid);
    }

    private Day readDay(UUID uuid) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT day, rounds FROM " + daily + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Day(rs.getString(1), rs.getInt(2)) : new Day("", 0);
            }
        } catch (SQLException e) {
            log.warning("Could not read the AFK rounds of " + uuid + ": " + e.getMessage());
            return new Day("", 0);
        }
    }
}
