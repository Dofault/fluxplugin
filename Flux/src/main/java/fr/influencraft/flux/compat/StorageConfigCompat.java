// COMPAT 2026-10-31: the database settings moved from the database section of config.yml to storage.yml - remove when
// every server running Flux has a storage.yml (Labs moved by hand on 2026-10-31; production never had the section).
package fr.influencraft.flux.compat;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

/**
 * Moves the former {@code database} section of config.yml into storage.yml, once, before the database starts: a server
 * set to MySQL in config.yml must never start on the default SQLite file of a fresh storage.yml (the data would split).
 */
public final class StorageConfigCompat {

    private StorageConfigCompat() {
    }

    public static void move(JavaPlugin flux) {
        File storage = new File(flux.getDataFolder(), "storage.yml");
        File config = new File(flux.getDataFolder(), "config.yml");
        if (storage.exists() || !config.isFile()) return;
        // read without the jar's defaults: only what the server's file really holds
        YamlConfiguration former = YamlConfiguration.loadConfiguration(config);
        ConfigurationSection database = former.getConfigurationSection("database");
        if (database == null) return;
        flux.saveResource("storage.yml", false);
        YamlConfiguration moved = YamlConfiguration.loadConfiguration(storage);
        for (String key : database.getKeys(false)) moved.set(key, database.get(key));
        former.set("database", null);
        try {
            moved.save(storage);
            former.save(config);
        } catch (IOException e) {
            flux.getLogger().severe("Database settings not moved from config.yml to storage.yml: " + e.getMessage()
                    + ". Move the database section by hand before the next start.");
            return;
        }
        flux.reloadConfig();
        flux.getLogger().info("Database settings moved from config.yml (section database) to storage.yml.");
    }
}
