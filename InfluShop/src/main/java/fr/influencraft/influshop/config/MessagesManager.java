package fr.influencraft.influshop.config;

import fr.influencraft.influshop.text.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Loads and exposes messages.yml. All player-facing text lives here so the plugin can be
 * re-worded without touching a single line of Java.
 */
public final class MessagesManager {

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration config;

    public MessagesManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        mergeDefaults();
    }

    private void mergeDefaults() {
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in == null) {
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key)) {
                    continue;
                }
                if (!config.isSet(key)) {
                    config.set(key, defaults.get(key));
                    changed = true;
                }
            }
            if (changed) {
                config.save(file);
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not merge default messages.yml keys: " + ex.getMessage());
        }
    }

    public String raw(String path) {
        String value = config.getString(path);
        if (value == null) {
            plugin.getLogger().warning("Missing message key: " + path);
            return "&c[missing message: " + path + "]";
        }
        return value;
    }

    public String raw(String path, Map<String, String> placeholders) {
        String value = raw(path);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return value;
    }

    public List<String> rawList(String path) {
        List<String> value = config.getStringList(path);
        return value != null ? value : Collections.emptyList();
    }

    public List<String> rawList(String path, Map<String, String> placeholders) {
        return rawList(path).stream().map(line -> {
            String result = line;
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
            return result;
        }).toList();
    }

    public Component component(String path, OfflinePlayer papiContext) {
        return TextUtil.parse(raw(path), papiContext);
    }

    public Component component(String path, Map<String, String> placeholders, OfflinePlayer papiContext) {
        return TextUtil.parse(raw(path, placeholders), papiContext);
    }

    public void send(CommandSender receiver, String path, OfflinePlayer papiContext) {
        receiver.sendMessage(component(path, papiContext));
    }

    public void send(CommandSender receiver, String path, Map<String, String> placeholders, OfflinePlayer papiContext) {
        receiver.sendMessage(component(path, placeholders, papiContext));
    }
}
