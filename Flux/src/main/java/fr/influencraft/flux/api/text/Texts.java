package fr.influencraft.flux.api.text;

import fr.influencraft.flux.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The one text pipeline of the suite, the same as in Flux menus: player placeholders (the suite's own, then
 * PlaceholderAPI), ItemsAdder glyphs ({@code :name:}), then colours. Colours accept legacy codes ({@code &a},
 * {@code &#RRGGBB}) and MiniMessage tags ({@code <red>}, {@code <gradient:...>}), mixed freely. Italics are off,
 * so a result can go straight into an item name or lore.
 *
 * <pre>
 *   player.sendMessage(Texts.parse("&#2ECF7BDone, %player%!", player));
 *   meta.lore(Texts.lines(List.of("<gray>Price: &f%amount%"), player));
 * </pre>
 */
public final class Texts {

    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private Texts() {
    }

    /** Full pipeline. {@code player} gives the placeholder context and may be null. */
    public static Component parse(String raw, OfflinePlayer player) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        return Text.line(player instanceof Player p ? p : null, raw);
    }

    public static Component parse(String raw) {
        return parse(raw, null);
    }

    public static List<Component> lines(List<String> raw, OfflinePlayer player) {
        List<Component> out = new ArrayList<>(raw.size());
        for (String line : raw) out.add(parse(line, player));
        return out;
    }

    /**
     * Lore lines: placeholders resolved, long lines wrapped to the suite's width ({@code lore.max-line-length} in
     * Flux's config) keeping their colours and decoration prefix, then parsed. Write one long sentence, Flux cuts it.
     */
    public static List<Component> lore(List<String> raw, OfflinePlayer player) {
        List<Component> out = new ArrayList<>();
        for (String line : raw) {
            for (String piece : wrap(placeholders(line, player))) out.add(parse(piece, player));
        }
        return out;
    }

    /**
     * Wraps the lore of an item built by code (setLore / lore(...) with long lines) to the suite's width. Flux already
     * does it for list entries ({@code material: entry}); call it for items given or shown outside Flux menus.
     */
    public static org.bukkit.inventory.ItemStack wrapLore(org.bukkit.inventory.ItemStack item) {
        if (item == null) return null;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.lore() == null) return item;
        List<Component> lore = meta.lore();
        List<Component> wrapped = Text.wrapLore(lore);
        if (wrapped != lore) {
            meta.lore(wrapped);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Same for legacy lore strings ({@code setLore}): every line longer than the suite's width is split, colours kept.
     * Accepts {@code &} and {@code §} codes; the result uses the same character as the input line.
     */
    public static List<String> wrapLines(List<String> lore) {
        if (lore == null) return null;
        List<String> out = new ArrayList<>(lore.size());
        for (String line : lore) {
            if (line == null) {
                out.add(null);
                continue;
            }
            boolean section = line.indexOf('§') >= 0;
            for (String piece : Text.wrap(section ? line.replace('§', '&') : line)) out.add(section ? toSection(piece) : piece);
        }
        return out;
    }

    private static final java.util.regex.Pattern SHORT_HEX = java.util.regex.Pattern.compile("&#([0-9a-fA-F]{6})");

    /** Back to § codes, hex colours in Bukkit's expanded form ({@code §x§R§R§G§G§B§B}). */
    private static String toSection(String line) {
        java.util.regex.Matcher m = SHORT_HEX.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            StringBuilder hex = new StringBuilder("&x");
            for (char c : m.group(1).toCharArray()) hex.append('&').append(c);
            m.appendReplacement(sb, hex.toString());
        }
        m.appendTail(sb);
        return sb.toString().replace('&', '§');
    }

    /** Same for lore lines already built as components. */
    public static List<Component> wrapLore(List<Component> lore) {
        return Text.wrapLore(lore);
    }

    /** One lore line split to the suite's width (see {@link #lore}); placeholders must already be resolved. */
    public static List<String> wrap(String line) {
        return Text.wrap(line);
    }

    /** Same with an explicit width (visible characters, colour codes not counted). */
    public static List<String> wrap(String line, int width) {
        return Text.wrap(line, width);
    }

    /** Colours only: no placeholder, no glyph (text already resolved, or coming from players). */
    public static Component colors(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        return Text.colors(raw.replace('§', '&')).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Placeholders resolved, nothing else: commands, conditions, text handed to another plugin. */
    public static String placeholders(String raw, OfflinePlayer player) {
        return Text.placeholders(player instanceof Player p ? p : null, raw);
    }

    /** Text without any formatting, e.g. to compare or log a name. */
    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    public static String plain(String raw, OfflinePlayer player) {
        return plain(parse(raw, player));
    }

    /** Legacy "§" string for the rare APIs that still want one (inventory titles of other plugins, signs...). */
    public static String legacy(Component component) {
        return SECTION.serialize(component);
    }

    private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer AMPERSAND =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.builder().character('&').hexCharacter('#').hexColors().build();

    /** Text written back with {@code &} codes and {@code &#RRGGBB} colours (to store it in a config or a database). */
    public static String ampersand(Component component) {
        return AMPERSAND.serialize(component);
    }

    public static void send(CommandSender to, String raw) {
        if (raw == null || raw.isEmpty()) return;
        to.sendMessage(parse(raw, to instanceof Player p ? p : null));
    }

    public static void actionBar(Player player, String raw) {
        if (raw == null || raw.isEmpty()) return;
        player.sendActionBar(parse(raw, player));
    }

    private static final java.util.regex.Pattern COMMAND_SHORTCUT = java.util.regex.Pattern.compile("\\[/[^\\]]+\\]");
    // stops on the last useful character: final punctuation belongs to the sentence, not to the URL
    private static final java.util.regex.Pattern URL = java.util.regex.Pattern.compile("https?://[^\\s]*[^\\s.,;:!?)\\]]");

    /**
     * Makes {@code [/command]} shortcuts run their command on click and plain URLs open on click, keeping the
     * text style (Minecraft makes nothing clickable by itself).
     */
    public static Component clickable(Component line) {
        Component out = line.replaceText(net.kyori.adventure.text.TextReplacementConfig.builder().match(COMMAND_SHORTCUT)
                .replacement((match, builder) -> {
                    String command = match.group().substring(1, match.group().length() - 1);
                    return builder.clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(command))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                    Component.text(command, net.kyori.adventure.text.format.NamedTextColor.GRAY)));
                }).build());
        return out.replaceText(net.kyori.adventure.text.TextReplacementConfig.builder().match(URL)
                .replacement((match, builder) -> builder.clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(match.group()))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                Component.text(match.group(), net.kyori.adventure.text.format.NamedTextColor.GRAY)))).build());
    }

    /** {@link #parse} then {@link #clickable}. */
    public static Component parseClickable(String raw, OfflinePlayer player) {
        return clickable(parse(raw, player));
    }

    /** Replaces {@code key, value} pairs in a raw text ({@code "%amount%", "10"}). */
    public static String replace(String raw, String... pairs) {
        if (raw == null) return "";
        String out = raw;
        for (int i = 0; i + 1 < pairs.length; i += 2) out = out.replace(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
        return out;
    }
}
