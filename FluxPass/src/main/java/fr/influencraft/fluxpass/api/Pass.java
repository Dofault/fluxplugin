package fr.influencraft.fluxpass.api;

import org.bukkit.entity.Player;

/**
 * The season pass for other plugins: a player's tier, pass points given from elsewhere (an event, a vote).
 * Without FluxPass, {@link #tier} is 0 and points are lost.
 */
public final class Pass {

    public interface Service {
        int tier(Player player);

        void addPoints(Player player, int points);
    }

    private static volatile Service service;

    private Pass() {
    }

    public static void register(Service s) {
        service = s;
    }

    public static boolean available() {
        return service != null;
    }

    public static int tier(Player player) {
        Service s = service;
        return s == null ? 0 : s.tier(player);
    }

    /** Pass points for an online player (climbs the tiers they pay for). */
    public static void addPoints(Player player, int points) {
        Service s = service;
        if (s != null) s.addPoints(player, points);
    }
}
