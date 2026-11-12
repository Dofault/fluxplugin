package fr.influencraft.flux.api.client;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Players taken up by an activity that wants the screen to itself (a dungeon run): other plugins keep their boss
 * bars, draws and such off it meanwhile, and tell what matters in the chat. The activity's plugin says who is in it;
 * nobody else needs to know that plugin.
 *
 * <pre>
 *   Focus.register(this, Dungeons::playing);          // FluxDungeons
 *   if (!Focus.focused(player)) player.showBossBar(bar);
 * </pre>
 */
public final class Focus {

    private record Owned(Plugin owner, Predicate<Player> test) {
    }

    private static final List<Owned> TESTS = new CopyOnWriteArrayList<>();

    private Focus() {
    }

    /** {@code test} answers whether a player is in the owner's activity; dropped when the owner stops. */
    public static void register(Plugin owner, Predicate<Player> test) {
        TESTS.add(new Owned(owner, test));
    }

    /** True when some activity has the player's screen. Main thread. */
    public static boolean focused(Player player) {
        if (player == null) return false;
        for (Owned o : TESTS) {
            if (!o.owner().isEnabled()) {
                TESTS.remove(o);
                continue;
            }
            try {
                if (o.test().test(player)) return true;
            } catch (RuntimeException ignored) {
                // a broken test never hides anything
            }
        }
        return false;
    }
}
