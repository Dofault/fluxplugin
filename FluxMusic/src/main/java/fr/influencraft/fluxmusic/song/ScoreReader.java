package fr.influencraft.fluxmusic.song;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a song written as a score in YAML:
 *
 * <pre>
 * title: "My song"
 * bpm: 100              # beats per minute
 * steps-per-beat: 4     # a step is then a sixteenth note
 * beats-per-bar: 4      # used to check bar lengths
 * loop: false
 * tracks:
 *   melody: { instrument: flute, volume: 1.0 }
 * patterns:             # reusable pieces, written @name or @name*3
 *   arp_dm: "D4:2 F4:2 A4:2 D5:2 F5:2 D5:2 A4:2 F4:2"
 * sections:
 *   theme:
 *     melody: "A4:4 D5:2 E5:2 F5:8 | @arp_dm"
 * order: [theme, theme]
 * </pre>
 *
 * A token is a note ({@code C4}, {@code F#5}, {@code Bb3}), a chord ({@code D4+F4+A4}), {@code x} (the
 * instrument's own pitch, for drums) or a rest ({@code .} / {@code -}), followed by {@code :n} to last n steps
 * (default 1). {@code |} separates bars: each bar is checked against {@code beats-per-bar}.
 */
public final class ScoreReader {

    private static final Pattern NOTE = Pattern.compile("([A-Ga-g])([#b]?)(-?\\d)");
    private static final Pattern PATTERN_REF = Pattern.compile("@([A-Za-z0-9_]+)(?:\\*(\\d+))?");
    private static final int[] LETTER = {9, 11, 0, 2, 4, 5, 7}; // A B C D E F G

    private record Track(Instrument instrument, float volume) {
    }

    private ScoreReader() {
    }

    public static Song read(String id, File file, float masterVolume) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        double bpm = yaml.getDouble("bpm", 100);
        int perBeat = Math.max(1, yaml.getInt("steps-per-beat", 4));
        int barSteps = Math.max(1, yaml.getInt("beats-per-bar", 4)) * perBeat;
        if (bpm <= 0) throw new IllegalArgumentException("bpm must be positive");
        long stepNanos = Math.round(60_000_000_000d / (bpm * perBeat));

        Map<String, Track> tracks = new LinkedHashMap<>();
        ConfigurationSection ts = yaml.getConfigurationSection("tracks");
        if (ts == null || ts.getKeys(false).isEmpty()) throw new IllegalArgumentException("no tracks");
        for (String name : ts.getKeys(false)) {
            String instrument = ts.getString(name + ".instrument", "harp");
            try {
                tracks.put(name, new Track(Instrument.named(instrument), (float) ts.getDouble(name + ".volume", 1.0)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("track " + name + ": unknown instrument " + instrument);
            }
        }

        Map<String, String> patterns = new LinkedHashMap<>();
        ConfigurationSection ps = yaml.getConfigurationSection("patterns");
        if (ps != null) for (String name : ps.getKeys(false)) patterns.put(name, text(ps, name));

        ConfigurationSection sections = yaml.getConfigurationSection("sections");
        if (sections == null) throw new IllegalArgumentException("no sections");
        List<String> order = yaml.getStringList("order");
        if (order.isEmpty()) order = new ArrayList<>(sections.getKeys(false));

        Song.Builder song = new Song.Builder();
        int cursor = 0;
        for (String sectionName : order) {
            ConfigurationSection section = sections.getConfigurationSection(sectionName);
            if (section == null) throw new IllegalArgumentException("order: unknown section " + sectionName);
            int sectionLength = 0;
            Map<String, Integer> lengths = new LinkedHashMap<>();
            for (String trackName : section.getKeys(false)) {
                Track track = tracks.get(trackName);
                if (track == null) throw new IllegalArgumentException("section " + sectionName + ": unknown track " + trackName);
                String where = sectionName + "/" + trackName;
                String line = expand(text(section, trackName), patterns, where, 0);
                int length = place(song, line, cursor, track, masterVolume, barSteps, where);
                lengths.put(trackName, length);
                sectionLength = Math.max(sectionLength, length);
            }
            for (var e : lengths.entrySet())
                if (e.getValue() != sectionLength)
                    song.warn(sectionName + "/" + e.getKey() + ": " + e.getValue() + " steps, section has " + sectionLength);
            cursor += sectionLength;
        }
        song.length(cursor);
        return song.build(id, yaml.getString("title", id), yaml.getString("author", ""), stepNanos,
                yaml.getBoolean("loop", false));
    }

    /** A score line, written as one string or a list of strings (joined as bars). */
    private static String text(ConfigurationSection s, String key) {
        if (s.isList(key)) return String.join(" | ", s.getStringList(key));
        return s.getString(key, "");
    }

    private static String expand(String line, Map<String, String> patterns, String where, int depth) {
        if (depth > 8) throw new IllegalArgumentException(where + ": patterns nested too deep");
        Matcher m = PATTERN_REF.matcher(line);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String body = patterns.get(m.group(1));
            if (body == null) throw new IllegalArgumentException(where + ": unknown pattern @" + m.group(1));
            int times = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            String once = expand(body, patterns, where, depth + 1);
            m.appendReplacement(out, Matcher.quoteReplacement(" " + (once + " ").repeat(times) + " "));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Places the notes of a line from step {@code start}; returns the line's length in steps. */
    private static int place(Song.Builder song, String line, int start, Track track, float master, int barSteps,
                             String where) {
        int step = 0;
        int barStart = 0;
        int bar = 1;
        boolean folded = false;
        for (String raw : line.trim().split("\\s+")) {
            if (raw.isEmpty()) continue;
            if (raw.equals("|")) {
                int length = step - barStart;
                if (length % barSteps != 0)
                    song.warn(where + ": bar " + bar + " lasts " + length + " steps instead of " + barSteps);
                bar += Math.max(1, length / barSteps);
                barStart = step;
                continue;
            }
            String body = raw;
            int duration = 1;
            int colon = raw.lastIndexOf(':');
            if (colon > 0) {
                body = raw.substring(0, colon);
                try {
                    duration = Integer.parseInt(raw.substring(colon + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(where + ": bad duration in " + raw);
                }
                if (duration < 1) throw new IllegalArgumentException(where + ": bad duration in " + raw);
            }
            if (!body.equals(".") && !body.equals("-")) {
                for (String note : body.split("\\+")) {
                    double midi;
                    if (note.equalsIgnoreCase("x")) {
                        midi = track.instrument().base();
                    } else {
                        midi = midi(note);
                        if (Double.isNaN(midi)) throw new IllegalArgumentException(where + ": bad note " + note);
                        if (!folded && (midi < track.instrument().lowest() || midi > track.instrument().highest())) {
                            song.warn(where + ": " + note + " out of the " + track.instrument().name().toLowerCase()
                                    + " range, moved by an octave");
                            folded = true;
                        }
                    }
                    song.note(start + step, track.instrument(), midi, track.volume() * master);
                }
            }
            step += duration;
        }
        int last = step - barStart;
        if (last > 0 && last % barSteps != 0)
            song.warn(where + ": bar " + bar + " lasts " + last + " steps instead of " + barSteps);
        return step;
    }

    /** MIDI number of a note name (C4 = 60), NaN when unreadable. */
    static double midi(String note) {
        Matcher m = NOTE.matcher(note.trim());
        if (!m.matches()) return Double.NaN;
        int semitone = LETTER[Character.toUpperCase(m.group(1).charAt(0)) - 'A'];
        if (m.group(2).equals("#")) semitone++;
        if (m.group(2).equals("b")) semitone--;
        return (Integer.parseInt(m.group(3)) + 1) * 12 + semitone;
    }
}
