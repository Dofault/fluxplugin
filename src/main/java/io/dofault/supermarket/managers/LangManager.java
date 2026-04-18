package io.dofault.supermarket.managers;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import net.md_5.bungee.api.ChatColor;

public class LangManager {
    private final JavaPlugin plugin;
    private final String locale;
    private final YamlConfiguration config;
    private final Map<String, String> messages = new HashMap<>();

    public LangManager(JavaPlugin plugin, String locale) {
        this.plugin = plugin;
        this.locale = locale;
        File file = new File(plugin.getDataFolder(), "lang/" + locale + ".yml");
        if (!file.exists()) {
            plugin.saveResource("lang/" + locale + ".yml", false);
        }
        this.config = YamlConfiguration.loadConfiguration(file);
        loadLanguage();
    }

    private void loadLanguage() {
        for (String key : config.getKeys(false)) {
            if (config.isString(key)) {
                messages.put(key, ChatColor.translateAlternateColorCodes('&', config.getString(key, key)));
            }
        }
    }

    public String get(String key, Map<String, String> placeholders) {
        String msg = messages.getOrDefault(key, key);
        if (placeholders != null) {
            for (var entry : placeholders.entrySet()) {
                msg = msg.replace("%" + entry.getKey() + "%", entry.getValue());
            }
        }
        return msg;
    }

    public String get(String key) {
        return get(key, null);
    }

    public List<String> getList(String key, Map<String, String> placeholders) {
        List<String> result = new ArrayList<>();
        if (!config.contains(key))
            return result;

        for (String line : config.getStringList(key)) {
            if (placeholders != null) {
                for (var entry : placeholders.entrySet()) {
                    line = line.replace("%" + entry.getKey() + "%", entry.getValue());
                }
            }
            result.add(ChatColor.translateAlternateColorCodes('&', line));
        }
        return result;
    }

    public List<String> getList(String key) {
        return getList(key, null);
    }
}
