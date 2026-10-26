package io.dofault.supermarket.managers;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final File file;
    private YamlConfiguration config;
    private final Map<String, String> messages = new HashMap<>();

    public LangManager(JavaPlugin plugin, String locale) {
        this.plugin = plugin;
        this.locale = locale;
        this.file = new File(plugin.getDataFolder(), "lang/" + locale + ".yml");
        if (!file.exists()) {
            plugin.saveResource("lang/" + locale + ".yml", false);
        }
        this.config = YamlConfiguration.loadConfiguration(file);
        loadLanguage();
    }

    public void reload() {
        this.config = YamlConfiguration.loadConfiguration(file);
        messages.clear();
        loadLanguage();
    }

    private void loadLanguage() {
        for (String key : config.getKeys(false)) {
            if (config.isString(key)) {
                messages.put(key, colorize(config.getString(key, key)));
            }
        }
    }

    // Les fichiers de langue déjà en place sur SemiRP écrivent leurs couleurs custom en
    // §x§R§R§G§G§B§B (forme développée), que Bukkit rend directement. La forme courte
    // &#RRGGBB de la charte graphique, elle, n'est PAS comprise par
    // ChatColor.translateAlternateColorCodes — elle est donc convertie ici avant lui, pour
    // que les deux écritures fonctionnent indifféremment dans les fichiers lang.
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([0-9a-fA-F]{6})");

    public static String colorize(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = HEX_PATTERN.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(ChatColor.of("#" + matcher.group(1)).toString()));
        }
        matcher.appendTail(out);
        return ChatColor.translateAlternateColorCodes('&', out.toString());
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
            result.add(colorize(line));
        }
        return result;
    }

    public List<String> getList(String key) {
        return getList(key, null);
    }
}
