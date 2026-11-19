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
 * @param beatNanos duration of one beat (0 when the file does not tell: Note Block Studio)
 * @param length    number of steps of the whole song (silences at the end included)
 * @param loopStart step a looping song goes back to (0 = the start; an intro is played once before it)
 * @param times     steps holding notes, increasing
 * @param notes     sounds of each of {@link #times}
 * @param angles    for each sound of {@link #notes}: where it comes from, in degrees around the listener (0 in front,
 *                  90 on his right, -90 on his left), or NaN for a sound played on the listener himself (no stereo)
 * @param instruments for each sound of {@link #notes}: the instrument playing it (a stage shows who plays)
 * @param warnings  what was fixed while reading the file (notes moved by an octave, bars of a wrong length...)
 */
public record Song(String id, String title, String author, long stepNanos, long beatNanos, int length, boolean loop,
                   int loopStart, int[] times, Sound[][] notes, float[][] angles, Instrument[][] instruments,
                   List<String> warnings) {

    /** Instruments heard at least once in the song. */
    public java.util.Set<Instrument> cast() {
        java.util.EnumSet<Instrument> set = java.util.EnumSet.noneOf(Instrument.class);
        for (Instrument[] step : instruments) java.util.Collections.addAll(set, step);
        return set;
    }

    /** Index in {@link #times} of the first step played again when the song loops. */
    public int loopIndex() {
        for (int i = 0; i < times.length; i++) if (times[i] >= loopStart) return i;
        return 0;
    }

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
        private record Placed(Sound sound, float angle, Instrument instrument) {
        }

        private final TreeMap<Integer, List<Placed>> steps = new TreeMap<>();
        private final List<String> warnings = new ArrayList<>();
        private int length;
        private long beatNanos;

        /** Duration of one beat, for what moves in time with the song. */
        public void beat(long nanos) {
            beatNanos = nanos;
        }

        public void note(int step, Instrument instrument, double midi, float volume) {
            note(step, instrument, midi, volume, Float.NaN);
        }

        /** A note coming from {@code angle} degrees around the listener (NaN = on the listener). */
        public void note(int step, Instrument instrument, double midi, float volume, float angle) {
            if (volume <= 0 || step < 0) return;
            Sound sound = Sound.sound()
                    .type(Key.key(Key.MINECRAFT_NAMESPACE, instrument.sound()))
                    .source(Sound.Source.RECORD)
                    .volume(Math.min(volume, 1f))
                    .pitch(instrument.pitch(midi))
                    // A fixed seed: the server would otherwise draw one from the player's random, which is not
                    // safe off the main thread (note block sounds have a single variant anyway).
                    .seed(0L)
                    .build();
            steps.computeIfAbsent(step, k -> new ArrayList<>()).add(new Placed(sound, angle, instrument));
            length = Math.max(length, step + 1);
        }

        public void length(int steps) {
            length = Math.max(length, steps);
        }

        public void warn(String message) {
            warnings.add(message);
        }

        public Song build(String id, String title, String author, long stepNanos, boolean loop, int loopStart) {
            int[] times = new int[steps.size()];
            Sound[][] notes = new Sound[steps.size()][];
            float[][] angles = new float[steps.size()][];
            Instrument[][] instruments = new Instrument[steps.size()][];
            int i = 0;
            for (var e : steps.entrySet()) {
                times[i] = e.getKey();
                List<Placed> placed = e.getValue();
                notes[i] = new Sound[placed.size()];
                angles[i] = new float[placed.size()];
                instruments[i] = new Instrument[placed.size()];
                for (int k = 0; k < placed.size(); k++) {
                    notes[i][k] = placed.get(k).sound();
                    angles[i][k] = placed.get(k).angle();
                    instruments[i][k] = placed.get(k).instrument();
                }
                i++;
            }
            int from = loopStart < 0 || loopStart >= length ? 0 : loopStart;
            return new Song(id, title, author, stepNanos, beatNanos, length, loop, from, times, notes, angles, instruments,
                    List.copyOf(warnings));
        }
    }
}
