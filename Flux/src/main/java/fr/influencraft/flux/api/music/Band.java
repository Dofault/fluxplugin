package fr.influencraft.flux.api.music;

import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Musicians standing somewhere in the world, each playing some note block instruments of a song ({@link Music#band}):
 * a note sounds from where its instrument is played, and the notes of an instrument nobody plays are left out. The
 * plugin owning the musicians (FluxPets: the music pets) gives where they are; every method is called from the
 * music's clock thread, so it must only read a snapshot kept up to date on the server thread.
 */
public interface Band {

    /** Players hearing the band now. Their background music fades out and waits until they leave this list. */
    Collection<Player> listeners();

    /** World the band plays in. */
    String world();

    /** Centre of the band (x, y, z). */
    double[] centre();

    /** Distance from a musician at which its notes are no longer heard, in blocks. */
    double radius();

    /**
     * Where this note block instrument ({@code flute}, {@code bass}, {@code basedrum}...) is played (x, y, z), null
     * when no musician of the band plays it: its notes are then left out.
     */
    double[] source(String instrument);

    /** A note of this instrument will sound in 200 ms (pitch 0.5 to 2): time to start the gesture playing it. */
    default void cue(String instrument, float pitch) {
    }

    /** A song played by the band: over ({@link #done}) once its last note rang, or when cancelled. */
    interface Session {
        boolean done();

        void cancel();
    }
}
