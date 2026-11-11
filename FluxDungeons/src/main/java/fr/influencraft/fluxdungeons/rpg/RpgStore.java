package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Durable rpg data: a player's class ({@code flux_dungeons_rpg_heroes}, one row, written once when he first chooses,
 * then on each class change) and his progress in each class ({@code flux_dungeons_rpg_progress}). Called off the main
 * thread.
 */
final class RpgStore {

    record Hero(String heroClass, long changed) {
    }

    record Progress(int level, double xp) {
    }

    private final Logger log;
    private final String heroes = Database.table("flux_dungeons_rpg_heroes");
    private final String progress = Database.table("flux_dungeons_rpg_progress");

    RpgStore(Logger log) throws SQLException {
        this.log = log;
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            Database.Dialect d = Database.dialect();
            // names of the first days (2026-11-11), without the suite's flux_ prefix
            Database.renameTable(c, Database.table("dungeons_rpg_heroes"), heroes);
            Database.renameTable(c, Database.table("dungeons_rpg_progress"), progress);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + heroes + " (uuid " + d.key(36) + " PRIMARY KEY, hero_class "
                    + d.key(32) + " NOT NULL, chosen BIGINT NOT NULL, changed BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + progress + " (uuid " + d.key(36) + " NOT NULL, hero_class "
                    + d.key(32) + " NOT NULL, level INTEGER NOT NULL, xp DOUBLE PRECISION NOT NULL, PRIMARY KEY (uuid, hero_class))");
        }
    }

    /** The player's class, null when he never chose one. Throws when unreadable (never mistaken for "none"). */
    Hero hero(UUID uuid) throws SQLException {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT hero_class, changed FROM " + heroes + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Hero(rs.getString(1), rs.getLong(2)) : null;
            }
        }
    }

    /**
     * Records the first choice of a class. True only for the call that created the row: a second click, a second
     * window or a second server thread never chooses twice.
     */
    boolean choose(UUID uuid, String heroClass) {
        long now = System.currentTimeMillis();
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement(Database.dialect().insertIgnore() + " " + heroes
                     + " (uuid, hero_class, chosen, changed) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, heroClass);
            ps.setLong(3, now);
            ps.setLong(4, now);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log.severe("Class choice of " + uuid + " not saved: " + e.getMessage());
            return false;
        }
    }

    /** Changes the class of a player who has one, only if it is still {@code from} (no double change). */
    boolean change(UUID uuid, String from, String to) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("UPDATE " + heroes + " SET hero_class = ?, changed = ? WHERE uuid = ? AND hero_class = ?")) {
            ps.setString(1, to);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, uuid.toString());
            ps.setString(4, from);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log.severe("Class change of " + uuid + " not saved: " + e.getMessage());
            return false;
        }
    }

    Progress progress(UUID uuid, String heroClass) throws SQLException {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT level, xp FROM " + progress + " WHERE uuid = ? AND hero_class = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, heroClass);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Progress(rs.getInt(1), rs.getDouble(2)) : new Progress(1, 0);
            }
        }
    }

    void save(UUID uuid, String heroClass, int level, double xp) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement(Database.dialect().upsert(progress, List.of("uuid", "hero_class", "level", "xp"),
                     List.of("uuid", "hero_class"), List.of("level", "xp")))) {
            ps.setString(1, uuid.toString());
            ps.setString(2, heroClass);
            ps.setInt(3, level);
            ps.setDouble(4, xp);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.severe("Rpg progress of " + uuid + " not saved: " + e.getMessage());
        }
    }
}
