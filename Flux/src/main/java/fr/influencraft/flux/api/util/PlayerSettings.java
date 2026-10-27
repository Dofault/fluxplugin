package fr.influencraft.flux.api.util;

import fr.influencraft.flux.api.data.Database;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small per-player preferences of the suite (notification toggles of the settings menu...), kept by Flux in the
 * table {@code flux_settings} of the suite's database. Keys are namespaced by plugin: {@code "fluxtools:job-xp-bar"}.
 * Cached; main thread only.
 *
 * <pre>
 *   if (PlayerSettings.flag(player, "fluxwebsite:vote-reminder", true)) remind(player);
 *   PlayerSettings.set(player, "fluxwebsite:vote-reminder", false);
 * </pre>
 */
public final class PlayerSettings {

    private static boolean ready;
    private static String table;
    private static java.util.logging.Logger logger;
    private static final Map<UUID, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private PlayerSettings() {
    }

    /** Called by Flux at start-up, once the database is started. */
    public static void open(Plugin flux) {
        logger = flux.getLogger();
        table = Database.table("flux_settings");
        Database.Dialect d = Database.dialect();
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + table + " (uuid " + d.key(36) + " NOT NULL, setting_key " + d.key(128)
                    + " NOT NULL, value TEXT NOT NULL, PRIMARY KEY (uuid, setting_key))");
            ready = true;
        } catch (SQLException e) {
            ready = false;
            logger.severe("Player settings unavailable (defaults used): " + e);
            return;
        }
        importFormerFile(new File(flux.getDataFolder(), "players.db"));
    }

    /** {@code plugins/Flux/players.db} of the first version: moved once. */
    private static void importFormerFile(File file) {
        if (!file.isFile()) return;
        int n = 0;
        try (Connection old = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
             Statement st = old.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, key, value FROM settings");
             Connection c = Database.connection();
             PreparedStatement ps = c.prepareStatement(upsert())) {
            while (rs.next()) {
                ps.setString(1, rs.getString(1));
                ps.setString(2, rs.getString(2));
                ps.setString(3, rs.getString(3));
                ps.executeUpdate();
                n++;
            }
        } catch (SQLException e) {
            logger.warning("Former players.db not moved into the database: " + e.getMessage());
            return;
        }
        file.renameTo(new File(file.getPath() + ".moved-to-flux"));
        new File(file.getPath() + "-wal").delete();
        new File(file.getPath() + "-shm").delete();
        logger.info(n + " player setting(s) moved into the database of Flux.");
    }

    private static String upsert() {
        return Database.dialect().upsert(table, java.util.List.of("uuid", "setting_key", "value"),
                java.util.List.of("uuid", "setting_key"), java.util.List.of("value"));
    }

    public static void close() {
        CACHE.clear();
        ready = false;
    }

    /** Drops the cache of a player who left. */
    public static void forget(UUID uuid) {
        CACHE.remove(uuid);
    }

    private static Map<String, String> load(UUID uuid) {
        return CACHE.computeIfAbsent(uuid, u -> {
            Map<String, String> values = new ConcurrentHashMap<>();
            if (!ready) return values;
            try (Connection c = Database.connection();
                 PreparedStatement ps = c.prepareStatement("SELECT setting_key, value FROM " + table + " WHERE uuid = ?")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) values.put(rs.getString(1), rs.getString(2));
                }
            } catch (SQLException e) {
                logger.warning("Could not read the settings of " + u + ": " + e);
            }
            return values;
        });
    }

    public static String get(OfflinePlayer player, String key, String fallback) {
        return load(player.getUniqueId()).getOrDefault(key, fallback);
    }

    public static boolean flag(OfflinePlayer player, String key, boolean fallback) {
        String v = load(player.getUniqueId()).get(key);
        return v == null ? fallback : Boolean.parseBoolean(v);
    }

    public static boolean has(OfflinePlayer player, String key) {
        return load(player.getUniqueId()).containsKey(key);
    }

    public static void set(OfflinePlayer player, String key, Object value) {
        String v = String.valueOf(value);
        load(player.getUniqueId()).put(key, v);
        if (!ready) return;
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement(upsert())) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, key);
            ps.setString(3, v);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Could not save the setting " + key + " of " + player.getUniqueId() + ": " + e);
        }
    }
}
