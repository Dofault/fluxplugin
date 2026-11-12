package fr.influencraft.flux.api.draw;

import fr.influencraft.flux.api.text.Texts;
import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * A slot machine with three reels: they spin (an animated texture, played smoothly by the client), stop one after
 * the other on the winner; the last one hesitates, icon by icon, before it lands.
 */
final class SlotsDraw implements Draws.Animation {

    private final List<Draws.Choice> last;
    private final Draws.Choice winner;

    SlotsDraw(List<Draws.Choice> pool, Draws.Choice winner) {
        this.winner = winner;
        this.last = Draws.reel(pool, winner, 7);
    }

    @Override
    public Component start() {
        return line(Draws.REEL, Draws.REEL, Draws.REEL);
    }

    @Override
    public int[] delays() {
        // spin, first reel stops, second, then the third shows 6 icons slower and slower and lands
        return new int[]{36, 22, 10, 6, 7, 9, 11, 14, 18};
    }

    @Override
    public Component step(int index) {
        String w = winner.sprite();
        if (index == 0) return line(w, Draws.REEL, Draws.REEL);
        if (index == 1) return line(w, w, Draws.REEL);
        return line(w, w, last.get(Math.min(index - 2, last.size() - 1)).sprite());
    }

    @Override
    public String sound(int index) {
        return index < 2 ? "click" : "draw-tick";
    }

    private static Component line(String a, String b, String c) {
        return Texts.parse("&#2BC4E8┃ " + a + " &#2BC4E8┃ " + b + " &#2BC4E8┃ " + c + " &#2BC4E8┃");
    }
}
