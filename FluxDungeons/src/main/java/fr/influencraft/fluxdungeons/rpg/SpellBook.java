package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.fluxdungeons.pages.PageKit;
import fr.influencraft.fluxdungeons.rpg.core.ClassDef;
import fr.influencraft.fluxdungeons.rpg.core.CombatMath;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The spells of a class as players read them, one source for every place that shows them (the class pages, the
 * « spells » line of /donjon): the A spell first, then F, the weapon's spell, the basic attack, then the class's own
 * mechanic (passive), each with its key, name, text (the numbers of classes.yml put in the lang file's sentences),
 * mana cost and icon (an item of the pack fluxdonjon, assets/spell_items.py).
 */
public final class SpellBook {

    /** Texts of the lang file, variables replaced. */
    @FunctionalInterface
    public interface Texts {
        String raw(String key, String... vars);
    }

    /**
     * One spell. {@code key}: the key's word in {@code class-pages.keys} (a, f, left, right, hold, passive);
     * {@code mana}: its cost, 0 for free (the basic attack), -1 for a passive; {@code icon}: its item.
     */
    public record Spell(String slot, String key, String name, String text, double mana, String icon) {
    }

    /** Pack of the spell icons. */
    public static final String PACK = "fluxdonjon";

    private SpellBook() {
    }

    /** Marks around a value inside a text, replaced at the end (colour codes would be taken off by PageKit.plain). */
    private static final char ON = '\u0001', OFF = '\u0002';

    /** The spells, plain texts (the class pages). */
    public static List<Spell> of(ClassDef c, Texts texts) {
        return of(c, texts, "", "");
    }

    /**
     * The spells, each value of their texts (numbers, durations, ranges) between {@code valueOn} and {@code valueOff}:
     * colour codes in a menu (the values in white, the sentence in its own colour).
     */
    public static List<Spell> of(ClassDef c, Texts texts, String valueOn, String valueOff) {
        List<Spell> out = new ArrayList<>();
        for (Spell sp : spells(c, texts)) {
            out.add(new Spell(sp.slot(), sp.key(), sp.name(),
                    sp.text().replace(String.valueOf(ON), valueOn).replace(String.valueOf(OFF), valueOff), sp.mana(), sp.icon()));
        }
        return out;
    }

    private static List<Spell> spells(ClassDef c, Texts texts) {
        List<Spell> out = new ArrayList<>();
        String id = c.id();
        for (String slot : ClassDef.SHOWN) {
            if (slot.equals("basic")) {
                ClassDef.Attack b = c.basic();
                out.add(new Spell("basic", c.basicOnRight() ? "hold" : "left", name(texts, id, "basic"),
                        text(texts, "class-pages.classes." + id + ".basic.description",
                                vars(texts, b.type(), b.coefficient(), 1, b.targets(), 0, 0, b.range(), Map.of())),
                        0, PACK + ":attaque_" + b.style()));
                continue;
            }
            ClassDef.Skill s = c.skills().get(slot);
            if (s == null) continue;
            out.add(new Spell(slot, c.key(slot), name(texts, id, slot),
                    text(texts, "class-pages.classes." + id + "." + slot + ".description",
                            // heals scale with magic power (CombatMath.heal), whatever the skill's damage type
                            vars(texts, s.kind().equals("heal") ? CombatMath.Type.MAGIC : s.damage(), s.coefficient(), s.hits(),
                                    s.targets(), s.duration(), s.mana(), c.basic().range(), s.extra())),
                    s.mana(), PACK + ":sort_" + s.type()));
        }
        // the class's own mechanic (elemental classes), with its numbers
        if (!c.passive().isEmpty()) {
            out.add(new Spell("passive", "passive", name(texts, id, "passive"),
                    text(texts, "class-pages.classes." + id + ".passive.description",
                            vars(texts, c.basic().type(), 0, 1, 1, 0, 0, c.basic().range(), c.passive())),
                    -1, PACK + ":passif_" + id));
        }
        return out;
    }

    private static String value(String v) {
        return ON + v + OFF;
    }

    private static String name(Texts texts, String classId, String slot) {
        return PageKit.plain(texts.raw("class-pages.classes." + classId + "." + slot + ".name"));
    }

    private static String text(Texts texts, String key, String[] vars) {
        return PageKit.plain(texts.raw(key, vars));
    }

    private static String[] vars(Texts texts, CombatMath.Type type, double coef, int hits, int targets, double duration,
                                 double mana, double range, Map<String, Double> extra) {
        List<String> v = new ArrayList<>(List.of(
                "%coef%", value(ClassPages.num(coef * 100) + " %"),
                "%power%", texts.raw("class-pages.powers." + (type == CombatMath.Type.MAGIC ? "magic" : "physical")),
                "%hits%", value(String.valueOf(Math.max(1, hits))),
                "%targets%", value(String.valueOf(Math.max(1, targets))),
                "%duration%", value(ClassPages.num(duration)),
                "%mana%", value(ClassPages.num(mana)),
                "%range%", value(ClassPages.num(extra.getOrDefault("range", range)))));
        for (Map.Entry<String, Double> e : extra.entrySet()) {
            v.add("%" + e.getKey() + "%");
            v.add(value(ClassPages.num(e.getValue())));
            v.add("%" + e.getKey() + "_pct%");
            v.add(value(ClassPages.num(e.getValue() * 100) + " %"));
        }
        return v.toArray(String[]::new);
    }
}
