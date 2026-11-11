package fr.influencraft.fluxmusic.api;

import org.bukkit.entity.Player;

import java.util.Set;

/**
 * Songs played to players with note block sounds (FluxMusic). Nothing is added to the resource pack: each note is
 * sent when it must play. Calls are safe from any thread; without FluxMusic every call does nothing.
 */
public final class Music {

    public interface Service {
        boolean play(Player player, String song);

        boolean stop(Player player);

        String playing(Player player);

        Set<String> songs();
    }

    private static volatile Service service;

    private Music() {
    }

    public static void register(Service s) {
        service = s;
    }

    /** Plays a song to a player (replacing the one he hears); false when the song does not exist. */
    public static boolean play(Player player, String song) {
        Service s = service;
        return s != null && s.play(player, song);
    }

    /** Stops the song a player hears; false when he heard none. */
    public static boolean stop(Player player) {
        Service s = service;
        return s != null && s.stop(player);
    }

    /** Id of the song a player hears, or null. */
    public static String playing(Player player) {
        Service s = service;
        return s == null ? null : s.playing(player);
    }

    public static Set<String> songs() {
        Service s = service;
        return s == null ? Set.of() : s.songs();
    }
}
