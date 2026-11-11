package fr.influencraft.fluxmusic.play;

import fr.influencraft.fluxmusic.song.Song;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Plays songs to players, note by note, with the client's own note block sounds.
 *
 * <p>Timing does not follow server ticks (50 ms, and late when the server lags): one clock thread sends each step
 * at its exact time, computed from the start of the song so errors never add up. Every note of a step leaves in the
 * same burst, so they reach the client together. The sound is attached to the player himself (it follows him and
 * plays in the "Jukebox/Note blocks" volume slider).</p>
 *
 * <p>When the clock is held back (pause of the JVM), the rest of the song is shifted rather than played in a rush.</p>
 */
public final class Jukebox {

    /** Further than this behind its time, a step shifts the rest of the song instead of catching up. */
    private static final long LATE_NANOS = 80_000_000L;
    /** Delay before the first note, so the first steps are not sent late. */
    private static final long LEAD_NANOS = 100_000_000L;

    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FluxMusic clock");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });
    private final Map<UUID, Playback> playing = new ConcurrentHashMap<>();
    /** Called when a song ends by itself (not when stopped), from the clock thread. */
    private final BiConsumer<Player, Song> onEnd;

    public Jukebox(BiConsumer<Player, Song> onEnd) {
        this.onEnd = onEnd;
    }

    public void play(Player player, Song song) {
        stop(player.getUniqueId());
        if (song.times().length == 0) return;
        Playback p = new Playback(player, song);
        playing.put(player.getUniqueId(), p);
        p.schedule();
    }

    public boolean stop(UUID player) {
        Playback p = playing.remove(player);
        if (p == null) return false;
        p.cancel();
        return true;
    }

    public Song playing(UUID player) {
        Playback p = playing.get(player);
        return p == null ? null : p.song;
    }

    public void shutdown() {
        playing.values().forEach(Playback::cancel);
        playing.clear();
        clock.shutdownNow();
    }

    private final class Playback implements Runnable {
        private final Player player;
        private final Song song;
        private long start = System.nanoTime() + LEAD_NANOS;
        private int index;
        private volatile boolean cancelled;
        private volatile ScheduledFuture<?> next;

        Playback(Player player, Song song) {
            this.player = player;
            this.song = song;
        }

        private long due() {
            return start + song.times()[index] * song.stepNanos();
        }

        void schedule() {
            if (cancelled) return;
            next = clock.schedule(this, due() - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        void cancel() {
            cancelled = true;
            ScheduledFuture<?> f = next;
            if (f != null) f.cancel(false);
        }

        @Override
        public void run() {
            if (cancelled) return;
            if (!player.isOnline()) {
                playing.remove(player.getUniqueId(), this);
                return;
            }
            long late = System.nanoTime() - due();
            if (late > LATE_NANOS) start += late;
            try {
                for (Sound s : song.notes()[index]) player.playSound(s, Sound.Emitter.self());
            } catch (RuntimeException e) {
                // the connection closed between the check and the send: the quit listener stops the song
            }
            index++;
            if (index >= song.times().length) {
                if (!song.loop()) {
                    if (playing.remove(player.getUniqueId(), this)) onEnd.accept(player, song);
                    return;
                }
                start += song.length() * song.stepNanos();
                index = 0;
            }
            schedule();
        }
    }
}
