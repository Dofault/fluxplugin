package fr.influencraft.flux.api.text;

import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Player-facing texts of a plugin, one file per language: {@code plugins/<Plugin>/lang/<language>.yml}.
 *
 * <ul>
 *   <li>The file is written from the jar on first start ({@code lang/<language>.yml}, else {@code lang/en_US.yml}).</li>
 *   <li>Keys added by a plugin update are appended to the file (comments kept) so that a reload never shows a
 *   missing text; a key still missing falls back to the jar's language file, then to English.</li>
 *   <li>A {@code prefix} key, when present, is put before every {@link #send} message; a message starting with
 *   {@code !} is sent without it. An empty message is not sent.</li>
 *   <li>Variables are {@code key, value} pairs: {@code send(player, "bought", "%amount%", "10")}.</li>
 *   <li>In chat messages, {@code [/command]} shortcuts and URLs are clickable.</li>
 * </ul>
 * The language comes from the plugin's {@code language} config key, else from Flux's (suite-wide default).
 */
public final class Messages {

    private static final String FALLBACK = "en_US";

    private final JavaPlugin plugin;
    private final String language;
    private YamlConfiguration messages = new YamlConfiguration();

    private Messages(JavaPlugin plugin, String language) {
        this.plugin = plugin;
        this.language = language;
        reload();
    }

    /** Messages in {@code language} ({@code fr_FR}, {@code en_US}...). */
    public static Messages load(JavaPlugin plugin, String language) {
        return new Messages(plugin, language == null || language.isBlank() ? FALLBACK : language.trim());
    }

    public String language() {
        return language;
    }

    public void reload() {
        String path = "lang/" + language + ".yml";
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            if (plugin.getResource(path) != null) {
                plugin.saveResource(path, false);
            } else if (plugin.getResource("lang/" + FALLBACK + ".yml") != null) {
                plugin.getLogger().warning(path + " is not shipped, starting from lang/" + FALLBACK + ".yml");
                copy("lang/" + FALLBACK + ".yml", file);
            }
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = bundled(path);
        if (defaults == null) defaults = bundled("lang/" + FALLBACK + ".yml");
        if (defaults != null) {
            boolean added = false;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key) || loaded.isSet(key)) continue;
                loaded.set(key, defaults.get(key));
                added = true;
            }
            if (added && file.exists()) {
                try {
                    loaded.save(file);
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not add the new keys to " + path + ": " + e.getMessage());
                }
            }
            loaded.setDefaults(defaults);
        }
        messages = loaded;
    }

    /** Raw text of a key ({@code ""} when unknown), variables replaced, no formatting. */
    public String raw(String key, String... vars) {
        return Texts.replace(messages.getString(key, ""), vars);
    }

    public List<String> rawList(String key, String... vars) {
        List<String> lines = messages.isList(key) ? messages.getStringList(key) : List.of(messages.getString(key, ""));
        return lines.stream().map(l -> Texts.replace(l, vars)).toList();
    }

    public boolean has(String key) {
        return messages.isSet(key);
    }

    public Component get(String key, OfflinePlayer context, String... vars) {
        return Texts.parse(raw(key, vars), context);
    }

    public List<Component> lines(String key, OfflinePlayer context, String... vars) {
        return Texts.lines(rawList(key, vars), context);
    }

    /** Chat message with the prefix (see class doc). A list key sends one line per entry. */
    public void send(CommandSender to, String key, String... vars) {
        OfflinePlayer context = to instanceof Player p ? p : null;
        String prefix = messages.getString("prefix", "");
        for (String line : rawList(key, vars)) {
            if (line.isEmpty()) continue;
            String text = line.startsWith("!") ? line.substring(1) : prefix + line;
            to.sendMessage(Texts.parseClickable(text, context));
        }
    }

    public void actionBar(Player player, String key, String... vars) {
        String text = raw(key, vars);
        if (!text.isEmpty()) player.sendActionBar(Texts.parse(text, player));
    }

    private YamlConfiguration bundled(String path) {
        try (InputStream in = plugin.getResource(path)) {
            return in == null ? null : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    private void copy(String resource, File to) {
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) return;
            to.getParentFile().mkdirs();
            java.nio.file.Files.copy(in, to.toPath());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write " + to + ": " + e.getMessage());
        }
    }
}
