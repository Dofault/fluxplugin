package fr.influencraft.flux.api.music;

import org.bukkit.entity.Player;

import java.util.Set;

/**
 * Music played to players, note by note with the client's note block sounds (nothing in the resource pack). The
 * songs and the player are provided by FluxMusic ({@code plugins/FluxMusic/songs/<id>.yml|.nbs}); without it every
 * call does nothing and returns false, so a caller may fall back to a plain sound. A player who turned music off
 * in the settings ({@link #SETTING}) hears nothing either: calls for him return false the same way.
 *
 * <p>Two layers per player:</p>
 * <ul>
 *   <li><b>music</b> ({@link #play}): background music, one at a time, a new one replaces the former;</li>
 *   <li><b>jingle</b> ({@link #jingle}): a short piece (level up, victory) played over the music, which keeps its
 *   time but stays silent until the jingle ends.</li>
 * </ul>
 * Calls are safe from any thread.
 */
public final class Music {

    /** Player setting (settings window, option {@code music}): false = this player hears no music. */
    public static final String SETTING = "flux:music";

    /** What the music plugin provides. */
    public interface Provider {
        /** @param loop true / false to force, null to follow the song file */
        boolean play(Player player, String song, Boolean loop);

        boolean jingle(Player player, String song);

        boolean stop(Player player);

        /** Id of the background music the player hears, or null. */
        String playing(Player player);

        Set<String> songs();
    }

    private static volatile Provider provider;

    private Music() {
    }

    public static void provide(Provider p) {
        provider = p;
    }

    public static boolean available() {
        return provider != null;
    }

    /** Background music, looping or not as its file says; false when the song (or the music plugin) is missing. */
    public static boolean play(Player player, String song) {
        Provider p = provider;
        return p != null && song != null && !song.isBlank() && p.play(player, song, null);
    }

    /** Background music played again and again until {@link #stop} (or another music). */
    public static boolean loop(Player player, String song) {
        Provider p = provider;
        return p != null && song != null && !song.isBlank() && p.play(player, song, true);
    }

    /** A short piece over the background music (which goes silent meanwhile, then goes on). */
    public static boolean jingle(Player player, String song) {
        Provider p = provider;
        return p != null && song != null && !song.isBlank() && p.jingle(player, song);
    }

    /** Stops the background music; false when there was none. */
    public static boolean stop(Player player) {
        Provider p = provider;
        return p != null && p.stop(player);
    }

    /** Id of the background music the player hears, or null. */
    public static String playing(Player player) {
        Provider p = provider;
        return p == null ? null : p.playing(player);
    }

    public static Set<String> songs() {
        Provider p = provider;
        return p == null ? Set.of() : p.songs();
    }
}
