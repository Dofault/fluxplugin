package fr.influencraft.fluxshop.managers;

import fr.influencraft.flux.api.FluxPlugin;
import net.md_5.bungee.api.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FluxShop's texts: the plugin's lang file ({@code lang/<language>.yml}, loaded by {@link FluxPlugin#messages()}), given
 * to the shop classes as section-coloured strings ({@code %key%} variables).
 */
public class LangManager {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([0-9a-fA-F]{6})");

    private final FluxPlugin plugin;

    public LangManager(FluxPlugin plugin) {
        this.plugin = plugin;
    }

    /** Texts are read again by {@code FluxPlugin} on reload: nothing to do here. */
    public void reload() {
        if (plugin.messages() != null) plugin.messages().reload();
    }

    /** {@code &#RRGGBB} and {@code &x} codes to section codes. */
    public static String colorize(String raw) {
        if (raw == null) return null;
        Matcher matcher = HEX_PATTERN.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(ChatColor.of("#" + matcher.group(1)).toString()));
        }
        matcher.appendTail(out);
        return ChatColor.translateAlternateColorCodes('&', out.toString());
    }

    private static String[] vars(Map<String, String> placeholders) {
        if (placeholders == null) return new String[0];
        List<String> out = new ArrayList<>();
        placeholders.forEach((k, v) -> {
            out.add("%" + k + "%");
            out.add(v);
        });
        return out.toArray(String[]::new);
    }

    public String get(String key, Map<String, String> placeholders) {
        String raw = plugin.messages().raw(key, vars(placeholders));
        return colorize(raw.isEmpty() ? key : raw);
    }

    public String get(String key) {
        return get(key, null);
    }

    public List<String> getList(String key, Map<String, String> placeholders) {
        if (!plugin.messages().has(key)) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String line : plugin.messages().rawList(key, vars(placeholders))) out.add(colorize(line));
        return out;
    }

    public List<String> getList(String key) {
        return getList(key, null);
    }
}
