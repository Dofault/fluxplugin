package fr.influencraft.fluxcrate;

import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * A short tune played with a note block sound ({@code effects.tune} of a crate). Notes are written
 * {@code <name><octave>}, from {@code F#3} to {@code F#5} (the range of a note block), {@code _} is a rest; each
 * {@code -} after a note holds it one more beat (a beat = 2 ticks). Example, the start of « Jingle Bells »:
 * {@code "E4- E4- E4--- E4- E4- E4--- E4- G4- C4-- D4 E4"}.
 */
record Tune(String sound, List<Note> notes) {

    private static final int BEAT = 2;
    private static final String NAMES = "C D EF G A B";

    /** A note: its pitch (0 = rest) and the ticks until the next one. */
    record Note(float pitch, int ticks) {
    }

    /** Null when there is no tune or it cannot be read (logged). */
    static Tune parse(String crate, String sound, String text, Logger log) {
        if (text == null || text.isBlank()) return null;
        List<Note> notes = new ArrayList<>();
        for (String token : text.trim().split("\\s+")) {
            int hold = 0;
            while (token.endsWith("-")) {
                hold++;
                token = token.substring(0, token.length() - 1);
            }
            float pitch = token.equals("_") ? 0 : pitch(token);
            if (pitch < 0) {
                log.warning("Crate " + crate + ": note " + token + " of the tune is not between F#3 and F#5, tune ignored.");
                return null;
            }
            notes.add(new Note(pitch, (1 + hold) * BEAT));
        }
        String key = sound == null || sound.isBlank() ? "minecraft:block.note_block.bell" : sound.trim();
        return notes.isEmpty() ? null : new Tune(key.toLowerCase(Locale.ROOT), List.copyOf(notes));
    }

    /** Pitch of a note block playing this note (F#4 = 1), -1 when unreadable or out of range. */
    private static float pitch(String note) {
        if (note.length() < 2) return -1;
        int semitone = NAMES.indexOf(Character.toUpperCase(note.charAt(0)));
        if (semitone < 0) return -1;
        int i = 1;
        if (note.charAt(i) == '#') {
            semitone++;
            i++;
        }
        int octave;
        try {
            octave = Integer.parseInt(note.substring(i));
        } catch (NumberFormatException e) {
            return -1;
        }
        int midi = 12 * (octave + 1) + semitone;
        if (midi < 54 || midi > 78) return -1;
        return (float) Math.pow(2, (midi - 66) / 12.0);
    }

    /** Plays the tune to this player only. */
    void play(Plugin plugin, Player player) {
        long at = 0;
        for (Note n : notes) {
            if (n.pitch() > 0) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) player.playSound(player.getLocation(), sound, SoundCategory.MASTER, 0.8f, n.pitch());
                }, at);
            }
            at += n.ticks();
        }
    }
}
