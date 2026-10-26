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
 */
public final class TitleOverlay {

    private final StringBuilder sb;
    private int cursor;

    /** @param cursor position (px from the left edge of the window) where the text already written stops */
    public TitleOverlay(String base, int cursor) {
        this.sb = new StringBuilder(base);
        this.cursor = cursor;
    }

    /** Places a glyph {@code width} px wide with its left edge at {@code x}. */
    public TitleOverlay add(String glyph, int x, int width) {
        int offset = x - cursor;
        if (offset != 0) sb.append(":offset_").append(offset).append(':');
        sb.append(':').append(glyph).append(':');
        cursor = x + width + 1;
        return this;
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
