package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.flux.api.text.Texts;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;


/**
 * The rpg universe's hud, in the server's one resource pack. Java players: a life bar and a mana bar sent in the
 * action bar, opaque and one pixel larger than the hearts and food rows they cover (font {@code fluxdungeons:hud},
 * assets/gen_hud.py). The vanilla life, food and saturation stay full in that universe (the real ones are the
 * engine's), so the rows underneath never shake nor blink out of the cover. Bedrock players: life and mana written in
 * the action bar ({@link Clients#bedrock}).
 */
final class RpgHud {

    private static final Key FONT = Key.key("fluxdungeons", "hud");
    /** In line with assets/gen_hud.py: bar width, space between the bars, digit advance. */
    private static final int STEPS = 40, BAR = 83, GAP = 18, DIGIT = 6;

    /** What the action bar shows now. */
    Component render(Player p, double hp, double maxHp, double mana, double maxMana) {
        if (Clients.bedrock(p)) return text(hp, maxHp, mana, maxMana);
        StringBuilder b = new StringBuilder();
        int[] cursor = {0};
        // life bar over the hearts row (one pixel left of the hotbar's left end)
        b.append((char) (0xE100 + step(hp, maxHp)));
        cursor[0] += BAR + 1;
        numbers(b, cursor, 0, hp, maxHp);
        moveTo(b, cursor, BAR + GAP);
        b.append((char) (0xE200 + step(mana, maxMana)));
        cursor[0] += BAR + 1;
        numbers(b, cursor, BAR + GAP, mana, maxMana);
        // the line is centred: 184 px, the hotbar's 182 plus one pixel on each side, both bars land on their rows
        moveTo(b, cursor, 2 * BAR + GAP);
        return Component.text(b.toString()).font(FONT).color(NamedTextColor.WHITE);
    }

    private static int step(double v, double max) {
        if (max <= 0) return 0;
        int s = (int) Math.ceil(Math.max(0, Math.min(1, v / max)) * STEPS);
        return v > 0 ? Math.max(1, s) : 0;
    }

    /** "current/max" centred inside the bar starting at {@code barX}. */
    private static void numbers(StringBuilder b, int[] cursor, int barX, double v, double max) {
        String t = Math.round(Math.max(0, v)) + "/" + Math.round(max);
        int width = t.length() * DIGIT - 1;
        moveTo(b, cursor, barX + Math.max(0, (BAR - width) / 2));
        for (char c : t.toCharArray()) b.append((char) (0xE300 + (c == '/' ? 10 : c - '0')));
        cursor[0] += t.length() * DIGIT;
    }

    /** Moves the pen to {@code x} with space glyphs (powers of two, back or forward). */
    private static void moveTo(StringBuilder b, int[] cursor, int x) {
        int d = x - cursor[0];
        int base = d < 0 ? 0xF001 : 0xF011;
        int n = Math.abs(d);
        for (int i = 7; i >= 0; i--) {
            while (n >= (1 << i)) {
                b.append((char) (base + i));
                n -= 1 << i;
            }
        }
        cursor[0] = x;
    }

    /** Bedrock (or no overlay): plain text. */
    private Component text(double hp, double maxHp, double mana, double maxMana) {
        return Texts.colors("&#FF5C5C❤ &f" + Math.round(hp) + "&7/" + Math.round(maxHp)
                + "   &#2BC4E8✦ &f" + Math.round(mana) + "&7/" + Math.round(maxMana));
    }
}
