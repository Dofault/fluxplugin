package fr.influencraft.flux.api.tab;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The tab list of the server, drawn by Flux ({@code plugins/Flux/tab.yml}): header and footer, the name of every
 * player in the list and above his head, the line below his name, the order of the list. Replaces the TAB plugin.
 *
 * <p>The templates of tab.yml are made of <b>pieces</b> {@code {id}} or {@code {id:argument}}: Flux gives the common
 * ones (player, ping, online, group, meta, balance...), a plugin gives the data it owns, computed in Java instead of
 * going through a placeholder:</p>
 * <pre>
 *   TabList.piece(this, "tag", player -> glyphOf(player));      // {tag} in tab.yml
 *   ...
 *   TabList.refresh(player);                                      // the player changed his tag: redraw him now
 * </pre>
 * Pieces are read on the main thread, often (every second for names, every few ticks for the header): keep them
 * cheap (cached values, no database).
 */
public final class TabList {

    private record Owned(Plugin owner, Function<Player, String> value) {
    }

    /** Implemented by Flux. */
    public interface Backend {
        void refresh(Player player);

        boolean collision(Player player, boolean collides);
    }

    private static final Map<String, Owned> PIECES = new ConcurrentHashMap<>();
    private static volatile Backend backend;

    private TabList() {
    }

    /** Called by Flux. */
    public static void bind(Backend b) {
        backend = b;
    }

    /** A piece {@code {id}} of the tab.yml templates (lower case id, no colon). */
    public static void piece(Plugin owner, String id, Function<Player, String> value) {
        PIECES.put(id.toLowerCase(Locale.ROOT), new Owned(owner, value));
    }

    public static void unregisterAll(Plugin owner) {
        PIECES.values().removeIf(o -> o.owner() == owner);
    }

    /** Value of a registered piece for a player, or null when no plugin gives it. */
    public static String value(String id, Player player) {
        Owned o = PIECES.get(id);
        if (o == null) return null;
        String v = o.value().apply(player);
        return v == null ? "" : v;
    }

    /** Redraws a player's name, nametag and line below the name now (instead of at the next refresh). */
    public static void refresh(Player player) {
        Backend b = backend;
        if (b != null && player != null) b.refresh(player);
    }

    /**
     * Whether other players can push this one ({@code false}: nobody pushes him and he pushes nobody, as players
     * standing still on the same spot must not), {@code true} back to {@code nametags.collision} of tab.yml. Pushing
     * is computed by each client from the teams Flux sends, so this is the only reliable way: never create a
     * scoreboard team for it. False when Flux draws no nametags (nothing changed).
     */
    public static boolean collision(Player player, boolean collides) {
        Backend b = backend;
        return b != null && player != null && b.collision(player, collides);
    }
}
