package fr.influencraft.flux.api.scoreboard;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The scoreboard on the right of the screen, drawn by Flux from {@code plugins/Flux/scoreboard.yml} (sections, lines,
 * colours, width). A plugin gives the rows it owns as a <b>list</b>, the file lays them out:
 * <pre>
 *   Scoreboard.list(this, "pass_missions", player -> List.of(Map.of("name", "Mine stone", "progress", "32/64")));
 *   // scoreboard.yml: {each: pass_missions, text: " <frame>{branch}</frame> <text>{name}</text> <value>{progress}</value>"}
 *   Scoreboard.refresh(player);   // changed: redraw now instead of at the next refresh
 * </pre>
 * Lists are read on the main thread every few ticks: keep them cheap. An empty list hides the lines (and sections)
 * that need it.
 */
public final class Scoreboard {

    private record Owned(Plugin owner, Function<Player, List<Map<String, String>>> rows) {
    }

    /** Implemented by Flux. */
    public interface Backend {
        void refresh(Player player);
    }

    private static final Map<String, Owned> LISTS = new ConcurrentHashMap<>();
    private static volatile Backend backend;

    private Scoreboard() {
    }

    /** Called by Flux. */
    public static void bind(Backend b) {
        backend = b;
    }

    /** A list {@code each: <id>} of scoreboard.yml: one row per item, its keys {@code {key}} in the line. */
    public static void list(Plugin owner, String id, Function<Player, List<Map<String, String>>> rows) {
        LISTS.put(id.toLowerCase(Locale.ROOT), new Owned(owner, rows));
    }

    public static void unregisterAll(Plugin owner) {
        LISTS.values().removeIf(o -> o.owner() == owner);
    }

    /** Whether a plugin gives this list. */
    public static boolean has(String id) {
        return LISTS.containsKey(id);
    }

    /** Rows of a plugin's list for a player, empty when no plugin gives it. */
    public static List<Map<String, String>> rows(String id, Player player) {
        Owned o = LISTS.get(id);
        if (o == null) return List.of();
        List<Map<String, String>> rows = o.rows().apply(player);
        return rows == null ? List.of() : rows;
    }

    /** Draws a player's scoreboard again now, with fresh values. */
    public static void refresh(Player player) {
        Backend b = backend;
        if (b != null && player != null) b.refresh(player);
    }
}
