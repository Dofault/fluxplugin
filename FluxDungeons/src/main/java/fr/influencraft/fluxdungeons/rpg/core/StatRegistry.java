package fr.influencraft.fluxdungeons.rpg.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every stat of the rpg, defined once in {@code stats.yml}: the single source of truth for ids, kinds, base values,
 * bounds and soft caps. Nothing else in the code knows a stat's limits.
 *
 * <pre>
 * families: [undead, beast, construct, elemental]     # damage_vs.&lt;family&gt; stats
 * elements: [fire, frost, lightning, holy, shadow]     # resist.&lt;element&gt; stats
 * stats:
 *   crit_chance:
 *     category: offense          # offense, defense, resource, utility (menu grouping)
 *     kind: percent              # flat (a number) or percent (0.25 = 25 %)
 *     base: 0.05                 # value of every combatant before any source
 *     min: 0
 *     max: 0.75                  # hard bound
 *     soft-cap: {from: 0.5, rate: 0.5}   # above 50 %, each extra point counts half
 * templates:                     # one stat per family / element
 *   "damage_vs.{family}": {category: offense, kind: percent, base: 0, min: 0, max: 1}
 *   "resist.{element}": {category: defense, kind: percent, base: 0, min: -1, max: 0.75}
 * </pre>
 */
public final class StatRegistry {

    public enum Kind { FLAT, PERCENT }

    /** A stat's definition. {@code softFrom} = NaN when it has no soft cap. */
    public record Def(String id, String category, Kind kind, double base, double min, double max, double softFrom, double softRate) {

        /** Soft cap then hard bounds: the only place a stat value is limited. */
        public double limit(double value) {
            double v = value;
            if (!Double.isNaN(softFrom) && v > softFrom) v = softFrom + (v - softFrom) * softRate;
            return Math.max(min, Math.min(max, v));
        }
    }

    private final Map<String, Def> stats;
    private final List<String> families, elements;

    private StatRegistry(Map<String, Def> stats, List<String> families, List<String> elements) {
        this.stats = Collections.unmodifiableMap(stats);
        this.families = List.copyOf(families);
        this.elements = List.copyOf(elements);
    }

    public static StatRegistry load(Node root) throws ConfigException {
        List<String> families = root.texts("families");
        List<String> elements = root.texts("elements");
        Map<String, Def> stats = new LinkedHashMap<>();
        Node section = root.required("stats");
        for (String id : section.keys()) stats.put(id, def(id, section.child(id)));
        Node templates = root.child("templates");
        for (String pattern : templates.keys()) {
            Node t = templates.child(pattern);
            List<String> values = pattern.contains("{family}") ? families : pattern.contains("{element}") ? elements : null;
            if (values == null) {
                templates.problem(pattern, "a template needs {family} or {element}");
                continue;
            }
            for (String v : values) {
                String id = pattern.replace("{family}", v).replace("{element}", v);
                stats.put(id, def(id, t));
            }
        }
        ConfigException.check(root.problems());
        return new StatRegistry(stats, families, elements);
    }

    private static Def def(String id, Node n) {
        String kindText = n.text("kind", "flat");
        Kind kind;
        try {
            kind = Kind.valueOf(kindText.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            n.problem("kind", "flat or percent, not " + kindText);
            kind = Kind.FLAT;
        }
        double min = n.number("min", 0);
        double max = n.number("max", Double.MAX_VALUE);
        double base = n.number("base", 0);
        if (max < min) n.problem("max", "lower than min");
        if (base < min || base > max) n.problem("base", base + " outside [min, max]");
        double softFrom = Double.NaN, softRate = 1;
        if (n.has("soft-cap")) {
            Node s = n.child("soft-cap");
            softFrom = s.number("from");
            softRate = s.number("rate", 0.5, 0, 1);
        }
        String category = n.text("category", "utility");
        if (!List.of("offense", "defense", "resource", "utility").contains(category)) n.problem("category", "offense, defense, resource or utility");
        return new Def(id, category, kind, base, min, max, softFrom, softRate);
    }

    public Def get(String id) {
        return stats.get(id);
    }

    public boolean has(String id) {
        return stats.containsKey(id);
    }

    public Collection<Def> all() {
        return stats.values();
    }

    public List<String> families() {
        return families;
    }

    public List<String> elements() {
        return elements;
    }

    /** Ids of a section of stats that are unknown (a config refers to a stat that does not exist). */
    public List<String> unknown(Iterable<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) if (!stats.containsKey(id)) out.add(id);
        return out;
    }
}
