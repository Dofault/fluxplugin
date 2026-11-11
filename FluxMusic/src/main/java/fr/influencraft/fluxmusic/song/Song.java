package fr.influencraft.fluxmusic.song;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * A song ready to play: a grid of steps of fixed duration, and the sounds to send on each step that has any.
 * Only the steps holding notes are kept ({@link #times}), so silences cost nothing.
 *
 * @param stepNanos duration of one step
 * @param length    number of steps of the whole song (silences at the end included)
 * @param times     steps holding notes, increasing
 * @param notes     sounds of each of {@link #times}
 * @param warnings  what was fixed while reading the file (notes moved by an octave, bars of a wrong length...)
 */
public record Song(String id, String title, String author, long stepNanos, int length, boolean loop,
                   int[] times, Sound[][] notes, List<String> warnings) {

    public long durationMillis() {
        return length * stepNanos / 1_000_000L;
    }

    public int noteCount() {
        int n = 0;
        for (Sound[] s : notes) n += s.length;
        return n;
    }

    /** Collects notes step by step, then builds the song. */
    public static final class Builder {
        private final TreeMap<Integer, List<Sound>> steps = new TreeMap<>();
        private final List<String> warnings = new ArrayList<>();
        private int length;

        public void note(int step, Instrument instrument, double midi, float volume) {
            if (volume <= 0) return;
            Sound sound = Sound.sound()
                    .type(Key.key(Key.MINECRAFT_NAMESPACE, instrument.sound()))
                    .source(Sound.Source.RECORD)
                    .volume(Math.min(volume, 1f))
                    .pitch(instrument.pitch(midi))
                    // A fixed seed: the server would otherwise draw one from the player's random, which is not
                    // safe off the main thread (note block sounds have a single variant anyway).
                    .seed(0L)
                    .build();
            steps.computeIfAbsent(step, k -> new ArrayList<>()).add(sound);
            length = Math.max(length, step + 1);
        }

        public void length(int steps) {
            length = Math.max(length, steps);
        }

        public void warn(String message) {
            warnings.add(message);
        }

        public Song build(String id, String title, String author, long stepNanos, boolean loop) {
            int[] times = new int[steps.size()];
            Sound[][] notes = new Sound[steps.size()][];
            int i = 0;
            for (var e : steps.entrySet()) {
                times[i] = e.getKey();
                notes[i++] = e.getValue().toArray(Sound[]::new);
            }
            return new Song(id, title, author, stepNanos, length, loop, times, notes, List.copyOf(warnings));
        }
    }
}
