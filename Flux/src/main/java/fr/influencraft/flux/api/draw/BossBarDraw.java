package fr.influencraft.flux.api.draw;

import fr.influencraft.flux.api.text.Texts;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.Player;

/** Base of the draws shown in a boss bar: one bar shown to every viewer, its text changed at each step. */
abstract class BossBarDraw implements Draws.Animation {

    final Draws.Show show;
    final BossBar bar;

    BossBarDraw(Draws.Show show) {
        this.show = show;
        this.bar = BossBar.bossBar(Texts.parse(""), 0f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        for (Player p : show.online()) p.showBossBar(bar);
    }

    void text(String raw, float progress) {
        bar.name(Texts.parse(raw));
        bar.progress(Math.max(0f, Math.min(1f, progress)));
    }

    /** {@code 『Heading』} prefix, the chevrons of the server's tournament bars. */
    String head() {
        return "&7『&#FFD94D" + show.heading() + "&7』  ";
    }

    @Override
    public void reveal() {
        bar.color(BossBar.Color.GREEN);
        text(head() + "&#FFD94D✦ &f" + show.winner().full() + " &#FFD94D✦", 1f);
    }

    @Override
    public void close() {
        for (Player p : show.online()) p.hideBossBar(bar);
    }
}
