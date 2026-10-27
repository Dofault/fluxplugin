package fr.influencraft.flux.rewards;

import fr.influencraft.flux.api.reward.Reward;
import fr.influencraft.flux.api.reward.StoredReward;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
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
 * Rewards of every player in SQLite ({@code plugins/Flux/rewards.db}). A claimed reward is kept with its claim date
 * (history, and the key stays taken so the same reward is never given twice). Synchronized: any thread.
 */
final class RewardStore {

    private static final String SEPARATOR = "\n";

    private final Connection db;
    private final Logger log;

    RewardStore(File folder, Logger log) throws SQLException {
        this.log = log;
        folder.mkdirs();
        db = DriverManager.getConnection("jdbc:sqlite:" + new File(folder, "rewards.db").getAbsolutePath());
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS rewards (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL,"
                    + " source TEXT NOT NULL, reward_key TEXT, label TEXT NOT NULL, icon TEXT NOT NULL, amount INTEGER NOT NULL,"
                    + " slots INTEGER NOT NULL, commands TEXT NOT NULL, created INTEGER NOT NULL, claimed INTEGER)");
            st.execute("CREATE INDEX IF NOT EXISTS rewards_pending ON rewards (uuid, claimed, id)");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS rewards_key ON rewards (uuid, reward_key) WHERE reward_key IS NOT NULL");
        }
    }

    synchronized void close() {
        try {
            db.close();
        } catch (SQLException ignored) {
            // server stopping
        }
    }

    /** False when the key is already taken or the database refused it. */
    synchronized boolean add(UUID uuid, Reward r) {
        try (PreparedStatement ps = db.prepareStatement("INSERT OR IGNORE INTO rewards (uuid, source, reward_key, label, icon,"
                + " amount, slots, commands, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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

    synchronized boolean has(UUID uuid, String key) {
        try (PreparedStatement ps = db.prepareStatement("SELECT 1 FROM rewards WHERE uuid = ? AND reward_key = ?")) {
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

    synchronized List<StoredReward> pending(UUID uuid) {
        List<StoredReward> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement("SELECT id, created, source, reward_key, label, icon, amount, slots,"
                + " commands FROM rewards WHERE uuid = ? AND claimed IS NULL ORDER BY id")) {
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
    synchronized boolean markClaimed(long id) {
        try (PreparedStatement ps = db.prepareStatement("UPDATE rewards SET claimed = ? WHERE id = ? AND claimed IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log.warning("Could not mark reward " + id + " as claimed: " + e);
            return false;
        }
    }
}
