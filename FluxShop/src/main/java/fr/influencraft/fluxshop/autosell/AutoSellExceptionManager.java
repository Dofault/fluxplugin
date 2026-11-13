package fr.influencraft.fluxshop.autosell;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les exceptions de la vente automatique de chaque joueur : ce qui ne part jamais en vente auto
 * (clés {@link AutoSellFilter} : nom de matériau ou {@code fish:<palier>}).
 * <p>
 * Tant que le joueur n'y a pas touché, il a les exceptions par défaut du serveur (et suit donc
 * leurs changements). Dès sa première modification, sa liste est écrite sur disque et figée ;
 * « Réinitialiser » dans le menu efface ce fichier pour revenir au défaut.
 * <p>
 * Stockées par joueur et non sur l'objet (contrairement à l'état actif/inactif, qui reste sur
 * l'ItemStack) : un joueur qui perd ou rachète son objet garde ses réglages. Table
 * {@code flux_shop_autosell} de la base Flux (2026-11-13, avant : un fichier par joueur dans {@code autosell/},
 * repris par FluxMigration), lue à la demande et gardée en cache jusqu'à la déconnexion.
 */
public class AutoSellExceptionManager implements Listener {

    private final JavaPlugin plugin;
    private final AutoSellFilter filter;
    // Optional.empty() = le joueur utilise le défaut
    private final Map<UUID, Optional<Set<String>>> cache = new ConcurrentHashMap<>();

    public AutoSellExceptionManager(JavaPlugin plugin, AutoSellFilter filter) {
        this.plugin = plugin;
        this.filter = filter;
    }

    /** @return une vue non modifiable des exceptions du joueur. */
    public Set<String> getExceptions(UUID uuid) {
        return load(uuid).map(Collections::unmodifiableSet).orElseGet(filter::getDefaultExceptions);
    }

    /** @return {@code true} si le joueur a encore les exceptions par défaut. */
    public boolean isDefault(UUID uuid) {
        return load(uuid).isEmpty();
    }

    public void setExceptions(UUID uuid, Collection<String> keys, boolean exception) {
        Set<String> set = load(uuid).orElseGet(() -> new LinkedHashSet<>(filter.getDefaultExceptions()));
        if (exception) {
            set.addAll(keys);
        } else {
            set.removeAll(keys);
        }
        cache.put(uuid, Optional.of(set));
        save(uuid, set);
    }

    public void resetToDefault(UUID uuid) {
        cache.put(uuid, Optional.empty());
        try (java.sql.Connection c = fr.influencraft.flux.api.data.Database.connection()) {
            delete(c, uuid);
        } catch (java.sql.SQLException e) {
            plugin.getLogger().severe("Auto-sell exceptions of " + uuid + " not reset: " + e.getMessage());
        }
    }

    private Optional<Set<String>> load(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::read);
    }

    private String table() {
        return fr.influencraft.flux.api.data.Database.table("flux_shop_autosell");
    }

    /** Table of the players' own lists (no row = the server's default). */
    public void open() throws java.sql.SQLException {
        try (java.sql.Connection c = fr.influencraft.flux.api.data.Database.connection(); java.sql.Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table() + " (uuid " + fr.influencraft.flux.api.data.Database.dialect().key(36)
                    + " NOT NULL PRIMARY KEY, exceptions TEXT NOT NULL, updated BIGINT NOT NULL)");
        }
    }

    private Optional<Set<String>> read(UUID uuid) {
        try (java.sql.Connection c = fr.influencraft.flux.api.data.Database.connection();
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT exceptions FROM " + table() + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String raw = rs.getString(1);
                Set<String> set = new LinkedHashSet<>();
                for (String k : raw.split("\n")) if (!k.isBlank()) set.add(k);
                return Optional.of(set);
            }
        } catch (java.sql.SQLException e) {
            plugin.getLogger().warning("Auto-sell exceptions of " + uuid + " not read, default used: " + e.getMessage());
            return Optional.empty();
        }
    }

    private void save(UUID uuid, Set<String> set) {
        try (java.sql.Connection c = fr.influencraft.flux.api.data.Database.connection()) {
            delete(c, uuid);
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO " + table() + " (uuid, exceptions, updated) VALUES (?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, String.join("\n", set));
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
        } catch (java.sql.SQLException e) {
            plugin.getLogger().severe("Auto-sell exceptions of " + uuid + " not saved: " + e.getMessage());
        }
    }

    private void delete(java.sql.Connection c, UUID uuid) throws java.sql.SQLException {
        try (java.sql.PreparedStatement ps = c.prepareStatement("DELETE FROM " + table() + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    /** A whole list at once (imports, FluxMigration through {@code fluxshop.api.AutoSellExceptions}). */
    public void replace(UUID uuid, Collection<String> keys) {
        Set<String> set = new LinkedHashSet<>(keys);
        cache.put(uuid, Optional.of(set));
        save(uuid, set);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cache.remove(event.getPlayer().getUniqueId());
    }
}
