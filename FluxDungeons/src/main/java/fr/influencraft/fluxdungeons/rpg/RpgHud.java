package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.fluxdungeons.rpg.core.ClassDef;
import fr.influencraft.fluxdungeons.rpg.core.CombatMath;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.List;


/**
 * The rpg universe's hud, in the server's one resource pack. Java players: a life bar and a mana bar sent in the
 * action bar, opaque and one pixel larger than the hearts and food rows they cover (font {@code fluxdungeons:hud},
 * assets/gen_hud.py). The vanilla life, food and saturation stay full in that universe (the real ones are the
 * engine's), so the rows underneath never shake nor blink out of the cover. Beside the hotbar, the class's three
 * skills: A and F on its left (the class level on a plate between the bars), the weapon's skill (its click) on its right, each under a key cap, dimmed while the
 * mana does not cover it (assets/hud_spells.py, hud_level.py). Bedrock players: life and mana written in the action bar
 * ({@link Clients#bedrock}).
 */
final class RpgHud {

    private static final Key FONT = Key.key("fluxdungeons", "hud");
    /** In line with assets/gen_hud.py: bar width, space between the bars, digit advance. */
    private static final int STEPS = 40, BAR = 83, GAP = 18, DIGIT = 6;
    /** In line with assets/hud_spells.py: icons by skill type, slot and key cap widths. */
    private static final List<String> SPELLS = List.of("charge", "war_cry", "whirlwind", "leap_back", "volley",
            "arrow_rain", "blink", "fireball", "frost_nova", "heal", "blessing", "sanctuary");
    private static final int BLANK = SPELLS.size(), DIM = BLANK + 1, SLOT = 22, KEY = 9;
    /** Left edge of each skill slot on the line (x 0 = one pixel left of the hotbar, which spans 1 to 183). */
    private static final int SLOT_A = -50, SLOT_F = -25, SLOT_RIGHT = 187;
    /** In line with assets/hud_level.py: the level plate between the bars (x 83 to 100, centre 92). */
    private static final int PLATE = 18, PLATE_X = 83;

    /** What the action bar shows now. */
    Component render(Player p, RpgPlayer r, double hp, double maxHp, double mana, double maxMana) {
        if (Clients.bedrock(p)) return text(hp, maxHp, mana, maxMana);
        StringBuilder b = new StringBuilder();
        int[] cursor = {0};
        ClassDef c = r.heroClass();
        if (c != null) {
            skill(b, cursor, SLOT_A, c.skills().get("a"), 0, r, mana);
            skill(b, cursor, SLOT_F, c.skills().get("f"), 1, r, mana);
            // the weapon's skill: a mouse with the button it is cast with lit (left for the archer, basic.click: right)
            skill(b, cursor, SLOT_RIGHT, c.skills().get("right"), c.key("right").equals("left") ? 3 : 2, r, mana);
        }
        moveTo(b, cursor, 0);
        // life bar over the hearts row (one pixel left of the hotbar's left end)
        b.append((char) (0xE100 + step(hp, maxHp)));
        cursor[0] += BAR + 1;
        numbers(b, cursor, 0, hp, maxHp);
        moveTo(b, cursor, BAR + GAP);
        b.append((char) (0xE200 + step(mana, maxMana)));
        cursor[0] += BAR + 1;
        numbers(b, cursor, BAR + GAP, mana, maxMana);
        level(b, cursor, r.level());
        // the line is centred (its width is where the pen ends, whatever was drawn left or right of it): 184 px,
        // the hotbar's 182 plus one pixel on each side, both bars land on their rows
        moveTo(b, cursor, 2 * BAR + GAP);
        return Component.text(b.toString()).font(FONT).color(NamedTextColor.WHITE);
    }

    /** The class level on its plate between the bars, with the bars' digits (vanilla level number hidden, see RpgPlayers). */
    private static void level(StringBuilder b, int[] cursor, int level) {
        moveTo(b, cursor, PLATE_X);
        b.append('\uE431');
        cursor[0] += PLATE + 1;
        String t = String.valueOf(Math.max(0, level));
        // centred on the screen's centre column (x 92): every number is an odd number of pixels wide
        moveTo(b, cursor, 92 - (t.length() * DIGIT - 1) / 2);
        for (char ch : t.toCharArray()) b.append((char) (0xE300 + ch - '0'));
        cursor[0] += t.length() * DIGIT;
    }

    /** One skill slot at {@code x} with its key cap ({@code key}: 0 = A, 1 = F, 2 = right click, 3 = left click). */
    private static void skill(StringBuilder b, int[] cursor, int x, ClassDef.Skill skill, int key, RpgPlayer r, double mana) {
        if (skill == null) return;
        int icon = SPELLS.indexOf(skill.type());
        if (icon < 0) icon = BLANK;
        // display only: the cast itself checks the mana again
        if (mana < CombatMath.manaCost(skill.mana(), r.stats())) icon += DIM;
        moveTo(b, cursor, x);
        b.append((char) (0xE400 + icon));
        cursor[0] += SLOT + 1;
        moveTo(b, cursor, x + (SLOT - KEY + 1) / 2);
        b.append((char) (0xE420 + key));
        cursor[0] += KEY + 1;
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
