package fr.influencraft.fluxcasino;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Balances and bets in SQLite (plugins/FluxCasino/casino.db, driver provided by Paper), written right away on every
 * movement (a chip is worth money: no delayed save). Every balance movement is also written to the ledger (player,
 * change, balance after, reason, date) to trace a dispute or an abuse. Balances and bets are cached (read by the
 * placeholders on every render). Main thread only.
 */
final class Store {

    private final Connection db;
    private final Map<UUID, long[]> cache = new ConcurrentHashMap<>(); // {chips, bet}
    private final long defaultBet;

    Store(File folder, long defaultBet) throws SQLException {
        this.defaultBet = defaultBet;
        folder.mkdirs();
        db = DriverManager.getConnection("jdbc:sqlite:" + new File(folder, "casino.db").getAbsolutePath());
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS balances (uuid TEXT PRIMARY KEY, name TEXT, chips INTEGER NOT NULL DEFAULT 0,"
                    + " bet INTEGER NOT NULL, updated INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS ledger (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL,"
                    + " delta INTEGER NOT NULL, balance INTEGER NOT NULL, reason TEXT NOT NULL, at INTEGER NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS ledger_uuid ON ledger (uuid, id)");
        }
    }

    void close() {
        try {
            db.close();
        } catch (SQLException ignored) {
            // server stopping
        }
    }

    private long[] row(UUID uuid) {
        return cache.computeIfAbsent(uuid, u -> {
            try (PreparedStatement ps = db.prepareStatement("SELECT chips, bet FROM balances WHERE uuid = ?")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : new long[]{0, defaultBet};
                }
            } catch (SQLException e) {
                throw new IllegalStateException("reading the balance of " + u, e);
            }
        });
    }

    long chips(UUID uuid) {
        return row(uuid)[0];
    }

    long bet(UUID uuid) {
        return row(uuid)[1];
    }

    void forget(UUID uuid) {
        cache.remove(uuid);
    }

    /**
     * Adds delta (negative = withdrawal) to the balance, ledger included, in one transaction. Refused (false) when the
     * balance would go negative. The cache is only updated once the write is committed.
     */
    boolean add(UUID uuid, String name, long delta, String reason) {
        long[] r = row(uuid);
        long after = r[0] + delta;
        if (after < 0) return false;
        try {
            db.setAutoCommit(false);
            write(uuid, name, after, r[1]);
            try (PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO ledger (uuid, delta, balance, reason, at) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, delta);
                ps.setLong(3, after);
                ps.setString(4, reason);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            db.commit();
            r[0] = after;
            return true;
        } catch (SQLException e) {
            try {
                db.rollback();
            } catch (SQLException ignored) {
                // the connection already failed
            }
            throw new IllegalStateException("writing the balance of " + uuid, e);
        } finally {
            try {
                db.setAutoCommit(true);
            } catch (SQLException ignored) {
                // same
            }
        }
    }

    void setBet(UUID uuid, String name, long bet) {
        long[] r = row(uuid);
        try {
            write(uuid, name, r[0], bet);
            r[1] = bet;
        } catch (SQLException e) {
            throw new IllegalStateException("writing the bet of " + uuid, e);
        }
    }

    private void write(UUID uuid, String name, long chips, long bet) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO balances (uuid, name, chips, bet, updated) VALUES (?, ?, ?, ?, ?)"
                + " ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, chips = excluded.chips, bet = excluded.bet,"
                + " updated = excluded.updated")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, chips);
            ps.setLong(4, bet);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** Last movements of a player (most recent first): {change, balance, reason, date}. */
    List<String[]> history(UUID uuid, int limit) {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT delta, balance, reason, at FROM ledger WHERE uuid = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{String.valueOf(rs.getLong(1)), String.valueOf(rs.getLong(2)), rs.getString(3),
                            String.valueOf(rs.getLong(4))});
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("lecture du journal de " + uuid, e);
        }
        return out;
    }
}
