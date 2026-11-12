package fr.influencraft.flux.api.draw;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A split-flap board, as in a station: the icon turns over and over, the letters of the name settle one by one
 * from the left. The letters are the server's language; the revealed name is the client's own translation.
 */
final class FlapDraw extends BossBarDraw {

    private static final String FLAPS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int SPIN = 14;
    private final String letters;
    private final List<Draws.Choice> reel;

    FlapDraw(Draws.Show show) {
        super(show);
        String l = show.winner().letters() == null || show.winner().letters().isBlank() ? show.winner().id() : show.winner().letters();
        letters = l.toUpperCase(Locale.ROOT);
        reel = show.reel(SPIN + letters.length());
    }

    @Override
    public int[] delays() {
        int[] d = new int[SPIN + letters.length()];
        for (int i = 0; i < d.length; i++) d[i] = i < SPIN ? 2 : 3;
        return d;
    }

    @Override
    public void step(int index) {
        int settled = Math.max(0, index - SPIN + 1);
        StringBuilder sb = new StringBuilder(head());
        Draws.Choice c = settled >= letters.length() ? show.winner() : reel.get(Math.min(index, reel.size() - 1));
        sb.append("&f").append(c.sprite().isEmpty() ? "&7■" : c.sprite()).append("  ");
        for (int i = 0; i < letters.length(); i++) {
            char ch = letters.charAt(i);
            if (ch == ' ') sb.append("  ");
            else if (i < settled) sb.append("&f").append(ch);
            else sb.append("&8").append(FLAPS.charAt(ThreadLocalRandom.current().nextInt(FLAPS.length())));
        }
        text(sb.toString(), (index + 1) / (float) delays().length);
    }

    @Override
    public String tick(int index) {
        return index % 2 == 0 ? "draw-tick" : null;
    }
}
