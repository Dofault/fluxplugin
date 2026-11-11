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

/**
 * Plays songs to players, note by note, with the client's own note block sounds.
 *
 * <p>Timing does not follow server ticks (50 ms, and late when the server lags): one clock thread sends each step
 * at its exact time, computed from the start of the song so errors never add up. Every note of a step leaves in the
 * same burst, so they reach the client together. The sound is attached to the player himself (it follows him and
 * plays in the "Jukebox/Note blocks" volume slider).</p>
 *
 * <p>Two layers per player: the background music, and jingles played over it. While a jingle plays, the music keeps
 * its time but sends nothing, so it comes back in rhythm after the jingle.</p>
 *
 * <p>When the clock is held back (pause of the JVM), the rest of the song is shifted rather than played in a rush.</p>
 */
public final class Jukebox {

    /** Further than this behind its time, a step shifts the rest of the song instead of catching up. */
    private static final long LATE_NANOS = 80_000_000L;
    /** Delay before the first note, so the first steps are not sent late. */
    private static final long LEAD_NANOS = 60_000_000L;

    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FluxMusic clock");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });
    private final Map<UUID, Playback> music = new ConcurrentHashMap<>();
    private final Map<UUID, Playback> jingles = new ConcurrentHashMap<>();

    /** Background music (replaces the former one). */
    public void play(Player player, Song song, boolean loop) {
        start(music, player, song, loop);
    }

    /** A jingle over the music (replaces a jingle still playing). */
    public void jingle(Player player, Song song) {
        start(jingles, player, song, false);
    }

    private void start(Map<UUID, Playback> layer, Player player, Song song, boolean loop) {
        Playback former = layer.remove(player.getUniqueId());
        if (former != null) former.cancel();
        if (song.times().length == 0) return;
        Playback p = new Playback(layer, player, song, loop);
        layer.put(player.getUniqueId(), p);
        p.schedule();
    }

    /** Stops the background music; false when there was none. */
    public boolean stop(UUID player) {
        Playback p = music.remove(player);
        if (p == null) return false;
        p.cancel();
        return true;
    }

    /** Stops everything the player hears (he left). */
    public void stopAll(UUID player) {
        stop(player);
        Playback j = jingles.remove(player);
        if (j != null) j.cancel();
    }

    public Song playing(UUID player) {
        Playback p = music.get(player);
        return p == null ? null : p.song;
    }

    public void shutdown() {
        music.values().forEach(Playback::cancel);
        jingles.values().forEach(Playback::cancel);
        music.clear();
        jingles.clear();
        clock.shutdownNow();
    }

    private final class Playback implements Runnable {
        private final Map<UUID, Playback> layer;
        private final Player player;
        private final Song song;
        private final boolean loop;
        private long start = System.nanoTime() + LEAD_NANOS;
        private int index;
        private volatile boolean cancelled;
        private volatile ScheduledFuture<?> next;

        Playback(Map<UUID, Playback> layer, Player player, Song song, boolean loop) {
            this.layer = layer;
            this.player = player;
            this.song = song;
            this.loop = loop;
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
                layer.remove(player.getUniqueId(), this);
                return;
            }
            long late = System.nanoTime() - due();
            if (late > LATE_NANOS) start += late;
            boolean silent = layer == music && jingles.containsKey(player.getUniqueId());
            if (!silent) {
                try {
                    for (Sound s : song.notes()[index]) player.playSound(s, Sound.Emitter.self());
                } catch (RuntimeException e) {
                    // the connection closed between the check and the send: the quit listener stops the song
                }
            }
            index++;
            if (index >= song.times().length) {
                if (!loop) {
                    // a jingle frees the music once its last notes have rung a little
                    if (layer == jingles) clock.schedule(() -> layer.remove(player.getUniqueId(), this),
                            Math.max(0, (song.length() - song.times()[song.times().length - 1]) * song.stepNanos()),
                            TimeUnit.NANOSECONDS);
                    else layer.remove(player.getUniqueId(), this);
                    return;
                }
                start += (song.length() - song.loopStart()) * song.stepNanos();
                index = song.loopIndex();
            }
            schedule();
        }
    }
}
