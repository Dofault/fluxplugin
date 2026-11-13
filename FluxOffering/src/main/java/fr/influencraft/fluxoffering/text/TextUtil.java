package fr.influencraft.fluxoffering.text;

import fr.influencraft.flux.api.text.Texts;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/** FluxOffering's text calls, all through Flux's pipeline ({@link Texts}: placeholders, glyphs, & and MiniMessage). */
public final class TextUtil {

    private TextUtil() {
    }

    public static Component parse(String raw, OfflinePlayer player) {
        return Texts.parse(raw == null ? "" : raw, player);
    }

    public static String plain(String raw, OfflinePlayer player) {
        return Texts.plain(parse(raw, player));
    }

    public static List<Component> parseList(List<String> raw, OfflinePlayer player) {
        List<Component> out = new ArrayList<>();
        for (String line : raw) out.add(parse(line, player));
        return out;
    }

    /** Item name or lore line: italics off. */
    public static Component parseItemText(String raw, OfflinePlayer player) {
        return parse(raw, player).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> parseItemTextList(List<String> raw, OfflinePlayer player) {
        List<Component> out = new ArrayList<>();
        for (String line : raw) out.add(parseItemText(line, player));
        return out;
    }

    public static void send(CommandSender receiver, String raw, OfflinePlayer papiContext) {
        receiver.sendMessage(parse(raw, papiContext));
    }
}
