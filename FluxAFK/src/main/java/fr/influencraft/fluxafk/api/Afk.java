package fr.influencraft.fluxafk.api;

import org.bukkit.entity.Player;

/**
 * AFK state of the players, for the other plugins of the suite (a salary that skips AFK players, a menu showing who
 * is away...). A player is AFK while he is in FluxAFK's zone (taken there after idling, or by /afk).
 */
public final class Afk {

    /** Implemented by FluxAFK. */
    public interface Service {
        boolean afk(Player player);

        /** Progress of the player's bar, 0 to 1. */
        double progress(Player player);
    }

    private static volatile Service service;

    private Afk() {
    }

    public static void register(Service s) {
        service = s;
    }

    public static boolean available() {
        return service != null;
    }

    public static boolean afk(Player player) {
        Service s = service;
        return s != null && player != null && s.afk(player);
    }

    public static double progress(Player player) {
        Service s = service;
        return s == null || player == null ? 0 : s.progress(player);
    }
}
