package fr.influencraft.flux.api;

/**
 * Builds one frame of a title animation: the menu background, then sprites placed to the pixel
 * ({@code :offset_N:} then the ItemsAdder glyph). The cursor follows the text advance: a glyph of width w
 * advances by w + 1 px, the gap to the next sprite is set with {@code :offset_N:}.
 *
 * <pre>
 *   TitleOverlay frame = new TitleOverlay(view.title(), view.titleCursor());
 *   frame.add("plinko_ball_y40", 86, 5);
 *   view.showTitle(frame.toString());
 * </pre>
 * Also the output of a title placeholder (FluxTags rows, the profile's rank line): start at cursor 0 right after the
 * background (whose advance brings the cursor back to the window's left edge), {@link #moveTo} 0 at the end, then
 * {@link #resolved()}.
 */
public final class TitleOverlay {

    private final StringBuilder sb;
    private int cursor;

    /** @param cursor position (px from the left edge of the window) where the text already written stops */
    public TitleOverlay(String base, int cursor) {
        this.sb = new StringBuilder(base);
        this.cursor = cursor;
    }

    /**
     * Places a glyph {@code width} px wide (its advance minus 1) with its left edge at {@code x}. {@code glyph}: a
     * shortcode id ({@code plinko_ball_y40}) or a namespaced id ({@code fluxtags:beta_r1}, written as its character).
     */
    public TitleOverlay add(String glyph, int x, int width) {
        int offset = x - cursor;
        if (offset != 0) sb.append(":offset_").append(offset).append(':');
        String own = glyph.indexOf(':') > 0 ? fr.influencraft.flux.api.items.CustomItems.glyph(glyph) : null;
        sb.append(own != null ? own : ":" + glyph + ":");
        cursor = x + width + 1;
        return this;
    }

    /** Same with a colour code before the glyph ({@code &f} keeps its own colours, {@code &8} darkens it). */
    public TitleOverlay add(String glyph, int x, int width, String colour) {
        int offset = x - cursor;
        if (offset != 0) sb.append(":offset_").append(offset).append(':');
        sb.append(colour);
        cursor = x;
        return add(glyph, x, width);
    }

    /**
     * Places already formatted text {@code width} px wide (its advance minus 1) with its left edge at {@code x}, for
     * sprites in a font of their own ({@code <font:fluxtags:r1>\uE000</font>}): one bitmap provider for a whole sheet
     * instead of one ItemsAdder glyph per picture and height.
     */
    public TitleOverlay addText(String text, int x, int width, String colour) {
        int offset = x - cursor;
        if (offset != 0) sb.append(":offset_").append(offset).append(':');
        sb.append(colour).append(text);
        cursor = x + width + 1;
        return this;
    }

    /** Sprites of Flux/assets/vanilla/sprites.yml: id -> {character, width} (ui/sprites.yml, written by gen_ui.py). */
    private static final java.util.Map<String, int[]> SPRITES = new java.util.HashMap<>();

    static {
        try (java.io.InputStream in = TitleOverlay.class.getResourceAsStream("/ui/sprites.yml")) {
            if (in != null) {
                var yml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                for (String id : yml.getKeys(false)) {
                    java.util.List<Integer> v = yml.getIntegerList(id);
                    if (v.size() == 2) SPRITES.put(id, new int[]{v.get(0), v.get(1)});
                }
            }
        } catch (java.io.IOException ignored) {
            // no table: addSprite draws nothing
        }
    }

    /**
     * Places a shared sprite (Flux/assets/vanilla/sprites.yml) with its top left corner at window pixel (x, y), y from
     * 0 to 220: one character of the {@code fluxui:y<y>} font, no ItemsAdder glyph per height. Unknown id: nothing.
     */
    public TitleOverlay addSprite(String id, int x, int y) {
        int[] s = SPRITES.get(id);
        if (s == null || y < 0 || y > 220) return this;
        return addText("<font:fluxui:y" + y + "><white>" + (char) s[0] + "</font>", x, s[1], "");
    }

    /** Width in px of a shared sprite (0 when unknown). */
    public static int spriteWidth(String id) {
        int[] s = SPRITES.get(id);
        return s == null ? 0 : s[1];
    }

    /** Moves the cursor to x without drawing (0: back to where a placeholder started). */
    public TitleOverlay moveTo(int x) {
        if (x != cursor) sb.append(":offset_").append(x - cursor).append(':');
        cursor = x;
        return this;
    }

    /** The text with every glyph replaced by its character, for a placeholder's output. */
    public String resolved() {
        return fr.influencraft.flux.api.items.CustomItems.glyphs(sb.toString());
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
