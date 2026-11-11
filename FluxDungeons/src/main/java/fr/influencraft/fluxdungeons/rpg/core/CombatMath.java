package fr.influencraft.fluxdungeons.rpg.core;

import java.util.function.DoubleSupplier;

/**
 * The damage, heal and shield formulas of the rpg: the only place they are written. Pure functions; the random draws
 * come from a supplier so tests replay thousands of hits exactly. The order is documented in docs/technique/rpg.md §5.
 */
public final class CombatMath {

    public enum Type { PHYSICAL, MAGIC, TRUE }

    /**
     * What a hit is, before any stat: its type, element (null = none), coefficient of the attacker's attack or magic
     * power, flat part, and whether it may crit or be dodged.
     */
    public record Hit(Type type, String element, double coefficient, double flat, boolean canCrit, boolean canDodge) {

        public static Hit physical(double coefficient) {
            return new Hit(Type.PHYSICAL, null, coefficient, 0, true, true);
        }

        public static Hit magic(double coefficient, String element) {
            return new Hit(Type.MAGIC, element, coefficient, 0, true, true);
        }

        /** Ignores defenses, crits and dodge (environment, a share of max life). */
        public static Hit pure(double amount) {
            return new Hit(Type.TRUE, null, 0, amount, false, false);
        }
    }

    public record Outcome(double damage, boolean crit, boolean dodged, double lifesteal, double manasteal) {
        static final Outcome DODGED = new Outcome(0, false, true, 0, 0);
    }

    private CombatMath() {
    }

    /** Defense constant of an attacker: the defense that halves its hits. Grows with its level (Balance). */
    public static double k(Balance b, int attackerLevel) {
        return b.kBase() + b.kPerLevel() * Math.max(1, attackerLevel);
    }

    /** Share of a hit stopped by this defense: {@code d / (d + K)}, never 100 %. */
    public static double mitigation(double defense, double k) {
        if (defense <= 0) return 0;
        return defense / (defense + k);
    }

    /**
     * One hit from {@code attacker} (level {@code level}) on {@code target} (of {@code family}, null = none).
     * <ol>
     *   <li>raw = attack or magic power × coefficient + flat;</li>
     *   <li>× (1 + damage bonus + bonus against the target's family);</li>
     *   <li>dodge (whole hit avoided);</li>
     *   <li>defense after penetration: × (1 − d/(d+K));</li>
     *   <li>× (1 − elemental resistance) (negative = weakness);</li>
     *   <li>crit: × (1 + (crit damage − 1) × (1 − crit resistance));</li>
     *   <li>× (1 − damage reduction), then at least the minimum damage.</li>
     * </ol>
     * True damage skips steps 2 to 6.
     */
    public static Outcome hit(Balance b, StatSheet attacker, int level, StatSheet target, String family, Hit hit, DoubleSupplier rng) {
        double raw;
        if (hit.type() == Type.TRUE) {
            raw = hit.flat();
            raw *= 1 - target.get("damage_reduction");
            return finish(b, attacker, raw, false);
        }
        double power = hit.type() == Type.PHYSICAL ? attacker.get("physical_attack") : attacker.get("magic_power");
        raw = power * hit.coefficient() + hit.flat();
        double bonus = attacker.get("damage_bonus");
        if (family != null) bonus += attacker.get("damage_vs." + family);
        raw *= 1 + bonus;
        if (hit.canDodge() && rng.getAsDouble() < target.get("dodge")) return Outcome.DODGED;
        boolean physical = hit.type() == Type.PHYSICAL;
        double defense = target.get(physical ? "physical_defense" : "magic_defense");
        double pen = attacker.get(physical ? "armor_penetration" : "magic_penetration");
        raw *= 1 - mitigation(defense * (1 - pen), k(b, level));
        if (hit.element() != null) raw *= 1 - target.get("resist." + hit.element());
        boolean crit = hit.canCrit() && rng.getAsDouble() < attacker.get("crit_chance");
        if (crit) raw *= 1 + (attacker.get("crit_damage") - 1) * (1 - target.get("crit_resistance"));
        raw *= 1 - target.get("damage_reduction");
        return finish(b, attacker, raw, crit);
    }

    private static Outcome finish(Balance b, StatSheet attacker, double raw, boolean crit) {
        double damage = raw <= 0 ? 0 : Math.max(b.minimumDamage(), round(raw));
        return new Outcome(damage, crit, false, round(damage * attacker.get("lifesteal")), round(damage * attacker.get("manasteal")));
    }

    /** A heal: (magic power × coefficient + flat) × (1 + healing power). Heals never crit (predictable support). */
    public static double heal(StatSheet healer, double coefficient, double flat) {
        return round(Math.max(0, healer.get("magic_power") * coefficient + flat) * (1 + healer.get("healing_power")));
    }

    /** A shield: (magic power × coefficient + flat) × (1 + shield power). */
    public static double shield(StatSheet caster, double coefficient, double flat) {
        return round(Math.max(0, caster.get("magic_power") * coefficient + flat) * (1 + caster.get("shield_power")));
    }

    /** Duration of an effect: × (1 + the caster's effect duration), × (1 − the target's tenacity) when harmful. */
    public static double duration(double seconds, StatSheet caster, StatSheet target, boolean harmful) {
        double d = seconds * (1 + (caster == null ? 0 : caster.get("effect_duration")));
        if (harmful && target != null) d *= 1 - target.get("tenacity");
        return Math.max(0, d);
    }

    /** Seconds between two basic attacks. */
    public static double attackInterval(StatSheet s) {
        return 1 / Math.max(0.01, s.get("attack_speed"));
    }

    public static double cooldown(double seconds, StatSheet s) {
        return seconds * (1 - s.get("cooldown_reduction"));
    }

    public static double manaCost(double mana, StatSheet s) {
        return mana * (1 - s.get("cost_reduction"));
    }

    /** One decimal: damage shown to players is what was dealt. */
    public static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
