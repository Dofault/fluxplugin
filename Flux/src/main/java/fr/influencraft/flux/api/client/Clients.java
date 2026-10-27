package fr.influencraft.flux.api.client;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which game a player plays on: Java, or Bedrock through a proxy (Geyser + Floodgate). Bedrock clients get no resource
 * pack of the server: custom items, font glyphs and the tricks of the menus (hidden inventory, title images) do not
 * show there. Flux reads this when it renders and uses the Bedrock equivalents of the {@code bedrock} section of its
 * config (vanilla item for a custom item, text for a glyph); plugins do the same through {@link #bedrock(Player)}.
 */
public final class Clients {

    /** What an adapter provides (Floodgate today). */
    public interface Provider {
        String name();

        boolean bedrock(UUID player);
    }

    private static final Pattern SHORTCODE = Pattern.compile(":([a-z0-9_]+:)?([a-z0-9_]+):");

    private static volatile Provider provider;
    private static volatile Map<String, String> glyphs = Map.of();
    private static volatile String defaultGlyph = "";
    private static volatile Map<String, Material> items = Map.of();
    private static volatile Material defaultItem = Material.PAPER;

    private Clients() {
    }

    /** Called by Flux. */
    public static void use(Provider p, ConfigurationSection bedrock) {
        provider = p;
        Map<String, String> g = new ConcurrentHashMap<>();
        Map<String, Material> i = new ConcurrentHashMap<>();
        if (bedrock != null) {
            ConfigurationSection gs = bedrock.getConfigurationSection("glyphs");
            if (gs != null) for (String k : gs.getKeys(false)) g.put(k.toLowerCase(Locale.ROOT), gs.getString(k, ""));
            ConfigurationSection is = bedrock.getConfigurationSection("items");
            if (is != null) {
                for (String k : is.getKeys(true)) {
                    Material m = Material.matchMaterial(is.getString(k, ""));
                    if (m != null) i.put(k.toLowerCase(Locale.ROOT), m);
                }
            }
            defaultGlyph = bedrock.getString("default-glyph", "");
            Material d = Material.matchMaterial(bedrock.getString("default-item", "PAPER"));
            defaultItem = d == null ? Material.PAPER : d;
        }
        glyphs = g;
        items = i;
    }

    public static String providerName() {
        Provider p = provider;
        return p == null ? "none" : p.name();
    }

    public static boolean bedrock(UUID player) {
        Provider p = provider;
        return p != null && player != null && p.bedrock(player);
    }

    public static boolean bedrock(Player player) {
        return player != null && bedrock(player.getUniqueId());
    }

    /** Glyph shortcodes ({@code :ns:id:} or {@code :id:}) written for a Bedrock client. */
    public static String bedrockGlyphs(String text) {
        if (text == null || text.indexOf(':') < 0) return text;
        Matcher m = SHORTCODE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String id = m.group(2).toLowerCase(Locale.ROOT);
            if (id.startsWith("offset_")) {
                m.appendReplacement(out, "");
                continue;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(glyphs.getOrDefault(id, defaultGlyph)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Vanilla material shown to a Bedrock client instead of a custom item ({@code ns:id} or {@code id}). */
    public static Material bedrockItem(String customId) {
        String id = customId.toLowerCase(Locale.ROOT);
        Material m = items.get(id);
        if (m == null && id.contains(":")) m = items.get(id.substring(id.indexOf(':') + 1));
        return m != null ? m : defaultItem;
    }
}
