package fr.influencraft.fluxcasino;

import fr.influencraft.flux.api.data.Database;
import java.io.File;
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

/**
 * Balances and bets in the suite's database (tables {@code casino_balances}, {@code casino_ledger}, see Flux
 * {@link Database}), written right away on every movement (a chip is worth money: no delayed save). Every balance
 * movement is also written to the ledger (player, change, balance after, reason, date) to trace a dispute or an abuse.
 * Balances and bets are cached (read by the placeholders on every render). Main thread only.
 */
final class Store {

    private final String balances = Database.table("casino_balances");
    private final String ledger = Database.table("casino_ledger");
    private final Map<UUID, long[]> cache = new ConcurrentHashMap<>(); // {chips, bet}
    private final long defaultBet;

    Store(org.bukkit.plugin.Plugin plugin, File folder, long defaultBet) throws SQLException {
        this.defaultBet = defaultBet;
        Database.Dialect d = Database.dialect();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + balances + " (uuid " + d.key(36) + " PRIMARY KEY, name TEXT,"
                    + " chips BIGINT NOT NULL DEFAULT 0, bet BIGINT NOT NULL, updated BIGINT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS " + ledger + " (id " + d.autoId() + ", uuid " + d.key(36) + " NOT NULL,"
                    + " delta BIGINT NOT NULL, balance BIGINT NOT NULL, reason TEXT NOT NULL, at BIGINT NOT NULL)");
            Database.index(c, "casino_ledger_uuid", ledger, "uuid, id", false);
        }
        // the file of the former versions (casino.db), moved once
        Database.importSqlite(plugin, new File(folder, "casino.db"), "balances", balances, "ledger", ledger);
    }

    void close() {
        // the pool belongs to Flux
    }

    private long[] row(UUID uuid) {
        return cache.computeIfAbsent(uuid, u -> {
            try (Connection c = Database.connection();
                 PreparedStatement ps = c.prepareStatement("SELECT chips, bet FROM " + balances + " WHERE uuid = ?")) {
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
        try (Connection c = Database.connection()) {
            c.setAutoCommit(false);
            try {
                write(c, uuid, name, after, r[1]);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO " + ledger + " (uuid, delta, balance, reason, at) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setString(1, uuid.toString());
                    ps.setLong(2, delta);
                    ps.setLong(3, after);
                    ps.setString(4, reason);
                    ps.setLong(5, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
            r[0] = after;
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("writing the balance of " + uuid, e);
        }
    }

    void setBet(UUID uuid, String name, long bet) {
        long[] r = row(uuid);
        try (Connection c = Database.connection()) {
            write(c, uuid, name, r[0], bet);
            r[1] = bet;
        } catch (SQLException e) {
            throw new IllegalStateException("writing the bet of " + uuid, e);
        }
    }

    private void write(Connection c, UUID uuid, String name, long chips, long bet) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(Database.dialect().upsert(balances, List.of("uuid", "name", "chips", "bet", "updated"),
                List.of("uuid"), List.of("name", "chips", "bet", "updated")))) {
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
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT delta, balance, reason, at FROM " + ledger + " WHERE uuid = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{String.valueOf(rs.getLong(1)), String.valueOf(rs.getLong(2)), rs.getString(3),
                            String.valueOf(rs.getLong(4))});
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading the ledger of " + uuid, e);
        }
        return out;
    }
}
