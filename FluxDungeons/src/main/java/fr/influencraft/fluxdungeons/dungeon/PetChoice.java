package fr.influencraft.fluxdungeons.dungeon;

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
 * The pet each player takes into the dungeons (2026-11-16, {@code flux_dungeons_pet}): one pet id, chosen in the
 * dungeon menu; none by default. Read at join, written when it changes (off the main thread).
 */
public final class PetChoice implements Listener {

    private final FluxPlugin plugin;
    private final String table = Database.table("flux_dungeons_pet");
    private final Map<UUID, String> chosen = new ConcurrentHashMap<>();

    public PetChoice(FluxPlugin plugin) throws SQLException {
        this.plugin = plugin;
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            Database.Dialect d = Database.dialect();
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (uuid " + d.key(36) + " NOT NULL, pet "
                    + d.key(64) + " NOT NULL, PRIMARY KEY (uuid))");
        }
        for (Player p : Bukkit.getOnlinePlayers()) load(p.getUniqueId());
    }

    /** The pet he takes into dungeons, null for none. */
    public String get(Player player) {
        return chosen.get(player.getUniqueId());
    }

    /** Takes this pet into the dungeons (null: none). */
    public void set(Player player, String pet) {
        UUID id = player.getUniqueId();
        if (pet == null) chosen.remove(id);
        else chosen.put(id, pet);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = Database.connection()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE uuid = ?")) {
                    ps.setString(1, id.toString());
                    ps.executeUpdate();
                }
                if (pet != null) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (uuid, pet) VALUES (?, ?)")) {
                        ps.setString(1, id.toString());
                        ps.setString(2, pet);
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Dungeon pet of " + id + " not saved: " + e.getMessage());
            }
        });
    }

    private void load(UUID id) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = Database.connection();
                 PreparedStatement ps = c.prepareStatement("SELECT pet FROM " + table + " WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) chosen.putIfAbsent(id, rs.getString(1));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Dungeon pet of " + id + " unreadable: " + e.getMessage());
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        load(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        chosen.remove(e.getPlayer().getUniqueId());
    }
}
