package fr.influencraft.flux.economy;

import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.economy.BalanceChangeEvent;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A currency kept by Flux ({@code currencies.<id>.type: flux}): Influence, casino chips, any currency added later.
 * Balances live in the suite's table {@code flux_core_balances} (one row per player and currency), written right away on
 * every movement (money: no delayed save), cached in memory (loaded before a player joins, see
 * {@link CurrencyService}). Every movement fires a {@link BalanceChangeEvent}, which records it in the ledger.
 * Amounts are rounded to {@code decimals} (0 = whole units). Main thread.
 */
public final class StoredCurrency implements Currency {

    static final String TABLE = Database.table("flux_core_balances");

    private final String id;
    private final int decimals;
    private final Map<UUID, Double> cache = new ConcurrentHashMap<>();

    StoredCurrency(String id, int decimals) {
        this.id = id;
        this.decimals = Math.max(0, Math.min(4, decimals));
    }

    static void createTable() throws SQLException {
        Database.Dialect d = Database.dialect();
        try (Connection c = Database.connection(); var st = c.createStatement()) {
            Database.renameTable(c, Database.table("flux_balances"), TABLE); // name before 2026-10-31
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (uuid " + d.key(36) + " NOT NULL, currency " + d.key(32)
                    + " NOT NULL, name TEXT, balance DOUBLE NOT NULL DEFAULT 0, updated BIGINT NOT NULL, PRIMARY KEY (uuid, currency))");
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean available() {
        return Database.available();
    }

    private double units(double amount) {
        double f = Math.pow(10, decimals);
        return Math.round(amount * f) / f;
    }

    /** Rounds a requested amount for this currency: a cost up (never less than asked), a payment down. */
    private double rounded(double amount, boolean up) {
        double f = Math.pow(10, decimals);
        return (up ? Math.ceil(amount * f - 1e-6) : Math.floor(amount * f + 1e-6)) / f;
    }

    @Override
    public double balance(OfflinePlayer player) {
        return balance(player.getUniqueId());
    }

    double balance(UUID uuid) {
        Double cached = cache.get(uuid);
        if (cached != null) return cached;
        double read = read(uuid);
        cache.putIfAbsent(uuid, read);
        return cache.get(uuid);
    }

    /** Off the main thread, before the player joins. */
    void preload(UUID uuid) {
        if (!cache.containsKey(uuid)) cache.putIfAbsent(uuid, read(uuid));
    }

    void forget(UUID uuid) {
        cache.remove(uuid);
    }

    private double read(UUID uuid) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT balance FROM " + TABLE + " WHERE uuid = ? AND currency = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading the " + id + " balance of " + uuid, e);
        }
    }

    @Override
    public boolean take(OfflinePlayer player, double amount) {
        double n = rounded(amount, true);
        if (n <= 0) return available();
        double before = balance(player);
        if (before + 1e-9 < n) return false;
        return change(player, before, units(before - n), false);
    }

    @Override
    public boolean give(OfflinePlayer player, double amount) {
        double n = rounded(amount, false);
        if (n <= 0) return available();
        double before = balance(player);
        return change(player, before, units(before + n), true);
    }

    /** Sets the balance (admin command, migration). */
    public boolean set(OfflinePlayer player, double amount) {
        double before = balance(player);
        return change(player, before, units(Math.max(0, amount)), amount > before);
    }

    private boolean change(OfflinePlayer player, double before, double after, boolean deposit) {
        UUID uuid = player.getUniqueId();
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement(Database.dialect().upsert(TABLE, List.of("uuid", "currency", "name", "balance", "updated"),
                     List.of("uuid", "currency"), List.of("name", "balance", "updated")))) {
            ps.setString(1, uuid.toString());
            ps.setString(2, id);
            ps.setString(3, Players.name(player, null));
            ps.setDouble(4, after);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[Flux] " + id + " balance of " + uuid + " not written: " + e.getMessage());
            return false;
        }
        cache.put(uuid, after);
        Bukkit.getPluginManager().callEvent(new BalanceChangeEvent(uuid, id, before, after, deposit));
        return true;
    }

    @Override
    public String format(double amount) {
        return Economy.format(id, amount);
    }
}
