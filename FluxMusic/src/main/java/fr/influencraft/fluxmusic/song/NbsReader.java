package fr.influencraft.fluxmusic.song;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a Note Block Studio file ({@code .nbs}, format version 1 to 5, as written by Open Note Block Studio).
 * Custom instruments are skipped; notes outside an instrument's two octaves are moved by an octave.
 */
public final class NbsReader {

    private record RawNote(int tick, int layer, int instrument, int key, int velocity, int cents) {
    }

    private NbsReader() {
    }

    public static Song read(String id, Path file, float masterVolume) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        if (in.getShort() != 0) throw new IOException("old NBS format (before version 1), save it again with Open Note Block Studio");
        int version = in.get() & 0xFF;
        if (version < 1 || version > 5) throw new IOException("NBS version " + version + " not supported");
        int vanillaCount = in.get() & 0xFF;
        int length = version >= 3 ? in.getShort() & 0xFFFF : 0;
        int layerCount = in.getShort() & 0xFFFF;
        String title = string(in);
        String author = string(in);
        String original = string(in);
        string(in); // description
        int tempo = in.getShort() & 0xFFFF; // ticks per second * 100
        in.get(); // auto-saving
        in.get(); // auto-saving duration
        in.get(); // time signature
        in.position(in.position() + 4 * 5); // minutes spent, clicks, blocks added/removed
        string(in); // imported file name
        boolean loop = false;
        int loopStart = 0;
        if (version >= 4) {
            loop = in.get() != 0;
            in.get(); // max loop count
            loopStart = in.getShort() & 0xFFFF;
        }

        List<RawNote> notes = new ArrayList<>();
        int tick = -1;
        for (int jump = in.getShort() & 0xFFFF; jump != 0; jump = in.getShort() & 0xFFFF) {
            tick += jump;
            int layer = -1;
            for (int lj = in.getShort() & 0xFFFF; lj != 0; lj = in.getShort() & 0xFFFF) {
                layer += lj;
                int instrument = in.get() & 0xFF;
                int key = in.get() & 0xFF;
                int velocity = 100;
                int cents = 0;
                if (version >= 4) {
                    velocity = in.get() & 0xFF;
                    in.get(); // panning
                    cents = in.getShort();
                }
                notes.add(new RawNote(tick, layer, instrument, key, velocity, cents));
            }
        }
        int[] layerVolume = new int[layerCount];
        java.util.Arrays.fill(layerVolume, 100);
        for (int i = 0; i < layerCount && in.hasRemaining(); i++) {
            string(in);
            if (version >= 4) in.get(); // lock
            layerVolume[i] = in.get() & 0xFF;
            if (version >= 2) in.get(); // stereo
        }

        Song.Builder song = new Song.Builder();
        int skipped = 0;
        for (RawNote n : notes) {
            Instrument instrument = n.instrument < vanillaCount ? Instrument.ofNbs(n.instrument) : null;
            if (instrument == null) {
                skipped++;
                continue;
            }
            // NBS key 45 is F#4, the harp's pitch 1.0 (MIDI 66): key + 21 = MIDI, shifted to the instrument's octave.
            double midi = n.key + 21 + (instrument.base() - Instrument.HARP.base()) + n.cents / 100.0;
            int lv = n.layer < layerCount ? layerVolume[n.layer] : 100;
            song.note(n.tick, instrument, midi, masterVolume * n.velocity / 100f * lv / 100f);
        }
        if (skipped > 0) song.warn(skipped + " notes of custom instruments skipped");
        song.length(Math.max(length, tick) + 1);
        if (tempo <= 0) tempo = 1000;
        long stepNanos = Math.round(1_000_000_000d * 100 / tempo);
        String by = !author.isBlank() ? author : original;
        return song.build(id, title.isBlank() ? id : title, by, stepNanos, loop, loopStart);
    }

    private static String string(ByteBuffer in) {
        int n = in.getInt();
        if (n < 0 || n > in.remaining()) throw new IllegalStateException("corrupted NBS string");
        byte[] b = new byte[n];
        in.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
