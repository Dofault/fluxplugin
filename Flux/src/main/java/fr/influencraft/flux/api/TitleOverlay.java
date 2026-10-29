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
