package fr.influencraft.flux.hud;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What hud.yml describes, and the shader table made from it.
 *
 * <p>How a part reaches its place: every part is written in a boss bar title starting at the centre of the screen
 * (each part is followed by a negative space of its own width, so the title is 0 px wide and the next part starts
 * at the centre again), coloured with a <b>marker</b>: red 249, green = the part's slot, blue = colour index * 2
 * (+1 for its shadow, through the shadow colour). The text shader finds the marker, moves the vertex from the top
 * centre of the boss bar line to the slot's place (an anchor in fractions of the screen plus pixels), scales it, and
 * gives it its real colour. Images are sprites of the {@code gui} atlas and the avatar is the game's own player head
 * object: both are 8 x 8 in text and scaled by the shader, so the pack holds no font picture for them.</p>
 */
final class HudLayout {

    static final int MARKER = 249;

    enum Layer {BACK, FRONT}

    /** A run of text of one colour; {@code text} may hold pieces. */
    record Run(String text, int colour) {
    }

    sealed interface Part permits Image, Head, Text {
        int slot();

        Layer layer();
    }

    record Image(int slot, Layer layer, String sprite, int colour) implements Part {
    }

    record Head(int slot, Layer layer, int colour) implements Part {
    }

    record Text(int slot, Layer layer, List<Run> runs) implements Part {
    }

    record Element(String id, String setting, String requires, List<Part> parts) {
    }

    /** One place of the screen: anchor (0..1 of the screen), offset in px, scale. */
    private record Slot(double anchorX, double anchorY, int x, int y, double scaleX, double scaleY) {
    }

    /** Main and shadow colours, ARGB. */
    private record Colour(int main, int shadow) {
    }

    private static final Pattern COLOUR_TAG = Pattern.compile("<#([0-9a-fA-F]{6})>|&#([0-9a-fA-F]{6})");

    final List<Element> elements = new ArrayList<>();
    private final List<Slot> slots = new ArrayList<>();
    private final List<Colour> colours = new ArrayList<>();
    private final Map<Colour, Integer> colourIndex = new LinkedHashMap<>();

    static HudLayout read(ConfigurationSection section, Logger log) {
        HudLayout layout = new HudLayout();
        if (section == null) return layout;
        for (String id : section.getKeys(false)) {
            ConfigurationSection e = section.getConfigurationSection(id);
            if (e == null) continue;
            double[] anchor = anchor(e.getString("anchor", "top-left"));
            int[] at = pair(e, "at", 0, 0);
            List<Part> parts = new ArrayList<>();
            for (Map<?, ?> raw : e.getMapList("parts")) {
                try {
                    Part p = layout.part(raw, anchor, at);
                    if (p != null) parts.add(p);
                } catch (RuntimeException ex) {
                    log.warning("hud.yml " + id + ": part " + raw + " ignored (" + ex.getMessage() + ")");
                }
            }
            if (parts.isEmpty()) {
                log.warning("hud.yml " + id + ": no part, element ignored");
                continue;
            }
            String setting = e.getString("setting", "");
            String requires = e.getString("requires", "");
            layout.elements.add(new Element(id, setting.isBlank() ? null : setting, requires.isBlank() ? null : requires.toLowerCase(Locale.ROOT), parts));
        }
        return layout;
    }

    private Part part(Map<?, ?> raw, double[] anchor, int[] base) {
        int[] at = pair(raw.get("at"), 0, 0);
        Layer layer = "back".equalsIgnoreCase(String.valueOf(raw.get("layer"))) ? Layer.BACK : Layer.FRONT;
        if (raw.containsKey("image")) {
            int[] size = pair(raw.get("size"), 8, 8);
            int slot = slot(anchor, base[0] + at[0], base[1] + at[1], size[0] / 8.0, size[1] / 8.0);
            return new Image(slot, layer, String.valueOf(raw.get("image")), colour(0xFFFFFFFF, 0));
        }
        if (raw.containsKey("head")) {
            int[] size = pair(raw.get("size"), 8, 8);
            int slot = slot(anchor, base[0] + at[0], base[1] + at[1], size[0] / 8.0, size[1] / 8.0);
            return new Head(slot, layer, colour(0xFFFFFFFF, 0));
        }
        if (raw.containsKey("text")) {
            double scale = raw.get("scale") instanceof Number n ? n.doubleValue() : 1.0;
            int slot = slot(anchor, base[0] + at[0], base[1] + at[1], scale, scale);
            Object s = raw.get("shadow");
            int shadow = s == null ? 0xFF000000 : "none".equalsIgnoreCase(String.valueOf(s)) ? 0 : argb(String.valueOf(s));
            return new Text(slot, layer, runs(String.valueOf(raw.get("text")), shadow));
        }
        throw new IllegalArgumentException("image, head or text expected");
    }

    private List<Run> runs(String template, int shadow) {
        List<Run> runs = new ArrayList<>();
        Matcher m = COLOUR_TAG.matcher(template);
        int colour = 0xFFFFFFFF;
        int from = 0;
        while (m.find()) {
            if (m.start() > from) runs.add(new Run(template.substring(from, m.start()), colour(colour, shadow)));
            colour = 0xFF000000 | Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2), 16);
            from = m.end();
        }
        if (from < template.length()) runs.add(new Run(template.substring(from), colour(colour, shadow)));
        return runs;
    }

    private int slot(double[] anchor, int x, int y, double sx, double sy) {
        if (slots.size() >= 254) throw new IllegalStateException("more than 254 parts");
        slots.add(new Slot(anchor[0], anchor[1], x, y, sx, sy));
        return slots.size();
    }

    private int colour(int main, int shadow) {
        Colour c = new Colour(main, shadow);
        Integer i = colourIndex.get(c);
        if (i != null) return i;
        if (colours.size() >= 127) throw new IllegalStateException("more than 127 colours");
        colours.add(c);
        colourIndex.put(c, colours.size() - 1);
        return colours.size() - 1;
    }

    /** Blue channel of a run's marker. */
    static int blue(int colour, boolean shadow) {
        return colour * 2 + (shadow ? 1 : 0);
    }

    boolean empty() {
        return slots.isEmpty();
    }

    /** Constants and function for the text shader, or null when there is nothing to draw. */
    String glsl() {
        if (slots.isEmpty()) return null;
        StringBuilder place = new StringBuilder("vec4(0.0)");
        StringBuilder scale = new StringBuilder("vec2(1.0)");
        for (Slot s : slots) {
            place.append(", vec4(").append(f(s.anchorX())).append(", ").append(f(s.anchorY())).append(", ")
                    .append(f(s.x())).append(", ").append(f(s.y())).append(')');
            scale.append(", vec2(").append(f(s.scaleX())).append(", ").append(f(s.scaleY())).append(')');
        }
        StringBuilder colour = new StringBuilder();
        for (Colour c : colours) {
            if (!colour.isEmpty()) colour.append(", ");
            colour.append(vec4(c.main())).append(", ").append(vec4(c.shadow()));
        }
        int n = slots.size();
        return """
                // The HUD of plugins/Flux/hud.yml (Flux, hud.HudLayout): boss bar text marked by its colour (red 249,
                // green = slot, blue = colour * 2 + shadow) goes from the top centre of its boss bar line to its slot's
                // place on the screen (anchor in fractions of the screen, offset in pixels), scaled, in its real colour.
                const int FLUX_HUD_SLOTS = %d;
                const vec4 FLUX_HUD_PLACE[%d] = vec4[](%s);
                const vec2 FLUX_HUD_SCALE[%d] = vec2[](%s);
                const int FLUX_HUD_COLOURS = %d;
                const vec4 FLUX_HUD_COLOUR[%d] = vec4[](%s);

                void flux_hud_vertex() {
                    ivec3 mark = ivec3(round(Color.rgb * 255.0));
                    if (mark.r != 249 || mark.g < 1 || mark.g > FLUX_HUD_SLOTS || mark.b >= FLUX_HUD_COLOURS) return;
                    vec2 gui = 2.0 / vec2(ProjMat[0][0], -ProjMat[1][1]);
                    vec4 p = ModelViewMat * vec4(Position, 1.0);
                    // Boss bar titles: x = width / 2 - textWidth / 2 (0 wide here), y = 3 + 19 per bar above.
                    float centre = floor(ceil(gui.x - 0.01) / 2.0);
                    float top = 3.0 + 19.0 * floor((p.y + 2.0) / 19.0);
                    vec4 place = FLUX_HUD_PLACE[mark.g];
                    p.xy = place.xy * gui + place.zw + vec2(p.x - centre, p.y - top) * FLUX_HUD_SCALE[mark.g];
                    gl_Position = ProjMat * p;
                    vertexColor = FLUX_HUD_COLOUR[mark.b];
                }
                """.formatted(n, n + 1, place, n + 1, scale, colours.size() * 2, colours.size() * 2, colour);
    }

    private static String vec4(int argb) {
        return "vec4(" + f(((argb >> 16) & 255) / 255.0) + ", " + f(((argb >> 8) & 255) / 255.0) + ", " + f((argb & 255) / 255.0)
                + ", " + f(((argb >>> 24) & 255) / 255.0) + ")";
    }

    private static String f(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", ".0") : s + ".0";
    }

    private static double[] anchor(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "top-right" -> new double[]{1, 0};
            case "top", "top-centre", "top-center" -> new double[]{0.5, 0};
            case "bottom-left" -> new double[]{0, 1};
            case "bottom-right" -> new double[]{1, 1};
            case "bottom", "bottom-centre", "bottom-center" -> new double[]{0.5, 1};
            case "left" -> new double[]{0, 0.5};
            case "right" -> new double[]{1, 0.5};
            case "centre", "center" -> new double[]{0.5, 0.5};
            default -> new double[]{0, 0};
        };
    }

    private static int[] pair(ConfigurationSection s, String key, int a, int b) {
        return pair(s.getList(key), a, b);
    }

    private static int[] pair(Object o, int a, int b) {
        if (o instanceof List<?> l && l.size() >= 2 && l.get(0) instanceof Number x && l.get(1) instanceof Number y) {
            return new int[]{x.intValue(), y.intValue()};
        }
        return new int[]{a, b};
    }

    private static int argb(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        long v = Long.parseLong(h, 16);
        return h.length() > 6 ? (int) v : 0xFF000000 | (int) v;
    }
}
