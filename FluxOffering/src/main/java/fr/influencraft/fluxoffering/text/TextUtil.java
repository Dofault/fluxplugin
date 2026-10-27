package fr.influencraft.fluxoffering.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a raw config/messages string into an Adventure {@link Component}.
 * <p>
 * Pipeline: PlaceholderAPI expansion (so %img_x% influemoji/influtags glyphs and any
 * other PAPI placeholder resolve first) -&gt; legacy '&' codes (including &amp;#RRGGBB hex)
 * are rewritten as MiniMessage tags -&gt; the whole string is parsed as MiniMessage, so
 * native &lt;tag&gt; formatting (gradients, hover, etc.) written directly in the yaml keeps working
 * side by side with legacy codes.
 */
public final class TextUtil {

    // Accepts both "&#RRGGBB" and bare "#RRGGBB" (the server-wide convention per CLAUDE.md,
    // used everywhere outside DeluxeMenus — e.g. the chat prefix "&r#2ECF7BOffrande" has no
    // '&' before the '#').
    private static final Pattern HEX_PATTERN = Pattern.compile("&?#([A-Fa-f0-9]{6})");
    private static final Pattern LEGACY_PATTERN = Pattern.compile("&([0-9a-fk-orA-FK-ORx])");

    private static final Map<Character, String> LEGACY_TAGS = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"),
            Map.entry('f', "white"), Map.entry('k', "obfuscated"), Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"), Map.entry('n', "underlined"), Map.entry('o', "italic"),
            Map.entry('r', "reset")
    );

    private TextUtil() {
    }

    private static String translateToMiniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        Matcher hexMatcher = HEX_PATTERN.matcher(input);
        StringBuilder afterHex = new StringBuilder();
        while (hexMatcher.find()) {
            hexMatcher.appendReplacement(afterHex, "<#" + hexMatcher.group(1) + ">");
        }
        hexMatcher.appendTail(afterHex);

        Matcher legacyMatcher = LEGACY_PATTERN.matcher(afterHex.toString());
        StringBuilder result = new StringBuilder();
        while (legacyMatcher.find()) {
            char code = Character.toLowerCase(legacyMatcher.group(1).charAt(0));
            if (code == 'x') {
                // &x&R&R&G&G&B&B (legacy-hex) is not expected in our own configs (we use &#RRGGBB
                // or bare #RRGGBB) but strip it harmlessly instead of leaking a raw '&x' into the text.
                legacyMatcher.appendReplacement(result, "");
                continue;
            }
            String tag = LEGACY_TAGS.get(code);
            legacyMatcher.appendReplacement(result, tag != null ? "<" + tag + ">" : "");
        }
        legacyMatcher.appendTail(result);
        return result.toString();
    }

    /**
     * @param player used both for PlaceholderAPI expansion context and may be null for
     *               console/global text.
     */
    public static Component parse(String raw, OfflinePlayer player) {
        // the Flux pipeline: suite placeholders, PlaceholderAPI, glyphs, legacy codes and MiniMessage mixed
        return raw == null ? Component.empty() : fr.influencraft.flux.api.text.Texts.parse(raw, player);
    }

    /**
     * Parses then flattens to plain text (colors/formatting/tags stripped). Used to sanitize
     * text sourced from an external system (e.g. an ItemsAdder item's own display name) before
     * splicing it as a {placeholder} into one of our own colored templates — otherwise stray
     * markup in that external text could break, or double-color, the surrounding template.
     */
    public static String plain(String raw, OfflinePlayer player) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(parse(raw, player));
    }

    public static List<Component> parseList(List<String> raw, OfflinePlayer player) {
        List<Component> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String line : raw) {
            result.add(parse(line, player));
        }
        return result;
    }

    /**
     * Same as {@link #parse}, but forces italics off. Minecraft renders any custom item
     * display name/lore in italics by default regardless of the component's own styling —
     * use this (never {@link #parse}) for anything going into an ItemMeta name or lore.
     */
    public static Component parseItemText(String raw, OfflinePlayer player) {
        return parse(raw, player).decoration(TextDecoration.ITALIC, false);
    }

    public static List<Component> parseItemTextList(List<String> raw, OfflinePlayer player) {
        List<Component> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String line : raw) {
            result.add(parseItemText(line, player));
        }
        return result;
    }

    public static void send(CommandSender receiver, String raw, OfflinePlayer papiContext) {
        receiver.sendMessage(parse(raw, papiContext));
    }
}
