package fr.influencraft.fluxdungeons.rpg.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatMathTest {

    private final Rpg rpg = Rpg.shipped();

    private StatSheet sheet(Random r, boolean extreme) {
        List<Modifier> m = new ArrayList<>();
        double scale = extreme ? 1e4 : 1;
        for (StatRegistry.Def d : rpg.stats.all()) {
            double span = d.kind() == StatRegistry.Kind.PERCENT ? 3 : 5000 * scale;
            m.add(Modifier.add(d.id(), (r.nextDouble() * 2 - 0.5) * span, "random"));
            if (r.nextInt(4) == 0) m.add(Modifier.percent(d.id(), r.nextDouble() * 4 - 1, "random"));
            if (extreme && r.nextInt(8) == 0) m.add(Modifier.multiply(d.id(), r.nextDouble() * 10, "random"));
        }
        return StatSheet.compute(rpg.stats, m);
    }

    /** Thousands of random fighters, extreme ones included: never a negative, infinite or absurd hit. */
    @Test
    void randomHitsStayFiniteAndBounded() {
        Random r = new Random(42);
        String[] families = {null, "undead", "beast"};
        String[] elements = {null, "fire", "frost", "holy"};
        for (int i = 0; i < 200_000; i++) {
            boolean extreme = i % 10 == 0;
            StatSheet a = sheet(r, extreme), d = sheet(r, extreme);
            int level = 1 + r.nextInt(50);
            CombatMath.Type type = CombatMath.Type.values()[r.nextInt(3)];
            CombatMath.Hit hit = new CombatMath.Hit(type, elements[r.nextInt(4)], r.nextDouble() * 5, r.nextDouble() * 50, true, true);
            CombatMath.Outcome o = CombatMath.hit(rpg.balance, a, level, d, families[r.nextInt(3)], hit, r::nextDouble);
            assertTrue(Double.isFinite(o.damage()) && o.damage() >= 0, "damage " + o.damage());
            double power = type == CombatMath.Type.PHYSICAL ? a.get("physical_attack") : type == CombatMath.Type.MAGIC ? a.get("magic_power") : 0;
            double raw = power * hit.coefficient() + hit.flat();
            // upper bound: every bonus at its max (damage bonus 3 + family 1, crit 4, weakness 2) on the raw hit
            double ceiling = Math.max(rpg.balance.minimumDamage(), raw * (1 + 3 + 1) * 4 * 2) + 0.05;
            assertTrue(o.damage() <= ceiling, "damage " + o.damage() + " above " + ceiling);
            // lifesteal capped at 25 % (+ rounding to one decimal)
            assertTrue(o.lifesteal() <= o.damage() * 0.25 + 0.051 && o.lifesteal() >= 0, "lifesteal " + o.lifesteal() + " of " + o.damage());
            if (o.dodged()) assertEquals(0, o.damage());
        }
    }

    @Test
    void critAndDodgeRatesMatchTheirStats() {
        Random r = new Random(7);
        StatSheet attacker = StatSheet.compute(rpg.stats, List.of(Modifier.add("physical_attack", 100, "x"), Modifier.add("crit_chance", 0.25, "x")));
        StatSheet target = StatSheet.compute(rpg.stats, List.of(Modifier.add("dodge", 0.15, "x")));
        int crits = 0, dodges = 0, n = 200_000;
        for (int i = 0; i < n; i++) {
            CombatMath.Outcome o = CombatMath.hit(rpg.balance, attacker, 10, target, null, CombatMath.Hit.physical(1), r::nextDouble);
            if (o.dodged()) dodges++;
            else if (o.crit()) crits++;
        }
        assertEquals(0.15, dodges / (double) n, 0.005);
        assertEquals(0.30, crits / (double) (n - dodges), 0.005);
    }

    @Test
    void moreDefenseAlwaysHelpsButNeverBlocksEverything() {
        StatSheet attacker = StatSheet.compute(rpg.stats, List.of(Modifier.add("physical_attack", 100, "x")));
        double previous = Double.MAX_VALUE;
        for (int def = 0; def <= 100_000; def = def == 0 ? 1 : def * 2) {
            StatSheet target = StatSheet.compute(rpg.stats, List.of(Modifier.add("physical_defense", def, "x")));
            double dmg = CombatMath.hit(rpg.balance, attacker, 20, target, null, new CombatMath.Hit(CombatMath.Type.PHYSICAL, null, 1, 0, false, false), () -> 0.99).damage();
            assertTrue(dmg <= previous, "defense " + def + " did not reduce the hit");
            assertTrue(dmg >= rpg.balance.minimumDamage());
            previous = dmg;
        }
        // the defense that equals K halves the hit
        double k = CombatMath.k(rpg.balance, 20);
        StatSheet half = StatSheet.compute(rpg.stats, List.of(Modifier.add("physical_defense", k, "x")));
        assertEquals(50, CombatMath.hit(rpg.balance, attacker, 20, half, null, new CombatMath.Hit(CombatMath.Type.PHYSICAL, null, 1, 0, false, false), () -> 0.99).damage(), 0.05);
    }

    @Test
    void trueDamageIgnoresDefenseButNotDamageReduction() {
        StatSheet attacker = StatSheet.compute(rpg.stats, List.of());
        StatSheet tank = StatSheet.compute(rpg.stats, List.of(Modifier.add("physical_defense", 1e5, "x"), Modifier.add("magic_defense", 1e5, "x"),
                Modifier.add("dodge", 0.3, "x"), Modifier.add("damage_reduction", 0.2, "x")));
        assertEquals(80, CombatMath.hit(rpg.balance, attacker, 1, tank, null, CombatMath.Hit.pure(100), () -> 0.0).damage(), 1e-9);
    }

    @Test
    void penetrationAndResistanceWork() {
        StatSheet plain = StatSheet.compute(rpg.stats, List.of(Modifier.add("magic_power", 100, "x")));
        StatSheet piercing = StatSheet.compute(rpg.stats, List.of(Modifier.add("magic_power", 100, "x"), Modifier.add("magic_penetration", 0.5, "x")));
        StatSheet target = StatSheet.compute(rpg.stats, List.of(Modifier.add("magic_defense", 200, "x"), Modifier.add("resist.fire", 0.5, "x"), Modifier.add("resist.frost", -0.5, "x")));
        CombatMath.Hit fire = new CombatMath.Hit(CombatMath.Type.MAGIC, "fire", 1, 0, false, false);
        CombatMath.Hit frost = new CombatMath.Hit(CombatMath.Type.MAGIC, "frost", 1, 0, false, false);
        double a = CombatMath.hit(rpg.balance, plain, 10, target, null, fire, () -> 1).damage();
        double b = CombatMath.hit(rpg.balance, piercing, 10, target, null, fire, () -> 1).damage();
        double c = CombatMath.hit(rpg.balance, plain, 10, target, null, frost, () -> 1).damage();
        assertTrue(b > a, "penetration");
        assertEquals(a * 3, c, 0.2);
    }

    @Test
    void healsScaleWithHealingPower() {
        StatSheet h = StatSheet.compute(rpg.stats, List.of(Modifier.add("magic_power", 50, "x"), Modifier.add("healing_power", 0.5, "x")));
        assertEquals(225, CombatMath.heal(h, 3, 0), 1e-9);
    }
}
