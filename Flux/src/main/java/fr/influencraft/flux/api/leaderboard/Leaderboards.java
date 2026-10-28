package fr.influencraft.flux.api.leaderboard;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleFunction;

/**
 * Leaderboards of the server, kept by Flux: each board has a source of values; Flux records the value of every
 * player (online players regularly and on quit, every known player once at start), keeps the top in its database and
 * shows it through placeholders:
 * <pre>
 *   %flux_top_&lt;board&gt;_&lt;n&gt;_name%    %flux_top_&lt;board&gt;_&lt;n&gt;_value%    (n-th player, 1 = best)
 *   %flux_top_&lt;board&gt;_rank%           %flux_top_&lt;board&gt;_value%          (the viewer)
 * </pre>
 * Boards are declared in Flux's config ({@code leaderboards}: a statistic, the money, a placeholder) or by a plugin of
 * the suite with {@link #register}.
 */
public final class Leaderboards {

    /** Value of a player for a board, null when unknown. Called on the main thread, for offline players too. */
    @FunctionalInterface
    public interface Source {
        Double value(OfflinePlayer player);
    }

    /** A board: its values and how a value is shown. */
    public record Board(String id, Source source, DoubleFunction<String> format, Plugin owner) {
    }

    public record Entry(UUID player, String name, double value) {
    }

    /** Implemented by Flux. */
    public interface Service {
        List<Entry> top(String board);

        int rank(String board, UUID player);

        Double value(String board, UUID player);
    }

    private static final Map<String, Board> BOARDS = new ConcurrentHashMap<>();
    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();
    private static volatile Service service;

    private Leaderboards() {
    }

    public static void register(Plugin owner, String id, Source source, DoubleFunction<String> format) {
        BOARDS.put(id.toLowerCase(java.util.Locale.ROOT), new Board(id.toLowerCase(java.util.Locale.ROOT), source, format, owner));
    }

    public static void unregisterAll(Plugin owner) {
        BOARDS.values().removeIf(b -> b.owner() == owner);
    }

    public static Map<String, Board> boards() {
        return BOARDS;
    }

    /** Display name of a board ({@code name} in Flux's config), else its id written as words. */
    public static String name(String id) {
        String own = NAMES.get(id.toLowerCase(java.util.Locale.ROOT));
        if (own != null) return own;
        String words = id.replace('_', ' ');
        return words.isEmpty() ? id : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    public static void name(String id, String name) {
        if (name == null || name.isBlank()) NAMES.remove(id.toLowerCase(java.util.Locale.ROOT));
        else NAMES.put(id.toLowerCase(java.util.Locale.ROOT), name);
    }

    public static Board board(String id) {
        return BOARDS.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    public static void service(Service s) {
        service = s;
    }

    /** Best players of a board (as many as Flux keeps, {@code leaderboards.size}). */
    public static List<Entry> top(String board) {
        return service == null ? List.of() : service.top(board);
    }

    /** Recorded value of a player, null when none. */
    public static Double value(String board, UUID player) {
        return service == null ? null : service.value(board, player);
    }

    /** Position of a player (1 = best), 0 when he has no value. */
    public static int rank(String board, UUID player) {
        return service == null ? 0 : service.rank(board, player);
    }
}
