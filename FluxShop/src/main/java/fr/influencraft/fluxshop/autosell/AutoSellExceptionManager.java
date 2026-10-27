package fr.influencraft.fluxshop.autosell;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
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
 * l'ItemStack) : un joueur qui perd ou rachète son objet garde ses réglages. Un fichier par
 * joueur dans {@code autosell/<uuid>.yml}, chargé à la demande et gardé en cache jusqu'à la
 * déconnexion (le filtre le consulte à chaque ramassage).
 */
public class AutoSellExceptionManager implements Listener {

    private final JavaPlugin plugin;
    private final AutoSellFilter filter;
    private final File folder;
    // Optional.empty() = le joueur utilise le défaut
    private final Map<UUID, Optional<Set<String>>> cache = new ConcurrentHashMap<>();

    public AutoSellExceptionManager(JavaPlugin plugin, AutoSellFilter filter) {
        this.plugin = plugin;
        this.filter = filter;
        this.folder = new File(plugin.getDataFolder(), "autosell");
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
        File file = file(uuid);
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("Impossible de supprimer " + file.getName() + " (réinitialisation de la vente auto).");
        }
    }

    private Optional<Set<String>> load(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::readFile);
    }

    private File file(UUID uuid) {
        return new File(folder, uuid + ".yml");
    }

    private Optional<Set<String>> readFile(UUID uuid) {
        File file = file(uuid);
        if (!file.exists()) {
            return Optional.empty();
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (!yaml.isList("exceptions")) {
            return Optional.empty();
        }
        return Optional.of(new LinkedHashSet<>(yaml.getStringList("exceptions")));
    }

    private void save(UUID uuid, Set<String> set) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("exceptions", new ArrayList<>(set));
        try {
            if (!folder.exists()) {
                folder.mkdirs();
            }
            yaml.save(file(uuid));
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder les exceptions de vente auto de " + uuid + " : " + e.getMessage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cache.remove(event.getPlayer().getUniqueId());
    }
}
