package fr.influencraft.flux.api.welcome;

import org.bukkit.entity.Player;

/**
 * Screens shown to a player when he joins (a questionnaire, the calendar...), one after another instead of all at
 * once: Flux waits for the resource pack (the menus' images), then shows the requested screens by {@code order}
 * (lowest first), the next one once the previous one called {@code done} (answered, closed). A screen requested
 * later in the session is shown when the current one is done. Main thread.
 */
public final class JoinScreens {

    @FunctionalInterface
    public interface Screen {
        /** Shows the screen (or nothing, if not needed any more); {@code done} must run once it is finished. */
        void show(Player player, Runnable done);
    }

    /** Implemented by Flux. */
    public interface Service {
        void request(Player player, int order, Screen screen);
    }

    private static volatile Service service;

    private JoinScreens() {
    }

    public static void bind(Service s) {
        service = s;
    }

    /** Orders used by the suite: the questionnaire of a new player (10), the calendar (100). */
    public static void request(Player player, int order, Screen screen) {
        Service s = service;
        if (s != null) s.request(player, order, screen);
        else screen.show(player, () -> {
        });
    }
}
