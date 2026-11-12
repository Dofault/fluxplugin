package fr.influencraft.flux.api.draw;

import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.text.Translations;
import net.kyori.adventure.text.Component;

import java.util.Locale;

/**
 * A station board: the icon spins (animated reel texture) and the name is scrambled letters (the game's own
 * obfuscated text, flickering every frame on the client, same width as the real letters); the letters settle one by
 * one from the left, then the icon lands. Letters in the server's language; the reveal shows the client's own name.
 */
final class FlapDraw implements Draws.Animation {

    private static final int SPIN = 22;
    private final Draws.Choice winner;
    private final String letters;

    FlapDraw(java.util.List<Draws.Choice> pool, Draws.Choice winner) {
        this.winner = winner;
        String name = winner.name();
        String plain = null;
        if (name.startsWith("<lang:") && name.endsWith(">")) plain = Translations.server(name.substring(6, name.length() - 1));
        if (plain == null) plain = Texts.plain(name, null);
        letters = plain.toUpperCase(Locale.ROOT);
    }

    @Override
    public Component start() {
        return line(0, false);
    }

    @Override
    public int[] delays() {
        int[] d = new int[letters.length() + 1];
        d[0] = SPIN;
        for (int i = 1; i < d.length; i++) d[i] = 3;
        return d;
    }

    @Override
    public Component step(int index) {
        return line(index, index == letters.length());
    }

    @Override
    public String sound(int index) {
        return index == letters.length() ? "click" : "draw-tick";
    }

    private Component line(int settled, boolean landed) {
        String icon = landed ? winner.sprite() : Draws.REEL;
        String done = letters.substring(0, Math.min(settled, letters.length()));
        String rest = letters.substring(Math.min(settled, letters.length()));
        return Texts.parse(icon + "  &f" + done + "&#5E8FA3&k" + rest.replace(' ', ' ') + "&r  " + icon);
    }
}
