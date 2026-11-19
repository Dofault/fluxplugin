package fr.influencraft.fluxdungeons.reward;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.data.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who beat which dungeon's boss, and how many times (2026-11-16, {@code flux_dungeons_victories}): the tower shows a
 * floor as cleared, and the mount of a boss is sold in the dungeon shop only to those who beat it. Read once at join,
 * kept in memory, written on each victory (off the main thread).
 */
public final class DungeonVictories implements Listener {

    private final FluxPlugin plugin;
    private final String table = Database.table("flux_dungeons_victories");
    private final Map<UUID, Map<String, Integer>> wins = new ConcurrentHashMap<>();

    public DungeonVictories(FluxPlugin plugin) throws SQLException {
        this.plugin = plugin;
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            Database.Dialect d = Database.dialect();
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (uuid " + d.key(36) + " NOT NULL, dungeon "
                    + d.key(32) + " NOT NULL, wins INTEGER NOT NULL, first_at BIGINT NOT NULL, PRIMARY KEY (uuid, dungeon))");
        }
        for (Player p : Bukkit.getOnlinePlayers()) load(p.getUniqueId());
    }

    /** Victories of the player on this dungeon (0: never beaten; also while his record is still being read). */
    public int wins(Player player, String dungeon) {
        Map<String, Integer> m = wins.get(player.getUniqueId());
        return m == null ? 0 : m.getOrDefault(dungeon, 0);
    }

    public boolean beaten(Player player, String dungeon) {
        return wins(player, dungeon) > 0;
    }

    /** One more victory; true when it is his first on that dungeon. */
    public boolean record(Player player, String dungeon) {
        UUID id = player.getUniqueId();
        int now = wins.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).merge(dungeon, 1, Integer::sum);
        long at = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = Database.connection()) {
                int changed;
                try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET wins = wins + 1 WHERE uuid = ? AND dungeon = ?")) {
                    ps.setString(1, id.toString());
                    ps.setString(2, dungeon);
                    changed = ps.executeUpdate();
                }
                if (changed == 0) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (uuid, dungeon, wins, first_at) VALUES (?, ?, 1, ?)")) {
                        ps.setString(1, id.toString());
                        ps.setString(2, dungeon);
                        ps.setLong(3, at);
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Victory of " + id + " on " + dungeon + " not saved: " + e.getMessage());
            }
        });
        return now == 1;
    }

    private void load(UUID id) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Integer> m = new ConcurrentHashMap<>();
            try (Connection c = Database.connection();
                 PreparedStatement ps = c.prepareStatement("SELECT dungeon, wins FROM " + table + " WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) m.put(rs.getString(1), rs.getInt(2));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Victories of " + id + " unreadable: " + e.getMessage());
                return;
            }
            // a victory recorded meanwhile (memory first) is added to what was stored
            wins.merge(id, m, (now, stored) -> {
                stored.forEach((k, v) -> now.merge(k, v, Integer::sum));
                return now;
            });
        });
    }

    /** Forgets every victory of the player (/fluxdungeons reset, test accounts). */
    public void erase(UUID id) {
        wins.remove(id);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = Database.connection();
                 PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Victories of " + id + " not erased: " + e.getMessage());
            }
            if (Bukkit.getPlayer(id) != null) wins.put(id, new ConcurrentHashMap<>());
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        load(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        wins.remove(e.getPlayer().getUniqueId());
    }
}
