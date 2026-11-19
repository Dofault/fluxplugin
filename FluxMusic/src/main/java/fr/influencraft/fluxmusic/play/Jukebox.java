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
    /** Distance from the head of a sound placed in stereo, in blocks, and the volume making up for it (a sound of
     *  volume 1 or less fades linearly over 16 blocks). */
    private static final double SIDE_DISTANCE = 2.5;
    private static final float SIDE_GAIN = (float) (16 / (16 - SIDE_DISTANCE));
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
                send(player, song.notes()[index], song.angles()[index], gain);
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

    /**
     * Sends the sounds of one step to a player's own ears ("in his headphones"), at {@code gain} times their volume.
     * Heard on himself, or on the camera he looks through (a scene), else the music would come from where his body
     * was left, on one side.
     */
    private static void send(Player player, Sound[] sounds, float[] angles, float gain) {
        try {
            org.bukkit.entity.Entity camera = fr.influencraft.flux.api.client.Camera.viewed(player);
            Sound.Emitter from = camera != null ? camera : Sound.Emitter.self();
            org.bukkit.Location ears = null;
            for (int i = 0; i < sounds.length; i++) {
                Sound s = gain >= 1f ? sounds[i] : quieter(sounds[i], gain);
                if (Float.isNaN(angles[i])) {
                    player.playSound(s, from);
                    continue;
                }
                // stereo: the sound is sent from a point around his head, on the side of the angle,
                // relative to where he looks (the client pans it); a little louder for the distance
                if (ears == null) {
                    org.bukkit.entity.Entity head = camera != null ? camera : player;
                    ears = head instanceof org.bukkit.entity.LivingEntity l ? l.getEyeLocation() : head.getLocation();
                }
                double yaw = Math.toRadians(ears.getYaw()), a = Math.toRadians(angles[i]);
                double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
                double x = ears.getX() + (fx * Math.cos(a) + rx * Math.sin(a)) * SIDE_DISTANCE;
                double z = ears.getZ() + (fz * Math.cos(a) + rz * Math.sin(a)) * SIDE_DISTANCE;
                player.playSound(louder(s, SIDE_GAIN), x, ears.getY(), z);
            }
        } catch (RuntimeException e) {
            // the connection closed between the check and the send: the quit listener stops the song
        }
    }

    // ---------------------------------------------------------------- broadcast

    /** How long before a note a scene is told about it ({@link Scene#cue}). */
    public static final long CUE_NANOS = 200_000_000L;

    /** Who hears a {@link Broadcast} and where it sounds from; every method is called on the clock thread. */
    public interface Scene {
        /** Players hearing the broadcast now (a snapshot safe to read from another thread). */
        java.util.Collection<Player> listeners();

        /** World of the stage and its centre. */
        String world();

        double x();

        double y();

        double z();

        /** Within this distance of the centre the music comes from the stage, in the headphones beyond. */
        double radius();

        /** Where this instrument is played on the stage (x, y, z). */
        double[] source(fr.influencraft.fluxmusic.song.Instrument instrument);

        /** A note of this instrument was just sent (its pitch, 0.5 to 2). */
        void played(fr.influencraft.fluxmusic.song.Instrument instrument, float pitch);

        /** A note of this instrument will be sent in {@link #CUE_NANOS}: time to start the gesture that plays it. */
        default void cue(fr.influencraft.fluxmusic.song.Instrument instrument, float pitch) {
        }
    }

    /**
     * One song played to many players on one clock: they all hear the same note at the same time (a band on a
     * stage). Far from the stage it sounds in each player's headphones as any music; within the stage's radius it
     * comes from the musicians themselves (whatever the radius: see {@code near} below), the two blending with the
 * distance so nothing jumps at the edge.
     * A player hearing his own music or a jingle is left alone meanwhile.
     */
    public final class Broadcast implements Runnable {
        private final Song song;
        private final Scene scene;
        private long start = System.nanoTime() + LEAD_NANOS;
        private int index;
        private volatile boolean done;
        private volatile ScheduledFuture<?> next;
        /** Step the scene is next told about ahead of time. */
        private int cueIndex;

        /** Tells the scene about the notes of the coming step, then waits for the one after. */
        private void cue() {
            if (done || cueIndex >= song.times().length) return;
            long wait = start + song.times()[cueIndex] * song.stepNanos() - CUE_NANOS - System.nanoTime();
            if (wait > 1_000_000L) {
                clock.schedule(this::cue, wait, TimeUnit.NANOSECONDS);
                return;
            }
            try {
                Sound[] sounds = song.notes()[cueIndex];
                fr.influencraft.fluxmusic.song.Instrument[] who = song.instruments()[cueIndex];
                for (int i = 0; i < sounds.length; i++) scene.cue(who[i], sounds[i].pitch());
            } catch (RuntimeException e) {
                // a scene reloaded under us
            }
            cueIndex++;
            cue();
        }

        private Broadcast(Song song, Scene scene) {
            this.song = song;
            this.scene = scene;
        }

        public Song song() {
            return song;
        }

        /** True once the song ended (its last notes rung) or was cancelled. */
        public boolean done() {
            return done;
        }

        public void cancel() {
            done = true;
            ScheduledFuture<?> f = next;
            if (f != null) f.cancel(false);
        }

        private void schedule() {
            if (done) return;
            next = clock.schedule(this, start + song.times()[index] * song.stepNanos() - System.nanoTime(),
                    TimeUnit.NANOSECONDS);
        }

        @Override
        public void run() {
            if (done) return;
            long late = System.nanoTime() - (start + song.times()[index] * song.stepNanos());
            if (late > LATE_NANOS) start += late;
            Sound[] sounds = song.notes()[index];
            float[] angles = song.angles()[index];
            fr.influencraft.fluxmusic.song.Instrument[] who = song.instruments()[index];
            try {
                double radius = scene.radius();
                // A client fades a sound out over 16 blocks, whatever we do. For the stage to be heard over its
                // whole radius, each note is sent from a point on the line to its musician, at 16/radius of the
                // real distance: same direction, and a fade that reaches silence at the radius instead of 16 blocks.
                double near = 16 / Math.max(1, radius);
                for (Player player : scene.listeners()) {
                    UUID id = player.getUniqueId();
                    if (!player.isOnline() || music.containsKey(id) || jingles.containsKey(id)) continue;
                    float head = 1f;
                    try {
                        org.bukkit.Location at = player.getEyeLocation();
                        if (at.getWorld() != null && at.getWorld().getName().equalsIgnoreCase(scene.world())) {
                            double dx = at.getX() - scene.x(), dy = at.getY() - scene.y(), dz = at.getZ() - scene.z();
                            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                            if (d < radius) head = (float) (d / radius);
                        }
                        if (head < 1f) for (int i = 0; i < sounds.length; i++) {
                            double[] from = scene.source(who[i]);
                            player.playSound(sounds[i], at.getX() + (from[0] - at.getX()) * near,
                                    at.getY() + (from[1] - at.getY()) * near, at.getZ() + (from[2] - at.getZ()) * near);
                        }
                    } catch (RuntimeException e) {
                        // he left meanwhile
                    }
                    if (head > 0.02f) send(player, sounds, angles, head);
                }
                for (int i = 0; i < sounds.length; i++) scene.played(who[i], sounds[i].pitch());
            } catch (RuntimeException e) {
                // a scene reloaded under us: the next step reads the new one
            }
            index++;
            if (index >= song.times().length) {
                // the song is over once its last notes have rung
                clock.schedule(() -> done = true,
                        Math.max(0, (song.length() - song.times()[song.times().length - 1]) * song.stepNanos()),
                        TimeUnit.NANOSECONDS);
                return;
            }
            schedule();
        }
    }

    /** Plays a song once to everybody of a scene, on one clock. */
    public Broadcast broadcast(Song song, Scene scene) {
        Broadcast b = new Broadcast(song, scene);
        if (song.times().length == 0) b.done = true;
        else {
            b.schedule();
            b.cue();
        }
        return b;
    }

    /** Whether the player hears a music or a jingle of his own (a broadcast stays silent for him meanwhile). */
    public boolean busy(UUID player) {
        return music.containsKey(player) || jingles.containsKey(player);
    }

    private static Sound louder(Sound s, float gain) {
        return Sound.sound().type(s.name()).source(s.source()).volume(Math.min(1f, s.volume() * gain)).pitch(s.pitch())
                .seed(0L).build();
    }

    private static Sound quieter(Sound s, float gain) {
        return Sound.sound().type(s.name()).source(s.source()).volume(s.volume() * gain).pitch(s.pitch())
                .seed(0L).build();
    }
}
