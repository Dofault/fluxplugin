package fr.influencraft.fluxmusic.song;

import java.util.Locale;

/**
 * The sixteen note block instruments of the vanilla client. Each one plays its sound at pitch 1.0 on a given note
 * ({@link #base}, as a MIDI number) and covers two octaves around it (pitch 0.5 to 2.0): a note written outside that
 * range is moved by whole octaves into it.
 */
public enum Instrument {
    HARP("harp", 66, 0),
    BASS("bass", 42, 1),
    BASEDRUM("basedrum", 66, 2),
    SNARE("snare", 66, 3),
    HAT("hat", 66, 4),
    GUITAR("guitar", 54, 5),
    FLUTE("flute", 78, 6),
    BELL("bell", 90, 7),
    CHIME("chime", 90, 8),
    XYLOPHONE("xylophone", 90, 9),
    IRON_XYLOPHONE("iron_xylophone", 66, 10),
    COW_BELL("cow_bell", 78, 11),
    DIDGERIDOO("didgeridoo", 42, 12),
    BIT("bit", 66, 13),
    BANJO("banjo", 66, 14),
    PLING("pling", 66, 15);

    private final String sound;
    /** MIDI note played at pitch 1.0 (an F#). */
    private final int base;
    /** Index in Note Block Studio files. */
    private final int nbs;

    Instrument(String sound, int base, int nbs) {
        this.sound = sound;
        this.base = base;
        this.nbs = nbs;
    }

    /** Sound key, without namespace. */
    public String sound() {
        return "block.note_block." + sound;
    }

    public int base() {
        return base;
    }

    /** Lowest and highest MIDI notes playable without moving the note by an octave. */
    public int lowest() {
        return base - 12;
    }

    public int highest() {
        return base + 12;
    }

    /** Semitones from {@link #base}, moved by octaves into the two playable octaves. */
    public double fold(double semitones) {
        while (semitones < -12) semitones += 12;
        while (semitones > 12) semitones -= 12;
        return semitones;
    }

    /** Pitch of the sound for a MIDI note (fractional notes allowed), moved by octaves into range. */
    public float pitch(double midi) {
        return (float) Math.pow(2, fold(midi - base) / 12.0);
    }

    public static Instrument named(String name) {
        String key = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (key) {
            case "PIANO" -> HARP;
            case "DOUBLE_BASS", "CONTREBASSE" -> BASS;
            case "BASS_DRUM", "KICK" -> BASEDRUM;
            case "CLICK", "HIHAT" -> HAT;
            case "BELLS" -> BELL;
            case "CHIMES" -> CHIME;
            case "COWBELL" -> COW_BELL;
            default -> valueOf(key);
        };
    }

    /** Instrument of a Note Block Studio index, or null for a custom one. */
    public static Instrument ofNbs(int index) {
        for (Instrument i : values()) if (i.nbs == index) return i;
        return null;
    }
}
