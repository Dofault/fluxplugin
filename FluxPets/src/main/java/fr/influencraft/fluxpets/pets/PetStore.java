package fr.influencraft.fluxpets.pets;

import fr.influencraft.flux.api.data.Database;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pets of the players in Flux's database (2026-11-13; eco's player profile before, copied by FluxMigration):
 * {@code flux_pets_levels} (level and XP of each pet adopted) and {@code flux_pets_players} (active pets in order,
 * hidden or not). Read once per player and kept in memory; changes are written in the background every 30 seconds,
 * when the player leaves and when the server stops (pets earn XP at every block: never one write per gain).
 */
public final class PetStore implements Listener {

    /** What FluxPets knows of one player. */
    public static final class Profile {
        final Map<String, Integer> levels = new ConcurrentHashMap<>();
        final Map<String, Double> xp = new ConcurrentHashMap<>();
        final List<String> active = new CopyOnWriteArrayList<>();
        volatile boolean hidden;
    }

    private static volatile PetStore instance;

    private final Plugin plugin;
    private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private BukkitTask flusher;

    private PetStore(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Opens the tables and starts the background writes. */
    public static PetStore start(Plugin plugin) throws SQLException {
        PetStore store = new PetStore(plugin);
        String key = Database.dialect().key(36);
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + levels() + " (uuid " + key + " NOT NULL, pet "
                    + Database.dialect().key(64) + " NOT NULL, level INTEGER NOT NULL, xp DOUBLE PRECISION NOT NULL, PRIMARY KEY (uuid, pet))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + players() + " (uuid " + key + " NOT NULL PRIMARY KEY,"
                    + " active TEXT NOT NULL, hidden INTEGER NOT NULL)");
        }
        Bukkit.getPluginManager().registerEvents(store, plugin);
        store.flusher = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, store::flush, 600L, 600L);
        Bukkit.getScheduler().runTaskTimer(plugin, store::forgetOffline, 6000L, 6000L);
        instance = store;
        return store;
    }

    /** Writes everything changed, now (server stopping). */
    public void stop() {
        if (flusher != null) flusher.cancel();
        flush();
        instance = null;
    }

    public static PetStore get() {
        return instance;
    }

    private static String levels() {
        return Database.table("flux_pets_levels");
    }

    private static String players() {
        return Database.table("flux_pets_players");
    }

    /** The profile of a player, read from the database the first time. */
    Profile of(UUID player) {
        return profiles.computeIfAbsent(player, this::read);
    }

    void changed(UUID player) {
        dirty.add(player);
    }

    /** Whether the database knows this player (imports leave him as he is). */
    public boolean known(UUID player) {
        try (Connection c = Database.connection(); PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + players() + " WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return true; // unknown: never overwrite
        }
    }

    /** Replaces a player's whole data and writes it at once (imports). */
    public void put(UUID player, Map<String, Integer> levels, Map<String, Double> xp, List<String> active, boolean hidden) {
        Profile p = new Profile();
        p.levels.putAll(levels);
        p.xp.putAll(xp);
        p.active.addAll(active);
        p.hidden = hidden;
        profiles.put(player, p);
        write(player, p);
    }

    private Profile read(UUID player) {
        Profile p = new Profile();
        try (Connection c = Database.connection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT pet, level, xp FROM " + levels() + " WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        p.levels.put(rs.getString(1), rs.getInt(2));
                        p.xp.put(rs.getString(1), rs.getDouble(3));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT active, hidden FROM " + players() + " WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        for (String id : rs.getString(1).split(",")) if (!id.isBlank()) p.active.add(id);
                        p.hidden = rs.getInt(2) != 0;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Pets of " + player + " not read: " + e.getMessage());
        }
        return p;
    }

    private void flush() {
        for (UUID id : new ArrayList<>(dirty)) {
            dirty.remove(id);
            Profile p = profiles.get(id);
            if (p != null) write(id, p);
        }
    }

    /** One transaction per player: the rows are never half written. */
    private void write(UUID player, Profile p) {
        try (Connection c = Database.connection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + levels() + " WHERE uuid = ?")) {
                    ps.setString(1, player.toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + levels() + " (uuid, pet, level, xp) VALUES (?, ?, ?, ?)")) {
                    for (Map.Entry<String, Integer> e : p.levels.entrySet()) {
                        if (e.getValue() <= 0 && p.xp.getOrDefault(e.getKey(), 0.0) <= 0) continue;
                        ps.setString(1, player.toString());
                        ps.setString(2, e.getKey());
                        ps.setInt(3, e.getValue());
                        ps.setDouble(4, p.xp.getOrDefault(e.getKey(), 0.0));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + players() + " WHERE uuid = ?")) {
                    ps.setString(1, player.toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + players() + " (uuid, active, hidden) VALUES (?, ?, ?)")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, String.join(",", p.active));
                    ps.setInt(3, p.hidden ? 1 : 0);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            dirty.add(player); // tried again at the next flush
            plugin.getLogger().severe("Pets of " + player + " not saved: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Profile p = profiles.get(id);
        if (p != null && dirty.remove(id)) Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(id, p));
    }

    /** Main thread, every 5 minutes: profiles of players offline and saved are forgotten (read again when needed). */
    private void forgetOffline() {
        profiles.keySet().removeIf(id -> Bukkit.getPlayer(id) == null && !dirty.contains(id));
    }
}
