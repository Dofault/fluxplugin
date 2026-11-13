package fr.influencraft.flux.hud;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * How far the client moves after each character of its default font, from {@code font_widths.json} (generated from
 * the client jar by {@code Flux/assets/vanilla/gen_font_widths.py}). Characters it lacks (unifont) count 6 px.
 */
final class TextWidths {

    private static final int FALLBACK = 6;
    private final Map<Integer, Integer> widths = new HashMap<>();
    /** The HUD font's QuinqueFive sheet (hud_font_widths.json, Flux/assets/hud/gen_hud.py). */
    private final Map<Integer, Integer> small = new HashMap<>();

    TextWidths(Plugin flux) {
        read(flux, "font_widths.json", widths);
        read(flux, "hud_font_widths.json", small);
    }

    private static void read(Plugin flux, String name, Map<Integer, Integer> widths) {
        try (InputStream in = flux.getResource(name)) {
            if (in == null) {
                flux.getLogger().warning(name + " missing: HUD texts measured roughly");
                return;
            }
            Map<String, Integer> read = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Integer>>() {
                    }.getType());
            if (read != null) read.forEach((k, v) -> widths.put(Integer.parseInt(k), v));
        } catch (Exception e) {
            flux.getLogger().warning(name + " not readable: " + e.getMessage());
        }
    }

    /** The HUD font has this character (QuinqueFive sheet, icons, spaces). */
    boolean small(int codePoint) {
        return small.containsKey(codePoint);
    }

    int width(String text, boolean inSmall) {
        Map<Integer, Integer> widths = inSmall ? small : this.widths;
        int w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            w += widths.getOrDefault(cp, FALLBACK);
            i += Character.charCount(cp);
        }
        return w;
    }
}
