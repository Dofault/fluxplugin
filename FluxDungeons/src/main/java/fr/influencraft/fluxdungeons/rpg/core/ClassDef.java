package fr.influencraft.fluxdungeons.rpg.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A class of {@code rpg/classes.yml}: stats at level 1 and per level, basic attack (left click) and three skills
 * (right click, A, F). Numbers only; how a skill looks and moves is coded per skill {@code type}.
 *
 * <pre>
 * warrior:
 *   stats: {max_health: 300, physical_attack: 18, physical_defense: 25}   # added at level 1
 *   per-level: {max_health: 30, physical_attack: 1.6}                    # added per level above 1
 *   basic: {damage: physical, coefficient: 1.0, targets: 3, range: 3.5}
 *   skills:
 *     right: {type: charge, kind: damage, damage: physical, coefficient: 2.2, cooldown: 8, mana: 20}
 *     a: {type: war_cry, kind: buff, cooldown: 18, mana: 30, duration: 6}
 *     f: {type: whirlwind, kind: damage, damage: physical, coefficient: 1.2, hits: 3, cooldown: 10, mana: 35}
 * </pre>
 */
public record ClassDef(String id, Map<String, Double> stats, Map<String, Double> perLevel, Attack basic,
                       Map<String, Skill> skills) {

    public static final List<String> SLOTS = List.of("right", "a", "f");

    /** The basic attack (left click). */
    public record Attack(CombatMath.Type type, String element, double coefficient, int targets, double range) {
        public CombatMath.Hit hit() {
            return new CombatMath.Hit(type, element, coefficient, 0, true, true);
        }
    }

    /**
     * A skill. {@code kind}: damage, heal, buff, mobility. {@code hits}: number of hits of one cast (a
     * whirlwind hits 3 times), {@code targets}: how many enemies it is made for (for the simulator and the menus),
     * {@code extra}: the type's own numbers (radius, distance, slow...), read by its code.
     */
    public record Skill(String slot, String type, String kind, CombatMath.Type damage, String element, double coefficient,
                        int hits, double cooldown, double mana, int targets, double duration, Map<String, Double> extra) {

        public CombatMath.Hit hit() {
            return new CombatMath.Hit(damage, element, coefficient, 0, true, true);
        }

        public double number(String key, double fallback) {
            return extra.getOrDefault(key, fallback);
        }
    }

    /** Stats of the class at a level (before gear), as modifiers. */
    public List<Modifier> modifiers(int level) {
        List<Modifier> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : stats.entrySet()) out.add(Modifier.add(e.getKey(), e.getValue(), "class"));
        for (Map.Entry<String, Double> e : perLevel.entrySet()) {
            out.add(Modifier.add(e.getKey(), e.getValue() * (Math.max(1, level) - 1), "level"));
        }
        return out;
    }

    public static Map<String, ClassDef> load(Node root, StatRegistry registry) throws ConfigException {
        Map<String, ClassDef> out = new LinkedHashMap<>();
        for (String id : root.keys()) out.put(id, read(id, root.child(id), registry));
        if (out.isEmpty()) root.problems().add(root.path() + ": no class");
        ConfigException.check(root.problems());
        return Collections.unmodifiableMap(out);
    }

    private static ClassDef read(String id, Node n, StatRegistry registry) {
        Map<String, Double> stats = n.required("stats").numbers();
        Map<String, Double> per = n.child("per-level").numbers();
        for (String s : registry.unknown(stats.keySet())) n.problem("stats." + s, "unknown stat");
        for (String s : registry.unknown(per.keySet())) n.problem("per-level." + s, "unknown stat");
        Node b = n.required("basic");
        Attack basic = new Attack(type(b), element(b, registry), b.number("coefficient", 1, 0, 100),
                (int) b.number("targets", 1, 1, 20), b.number("range", 3, 0.5, 40));
        Map<String, Skill> skills = new LinkedHashMap<>();
        Node sk = n.required("skills");
        for (String slot : SLOTS) {
            if (!sk.has(slot)) {
                sk.problem(slot, "missing (every class has right, a and f)");
                continue;
            }
            Node s = sk.child(slot);
            String kind = s.text("kind", "damage");
            if (!List.of("damage", "heal", "buff", "mobility").contains(kind)) s.problem("kind", "damage, heal, buff or mobility");
            Map<String, Double> extra = new LinkedHashMap<>();
            Node ex = s.child("extra");
            for (String k : ex.keys()) extra.put(k, ex.number(k, 0));
            skills.put(slot, new Skill(slot, s.text("type", ""), kind, type(s), element(s, registry),
                    s.number("coefficient", 0, 0, 100), (int) s.number("hits", 1, 1, 50), s.number("cooldown", 1, 0.1, 600),
                    s.number("mana", 0, 0, 10000), (int) s.number("targets", 1, 1, 50), s.number("duration", 0, 0, 600), extra));
            if (s.text("type", "").isEmpty()) s.problem("type", "missing");
        }
        return new ClassDef(id, stats, per, basic, skills);
    }

    private static CombatMath.Type type(Node n) {
        String t = n.text("damage", "physical");
        try {
            return CombatMath.Type.valueOf(t.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            n.problem("damage", "physical, magic or true, not " + t);
            return CombatMath.Type.PHYSICAL;
        }
    }

    private static String element(Node n, StatRegistry registry) {
        String e = n.text("element", null);
        if (e != null && !registry.elements().contains(e)) n.problem("element", "unknown element " + e);
        return e;
    }
}
