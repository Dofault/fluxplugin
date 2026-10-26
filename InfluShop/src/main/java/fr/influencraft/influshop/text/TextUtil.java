package fr.influencraft.influshop.text;

import fr.influencraft.influshop.integration.PlaceholderHook;
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
 * Turns a raw config/messages string into an Adventure {@link Component}. Same pipeline as
 * InfluOffrande's TextUtil, kept in sync deliberately: PlaceholderAPI expansion (so
 * %img_influence%/%img_left_click%/%img_right_click% resolve first) -&gt; legacy '&amp;' codes
 * (including &amp;#RRGGBB hex) rewritten as MiniMessage tags -&gt; parsed as MiniMessage.
 */
public final class TextUtil {

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
                legacyMatcher.appendReplacement(result, "");
                continue;
            }
            String tag = LEGACY_TAGS.get(code);
            legacyMatcher.appendReplacement(result, tag != null ? "<" + tag + ">" : "");
        }
        legacyMatcher.appendTail(result);
        return result.toString();
    }

    public static Component parse(String raw, OfflinePlayer player) {
        if (raw == null) {
            return Component.empty();
        }
        String expanded = PlaceholderHook.apply(raw, player);
        String miniSource = translateToMiniMessage(expanded);
        try {
            return MiniMessage.miniMessage().deserialize(miniSource);
        } catch (Exception ex) {
            return Component.text(miniSource.replaceAll("<[^>]*>", ""));
        }
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

    /**
     * Deserializes a string that is ALREADY valid MiniMessage — e.g. one produced by
     * {@code MiniMessage.miniMessage().serialize(component)} when an entry's name/lore
     * snapshot was captured off a real item. Never route this through {@link #parse}: its
     * hex-code translation step rewrites any bare "#RRGGBB" it finds, including the hex
     * already sitting inside a valid "&lt;#RRGGBB&gt;"/"&lt;/#RRGGBB&gt;" tag — which mangles
     * the tag into "&lt;&lt;#RRGGBB&gt;&gt;" and shows the broken brackets literally in-game
     * instead of the color.
     */
    public static Component parseMiniMessageItemText(String raw, OfflinePlayer player) {
        if (raw == null) {
            return Component.empty();
        }
        try {
            return MiniMessage.miniMessage().deserialize(raw).decoration(TextDecoration.ITALIC, false);
        } catch (Exception ex) {
            return Component.text(raw.replaceAll("<[^>]*>", "")).decoration(TextDecoration.ITALIC, false);
        }
    }

    public static List<Component> parseMiniMessageItemTextList(List<String> raw, OfflinePlayer player) {
        List<Component> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String line : raw) {
            result.add(parseMiniMessageItemText(line, player));
        }
        return result;
    }
}
