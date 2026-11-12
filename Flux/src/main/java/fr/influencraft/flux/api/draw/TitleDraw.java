package fr.influencraft.flux.api.draw;

import fr.influencraft.flux.api.text.Texts;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;

/**
 * The icon in the middle of the screen, four times its size (a title), changing slower and slower; the name under
 * it. A title never takes the controls: the player keeps playing through it.
 */
final class TitleDraw implements Draws.Animation {

    private static final int STEPS = 24;
    private final Draws.Show show;
    private final List<Draws.Choice> reel;

    TitleDraw(Draws.Show show) {
        this.show = show;
        this.reel = show.reel(STEPS);
    }

    @Override
    public int[] delays() {
        return Draws.wheel(STEPS, 10);
    }

    @Override
    public void step(int index) {
        Draws.Choice c = reel.get(index);
        send(c.sprite().isEmpty() ? "&7?" : c.sprite(), "&7" + show.heading() + " &8» &f" + c.name(), 0, 40, 0);
    }

    @Override
    public void reveal() {
        Draws.Choice w = show.winner();
        send(w.sprite().isEmpty() ? "&#FFD94D✦" : w.sprite(), "&#FFD94D✦ &f" + w.name() + " &#FFD94D✦", 0, 50, 15);
    }

    @Override
    public int hold() {
        return 65;
    }

    private void send(String title, String subtitle, int in, int stay, int out) {
        Title t = Title.title(Texts.parse(title), Texts.parse(subtitle),
                Title.Times.times(Duration.ofMillis(in * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(out * 50L)));
        for (Player p : show.online()) p.showTitle(t);
    }

    @Override
    public void close() {
    }
}
