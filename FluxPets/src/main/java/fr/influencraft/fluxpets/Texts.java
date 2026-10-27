package fr.influencraft.fluxpets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mise en forme : codes & / &#RRGGBB et glyphes ItemsAdder %img_<id>%. */
public final class Texts {

    private static final Pattern GLYPH = Pattern.compile("%img_([a-z0-9_]+(?::[a-z0-9_]+)?)%");
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
        if (!fr.influencraft.flux.api.items.CustomItems.available()) {
            return "";
        }
        // %img_<namespace>:<id>%: glyph of any pack (Flux custom items service)
        for (String ns : id.contains(":") ? List.of("") : GLYPH_NAMESPACES) {
            String full = ns.isEmpty() ? id : ns + ":" + id;
            if (fr.influencraft.flux.api.items.CustomItems.glyphExists(full)) {
                return fr.influencraft.flux.api.items.CustomItems.glyphs(":" + full + ":");
            }
        }
        return "";
    }

    /** Texte -> composant, sans italique (noms et lores d'items, titres). */
    public static Component of(String legacy) {
        return fr.influencraft.flux.api.text.Texts.colors(glyphs(legacy)).decoration(TextDecoration.ITALIC, false);
    }
}
