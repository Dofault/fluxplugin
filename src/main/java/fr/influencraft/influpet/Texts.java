package fr.influencraft.influpet;

import dev.lone.itemsadder.api.FontImages.FontImageWrapper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mise en forme : codes & / &#RRGGBB et glyphes ItemsAdder %img_<id>%. */
public final class Texts {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .hexColors()
            .build();

    private static final Pattern GLYPH = Pattern.compile("%img_([a-z0-9_]+)%");
    private static final List<String> GLYPH_NAMESPACES = List.of("influpet", "influtags", "influemoji", "influitems", "influcards");
    private static final Map<String, String> GLYPH_CACHE = new HashMap<>();

    private Texts() {
    }

    public static void clearCache() {
        GLYPH_CACHE.clear();
    }

    /** Remplace les %img_<id>% par le caractère du glyphe ItemsAdder (laissés tels quels s'il est introuvable). */
    public static String glyphs(String input) {
        if (input == null || !input.contains("%img_")) {
            return input == null ? "" : input;
        }
        Matcher m = GLYPH.matcher(input);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String id = m.group(1);
            String glyph = GLYPH_CACHE.computeIfAbsent(id, Texts::lookupGlyph);
            m.appendReplacement(out, Matcher.quoteReplacement(glyph.isEmpty() ? m.group() : glyph));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String lookupGlyph(String id) {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            return "";
        }
        for (String ns : GLYPH_NAMESPACES) {
            try {
                FontImageWrapper wrapper = new FontImageWrapper(ns + ":" + id);
                if (wrapper.exists()) {
                    return wrapper.getString();
                }
            } catch (RuntimeException ignored) {
                // glyphe absent de ce namespace
            }
        }
        return "";
    }

    /** Texte -> composant, sans italique (noms et lores d'items, titres). */
    public static Component of(String legacy) {
        return LEGACY.deserialize(glyphs(legacy)).decoration(TextDecoration.ITALIC, false);
    }
}
