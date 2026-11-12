package fr.influencraft.fluxportal.window;

import org.bukkit.map.MapPalette;

import java.awt.Color;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;

/**
 * The map colours as an alphabet: a map pixel can only be one of the game's map colours, so each distinct colour is
 * given a symbol 0..240 (a number in the window's data), plus two marker colours that tell the shader a map is a
 * window. The shader finds the symbol of a texel back through a hash table written into it ({@link #glsl()}).
 * Window technique of RPE-portals (GPL-3.0).
 */
public final class MapColours {

    public static final int SYMBOLS = 241;
    public static final int CLEAR = 240;

    private final int[] rgbById = new int[256];
    private final int[] symbolId = new int[SYMBOLS];
    private final int[] symbolOf = new int[256];
    private final int mark0, mark1;
    private final TreeMap<Integer, Integer> idByRgb = new TreeMap<>();

    public MapColours() {
        IntUnaryOperator colour = colourTable();
        Map<Integer, Integer> distinct = new LinkedHashMap<>();
        for (int id = 4; id < 256; id++) {
            int argb;
            try {
                argb = colour.applyAsInt(id);
            } catch (RuntimeException e) {
                continue;
            }
            if (argb >>> 24 != 255) continue;
            rgbById[id] = argb & 0xFFFFFF;
            distinct.putIfAbsent(argb & 0xFFFFFF, id);
        }
        idByRgb.putAll(distinct);
        List<Integer> unique = new ArrayList<>(distinct.values());
        if (unique.size() < SYMBOLS + 2) throw new IllegalStateException("Only " + unique.size() + " distinct map colours, the window needs 243");
        Arrays.fill(symbolOf, -1);
        for (int d = 0; d < SYMBOLS; d++) {
            symbolId[d] = unique.get(d);
            symbolOf[unique.get(d)] = d;
        }
        mark0 = unique.get(SYMBOLS);
        mark1 = unique.get(SYMBOLS + 1);
    }

    /** The game's own colour of each map colour id (the server's table, else Bukkit's copy of it). */
    private static IntUnaryOperator colourTable() {
        try {
            Method m = Class.forName("net.minecraft.world.level.material.MapColor").getMethod("getColorFromPackedId", int.class);
            m.invoke(null, 4);
            return id -> {
                try {
                    return (Integer) m.invoke(null, id);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            };
        } catch (ReflectiveOperationException | RuntimeException e) {
            return id -> {
                @SuppressWarnings("deprecation") Color c = MapPalette.getColor((byte) id);
                return c.getAlpha() << 24 | c.getRGB() & 0xFFFFFF;
            };
        }
    }

    public byte symbol(int value) {
        return (byte) symbolId[Math.max(0, Math.min(CLEAR, value))];
    }

    public byte mark0() {
        return (byte) mark0;
    }

    public byte mark1() {
        return (byte) mark1;
    }

    /** The map colour nearest to {@code rgb} (weighted RGB distance). */
    public byte nearest(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        int best = 0;
        long bestD = Long.MAX_VALUE;
        for (Map.Entry<Integer, Integer> e : idByRgb.entrySet()) {
            int c = e.getKey();
            int dr = r - (c >> 16 & 0xFF), dg = g - (c >> 8 & 0xFF), db = b - (c & 0xFF);
            long rm = (r + (c >> 16 & 0xFF)) / 2;
            long d = (512 + rm) * dr * dr / 256 + 4L * dg * dg + (767 - rm) * db * db / 256;
            if (d < bestD) {
                bestD = d;
                best = e.getValue();
            }
        }
        return (byte) best;
    }

    /**
     * A 16x16 texture into a 16x16 square of a map: each texel's luma (0 = see-through) in its symbol's high part, the
     * chroma of each 2x2 block spelt by the low digits of its four texels (the shader reads it back as YCbCr).
     */
    public void encodeTile(int[] argb, byte[] into, int x0, int y0) {
        for (int by = 0; by < 16; by += 2) {
            for (int bx = 0; bx < 16; bx += 2) {
                double cb = 0, cr = 0;
                int n = 0;
                for (int k = 0; k < 4; k++) {
                    int c = argb[(by + (k >> 1)) * 16 + bx + (k & 1)];
                    if (c >>> 24 < 128) continue;
                    int r = c >> 16 & 0xFF, g = c >> 8 & 0xFF, b = c & 0xFF;
                    cb += 128 - 0.168736 * r - 0.331264 * g + 0.5 * b;
                    cr += 128 + 0.5 * r - 0.418688 * g - 0.081312 * b;
                    n++;
                }
                int qb = n == 0 ? 32 : (int) Math.max(0, Math.min(63, Math.round(cb / n * 63 / 255)));
                int qr = n == 0 ? 32 : (int) Math.max(0, Math.min(63, Math.round(cr / n * 63 / 255)));
                int[] digits = {qb >> 3, qb & 7, qr >> 3, qr & 7};
                for (int k = 0; k < 4; k++) {
                    int x = bx + (k & 1), y = by + (k >> 1);
                    int c = argb[y * 16 + x];
                    int sym;
                    if (c >>> 24 < 128) sym = digits[k];
                    else {
                        double l = 0.299 * (c >> 16 & 0xFF) + 0.587 * (c >> 8 & 0xFF) + 0.114 * (c & 0xFF);
                        sym = (1 + (int) Math.max(0, Math.min(28, Math.round(l * 28 / 255)))) * 8 + digits[k];
                    }
                    into[(y0 + y) * 128 + x0 + x] = symbol(sym);
                }
            }
        }
    }

    /** The values the shader template needs ({@code @HASH@}, {@code @SYMS@}...). */
    public Map<String, String> glsl() {
        StringBuilder syms = new StringBuilder(), rgbs = new StringBuilder();
        for (int id = 0; id < 256; id++) {
            if (id > 0) {
                syms.append(',');
                rgbs.append(',');
            }
            syms.append(symbolOf[id]);
            rgbs.append(rgbById[id]);
        }
        // open addressing on 1024 slots: the multiplier that needs the fewest probes
        long[] muls = {2654435761L, 2246822519L, 3266489917L, 668265263L, 374761393L, 2870177451L, 1597334677L, 3812015801L};
        int bestProbes = Integer.MAX_VALUE;
        long bestMul = muls[0];
        long[] best = null;
        for (long mul : muls) {
            long[] table = new long[1024];
            Arrays.fill(table, 0xFFFFFFFFL);
            int longest = 0;
            for (Map.Entry<Integer, Integer> e : idByRgb.entrySet()) {
                long key = e.getKey() & 0xFFFFFFL;
                int h = (int) ((key * mul & 0xFFFFFFFFL) >>> 22);
                int probes = 1;
                while (table[h] != 0xFFFFFFFFL) {
                    h = h + 1 & 1023;
                    probes++;
                }
                table[h] = key << 8 | e.getValue() & 0xFF;
                longest = Math.max(longest, probes);
            }
            if (longest < bestProbes) {
                bestProbes = longest;
                bestMul = mul;
                best = table;
            }
        }
        StringBuilder hash = new StringBuilder();
        for (int i = 0; i < best.length; i++) {
            if (i > 0) hash.append(',');
            hash.append(best[i]).append('u');
        }
        Map<String, String> out = new HashMap<>();
        out.put("@HASH@", hash.toString());
        out.put("@HASH_MUL@", Long.toString(bestMul));
        out.put("@HASH_PROBES@", Integer.toString(bestProbes));
        out.put("@SYMS@", syms.toString());
        out.put("@RGBS@", rgbs.toString());
        out.put("@MARK0@", Integer.toString(mark0));
        out.put("@MARK1@", Integer.toString(mark1));
        out.put("@BASE@", Integer.toString(Window.BASE));
        return out;
    }
}
