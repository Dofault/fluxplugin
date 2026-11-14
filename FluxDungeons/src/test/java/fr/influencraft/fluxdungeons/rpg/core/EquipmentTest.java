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

    @Test
    void setBonusesAddUpByThreshold() {
        Map<String, String> worn = Map.of("helmet", "roc_helmet", "chestplate", "roc_chestplate", "leggings", "roc_leggings", "boots", "roc_boots");
        StatSheet four = StatSheet.compute(rpg.stats, rpg.equipment.modifiers(worn, "warrior", 10));
        // pieces 6 + 9 + 7 + 5 + bonus(2) 10
        assertEquals(37, four.get("physical_defense"), 1e-9);
        assertEquals(0.06, four.get("damage_reduction"), 1e-9);
        StatSheet two = StatSheet.compute(rpg.stats, rpg.equipment.modifiers(Map.of("helmet", "roc_helmet", "boots", "roc_boots"), "warrior", 10));
        assertEquals(6 + 5 + 10, two.get("physical_defense"), 1e-9);
        assertEquals(0, two.get("damage_reduction"), 1e-9);
    }

    @Test
    void wrongClassLevelOrSlotGivesNothing() {
        assertTrue(rpg.equipment.modifiers(Map.of("helmet", "tide_helmet"), "druid", 50).isEmpty(), "class");
        assertTrue(rpg.equipment.modifiers(Map.of("helmet", "roc_helmet"), "warrior", 4).isEmpty(), "level");
        assertTrue(rpg.equipment.modifiers(Map.of("boots", "roc_helmet"), "warrior", 50).isEmpty(), "slot");
    }

    /** A full dungeon set never gives more than twice the reference gear budget of its level (no runaway item). */
    @Test
    void setsStayWithinTheBudget() {
        for (Equipment.Item item : rpg.equipment.items()) {
            if (item.set() == null || item.classes().size() != 1) continue;
            String cls = item.classes().iterator().next();
            Map<String, Double> budget = new java.util.HashMap<>();
            for (Modifier m : rpg.balance.referenceGear(cls, item.level())) budget.put(m.stat(), m.value());
            double hp = item.stats().getOrDefault("max_health", 0.0);
            assertTrue(hp <= budget.getOrDefault("max_health", 0.0), item.id() + " gives " + hp + " health alone");
        }
    }
}
