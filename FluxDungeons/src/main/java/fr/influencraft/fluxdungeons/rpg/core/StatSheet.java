package fr.influencraft.fluxdungeons.rpg.core;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * The final stats of one combatant, computed from its modifiers in one fixed order (stats.yml documents it):
 *
 * <pre>
 *   value = limit( (base + Σ ADD) × (1 + Σ PERCENT) × Π (1 + MULTIPLY) )
 * </pre>
 *
 * where {@code base} is the stat's base value in stats.yml and {@code limit} its soft cap then its bounds. Immutable:
 * a change of source builds a new sheet (cheap: a few dozen stats).
 */
public final class StatSheet {

    private final StatRegistry registry;
    private final Map<String, Double> values;

    private StatSheet(StatRegistry registry, Map<String, Double> values) {
        this.registry = registry;
        this.values = values;
    }

    /** Modifiers on unknown stats are ignored here: configs are checked against the registry when loaded. */
    public static StatSheet compute(StatRegistry registry, Collection<Modifier> modifiers) {
        Map<String, double[]> acc = new HashMap<>();   // add, percent, multiply
        for (Modifier m : modifiers) {
            if (!registry.has(m.stat()) || !Double.isFinite(m.value())) continue;
            double[] a = acc.computeIfAbsent(m.stat(), k -> new double[]{0, 0, 1});
            switch (m.op()) {
                case ADD -> a[0] += m.value();
                case PERCENT -> a[1] += m.value();
                case MULTIPLY -> a[2] *= Math.max(0, 1 + m.value());
            }
        }
        Map<String, Double> values = new HashMap<>();
        for (StatRegistry.Def d : registry.all()) {
            double[] a = acc.get(d.id());
            double raw = a == null ? d.base() : (d.base() + a[0]) * Math.max(0, 1 + a[1]) * a[2];
            values.put(d.id(), d.limit(raw));
        }
        return new StatSheet(registry, values);
    }

    /** A stat's final value (its base when the stat is unknown to this sheet, 0 when unknown everywhere). */
    public double get(String stat) {
        Double v = values.get(stat);
        if (v != null) return v;
        StatRegistry.Def d = registry.get(stat);
        return d == null ? 0 : d.base();
    }

    public StatRegistry registry() {
        return registry;
    }
}
