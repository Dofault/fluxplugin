package fr.influencraft.fluxdungeons.rpg.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsTest {

    private final Rpg rpg = Rpg.shipped();

    @Test
    void shippedConfigsLoad() {
        assertEquals(List.of("warrior", "archer", "mage", "healer", "cryomancer"), List.copyOf(rpg.classes.keySet()));
        assertTrue(rpg.classes.get("cryomancer").hidden() && !rpg.classes.get("mage").hidden(), "hidden flag read");
        assertEquals(3, rpg.classes.get("cryomancer").passive("chill-max", 0), "passive numbers read");
        assertTrue(rpg.stats.has("damage_vs.undead") && rpg.stats.has("resist.fire"), "templates expanded");
    }

    @Test
    void orderIsAddThenPercentThenMultiply() {
        StatSheet s = StatSheet.compute(rpg.stats, List.of(
                Modifier.add("max_health", 200, "class"), Modifier.add("max_health", 100, "item"),
                Modifier.percent("max_health", 0.10, "item"), Modifier.percent("max_health", 0.15, "set"),
                Modifier.multiply("max_health", 0.5, "boss phase")));
        // (base 1 + 300) x (1 + 0.25) x 1.5
        assertEquals(301 * 1.25 * 1.5, s.get("max_health"), 1e-9);
    }

    @Test
    void percentsAreNeverCompounded() {
        List<Modifier> m = new ArrayList<>();
        m.add(Modifier.add("max_health", 100, "class"));
        for (int i = 0; i < 10; i++) m.add(Modifier.percent("max_health", 0.1, "item " + i));
        assertEquals(101 * 2, StatSheet.compute(rpg.stats, m).get("max_health"), 1e-9);
    }

    @Test
    void softCapThenBounds() {
        // crit_chance: soft cap from 0.5 at rate 0.5, max 0.75
        assertEquals(0.05, StatSheet.compute(rpg.stats, List.of()).get("crit_chance"), 1e-9);
        assertEquals(0.6, StatSheet.compute(rpg.stats, List.of(Modifier.add("crit_chance", 0.65, "x"))).get("crit_chance"), 1e-9);
        assertEquals(0.75, StatSheet.compute(rpg.stats, List.of(Modifier.add("crit_chance", 5, "x"))).get("crit_chance"), 1e-9);
        assertEquals(1, StatSheet.compute(rpg.stats, List.of(Modifier.add("max_health", -500, "x"))).get("max_health"), 1e-9);
    }

    @Test
    void unknownStatsAndBrokenNumbersAreIgnoredAtCompute() {
        StatSheet s = StatSheet.compute(rpg.stats, List.of(Modifier.add("no_such_stat", 5, "x"),
                Modifier.add("max_health", Double.NaN, "x"), Modifier.add("max_health", 10, "x")));
        assertEquals(1 + 10, s.get("max_health"), 1e-9);
    }

    @Test
    void brokenStatsFileIsRefusedWithEveryProblem() {
        List<String> problems = new ArrayList<>();
        Node root = Rpg.yaml("""
                stats:
                  a: {kind: percent, base: 2, min: 0, max: 1}
                  b: {kind: weird}
                  c: {kind: flat, max: -1, min: 0}
                templates:
                  "nothing": {kind: flat}
                """, problems);
        ConfigException e = assertThrows(ConfigException.class, () -> StatRegistry.load(root));
        // a.base, b.kind, c.max, c.base, the template without placeholder
        assertEquals(5, e.problems().size(), e.problems().toString());
    }

    @Test
    void classWithUnknownStatOrMissingSkillIsRefused() {
        List<String> problems = new ArrayList<>();
        Node root = Rpg.yaml("""
                knight:
                  stats: {max_helth: 10}
                  basic: {damage: physical}
                  skills:
                    right: {type: charge}
                """, problems);
        ConfigException e = assertThrows(ConfigException.class, () -> ClassDef.load(root, rpg.stats));
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("max_helth")), e.problems().toString());
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("skills.a")), e.problems().toString());
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("skills.f")), e.problems().toString());
    }
}
