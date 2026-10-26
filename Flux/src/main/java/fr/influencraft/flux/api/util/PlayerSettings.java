package fr.influencraft.flux.api.util;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small per-player preferences of the suite (notification toggles of the settings menu...), kept by Flux in
 * {@code plugins/Flux/players.db}. Keys are namespaced by plugin: {@code "fluxtools:job-xp-bar"}. Cached; main
 * thread only.
 *
 * <pre>
 *   if (PlayerSettings.flag(player, "fluxwebsite:vote-reminder", true)) remind(player);
 *   PlayerSettings.set(player, "fluxwebsite:vote-reminder", false);
 * </pre>
 */
public final class PlayerSettings {

    private static Connection db;
    private static java.util.logging.Logger logger;
    private static final Map<UUID, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private PlayerSettings() {
    }

    /** Called by Flux at start-up. */
    public static void open(Plugin flux) {
        logger = flux.getLogger();
        try {
            flux.getDataFolder().mkdirs();
            db = DriverManager.getConnection("jdbc:sqlite:" + new File(flux.getDataFolder(), "players.db").getAbsolutePath());
            try (Statement st = db.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("CREATE TABLE IF NOT EXISTS settings (uuid TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL,"
                        + " PRIMARY KEY (uuid, key))");
            }
        } catch (SQLException e) {
            db = null;
            logger.severe("Player settings unavailable (defaults used): " + e);
        }
    }

    public static void close() {
        CACHE.clear();
        if (db == null) return;
        try {
            db.close();
        } catch (SQLException ignored) {
            // server stopping
        }
        db = null;
    }

    /** Drops the cache of a player who left. */
    public static void forget(UUID uuid) {
        CACHE.remove(uuid);
    }

    private static Map<String, String> load(UUID uuid) {
        return CACHE.computeIfAbsent(uuid, u -> {
            Map<String, String> values = new ConcurrentHashMap<>();
            if (db == null) return values;
            try (PreparedStatement ps = db.prepareStatement("SELECT key, value FROM settings WHERE uuid = ?")) {
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
        if (db == null) return;
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO settings (uuid, key, value) VALUES (?, ?, ?) ON CONFLICT(uuid, key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, key);
            ps.setString(3, v);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Could not save the setting " + key + " of " + player.getUniqueId() + ": " + e);
        }
    }
}
