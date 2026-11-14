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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A currency kept by Flux ({@code currencies.<id>.type: flux}): BitBlock (the server's money, also served to Vault by
 * {@link VaultProvider}), Influence, casino chips, any currency added later. Balances live in the suite's table
 * {@code flux_core_balances} (one row per player and currency) and are cached in memory (loaded before a player
 * joins, see {@link CurrencyService}).
 *
 * <ul>
 *   <li><b>Any thread.</b> Third-party plugins call Vault from wherever they are (a job paying from an async task):
 *   every read-modify-write of a balance happens under one lock, and the {@link BalanceChangeEvent} is fired on the
 *   calling thread, where the origin of the movement (context or call stack) can still be read.</li>
 *   <li><b>Writes</b> ({@code currencies.<id>.save}): {@code immediate} writes the row before the call returns (rare,
 *   large movements: Influence); {@code batched} keeps the cache as the truth and writes the changed balances every
 *   second on the database thread, and when the server stops (a job paying on every block must not write a row on
 *   every block). A crash loses at most the last second of a batched currency.</li>
 *   <li>Amounts are rounded to {@code decimals} (0 = whole units); a balance never goes below 0 nor above
 *   {@code max-balance}.</li>
 * </ul>
 */
public final class StoredCurrency implements Currency {

    static final String TABLE = Database.table("flux_core_balances");

    private final String id;
    private final int decimals;
    private final boolean batched;
    private final double startBalance;
    private final double maxBalance;

    /** Guards the four collections below and every balance change. */
    private final Object lock = new Object();
    private final Map<UUID, Double> cache = new HashMap<>();
    /** Batched mode: balances changed since the last write, with the name to store. */
    private final Map<UUID, String> dirty = new HashMap<>();
    /** Players whose balance stays in memory (online, or joining). */
    private final Set<UUID> pinned = new HashSet<>();
    /** The last batched write failed (logged once, not every second). */
    private volatile boolean failing;

    StoredCurrency(String id, int decimals, boolean batched, double startBalance, double maxBalance) {
        this.id = id;
        this.decimals = Math.max(0, Math.min(4, decimals));
        this.batched = batched;
        this.startBalance = Math.max(0, startBalance);
        this.maxBalance = maxBalance <= 0 ? 1e13 : maxBalance;
    }

    static void createTable() throws SQLException {
        Database.Dialect d = Database.dialect();
        try (Connection c = Database.connection(); var st = c.createStatement()) {
            Database.renameTable(c, Database.table("flux_balances"), TABLE); // name before 2026-10-31
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (uuid " + d.key(36) + " NOT NULL, currency " + d.key(32)
                    + " NOT NULL, name TEXT, balance DOUBLE NOT NULL DEFAULT 0, updated BIGINT NOT NULL, PRIMARY KEY (uuid, currency))");
            Database.index(c, "flux_core_balances_top", TABLE, "currency, balance", false);
        }
    }

    @Override
    public String id() {
        return id;
    }

    public int decimals() {
        return decimals;
    }

    public double maxBalance() {
        return maxBalance;
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

    public double balance(UUID uuid) {
        synchronized (lock) {
            Double cached = cache.get(uuid);
            if (cached != null) return cached;
        }
        // read outside the lock (a database read must not hold every payment up), then keep the first value stored
        double read = read(uuid);
        synchronized (lock) {
            Double raced = cache.putIfAbsent(uuid, read);
            return raced != null ? raced : read;
        }
    }

    /** Off the main thread, before the player joins: his balance stays in memory until {@link #forget}. */
    void preload(UUID uuid) {
        synchronized (lock) {
            pinned.add(uuid);
            if (cache.containsKey(uuid)) return;
        }
        double read = read(uuid);
        synchronized (lock) {
            cache.putIfAbsent(uuid, read);
        }
    }

    /** The player left: his balance leaves the memory, once written. */
    void forget(UUID uuid) {
        synchronized (lock) {
            pinned.remove(uuid);
            if (!dirty.containsKey(uuid)) cache.remove(uuid);
        }
    }

    /** Balance in the table, or the start balance for a player without a row. */
    private double read(UUID uuid) {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT balance FROM " + TABLE + " WHERE uuid = ? AND currency = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : startBalance;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reading the " + id + " balance of " + uuid, e);
        }
    }

    @Override
    public boolean take(OfflinePlayer player, double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) return false;
        double n = rounded(amount, true);
        if (n <= 0) return available();
        return change(player, before -> before + 1e-9 < n ? null : units(before - n), false);
    }

    @Override
    public boolean give(OfflinePlayer player, double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) return false;
        double n = rounded(amount, false);
        if (n <= 0) return available();
        return change(player, before -> before + n > maxBalance ? null : units(before + n), true);
    }

    /** Sets the balance (admin command, migration). */
    public boolean set(OfflinePlayer player, double amount) {
        if (Double.isNaN(amount)) return false;
        double target = units(Math.max(0, Math.min(maxBalance, amount)));
        boolean[] up = new boolean[1];
        return change(player, before -> {
            up[0] = target > before;
            return target;
        }, up);
    }

    private boolean change(OfflinePlayer player, java.util.function.DoubleFunction<Double> next, boolean deposit) {
        return change(player, next, new boolean[] {deposit});
    }

    /**
     * One balance change: {@code next} gives the new balance from the current one (null = refused). The check and the
     * change are one step under the lock: two payments at once can never both pass on the same money.
     */
    private boolean change(OfflinePlayer player, java.util.function.DoubleFunction<Double> next, boolean[] deposit) {
        if (!available()) return false;
        UUID uuid = player.getUniqueId();
        String name = Players.name(player, null);
        double before = balance(uuid); // loads the row when needed, outside the lock
        double after;
        synchronized (lock) {
            before = cache.getOrDefault(uuid, before);
            Double wanted = next.apply(before);
            if (wanted == null) return false;
            after = wanted;
            if (after == before) return true;
            if (batched) {
                dirty.put(uuid, name);
            } else if (!write(uuid, name, after)) {
                return false;
            }
            cache.put(uuid, after);
        }
        Bukkit.getPluginManager().callEvent(new BalanceChangeEvent(uuid, id, before, after, deposit[0]));
        return true;
    }

    private boolean write(UUID uuid, String name, double balance) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(upsert())) {
            bind(ps, uuid, name, balance);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[Flux] " + id + " balance of " + uuid + " not written: " + e.getMessage());
            return false;
        }
    }

    private static String upsert() {
        return Database.dialect().upsert(TABLE, List.of("uuid", "currency", "name", "balance", "updated"),
                List.of("uuid", "currency"), List.of("name", "balance", "updated"));
    }

    private void bind(PreparedStatement ps, UUID uuid, String name, double balance) throws SQLException {
        ps.setString(1, uuid.toString());
        ps.setString(2, id);
        ps.setString(3, name);
        ps.setDouble(4, balance);
        ps.setLong(5, System.currentTimeMillis());
    }

    /**
     * Batched mode: writes every balance changed since the last call, in one transaction. Called every second on the
     * database thread and once more when the server stops. A failed write keeps the balances marked, for the next call.
     */
    void flush() {
        if (!batched) return;
        Map<UUID, String> names;
        Map<UUID, Double> values = new HashMap<>();
        synchronized (lock) {
            if (dirty.isEmpty()) return;
            names = new HashMap<>(dirty);
            dirty.clear();
            for (UUID uuid : names.keySet()) values.put(uuid, cache.get(uuid));
        }
        List<UUID> failed = new ArrayList<>();
        try (Connection c = Database.connection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(upsert())) {
                for (Map.Entry<UUID, Double> e : values.entrySet()) {
                    if (e.getValue() == null) continue;
                    bind(ps, e.getKey(), names.get(e.getKey()), e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
            if (failing) {
                failing = false;
                Bukkit.getLogger().info("[Flux] " + id + " balances written again");
            }
        } catch (SQLException e) {
            failed.addAll(values.keySet());
            if (!failing) {
                failing = true;
                Bukkit.getLogger().severe("[Flux] " + values.size() + " " + id + " balance(s) not written, kept in memory and retried: " + e.getMessage());
            }
        }
        synchronized (lock) {
            for (UUID uuid : failed) dirty.putIfAbsent(uuid, names.get(uuid));
            // balances of players who left meanwhile leave the memory once written
            for (UUID uuid : values.keySet()) {
                if (!pinned.contains(uuid) && !dirty.containsKey(uuid)) cache.remove(uuid);
            }
        }
    }

    /** One row of {@link #top}. */
    public record Rank(UUID uuid, String name, double balance) {
    }

    /**
     * Richest players, from the table (off the main thread: call {@link #flush} first for exact figures), {@code limit}
     * rows from {@code offset}.
     */
    public List<Rank> top(int offset, int limit) throws SQLException {
        List<Rank> out = new ArrayList<>();
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, name, balance FROM " + TABLE
                     + " WHERE currency = ? AND balance > 0 ORDER BY balance DESC, uuid LIMIT ? OFFSET ?")) {
            ps.setString(1, id);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Rank(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getDouble(3)));
            }
        }
        return out;
    }

    /** Number of accounts with money and the money they hold together: {@code {accounts, total}}. */
    public double[] totals() throws SQLException {
        try (Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), COALESCE(SUM(balance), 0) FROM " + TABLE
                     + " WHERE currency = ? AND balance > 0")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new double[] {rs.getLong(1), rs.getDouble(2)} : new double[] {0, 0};
            }
        }
    }

    @Override
    public String format(double amount) {
        return Economy.format(id, amount);
    }
}
