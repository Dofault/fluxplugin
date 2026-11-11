package fr.influencraft.flux.universe;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The profile swap of one player between two universes, written without Bukkit so that every crash point can be
 * tested ({@code TransitionTest}).
 *
 * <p>The player carries a <b>marker</b> (universe + version) saved by the game in the same file and at the same time
 * as his inventory. The rules that make the swap safe:</p>
 * <ol>
 *   <li>the live state is captured and the marker bumped to a new version <i>in the same tick</i> (nothing can change
 *   in between: the player is locked), so a player file holding version n always holds the content of version n;</li>
 *   <li>the captured profile is written to the repository with that version, off the main thread;</li>
 *   <li>only once the write is confirmed is the incoming profile applied (and the marker set to its version);</li>
 *   <li>at join, the repository's copy of the marker's universe wins only when it is <b>strictly newer</b> than the
 *   marker: the file then predates a swap that was saved ({@link #reconcile}).</li>
 * </ol>
 * At any instant each universe's items live in exactly one authoritative place: no loss, no duplicate.
 *
 * @param <S> a captured profile (serialized text in production)
 */
public final class Transition<S> {

    /** The player in the game: his marker and what he carries. Main thread only. */
    public interface Live<S> {
        String universe();

        long version();

        /** Everything the player has, as a profile. */
        S capture();

        /** Sets only the marker (same content). */
        void mark(String universe, long version);

        /** Replaces everything the player has with this profile (null = a fresh one) and sets the marker. */
        void apply(S profile, String universe, long version);

        /** Asks the game to write the player's file now (marker and content together). */
        void persist();
    }

    /** Where the profiles of the universes the player is not in are kept. */
    public interface Repository<S> {
        /**
         * The stored profile of this universe, null when there is none. Any thread. Throws when it cannot be read:
         * "unreadable" must never be mistaken for "none", or a fresh profile would later overwrite the real one.
         */
        Stored<S> load(String universe);

        /** Writes a profile with its version; false when the write failed. Called off the main thread. */
        boolean save(String universe, long version, S profile);
    }

    public record Stored<S>(long version, S profile) {
    }

    /** How a swap ended. */
    public enum Outcome {
        /** The player now carries the incoming profile. */
        DONE,
        /** The outgoing profile could not be saved: nothing was changed, the player keeps what he had. */
        SAVE_FAILED,
        /** The incoming profile could not be read: the player keeps what he had (saved, marker equal to it). */
        LOAD_FAILED,
        /** The player left (or the swap was abandoned) before it finished: the next join reconciles. */
        ABANDONED
    }

    private Transition() {
    }

    /**
     * At join, before anything else: if the repository holds a newer copy of the universe the player file claims, the
     * file is stale (a swap was saved but the file was not written afterwards) and the stored copy replaces it.
     * Returns true when the live state was replaced.
     */
    public static <S> boolean reconcile(Live<S> live, Repository<S> repo) {
        String universe = live.universe();
        Stored<S> stored = repo.load(universe);   // throws when unreadable: the caller keeps the player out
        if (stored == null || stored.version() <= live.version()) return false;
        live.apply(stored.profile(), universe, stored.version());
        live.persist();
        return true;
    }

    /**
     * Swaps the player into {@code target}. {@code async} runs the write, {@code main} brings the result back to the
     * main thread; {@code stillHere} is asked on the main thread before applying (false = the player left).
     * {@code done} receives the outcome on the main thread.
     */
    public static <S> void swap(Live<S> live, Repository<S> repo, String target, Executor async, Executor main,
                                java.util.function.BooleanSupplier stillHere, Consumer<Outcome> done) {
        String outgoing = live.universe();
        if (outgoing.equals(target)) {
            done.accept(Outcome.DONE);
            return;
        }
        // 1. capture and mark in the same tick
        S captured = live.capture();
        Stored<S> previous;
        try {
            previous = repo.load(outgoing);
        } catch (RuntimeException e) {
            done.accept(Outcome.LOAD_FAILED);
            return;
        }
        long version = Math.max(live.version(), previous == null ? 0 : previous.version()) + 1;
        live.mark(outgoing, version);
        // 2. write off the main thread
        async.execute(() -> {
            boolean saved;
            try {
                saved = repo.save(outgoing, version, captured);
            } catch (RuntimeException e) {
                saved = false;
            }
            Stored<S> incoming = null;
            boolean readable = true;
            if (saved) {
                try {
                    incoming = repo.load(target);
                } catch (RuntimeException e) {
                    readable = false;
                }
            }
            boolean ok = saved, read = readable;
            Stored<S> in = incoming;
            // 3. apply on the main thread once the write is confirmed
            main.execute(() -> {
                if (!stillHere.getAsBoolean()) {
                    done.accept(Outcome.ABANDONED);
                    return;
                }
                if (!ok) {
                    done.accept(Outcome.SAVE_FAILED);
                    return;
                }
                if (!read) {
                    done.accept(Outcome.LOAD_FAILED);
                    return;
                }
                live.apply(in == null ? null : in.profile(), target, in == null ? 0 : in.version());
                live.persist();
                done.accept(Outcome.DONE);
            });
        });
    }
}
