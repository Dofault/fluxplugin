package fr.influencraft.flux.menu;

import fr.influencraft.flux.Text;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Draws the {@link Widget}s of a menu in its title, with the fonts of the pack {@code fluxui}: one font per window
 * row ({@code fluxui:y<Y>}, Y = window y of the top) holding the menu font, the bar chunks and the sprites.
 * Every widget starts at the title cursor and brings it back ({@code :offset_N:}), so widgets and title layers stay
 * independent. Character widths come from {@code ui/glyphs.yml} (written by gen_ui.py).
 */
public final class UiTitle {

    private static final int CHUNK_BASE = 0xE000, SPRITE_BASE = 0xE100, FRAME_BASE = 0xE110;
    private static final Map<Character, Integer> WIDTHS = new HashMap<>();
    private static final java.util.regex.Pattern COLOURS =
            java.util.regex.Pattern.compile("(?i)[&§]#[0-9a-f]{6}|[&§][0-9a-fk-or]|<[^>]*>");
    private static int[] chunks = {1, 2, 4, 8, 16, 32, 64, 128};
    private static List<String> barColours = List.of("green", "gold", "blue", "red", "purple");
    private static int barHeight = 10, pagerWidth = 70, pagerButtonWidth = 36;

    static {
        try (InputStream in = UiTitle.class.getResourceAsStream("/ui/glyphs.yml")) {
            if (in != null) {
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                var chars = yml.getConfigurationSection("chars");
                if (chars != null) for (String k : chars.getKeys(false)) if (!k.isEmpty()) WIDTHS.put(k.charAt(0), chars.getInt(k));
                List<Integer> c = yml.getIntegerList("chunks");
                if (!c.isEmpty()) chunks = c.stream().mapToInt(Integer::intValue).toArray();
                if (!yml.getStringList("bar-colours").isEmpty()) barColours = yml.getStringList("bar-colours");
                barHeight = yml.getInt("bar-height", barHeight);
                pagerWidth = yml.getInt("pager-width", pagerWidth);
                pagerButtonWidth = yml.getInt("pager-button-width", pagerButtonWidth);
            }
        } catch (Exception ignored) {
            // no glyph table: text widgets draw nothing
        }
    }

    private UiTitle() {
    }

    /** Title text of the widgets, from {@code cursor} (window x of the title cursor) back to it. */
    public static String render(Player player, List<Widget> widgets, int cursor, int rows) {
        StringBuilder out = new StringBuilder();
        for (Widget w : widgets) {
            if (!Requirement.all(w.requirements(), player)) continue;
            switch (w) {
                case Widget.Text t -> text(out, cursor, t.x(), t.y(), t.align(), Text.placeholders(player, t.text()),
                        t.colour(), t.ink());
                case Widget.Slots s -> slots(out, cursor, s, rows);
                case Widget.Image i -> {
                    move(out, i.x() - cursor);
                    out.append(':').append(Text.placeholders(player, i.glyph())).append(':');
                    move(out, cursor - i.x() - i.width() - 1);
                }
                case Widget.Bar b -> bar(out, player, b, cursor);
                case Widget.Pager p -> pager(out, player, p, cursor, rows);
                case Widget.Lit l -> {
                    int[] at = fr.influencraft.flux.PathRenderer.slotPosition(l.slot(), rows);
                    sprite(out, cursor, at[0] - 2, at[1] - 2, SPRITE_BASE + 1, 20);
                }
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- pieces

    /** Text of the menu font; unknown characters are dropped. */
    static void text(StringBuilder out, int cursor, int x, int y, Widget.Align align, String raw, String colour) {
        text(out, cursor, x, y, align, raw, colour, false);
    }

    static void text(StringBuilder out, int cursor, int x, int y, Widget.Align align, String raw, String colour, boolean ink) {
        String s = clean(raw);
        if (s.isEmpty()) return;
        int width = width(s);
        int left = switch (align) {
            case LEFT -> x;
            case CENTER -> x - width / 2;
            case RIGHT -> x - width;
        };
        move(out, left - cursor);
        // container titles are tinted dark grey by default: colour always set (white unless given)
        String tint = colour != null && !colour.isBlank() ? colour : ink ? "#404040" : "white";
        out.append("<font:fluxui:").append(ink ? 'n' : 'y').append(y).append("><").append(tint).append('>')
                .append(s).append("</font>");
        move(out, cursor - left - advance(s));
    }

    private static void bar(StringBuilder out, Player player, Widget.Bar b, int cursor) {
        double value = decimal(Text.placeholders(player, b.progress()));
        double max = decimal(Text.placeholders(player, b.max()));
        double ratio = Math.max(0, Math.min(1, value / (max <= 0 ? 1 : max)));
        if (b.frame()) barFrame(out, b, cursor);
        int inner = b.width() - 2;
        int fill = (int) Math.round(inner * ratio);
        int colour = Math.max(0, barColours.indexOf(b.colour()));
        if (fill > 0) {
            int x = b.x() + 1;
            move(out, x - cursor);
            int drawn = 0;
            for (int k = chunks.length - 1; k >= 0; k--) {
                while (fill - drawn >= chunks[k]) {
                    // a bitmap glyph advances by its width + 1: pull back 1 px after each chunk
                    out.append("<font:fluxui:y").append(b.y() + 1).append('>')
                            .append("<white>").append((char) (CHUNK_BASE + 16 * colour + k)).append("</font>");
                    move(out, -1);
                    drawn += chunks[k];
                }
            }
            move(out, cursor - x - drawn);
        }
        if (b.text() != null && !b.text().isBlank()) {
            text(out, cursor, b.x() + b.width() / 2, b.y() + 1 + (barHeight - 5) / 2, Widget.Align.CENTER,
                    Text.placeholders(player, b.text()), null);
        }
    }

    /**
     * Pager of the shop reference on one row: light button 2 slots wide on {@code prev} and the slot after it, field
     * "page/pages" in the middle, button on the slot before {@code next} and {@code next}. Buttons greyed when there is
     * no page that way; nothing at all when the list fits on one page. The clicks are invisible items (MenuLoader).
     */
    /** Empty frame of a bar: black edge, body chunks (as the fill), black edge; each glyph pulled back 1 px. */
    private static void barFrame(StringBuilder out, Widget.Bar b, int cursor) {
        move(out, b.x() - cursor);
        StringBuilder chars = new StringBuilder().append((char) (FRAME_BASE + 8)).append('\uE1F0');
        int body = b.width() - 2, drawn = 0;
        for (int k = chunks.length - 1; k >= 0; k--) {
            while (body - drawn >= chunks[k]) {
                chars.append((char) (FRAME_BASE + k)).append('\uE1F0');
                drawn += chunks[k];
            }
        }
        chars.append((char) (FRAME_BASE + 8)).append('\uE1F0');
        out.append("<font:fluxui:y").append(b.y()).append("><white>").append(chars).append("</font>");
        move(out, cursor - b.x() - b.width());
    }

    private static void pager(StringBuilder out, Player player, Widget.Pager p, int cursor, int rows) {
        int pages = number(Text.placeholders(player, "%flux_list_" + p.list() + "_pages%"));
        if (pages <= 1) return;
        int page = number(Text.placeholders(player, "%flux_list_" + p.list() + "_page%"));
        int[] a = fr.influencraft.flux.PathRenderer.slotPosition(p.prev(), rows);
        int[] b = fr.influencraft.flux.PathRenderer.slotPosition(p.next(), rows);
        int top = a[1] - 1;
        int leftEdge = a[0] - 1, rightEdge = b[0] + 16;
        sprite(out, cursor, leftEdge, top, SPRITE_BASE + (page > 1 ? 2 : 3), pagerButtonWidth);
        sprite(out, cursor, rightEdge - pagerButtonWidth + 1, top, SPRITE_BASE + (page < pages ? 4 : 5), pagerButtonWidth);
        int centre = (leftEdge + rightEdge + 1) / 2;
        sprite(out, cursor, centre - pagerWidth / 2, top, SPRITE_BASE, pagerWidth);
        text(out, cursor, centre + 1, top + 5, Widget.Align.CENTER, page + "/" + pages, null);
    }

    /** Slot frames, one run per row of consecutive slots (a frame advances 19, pulled back 1: 18 = the slot pitch). */
    private static void slots(StringBuilder out, int cursor, Widget.Slots w, int rows) {
        int[] sorted = w.slots().clone();
        java.util.Arrays.sort(sorted);
        char frame = (char) (SPRITE_BASE + (w.dark() ? 7 : 6));
        int i = 0;
        while (i < sorted.length) {
            int[] first = fr.influencraft.flux.PathRenderer.slotPosition(sorted[i], rows);
            int n = 1;
            while (i + n < sorted.length) {
                int[] next = fr.influencraft.flux.PathRenderer.slotPosition(sorted[i + n], rows);
                if (next[1] != first[1] || next[0] != first[0] + 18 * n) break;
                n++;
            }
            int x = first[0] - 1;
            move(out, x - cursor);
            out.append("<font:fluxui:y").append(first[1] - 1).append("><white>");
            for (int k = 0; k < n; k++) out.append(frame).append('\uE1F0');
            out.append("</font>");
            move(out, cursor - x - 18 * n);
            i += n;
        }
    }

    private static void sprite(StringBuilder out, int cursor, int x, int y, int ch, int width) {
        move(out, x - cursor);
        out.append("<font:fluxui:y").append(y).append("><white>").append((char) ch).append("</font>");
        move(out, cursor - x - width - 1);
    }

    // ---------------------------------------------------------------- helpers

    static String clean(String raw) {
        if (raw == null) return "";
        String plain = COLOURS.matcher(raw).replaceAll("").toUpperCase(Locale.ROOT);
        StringBuilder s = new StringBuilder();
        for (char c : plain.toCharArray()) if (c == ' ' || WIDTHS.containsKey(c)) s.append(c);
        return s.toString().trim();
    }

    /** Visible width in px (last character without its trailing pixel of spacing). */
    public static int width(String s) {
        return Math.max(0, advance(s) - 1);
    }

    static int advance(String s) {
        int total = 0;
        for (char c : s.toCharArray()) total += c == ' ' ? 4 : WIDTHS.getOrDefault(c, 0) + 1;
        return total;
    }

    private static void move(StringBuilder out, int px) {
        if (px != 0) out.append(":offset_").append(px).append(':');
    }

    private static double decimal(String s) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.').replace("%", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
