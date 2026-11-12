package fr.influencraft.flux.api.hud;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The HUD drawn by Flux on the screen's corners ({@code plugins/Flux/hud.yml}): the player's card at the top left
 * (avatar, money), boxes at the top right (Vote Party, position). Java players see it through the text shader of the
 * pack (two boss bars, kept below the others); Bedrock players get one plain boss bar with the same data.
 *
 * <p>Like the tab list, hud.yml is made of <b>pieces</b> {@code {id}}: Flux gives the common ones (balance,
 * influence, x, y, z, compass, player), a plugin gives the data it owns:</p>
 * <pre>
 *   Hud.piece(this, "voteparty", player -> String.valueOf(party.current()));   // {voteparty} in hud.yml
 *   Hud.refresh(player);                                                        // changed: redraw now
 * </pre>
 * A piece giving null or "" hides the elements that {@code require} it. Pieces are read on the main thread every
 * second or so (position pieces more often): keep them cheap.
 */
public final class Hud {

    private record Owned(Plugin owner, Function<Player, String> value) {
    }

    /** Implemented by Flux. */
    public interface Backend {
        void refresh(Player player);
    }

    private static final Map<String, Owned> PIECES = new ConcurrentHashMap<>();
    private static volatile Backend backend;

    private Hud() {
    }

    /** Called by Flux. */
    public static void bind(Backend b) {
        backend = b;
    }

    /** A piece {@code {id}} of hud.yml (lower case id). */
    public static void piece(Plugin owner, String id, Function<Player, String> value) {
        PIECES.put(id.toLowerCase(Locale.ROOT), new Owned(owner, value));
    }

    public static void unregisterAll(Plugin owner) {
        PIECES.values().removeIf(o -> o.owner() == owner);
    }

    /** Value of a plugin's piece for a player, null when no plugin gives it. */
    public static String value(String id, Player player) {
        Owned o = PIECES.get(id);
        if (o == null) return null;
        String v = o.value().apply(player);
        return v == null ? "" : v;
    }

    /** Draws a player's HUD again now, with fresh values, instead of at the next refresh. */
    public static void refresh(Player player) {
        Backend b = backend;
        if (b != null && player != null) b.refresh(player);
    }
}
