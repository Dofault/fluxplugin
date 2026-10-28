package fr.influencraft.fluxfly.api;

import java.util.UUID;

/**
 * Fly time of the players, for the other plugins of the suite (rewards, migrations). Seconds; {@link #UNLIMITED} is an
 * unlimited bank. Works for offline players too.
 */
public final class FlyTime {

    public static final long UNLIMITED = -1;

    /** Implemented by FluxFly. */
    public interface Service {
        long get(UUID player);

        boolean known(UUID player);

        void set(UUID player, long seconds);

        void add(UUID player, long seconds);
    }

    private static volatile Service service;

    private FlyTime() {
    }

    public static void register(Service s) {
        service = s;
    }

    public static boolean available() {
        return service != null;
    }

    /** Seconds left (the starting time for a player never seen). */
    public static long get(UUID player) {
        return service == null ? 0 : service.get(player);
    }

    /** Whether the player already has a fly time bank (seen by FluxFly, or migrated). */
    public static boolean known(UUID player) {
        return service != null && service.known(player);
    }

    public static void set(UUID player, long seconds) {
        if (service != null) service.set(player, seconds);
    }

    /** Adds (or removes, negative) seconds; an unlimited bank stays unlimited. */
    public static void add(UUID player, long seconds) {
        if (service != null) service.add(player, seconds);
    }
}
