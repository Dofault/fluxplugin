package fr.influencraft.flux.hud;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What hud.yml describes, and the shader table made from it.
 *
 * <p>How a part reaches its place: every part is written in a boss bar title starting at the centre of the screen
 * (each part is followed by a negative space of its own width, so the title is 0 px wide and the next part starts
 * at the centre again), coloured with a <b>marker</b>: red 249, green = low byte of the part's slot, blue = high bits
 * of the slot (top 3 bits), colour index * 2, + 1 for its shadow (through the shadow colour). The text shader finds
 * the marker, puts the quad at the slot's place (an anchor in fractions of the screen plus pixels), scaled, and gives
 * it its real colour. Images are sprites of the {@code gui} atlas and the avatar is the game's own player head object:
 * both are 8 x 8 in text and scaled by the shader, so the pack holds no font picture for them.</p>
 *
 * <p><b>Places.</b> Elements belong to <b>groups</b> (the profile card; the information boxes) whose corner each
 * player picks in the settings. The elements of a corner stack from its edge, groups in their order, without a hole
 * when one is hidden. The shader cannot be told a place at run time, so every place an element can take (each corner,
 * each set of elements above it) gets its slots at load time: a few hundred for the HUD of the server (2047 at most).</p>
 */
final class HudLayout {

    static final int MARKER = 249;
    static final int MAX_SLOTS = 2047;
    static final int MAX_COLOURS = 16;

    enum Layer {BACK, FRONT}

    /** A corner of the screen and which way its elements stack. */
    enum Corner {
        TOP_LEFT(0, 0), TOP_RIGHT(1, 0), BOTTOM_LEFT(0, 1), BOTTOM_RIGHT(1, 1);

        final double anchorX;
        final double anchorY;

        Corner(double anchorX, double anchorY) {
            this.anchorX = anchorX;
            this.anchorY = anchorY;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        static Corner of(String key, Corner fallback) {
            if (key == null) return fallback;
            for (Corner c : values()) if (c.key().equalsIgnoreCase(key.trim())) return c;
            return fallback;
        }
    }

    /** A run of text of one colour; {@code text} may hold pieces. */
    record Run(String text, int colour) {
    }

    /** Where an element's top-left corner is: an anchor of the screen (0..1) plus pixels. */
    record Place(double anchorX, double anchorY, double x, double y) {
    }

    sealed interface Part permits Image, Head, Text {
        /** Index of the part in the whole HUD (slot lookup). */
        int index();

        Layer layer();
    }

    record Image(int index, Layer layer, String sprite, int colour, int x, int y, int width, int height) implements Part {
    }

    record Head(int index, Layer layer, int colour, int x, int y, int width, int height) implements Part {
    }

    /** {@code small}: the HUD font's QuinqueFive capitals (5 px) instead of the game's font. */
    record Text(int index, Layer layer, List<Run> runs, boolean small, int x, int y, double scale) implements Part {
    }

    /** {@code fixed}: its place when it belongs to no group, else null. */
    record Element(String id, String setting, String requires, int width, int height, Place fixed, List<Part> parts) {
    }

    record Group(String id, String setting, Corner byDefault, List<Element> elements) {
    }

    record Placed(Element element, Place place) {
    }

    private record Slot(double anchorX, double anchorY, double x, double y, double scaleX, double scaleY, double height) {
    }

    private record SlotKey(int part, Place place) {
    }

    /** Main and shadow colours, ARGB. */
    private record Colour(int main, int shadow) {
    }

    private static final Pattern COLOUR_TAG = Pattern.compile("<#([0-9a-fA-F]{6})>|&#([0-9a-fA-F]{6})");

    final List<Element> elements = new ArrayList<>();
    final List<Group> groups = new ArrayList<>();
    private int marginX = 15;
    private int marginY = 10;
    private int gap = 3;
    private int partCount;
    private final List<Slot> slots = new ArrayList<>();
    private final Map<SlotKey, Integer> slotIndex = new HashMap<>();
    private final List<Colour> colours = new ArrayList<>();
    private final Map<Colour, Integer> colourIndex = new LinkedHashMap<>();

    static HudLayout read(ConfigurationSection root, Logger log) {
        HudLayout layout = new HudLayout();
        if (root == null) return layout;
        List<Integer> margin = root.getIntegerList("margin");
        if (margin.size() >= 2) {
            layout.marginX = margin.get(0);
            layout.marginY = margin.get(1);
        }
        layout.gap = root.getInt("gap", 3);
        ConfigurationSection section = root.getConfigurationSection("elements");
        Map<String, Element> byId = new LinkedHashMap<>();
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection e = section.getConfigurationSection(id);
                if (e == null) continue;
                Element el = layout.element(id, e, log);
                if (el != null) byId.put(id, el);
            }
        }
        layout.elements.addAll(byId.values());
        ConfigurationSection gs = root.getConfigurationSection("groups");
        if (gs != null) {
            for (String id : gs.getKeys(false)) {
                ConfigurationSection g = gs.getConfigurationSection(id);
                if (g == null) continue;
                List<Element> members = new ArrayList<>();
                for (String e : g.getStringList("elements")) {
                    Element el = byId.get(e);
                    if (el == null) log.warning("hud.yml group " + id + ": unknown element " + e);
                    else if (el.fixed() != null) log.warning("hud.yml group " + id + ": element " + e + " has its own anchor, left out");
                    else members.add(el);
                }
                String setting = g.getString("setting", "");
                layout.groups.add(new Group(id, setting.isBlank() ? null : setting,
                        Corner.of(g.getString("default"), Corner.TOP_LEFT), members));
            }
        }
        try {
            layout.registerSlots();
        } catch (IllegalStateException ex) {
            log.severe("hud.yml: " + ex.getMessage() + ", HUD off");
            layout.slots.clear();
            layout.slotIndex.clear();
        }
        return layout;
    }

    private Element element(String id, ConfigurationSection e, Logger log) {
        int[] size = pair(e.getList("size"), 0, 0);
        Place fixed = null;
        if (e.contains("anchor") || e.contains("at")) {
            double[] anchor = anchor(e.getString("anchor", "top-left"));
            int[] at = pair(e.getList("at"), 0, 0);
            fixed = new Place(anchor[0], anchor[1], at[0], at[1]);
        }
        List<Part> parts = new ArrayList<>();
        for (Map<?, ?> raw : e.getMapList("parts")) {
            try {
                parts.add(part(raw));
            } catch (RuntimeException ex) {
                log.warning("hud.yml " + id + ": part " + raw + " ignored (" + ex.getMessage() + ")");
            }
        }
        if (parts.isEmpty()) {
            log.warning("hud.yml " + id + ": no part, element ignored");
            return null;
        }
        String setting = e.getString("setting", "");
        String requires = e.getString("requires", "");
        return new Element(id, setting.isBlank() ? null : setting, requires.isBlank() ? null : requires.toLowerCase(Locale.ROOT),
                size[0], size[1], fixed, parts);
    }

    private Part part(Map<?, ?> raw) {
        int[] at = pair(raw.get("at"), 0, 0);
        Layer layer = "back".equalsIgnoreCase(String.valueOf(raw.get("layer"))) ? Layer.BACK : Layer.FRONT;
        if (raw.containsKey("image")) {
            int[] size = pair(raw.get("size"), 8, 8);
            return new Image(partCount++, layer, String.valueOf(raw.get("image")), colour(0xFFFFFFFF, 0), at[0], at[1], size[0], size[1]);
        }
        if (raw.containsKey("head")) {
            int[] size = pair(raw.get("size"), 8, 8);
            return new Head(partCount++, layer, colour(0xFFFFFFFF, 0), at[0], at[1], size[0], size[1]);
        }
        if (raw.containsKey("text")) {
            double scale = raw.get("scale") instanceof Number n ? n.doubleValue() : 1.0;
            boolean small = "small".equalsIgnoreCase(String.valueOf(raw.get("font")));
            Object s = raw.get("shadow");
            int shadow = s == null || "none".equalsIgnoreCase(String.valueOf(s)) ? 0 : argb(String.valueOf(s));
            return new Text(partCount++, layer, runs(String.valueOf(raw.get("text")), shadow), small, at[0], at[1], scale);
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

    private int colour(int main, int shadow) {
        Colour c = new Colour(main, shadow);
        Integer i = colourIndex.get(c);
        if (i != null) return i;
        if (colours.size() >= MAX_COLOURS) throw new IllegalStateException("more than " + MAX_COLOURS + " colours");
        colours.add(c);
        colourIndex.put(c, colours.size() - 1);
        return colours.size() - 1;
    }

    // ------------------------------------------------------------------------------------------------ places

    /** Grouped elements in stacking order (groups in their order, elements in theirs). */
    private List<Element> stackOrder() {
        List<Element> out = new ArrayList<>();
        for (Group g : groups) out.addAll(g.elements());
        return out;
    }

    /**
     * Where the shown elements go: fixed ones at their place, grouped ones stacked in their group's corner
     * ({@code corners}: corner of each group).
     */
    List<Placed> arrange(Map<Group, Corner> corners, Predicate<Element> shown) {
        List<Placed> out = new ArrayList<>();
        for (Element e : elements) if (e.fixed() != null && shown.test(e)) out.add(new Placed(e, e.fixed()));
        Map<Corner, List<Element>> stacks = new LinkedHashMap<>();
        for (Group g : groups) {
            Corner c = corners.getOrDefault(g, g.byDefault());
            for (Element e : g.elements()) if (shown.test(e)) stacks.computeIfAbsent(c, k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<Corner, List<Element>> s : stacks.entrySet()) out.addAll(stack(s.getKey(), s.getValue()));
        return out;
    }

    /** The elements of one corner, in order, from its edge (top corners downwards, bottom ones upwards). */
    private List<Placed> stack(Corner corner, List<Element> list) {
        List<Placed> out = new ArrayList<>();
        double y;
        if (corner.anchorY == 0) {
            y = marginY;
        } else {
            double total = -gap;
            for (Element e : list) total += e.height() + gap;
            y = -marginY - total;
        }
        for (Element e : list) {
            double x = corner.anchorX == 0 ? marginX : -marginX - e.width();
            out.add(new Placed(e, new Place(corner.anchorX, corner.anchorY, x, y)));
            y += e.height() + gap;
        }
        return out;
    }

    /** Every place an element can take, with the slots of its parts. */
    private void registerSlots() {
        for (Element e : elements) if (e.fixed() != null) register(e, e.fixed());
        List<Element> grouped = stackOrder();
        if (grouped.size() > 10) throw new IllegalStateException("more than 10 grouped elements");
        for (Corner c : Corner.values()) {
            for (int mask = 1; mask < (1 << grouped.size()); mask++) {
                List<Element> subset = new ArrayList<>();
                for (int i = 0; i < grouped.size(); i++) if ((mask & (1 << i)) != 0) subset.add(grouped.get(i));
                for (Placed p : stack(c, subset)) register(p.element(), p.place());
            }
        }
    }

    private void register(Element e, Place place) {
        for (Part part : e.parts()) {
            SlotKey key = new SlotKey(part.index(), place);
            if (slotIndex.containsKey(key)) continue;
            if (slots.size() >= MAX_SLOTS) throw new IllegalStateException("more than " + MAX_SLOTS + " places");
            // Heights are not taken from where the game drew the quad (a boss bar line, which differs between fonts and
            // objects by a pixel or two): the shader puts a quad's top corners on the slot's top and its bottom ones
            // "height" lower (8 x scale for an object, a font's cell for text: 7 px in QuinqueFive, 8 px in the game's).
            Slot slot = switch (part) {
                case Image i -> new Slot(place.anchorX(), place.anchorY(), place.x() + i.x(), place.y() + i.y(), i.width() / 8.0, i.height() / 8.0, i.height());
                case Head h -> new Slot(place.anchorX(), place.anchorY(), place.x() + h.x(), place.y() + h.y(), h.width() / 8.0, h.height() / 8.0, h.height());
                case Text t -> new Slot(place.anchorX(), place.anchorY(), place.x() + t.x(), place.y() + t.y(), t.scale(), t.scale(), (t.small() ? 7 : 8) * t.scale());
            };
            slots.add(slot);
            slotIndex.put(key, slots.size());
        }
    }

    /** Slot of a part at an element's place, 0 when unknown. */
    int slot(Part part, Place place) {
        Integer i = slotIndex.get(new SlotKey(part.index(), place));
        return i == null ? 0 : i;
    }

    /** Green and blue channels of a part's marker (red is {@link #MARKER}). */
    static int green(int slot) {
        return slot & 255;
    }

    static int blue(int slot, int colour, boolean shadow) {
        return (slot >> 8) << 5 | colour << 1 | (shadow ? 1 : 0);
    }

    boolean empty() {
        return slots.isEmpty();
    }

    int slotCount() {
        return slots.size();
    }

    /** Constants and function for the text shader, or null when there is nothing to draw. */
    String glsl() {
        if (slots.isEmpty()) return null;
        StringBuilder place = new StringBuilder("vec4(0.0)");
        StringBuilder scale = new StringBuilder("vec3(1.0)");
        for (Slot s : slots) {
            place.append(",\n    vec4(").append(f(s.anchorX())).append(", ").append(f(s.anchorY())).append(", ")
                    .append(f(s.x())).append(", ").append(f(s.y())).append(')');
            scale.append(",\n    vec3(").append(f(s.scaleX())).append(", ").append(f(s.scaleY())).append(", ").append(f(s.height())).append(')');
        }
        StringBuilder colour = new StringBuilder();
        for (Colour c : colours) {
            if (!colour.isEmpty()) colour.append(", ");
            colour.append(vec4(c.main())).append(", ").append(vec4(c.shadow()));
        }
        int n = slots.size();
        return """
                // The HUD of plugins/Flux/hud.yml (Flux, hud.HudLayout): boss bar text marked by its colour (red 249,
                // green = low byte of the slot, blue = slot / 256 in its top 3 bits, colour * 2 + shadow below) goes to
                // its slot's place on the screen (anchor in fractions of the screen, offset in pixels), in its real
                // colour. Across: its distance from the screen's centre (where every part starts), scaled. Down: a
                // quad's top corners (vertices 0 and 3) on the slot's top, its bottom ones (1 and 2) the slot's height
                // lower, a shadow one scaled pixel lower still.
                const int FLUX_HUD_SLOTS = %d;
                const vec4 FLUX_HUD_PLACE[%d] = vec4[](%s);
                const vec3 FLUX_HUD_SCALE[%d] = vec3[](%s);
                const int FLUX_HUD_COLOURS = %d;
                const vec4 FLUX_HUD_COLOUR[%d] = vec4[](%s);

                void flux_hud_vertex() {
                    ivec3 mark = ivec3(round(Color.rgb * 255.0));
                    if (mark.r != 249) return;
                    int slot = mark.g + ((mark.b >> 5) << 8);
                    int colour = mark.b & 31;
                    if (slot < 1 || slot > FLUX_HUD_SLOTS || colour >= FLUX_HUD_COLOURS) return;
                    vec2 gui = 2.0 / vec2(ProjMat[0][0], -ProjMat[1][1]);
                    vec4 p = ModelViewMat * vec4(Position, 1.0);
                    // Boss bar titles: x = width / 2 - textWidth / 2, and the title is 0 wide.
                    float centre = floor(ceil(gui.x - 0.01) / 2.0);
                    vec4 place = FLUX_HUD_PLACE[slot];
                    vec3 scale = FLUX_HUD_SCALE[slot];
                    int corner = gl_VertexID %% 4;
                    float down = (corner == 1 || corner == 2) ? scale.z : 0.0;
                    if ((colour & 1) == 1) down += scale.y;
                    p.x = place.x * gui.x + place.z + (p.x - centre) * scale.x;
                    p.y = place.y * gui.y + place.w + down;
                    gl_Position = ProjMat * p;
                    vertexColor = FLUX_HUD_COLOUR[colour];
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
