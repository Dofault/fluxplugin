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

    public static void send(CommandSender to, String raw) {
        if (raw == null || raw.isEmpty()) return;
        to.sendMessage(parse(raw, to instanceof Player p ? p : null));
    }

    public static void actionBar(Player player, String raw) {
        if (raw == null || raw.isEmpty()) return;
        player.sendActionBar(parse(raw, player));
    }

    /** Replaces {@code key, value} pairs in a raw text ({@code "%amount%", "10"}). */
    public static String replace(String raw, String... pairs) {
        if (raw == null) return "";
        String out = raw;
        for (int i = 0; i + 1 < pairs.length; i += 2) out = out.replace(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
        return out;
    }
}
