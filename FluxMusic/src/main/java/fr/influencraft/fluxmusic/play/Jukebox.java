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
 *
 * <p>Background music never stops dead: {@link #stop} and a new music fade the former one out (its volume goes down
 * to nothing over {@link #fadeOut(double)} seconds, the new music starting meanwhile). Only a player leaving the server
 * or the plugin shutting down cuts it at once.</p>
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
    /** Music fading out, no longer the player's music but still heard; cut at once when he leaves. */
    private final Map<UUID, Playback> fading = new ConcurrentHashMap<>();
    private volatile long fadeNanos = 2_500_000_000L;

    /** Length of the fade out of a stopped music, in seconds (0 = cut at once). */
    public void fadeOut(double seconds) {
        fadeNanos = (long) (Math.max(0, seconds) * 1_000_000_000L);
    }

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
        if (former != null) {
            if (layer == music) fade(former);
            else former.cancel();
        }
        if (song.times().length == 0) return;
        Playback p = new Playback(layer, player, song, loop);
        layer.put(player.getUniqueId(), p);
        p.schedule();
    }

    /** Stops the background music; false when there was none. */
    public boolean stop(UUID player) {
        Playback p = music.remove(player);
        if (p == null) return false;
        fade(p);
        return true;
    }

    /** Lets the music die out instead of cutting it (a music still fading for this player is cut). */
    private void fade(Playback p) {
        UUID id = p.player.getUniqueId();
        Playback older = fading.put(id, p);
        if (older != null && older != p) older.cancel();
        if (fadeNanos <= 0) {
            fading.remove(id, p);
            p.cancel();
            return;
        }
        p.fadeFrom = System.nanoTime();
    }

    /** Stops everything the player hears (he left). */
    public void stopAll(UUID player) {
        stop(player);
        Playback j = jingles.remove(player);
        if (j != null) j.cancel();
        Playback f = fading.remove(player);
        if (f != null) f.cancel();
    }

    public Song playing(UUID player) {
        Playback p = music.get(player);
        return p == null ? null : p.song;
    }

    public void shutdown() {
        music.values().forEach(Playback::cancel);
        jingles.values().forEach(Playback::cancel);
        fading.values().forEach(Playback::cancel);
        music.clear();
        jingles.clear();
        fading.clear();
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
        /** When the fade out began (System.nanoTime), 0 while the music plays normally. */
        private volatile long fadeFrom;
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
                fading.remove(player.getUniqueId(), this);
                return;
            }
            long now = System.nanoTime();
            float gain = 1f;
            if (fadeFrom != 0) {
                long fade = fadeNanos;
                gain = fade <= 0 ? 0f : 1f - (float) (now - fadeFrom) / fade;
                if (gain <= 0f) {
                    cancelled = true;
                    fading.remove(player.getUniqueId(), this);
                    return;
                }
                gain *= gain; // the ear hears loudness, not volume: a squared curve sounds like an even decline
            }
            long late = now - due();
            if (late > LATE_NANOS) start += late;
            boolean silent = layer == music && jingles.containsKey(player.getUniqueId());
            if (!silent) {
                try {
                    for (Sound s : song.notes()[index]) player.playSound(gain >= 1f ? s : quieter(s, gain),
                            Sound.Emitter.self());
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
                    fading.remove(player.getUniqueId(), this);
                    return;
                }
                start += (song.length() - song.loopStart()) * song.stepNanos();
                index = song.loopIndex();
            }
            schedule();
        }
    }

    private static Sound quieter(Sound s, float gain) {
        return Sound.sound().type(s.name()).source(s.source()).volume(s.volume() * gain).pitch(s.pitch())
                .seed(0L).build();
    }
}
