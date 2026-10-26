package fr.influencraft.influoffrande.data;

import fr.influencraft.influoffrande.model.PlayerData;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player progress storage, one YAML file per UUID under plugins/InfluOffrande/data/.
 * <p>
 * Chosen over a database: with a few thousand players this is a handful of KB per file,
 * writes only ever happen on a deposit or an admin edit (never polled), and it keeps the
 * plugin dependency-free. Online players are cached in memory; offline lookups (admin
 * commands, PAPI on offline players) read straight from disk since that file is tiny.
 */
public final class DataManager {

    private final JavaPlugin plugin;
    private final File dataFolder;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();

    public DataManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "data");
    }

    public void init() {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().severe("Could not create data folder: " + dataFolder);
        }
    }

    private File fileFor(UUID uuid) {
        return new File(dataFolder, uuid.toString() + ".yml");
    }

    private PlayerData readFromDisk(UUID uuid, String name) {
        File file = fileFor(uuid);
        PlayerData data = new PlayerData(uuid, name);
        if (!file.exists()) {
            return data;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String storedName = yaml.getString("player-name");
        if (storedName != null) {
            data.lastKnownName(storedName);
        }
        data.menuFilterMode(yaml.getString("menu-filter-mode", "ALL"));
        ConfigurationSection collectionsSection = yaml.getConfigurationSection("collections");
        if (collectionsSection != null) {
            for (String collectionId : collectionsSection.getKeys(false)) {
                ConfigurationSection itemsSection = collectionsSection.getConfigurationSection(collectionId);
                if (itemsSection == null) {
                    continue;
                }
                for (String itemKey : itemsSection.getKeys(false)) {
                    if (itemKey.equals("money")) {
                        data.rawMoney().put(collectionId, itemsSection.getLong(itemKey));
                        continue;
                    }
                    data.raw().computeIfAbsent(collectionId, c -> new ConcurrentHashMap<>())
                            .put(itemKey, itemsSection.getInt(itemKey));
                }
            }
        }
        data.clearDirty();
        return data;
    }

    private void writeToDisk(PlayerData data) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("uuid", data.uuid().toString());
        yaml.set("player-name", data.lastKnownName());
        yaml.set("menu-filter-mode", data.menuFilterMode());
        ConfigurationSection collectionsSection = yaml.createSection("collections");
        Set<String> collectionIds = new LinkedHashSet<>();
        collectionIds.addAll(data.raw().keySet());
        collectionIds.addAll(data.rawMoney().keySet());
        for (String collectionId : collectionIds) {
            Map<String, Integer> items = data.raw().getOrDefault(collectionId, Map.of());
            long money = data.rawMoney().getOrDefault(collectionId, 0L);
            if (items.isEmpty() && money <= 0) {
                continue;
            }
            ConfigurationSection itemsSection = collectionsSection.createSection(collectionId);
            for (Map.Entry<String, Integer> item : items.entrySet()) {
                itemsSection.set(item.getKey(), item.getValue());
            }
            if (money > 0) {
                itemsSection.set("money", money);
            }
        }
        try {
            yaml.save(fileFor(data.uuid()));
            data.clearDirty();
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save data for " + data.uuid() + ": " + ex.getMessage());
        }
    }

    /**
     * Loads (or creates) a player's data into the online cache. Safe to call off the main thread.
     */
    public PlayerData loadIntoCache(UUID uuid, String name) {
        PlayerData data = readFromDisk(uuid, name);
        cache.put(uuid, data);
        return data;
    }

    public PlayerData get(Player player) {
        return cache.computeIfAbsent(player.getUniqueId(), id -> readFromDisk(id, player.getName()));
    }

    /**
     * Reads straight from disk, bypassing the online cache. Use for offline players
     * (admin commands, PAPI on an OfflinePlayer that isn't connected).
     */
    public PlayerData getOffline(OfflinePlayer player) {
        PlayerData cached = cache.get(player.getUniqueId());
        if (cached != null) {
            return cached;
        }
        return readFromDisk(player.getUniqueId(), player.getName());
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    public void saveAsync(PlayerData data) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> writeToDisk(data));
    }

    public void saveSync(PlayerData data) {
        writeToDisk(data);
    }

    /**
     * Saves every dirty entry currently cached in memory. Used by the autosave task and on shutdown.
     */
    public void saveAllDirty() {
        for (PlayerData data : cache.values()) {
            if (data.isDirty()) {
                writeToDisk(data);
            }
        }
    }

    /**
     * Persists an offline player's data that was modified directly on disk (admin reset/set commands).
     */
    public void saveOffline(PlayerData data) {
        PlayerData cached = cache.get(data.uuid());
        if (cached == data) {
            saveAsync(data);
        } else {
            writeToDisk(data);
        }
    }
}
