package fr.influencraft.fluxoffering.data;

import fr.influencraft.fluxoffering.model.PlayerData;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import fr.influencraft.flux.api.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player progress storage, one YAML file per UUID under plugins/FluxOffering/data/.
 * <p>
 * Chosen over a database: with a few thousand players this is a handful of KB per file,
 * writes only ever happen on a deposit or an admin edit (never polled), and it keeps the
 * plugin dependency-free. Online players are cached in memory; offline lookups (admin
 * commands, PAPI on offline players) read straight from disk since that file is tiny.
 */
/**
 * Offering progress of the players, in Flux's database (2026-11-13; files {@code data/<uuid>.yml} before, copied by
 * FluxMigration): {@code flux_offering_players} (name, menu filter) and {@code flux_offering_deposits} (one row per
 * collection and item; the money of a collection under the item key {@link #MONEY}). Cached while online.
 */
public final class DataManager {

    /** Item key of a collection's money in the deposits table (never an item key). */
    public static final String MONEY = "$money";

    private final JavaPlugin plugin;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();

    public DataManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private static String players() {
        return Database.table("flux_offering_players");
    }

    private static String deposits() {
        return Database.table("flux_offering_deposits");
    }

    public void init() {
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            String key = Database.dialect().key(36);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + players() + " (uuid " + key + " NOT NULL PRIMARY KEY,"
                    + " name VARCHAR(32), filter VARCHAR(32) NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + deposits() + " (uuid " + key + " NOT NULL, collection "
                    + Database.dialect().key(64) + " NOT NULL, item " + Database.dialect().key(191) + " NOT NULL, amount BIGINT NOT NULL,"
                    + " PRIMARY KEY (uuid, collection, item))");
        } catch (SQLException e) {
            plugin.getLogger().severe("Offering tables unavailable: " + e.getMessage());
        }
    }

    /** Whether the database knows this player (imports skip him). */
    public boolean known(UUID uuid) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + players() + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return true; // unknown: never overwrite
        }
    }

    private PlayerData read(UUID uuid, String name) {
        PlayerData data = new PlayerData(uuid, name);
        try (Connection c = Database.connection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT name, filter FROM " + players() + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        if (rs.getString(1) != null) data.lastKnownName(rs.getString(1));
                        data.menuFilterMode(rs.getString(2));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT collection, item, amount FROM " + deposits() + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String col = rs.getString(1), item = rs.getString(2);
                        if (MONEY.equals(item)) data.rawMoney().put(col, rs.getLong(3));
                        else data.raw().computeIfAbsent(col, k -> new ConcurrentHashMap<>()).put(item, (int) rs.getLong(3));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not read the offerings of " + uuid + ": " + e.getMessage());
        }
        data.clearDirty();
        return data;
    }

    /** Writes the whole progress of a player (one transaction: never half written). */
    public void write(PlayerData data) {
        try (Connection c = Database.connection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + players() + " WHERE uuid = ?")) {
                    ps.setString(1, data.uuid().toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + players() + " (uuid, name, filter) VALUES (?, ?, ?)")) {
                    ps.setString(1, data.uuid().toString());
                    ps.setString(2, data.lastKnownName());
                    ps.setString(3, data.menuFilterMode() == null ? "ALL" : data.menuFilterMode());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + deposits() + " WHERE uuid = ?")) {
                    ps.setString(1, data.uuid().toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + deposits() + " (uuid, collection, item, amount) VALUES (?, ?, ?, ?)")) {
                    for (Map.Entry<String, Map<String, Integer>> col : data.raw().entrySet()) {
                        for (Map.Entry<String, Integer> item : col.getValue().entrySet()) {
                            if (item.getValue() <= 0) continue;
                            ps.setString(1, data.uuid().toString());
                            ps.setString(2, col.getKey());
                            ps.setString(3, item.getKey());
                            ps.setLong(4, item.getValue());
                            ps.addBatch();
                        }
                    }
                    for (Map.Entry<String, Long> money : data.rawMoney().entrySet()) {
                        if (money.getValue() <= 0) continue;
                        ps.setString(1, data.uuid().toString());
                        ps.setString(2, money.getKey());
                        ps.setString(3, MONEY);
                        ps.setLong(4, money.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
                data.clearDirty();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not save the offerings of " + data.uuid() + ": " + e.getMessage());
        }
    }

    /**
     * Loads (or creates) a player's data into the online cache. Safe to call off the main thread.
     */
    public PlayerData loadIntoCache(UUID uuid, String name) {
        PlayerData data = read(uuid, name);
        cache.put(uuid, data);
        return data;
    }

    public PlayerData get(Player player) {
        return cache.computeIfAbsent(player.getUniqueId(), id -> read(id, player.getName()));
    }

    /**
     * Reads straight from the database, bypassing the online cache. Use for offline players
     * (admin commands, PAPI on an OfflinePlayer that isn't connected).
     */
    public PlayerData getOffline(OfflinePlayer player) {
        PlayerData cached = cache.get(player.getUniqueId());
        if (cached != null) {
            return cached;
        }
        return read(player.getUniqueId(), player.getName());
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    public void saveAsync(PlayerData data) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> write(data));
    }

    public void saveSync(PlayerData data) {
        write(data);
    }

    /**
     * Saves every dirty entry currently cached in memory. Used by the autosave task and on shutdown.
     */
    public void saveAllDirty() {
        for (PlayerData data : cache.values()) {
            if (data.isDirty()) {
                write(data);
            }
        }
    }

    /**
     * Persists an offline player's data changed outside the cache (admin reset/set commands).
     */
    public void saveOffline(PlayerData data) {
        PlayerData cached = cache.get(data.uuid());
        if (cached == data) {
            saveAsync(data);
        } else {
            write(data);
        }
    }
}
