package fr.influencraft.flux.rewards;

import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.reward.Reward;
import fr.influencraft.flux.api.reward.StoredReward;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Rewards of every player, table {@code flux_rewards} of the suite's database ({@link Database}). A claimed reward is
 * kept with its claim date (history, and its key stays taken so the same reward is never given twice). A reward not
 * claimed in time is kept with its expiry date ({@code expired}); {@code warned} counts the expiry reminders sent.
 * Any thread.
 */
final class RewardStore {

    private static final String SEPARATOR = "\n";

    private final String table = Database.table("flux_rewards");
    /** Players who asked for no Discord reminder. */
    private final String optOut = Database.table("flux_rewards_discord_optout");
    private final Logger log;

    RewardStore(File folder, Logger log) throws SQLException {
        this.log = log;
        Database.Dialect d = Database.dialect();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + table + " (id " + d.autoId() + ", uuid " + d.key(36) + " NOT NULL,"
                    + " source " + d.key(64) + " NOT NULL, reward_key " + d.key(191) + ", label TEXT NOT NULL, icon TEXT NOT NULL,"
                    + " amount INTEGER NOT NULL, slots INTEGER NOT NULL, commands TEXT NOT NULL, created BIGINT NOT NULL, claimed BIGINT)");
            Database.index(c, "flux_rewards_pending", table, "uuid, claimed, id", false);
            // NULL keys never collide: only keyed rewards are unique
            Database.index(c, "flux_rewards_key", table, "uuid, reward_key", true);
            addColumn(st, "expired BIGINT");
            addColumn(st, "warned INTEGER NOT NULL DEFAULT 0");
            st.execute("CREATE TABLE IF NOT EXISTS " + optOut + " (uuid " + d.key(36) + " NOT NULL PRIMARY KEY, since BIGINT NOT NULL)");
        }
        // former file of the first version
        Database.importSqlite(org.bukkit.Bukkit.getPluginManager().getPlugin("Flux"), new File(folder, "rewards.db"), "rewards", table);
    }

    /** Columns added after the first version (already there: the database refuses, nothing to do). */
    private void addColumn(Statement st, String column) {
        try {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column);
        } catch (SQLException ignored) {
            // column already there
        }
    }

    void close() {
        // the pool belongs to Database
    }

    /** False when the key is already taken or the database refused it. */
    boolean add(UUID uuid, Reward r) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(Database.dialect().insertIgnore() + " " + table
                + " (uuid, source, reward_key, label, icon, amount, slots, commands, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, r.source());
            ps.setString(3, r.key());
            ps.setString(4, r.label());
            ps.setString(5, r.icon());
            ps.setInt(6, r.amount());
            ps.setInt(7, r.slots());
            ps.setString(8, String.join(SEPARATOR, r.commands()));
            ps.setLong(9, System.currentTimeMillis());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log.severe("Reward NOT kept for " + uuid + " (" + r + "): " + e);
            return false;
        }
    }

    boolean has(UUID uuid, String key) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + table + " WHERE uuid = ? AND reward_key = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.warning("Could not read reward key " + key + " of " + uuid + ": " + e);
            return true; // unknown: never risk a duplicate
        }
    }

    List<StoredReward> pending(UUID uuid) {
        List<StoredReward> out = new ArrayList<>();
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("SELECT id, created, source, reward_key, label, icon, amount, slots,"
                + " commands FROM " + table + " WHERE uuid = ? AND claimed IS NULL AND expired IS NULL ORDER BY id")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String cmds = rs.getString(9);
                    out.add(new StoredReward(rs.getLong(1), rs.getLong(2), new Reward(rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8),
                            cmds.isEmpty() ? List.of() : Arrays.asList(cmds.split(SEPARATOR)))));
                }
            }
        } catch (SQLException e) {
            log.warning("Could not read the rewards of " + uuid + ": " + e);
        }
        return out;
    }

    /** Marks a reward as claimed; true only for the call that actually claimed it. */
    boolean markClaimed(long id) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET claimed = ? WHERE id = ? AND claimed IS NULL AND expired IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log.warning("Could not mark reward " + id + " as claimed: " + e);
            return false;
        }
    }

    /** Players with rewards waiting since before {@code createdBefore} and fewer than {@code warned} reminders, and their count. */
    java.util.Map<UUID, Integer> due(long createdBefore, int warned) {
        java.util.Map<UUID, Integer> out = new java.util.LinkedHashMap<>();
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("SELECT uuid, COUNT(*) FROM " + table
                + " WHERE claimed IS NULL AND expired IS NULL AND created < ? AND warned < ? GROUP BY uuid")) {
            ps.setLong(1, createdBefore);
            ps.setInt(2, warned);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(UUID.fromString(rs.getString(1)), rs.getInt(2));
            }
        } catch (SQLException | IllegalArgumentException e) {
            log.warning("Could not read the rewards about to expire: " + e);
        }
        return out;
    }

    /** Sets the reminder count of the rewards {@link #due} found. */
    void markWarned(long createdBefore, int warned) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("UPDATE " + table
                + " SET warned = ? WHERE claimed IS NULL AND expired IS NULL AND created < ? AND warned < ?")) {
            ps.setInt(1, warned);
            ps.setLong(2, createdBefore);
            ps.setInt(3, warned);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not mark the expiry reminders: " + e);
        }
    }

    /** Expires the rewards waiting since before {@code createdBefore}; players and how many each lost. */
    java.util.Map<UUID, Integer> expire(long createdBefore) {
        java.util.Map<UUID, Integer> lost = due(createdBefore, Integer.MAX_VALUE);
        if (lost.isEmpty()) return lost;
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("UPDATE " + table
                + " SET expired = ? WHERE claimed IS NULL AND expired IS NULL AND created < ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, createdBefore);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not expire the old rewards: " + e);
            return java.util.Map.of();
        }
        return lost;
    }

    boolean discordOptOut(UUID uuid) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + optOut + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.warning("Could not read the Discord reminder choice of " + uuid + ": " + e);
            return true; // unknown: do not disturb
        }
    }

    void setDiscordOptOut(UUID uuid, boolean off) {
        String sql = off ? Database.dialect().insertIgnore() + " " + optOut + " (uuid, since) VALUES (?, ?)"
                : "DELETE FROM " + optOut + " WHERE uuid = ?";
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            if (off) ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("Could not save the Discord reminder choice of " + uuid + ": " + e);
        }
    }
}
