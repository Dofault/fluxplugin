package fr.influencraft.fluxoffering.config;

import fr.influencraft.fluxoffering.text.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Loads and exposes messages.yml. All player-facing text lives here so the plugin
 * can be re-worded without touching a single line of Java.
 */
/**
 * FluxOffering's texts: the plugin's lang file ({@code lang/<language>.yml}, loaded by {@code FluxPlugin}), with
 * {@code {key}} variables replaced here before the colours.
 */
public final class MessagesManager {

    private final fr.influencraft.flux.api.FluxPlugin plugin;

    public MessagesManager(fr.influencraft.flux.api.FluxPlugin plugin) {
        this.plugin = plugin;
    }

    /** Texts are (re)read by FluxPlugin. */
    public void load() {
        if (plugin.messages() != null) plugin.messages().reload();
    }

    public String raw(String path) {
        String value = plugin.messages() == null ? "" : plugin.messages().raw(path);
        if (value.isEmpty()) {
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
        if (plugin.messages() == null || !plugin.messages().has(path)) return Collections.emptyList();
        return plugin.messages().rawList(path);
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

    /**
     * Sends a multi-line message (a yaml list) as a SINGLE chat message whose lines are
     * separated by newlines: used for the framed blocks (wiki link...) built on the same
     * template as DiscordSRV's /discord, which is itself one message with embedded newlines.
     * Sending one message per line instead leaves the block at the mercy of anything that
     * post-processes chat messages one by one (spacing, prefixes).
     */
    public void sendList(CommandSender receiver, String path, Map<String, String> placeholders, OfflinePlayer papiContext) {
        List<String> lines = rawList(path, placeholders);
        if (lines.isEmpty()) {
            return;
        }
        Component message = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                message = message.append(Component.newline());
            }
            message = message.append(TextUtil.parse(lines.get(i), papiContext));
        }
        receiver.sendMessage(message);
    }
}
