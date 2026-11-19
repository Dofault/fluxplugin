package fr.influencraft.fluxdungeons.rpg.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipmentTest {

    private final Rpg rpg = Rpg.shipped();

    @Test
    void everyClassHasItsWeapon() {
        for (String c : rpg.classes.keySet()) {
            Equipment.Item w = rpg.equipment.item("class_" + c + "_weapon");
            assertTrue(w != null && w.slot().equals("weapon") && w.usableBy(c) && w.level() == 1, c);
        }
    }

    /** The pieces of a set, by slot (the dungeon sets of gear.py: fluxrpg:<dungeon>_<piece>). */
    private Map<String, String> set(String id) {
        Map<String, String> worn = new java.util.HashMap<>();
        for (Equipment.Item item : rpg.equipment.items()) if (id.equals(item.set())) worn.put(item.slot(), item.id());
        return worn;
    }

    private double sum(Map<String, String> worn, String stat) {
        double v = 0;
        for (String id : worn.values()) v += rpg.equipment.item(id).stats().getOrDefault(stat, 0.0);
        return v;
    }

    @Test
    void setBonusesAddUpByThreshold() {
        Map<String, String> worn = set("rochiste");
        assertEquals(4, worn.size(), "the Rochiste set has four pieces");
        StatSheet four = StatSheet.compute(rpg.stats, rpg.equipment.modifiers(worn, "cryomancer", 50));
        StatSheet none = StatSheet.compute(rpg.stats, List.of());
        double bonus2 = 0;
        // physical defense: the pieces plus the bonus at 2 pieces
        StatSheet two = StatSheet.compute(rpg.stats, rpg.equipment.modifiers(Map.of("helmet", worn.get("helmet"), "boots", worn.get("boots")), "cryomancer", 50));
        bonus2 = two.get("physical_defense") - rpg.equipment.item(worn.get("helmet")).stats().get("physical_defense")
                - rpg.equipment.item(worn.get("boots")).stats().get("physical_defense");
        assertTrue(bonus2 > 0, "bonus at 2 pieces");
        assertEquals(sum(worn, "physical_defense") + bonus2, four.get("physical_defense"), 1e-9);
        // the bonus at 4 pieces only with four
        assertTrue(four.get("damage_reduction") > sum(worn, "damage_reduction") + none.get("damage_reduction") + 1e-9, "bonus at 4 pieces");
        assertEquals(none.get("damage_reduction") + rpg.equipment.item(worn.get("helmet")).stats().getOrDefault("damage_reduction", 0.0)
                + rpg.equipment.item(worn.get("boots")).stats().getOrDefault("damage_reduction", 0.0), two.get("damage_reduction"), 1e-9);
    }

    @Test
    void wrongClassLevelOrSlotGivesNothing() {
        Map<String, String> worn = set("rochiste");
        assertTrue(rpg.equipment.modifiers(Map.of("weapon", "class_cryomancer_weapon"), "druid", 50).isEmpty(), "class");
        assertTrue(rpg.equipment.modifiers(Map.of("helmet", worn.get("helmet")), "cryomancer", 9).isEmpty(), "level");
        assertTrue(rpg.equipment.modifiers(Map.of("boots", worn.get("helmet")), "cryomancer", 50).isEmpty(), "slot");
    }

    /**
     * A full dungeon set never gives more than twice the reference gear's life of its level (no runaway set), and the
     * sets of the tower never go down from a floor to the next one in level.
     */
    @Test
    void setsStayWithinTheBudget() {
        Map<String, Map<String, String>> sets = new java.util.HashMap<>();
        for (Equipment.Item item : rpg.equipment.items()) {
            if (item.set() != null) sets.computeIfAbsent(item.set(), k -> new java.util.HashMap<>()).put(item.slot(), item.id());
        }
        assertEquals(10, sets.size(), "one set per floor of the tower");
        for (var e : sets.entrySet()) {
            int level = rpg.equipment.item(e.getValue().values().iterator().next()).level();
            double ref = 0;
            for (String cls : List.of("cryomancer", "pyromancer", "druid")) {
                for (Modifier m : rpg.balance.referenceGear(cls, level)) if (m.stat().equals("max_health")) ref += m.value() / 3;
            }
            double hp = sum(e.getValue(), "max_health");
            assertTrue(hp <= 2 * ref, e.getKey() + " gives " + hp + " health, budget " + ref);
        }
    }
}
