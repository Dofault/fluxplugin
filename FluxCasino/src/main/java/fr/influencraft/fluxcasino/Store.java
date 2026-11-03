package fr.influencraft.fluxcasino;

import fr.influencraft.flux.api.data.Database;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bets of the players in the suite's database (table {@code flux_casino_balances}, see Flux {@link Database}), cached.
 * The chips themselves are Flux's {@code chips} currency since 2026-10-30 (balances, history: Flux); the
 * {@code chips} column and the {@code flux_casino_ledger} table only hold the data of the former versions, copied by
 * FluxMigration. Main thread only.
 */
final class Store {

    private final String balances = Database.table("flux_casino_balances");
    private final String ledger = Database.table("flux_casino_ledger");
    private final Map<UUID, Long> bets = new ConcurrentHashMap<>();
    private final long defaultBet;

    Store(org.bukkit.plugin.Plugin plugin, File folder, long defaultBet) throws SQLException {
        this.defaultBet = defaultBet;
        Database.Dialect d = Database.dialect();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            // names before 2026-10-31
            Database.renameTable(c, Database.table("casino_balances"), balances);
            Database.renameTable(c, Database.table("casino_ledger"), ledger, "casino_ledger_uuid");
            st.execute("CREATE TABLE IF NOT EXISTS " + balances + " (uuid " + d.key(36) + " PRIMARY KEY, name TEXT,"
                    + " chips BIGINT NOT NULL DEFAULT 0, bet BIGINT NOT NULL, updated BIGINT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS " + ledger + " (id " + d.autoId() + ", uuid " + d.key(36) + " NOT NULL,"
                    + " delta BIGINT NOT NULL, balance BIGINT NOT NULL, reason TEXT NOT NULL, at BIGINT NOT NULL)");
        }
        // the file of the former versions (casino.db), moved once
        Database.importSqlite(plugin, new File(folder, "casino.db"), "balances", balances, "ledger", ledger);
    }

    long bet(UUID uuid) {
        return bets.computeIfAbsent(uuid, u -> {
            try (Connection c = Database.connection();
                 PreparedStatement ps = c.prepareStatement("SELECT bet FROM " + balances + " WHERE uuid = ?")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : defaultBet;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("reading the bet of " + u, e);
            }
        });
    }

    void forget(UUID uuid) {
        bets.remove(uuid);
    }

    void setBet(UUID uuid, String name, long bet) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(Database.dialect().upsert(balances,
                List.of("uuid", "name", "bet", "updated"), List.of("uuid"), List.of("name", "bet", "updated")))) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, bet);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            bets.put(uuid, bet);
        } catch (SQLException e) {
            throw new IllegalStateException("writing the bet of " + uuid, e);
        }
    }
}
