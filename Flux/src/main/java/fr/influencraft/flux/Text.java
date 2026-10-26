package fr.influencraft.flux;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Textes des menus : placeholders PlaceholderAPI, glyphes ItemsAdder (:nom:), couleurs
 * legacy « & » et hexadécimales « &#RRGGBB » (même syntaxe que nos menus DeluxeMenus).
 */
public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .hexColors()
            .build();

    private static final java.util.regex.Pattern LEFTOVER_GLYPH = java.util.regex.Pattern.compile(":[a-z0-9_-]+:");
    private static final java.util.Set<String> warnedGlyphs = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static java.util.logging.Logger logger;

    private static Boolean papi;
    private static Boolean itemsAdder;

    private Text() {
    }

    static void init(java.util.logging.Logger log) {
        logger = log;
    }

    static void resetHooks() {
        warnedGlyphs.clear();
        papi = null;
        itemsAdder = null;
    }

    private static boolean hasPapi() {
        if (papi == null) papi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        return papi;
    }

    static boolean hasItemsAdder() {
        if (itemsAdder == null) itemsAdder = Bukkit.getPluginManager().isPluginEnabled("ItemsAdder");
        return itemsAdder;
    }

    /** Placeholders seuls (texte brut, sans couleurs) : commandes, conditions. */
    /**
     * Cache des placeholders le temps d'un affichage de menu (fil principal) : un même texte
     * n'est résolu qu'une fois (ex : %ecobattlepass_tier_pass% répété dans des dizaines de
     * conditions). Ré-entrant : begin/end imbriqués, le cache vit jusqu'au dernier end.
     */
    private static java.util.Map<String, String> memo;
    private static Player memoPlayer;
    private static int memoDepth;

    public static void beginMemo(Player player) {
        if (memoDepth++ == 0) {
            memo = new java.util.HashMap<>();
            memoPlayer = player;
        }
    }

    public static void endMemo() {
        if (memoDepth > 0 && --memoDepth == 0) {
            memo = null;
            memoPlayer = null;
        }
    }

    public static String placeholders(Player player, String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (memo != null && player == memoPlayer && Bukkit.isPrimaryThread()) {
            String cached = memo.get(raw);
            if (cached == null) {
                cached = resolve(player, raw);
                memo.put(raw, cached);
            }
            return cached;
        }
        return resolve(player, raw);
    }

    private static final java.util.regex.Pattern OWN_PLACEHOLDER = java.util.regex.Pattern.compile("%([a-z0-9]+)_([^%\\s]+)%");

    private static String resolve(Player player, String raw) {
        String out = raw;
        if (player != null) out = out.replace("%player%", player.getName());
        if (out.indexOf('%') >= 0) out = suitePlaceholders(player, out);
        if (hasPapi() && player != null && out.indexOf('%') >= 0) {
            out = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, out);
        }
        return out;
    }

    /**
     * Placeholders of the plugins of the suite (FluxApi.registerPlaceholders), resolved without PlaceholderAPI.
     * Unknown prefixes are left as they are, for PlaceholderAPI.
     */
    private static String suitePlaceholders(Player player, String text) {
        java.util.regex.Matcher m = OWN_PLACEHOLDER.matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            fr.influencraft.flux.api.PlaceholderProvider provider = fr.influencraft.flux.api.FluxApi.placeholders(m.group(1));
            if (provider == null) continue;
            String value;
            try {
                value = provider.request(player, m.group(2));
            } catch (RuntimeException e) {
                if (logger != null && warnedGlyphs.add(m.group())) logger.warning("Placeholder " + m.group() + " en erreur : " + e);
                value = null;
            }
            if (value == null) continue;
            if (out == null) out = new StringBuilder(text.length());
            out.append(text, last, m.start()).append(value);
            last = m.end();
        }
        if (out == null) return text;
        return out.append(text, last, text.length()).toString();
    }

    /** Ligne affichée : placeholders, glyphes ItemsAdder puis couleurs, sans italique. */
    public static Component line(Player player, String raw) {
        String out = placeholders(player, raw);
        if (hasItemsAdder() && out.indexOf(':') >= 0) {
            out = dev.lone.itemsadder.api.FontImages.FontImageWrapper.replaceFontImages(out);
            java.util.regex.Matcher m = LEFTOVER_GLYPH.matcher(out);
            while (m.find()) {
                if (logger != null && warnedGlyphs.add(m.group())) {
                    logger.warning("Glyphe ItemsAdder non converti " + m.group()
                            + " (absent du registre : /iareload après l'avoir ajouté ?)");
                }
            }
        }
        // Les placeholders peuvent renvoyer des codes « § » : on les ramène à « & ».
        out = out.replace('§', '&');
        return colors(out).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** A MiniMessage tag: {@code <red>}, {@code <#FF0000>}, {@code </bold>}, {@code <gradient:...>}... */
    private static final java.util.regex.Pattern MINI_TAG = java.util.regex.Pattern.compile("<[/!#]?[a-zA-Z0-9_#:.-]+(:[^>]*)?>");
    private static final java.util.regex.Pattern LEGACY_HEX = java.util.regex.Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final java.util.regex.Pattern LEGACY_CODE = java.util.regex.Pattern.compile("&([0-9a-fk-orA-FK-OR])");
    private static final java.util.Map<Character, String> LEGACY_TAGS = java.util.Map.ofEntries(
            java.util.Map.entry('0', "black"), java.util.Map.entry('1', "dark_blue"), java.util.Map.entry('2', "dark_green"),
            java.util.Map.entry('3', "dark_aqua"), java.util.Map.entry('4', "dark_red"), java.util.Map.entry('5', "dark_purple"),
            java.util.Map.entry('6', "gold"), java.util.Map.entry('7', "gray"), java.util.Map.entry('8', "dark_gray"),
            java.util.Map.entry('9', "blue"), java.util.Map.entry('a', "green"), java.util.Map.entry('b', "aqua"),
            java.util.Map.entry('c', "red"), java.util.Map.entry('d', "light_purple"), java.util.Map.entry('e', "yellow"),
            java.util.Map.entry('f', "white"), java.util.Map.entry('k', "obfuscated"), java.util.Map.entry('l', "bold"),
            java.util.Map.entry('m', "strikethrough"), java.util.Map.entry('n', "underlined"), java.util.Map.entry('o', "italic"),
            java.util.Map.entry('r', "reset"));

    /**
     * Colours of a line: legacy codes ({@code &a}, {@code &#RRGGBB}) by default; when the line also holds
     * MiniMessage tags, the legacy codes are turned into tags and the whole line is read as MiniMessage, so both
     * syntaxes can be mixed. A line that fails to parse as MiniMessage is shown without its tags.
     */
    public static Component colors(String text) {
        if (text.indexOf('<') < 0 || !MINI_TAG.matcher(text).find()) return LEGACY.deserialize(text);
        String mini = LEGACY_HEX.matcher(text).replaceAll("<#$1>");
        java.util.regex.Matcher m = LEGACY_CODE.matcher(mini);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String tag = LEGACY_TAGS.get(Character.toLowerCase(m.group(1).charAt(0)));
            m.appendReplacement(sb, tag == null ? "" : "<" + tag + ">");
        }
        m.appendTail(sb);
        try {
            return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(sb.toString());
        } catch (RuntimeException e) {
            return Component.text(sb.toString().replaceAll("<[^>]*>", ""));
        }
    }

    /** Placeholders and ItemsAdder glyphs only, no colour parsing (text sent to another plugin, commands). */
    public static String glyphs(String text) {
        if (!hasItemsAdder() || text.indexOf(':') < 0) return text;
        return dev.lone.itemsadder.api.FontImages.FontImageWrapper.replaceFontImages(text);
    }
}
