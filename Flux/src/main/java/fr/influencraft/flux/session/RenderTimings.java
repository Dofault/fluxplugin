package fr.influencraft.flux.session;

import org.bukkit.Bukkit;

/**
 * Where the time of a menu render goes (main thread only), written in the "slow display" warning: placeholders of
 * PlaceholderAPI, placeholders of the suite, ItemsAdder glyphs, colours, and the whole item building.
 */
public final class RenderTimings {

    public static final int PAPI = 0, SUITE = 1, GLYPHS = 2, COLORS = 3, ITEMS = 4;
    private static final String[] NAMES = {"PlaceholderAPI", "placeholders Flux", "glyphes ItemsAdder", "couleurs", "objets"};
    private static final long[] NANOS = new long[NAMES.length];
    private static final int[] CALLS = new int[NAMES.length];

    private RenderTimings() {
    }

    public static void reset() {
        java.util.Arrays.fill(NANOS, 0);
        java.util.Arrays.fill(CALLS, 0);
    }

    public static void add(int bucket, long startNanos) {
        if (!Bukkit.isPrimaryThread()) return;
        NANOS[bucket] += System.nanoTime() - startNanos;
        CALLS[bucket]++;
    }

    public static String summary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NAMES.length; i++) {
            if (CALLS[i] == 0) continue;
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(NAMES[i]).append(' ').append(NANOS[i] / 1_000_000).append(" ms (").append(CALLS[i]).append(')');
        }
        return sb.toString();
    }
}
