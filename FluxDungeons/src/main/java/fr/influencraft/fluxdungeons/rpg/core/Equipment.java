package fr.influencraft.fluxdungeons.rpg.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Items of the rpg universe ({@code rpg/equipment.yml}): their slot, classes, level, rarity, stats and set, and the
 * bonuses of the sets. The look of each item (material, name, model) is in its {@code look} section, made into a
 * Flux item ({@code fluxrpg:<id>}) by the plugin; its description is written from these numbers, never by hand.
 *
 * <pre>
 * rarities:
 *   rare: {color: "#5A85EA", order: 3}
 * items:
 *   roc_helmet:
 *     slot: helmet                      # weapon, helmet, chestplate, leggings, boots
 *     classes: [warrior]                # empty = every class
 *     level: 5
 *     rarity: rare
 *     set: roc
 *     stats: {max_health: 40, physical_defense: 6}
 *     look: {material: IRON_HELMET, name: "Heaume de roc", model: ""}
 * sets:
 *   roc:
 *     bonuses:
 *       2: {physical_defense: 10}
 *       4: {damage_reduction: 0.08, tenacity: 0.15}
 * </pre>
 */
public final class Equipment {

    public static final List<String> SLOTS = List.of("weapon", "helmet", "chestplate", "leggings", "boots");

    public record Rarity(String id, String color, int order) {
    }

    public record Item(String id, String slot, Set<String> classes, int level, Rarity rarity, String set,
                       Map<String, Double> stats, List<String> description, Map<String, Object> look) {

        public boolean usableBy(String classId) {
            return classes.isEmpty() || classes.contains(classId);
        }
    }

    public record ItemSet(String id, String name, Map<Integer, Map<String, Double>> bonuses) {
    }

    private final Map<String, Rarity> rarities;
    private final Map<String, Item> items;
    private final Map<String, ItemSet> sets;

    private Equipment(Map<String, Rarity> rarities, Map<String, Item> items, Map<String, ItemSet> sets) {
        this.rarities = Collections.unmodifiableMap(rarities);
        this.items = Collections.unmodifiableMap(items);
        this.sets = Collections.unmodifiableMap(sets);
    }

    @SuppressWarnings("unchecked")
    public static Equipment load(Node root, StatRegistry stats, Collection<String> classIds) throws ConfigException {
        Map<String, Rarity> rarities = new LinkedHashMap<>();
        Node r = root.required("rarities");
        for (String id : r.keys()) {
            Node n = r.child(id);
            String color = n.text("color", "#FFFFFF");
            if (!color.matches("#[0-9A-Fa-f]{6}")) n.problem("color", "a colour #RRGGBB");
            rarities.put(id, new Rarity(id, color, (int) n.number("order", rarities.size() + 1, 0, 100)));
        }
        Map<String, ItemSet> sets = new LinkedHashMap<>();
        Node s = root.child("sets");
        for (String id : s.keys()) {
            Node n = s.child(id);
            Map<Integer, Map<String, Double>> bonuses = new TreeMap<>();
            Node b = n.required("bonuses");
            for (String count : b.keys()) {
                int pieces;
                try {
                    pieces = Integer.parseInt(count);
                } catch (NumberFormatException e) {
                    b.problem(count, "a number of pieces");
                    continue;
                }
                Map<String, Double> bonus = b.child(count).numbers();
                for (String bad : stats.unknown(bonus.keySet())) b.problem(count + "." + bad, "unknown stat");
                bonuses.put(pieces, bonus);
            }
            sets.put(id, new ItemSet(id, n.text("name", id), bonuses));
        }
        Map<String, Item> items = new LinkedHashMap<>();
        Node all = root.required("items");
        for (String id : all.keys()) {
            Node n = all.child(id);
            String slot = n.text("slot", "");
            if (!SLOTS.contains(slot)) n.problem("slot", "one of " + SLOTS);
            Set<String> classes = new java.util.LinkedHashSet<>(n.texts("classes"));
            for (String c : classes) if (!classIds.contains(c)) n.problem("classes", "unknown class " + c);
            Rarity rarity = rarities.get(n.text("rarity", "common"));
            if (rarity == null) {
                n.problem("rarity", "unknown rarity " + n.text("rarity", "common"));
                rarity = new Rarity("common", "#FFFFFF", 1);
            }
            String set = n.text("set", null);
            if (set != null && !sets.containsKey(set)) n.problem("set", "unknown set " + set);
            Map<String, Double> st = n.child("stats").numbers();
            for (String bad : stats.unknown(st.keySet())) n.problem("stats." + bad, "unknown stat");
            Node look = n.required("look");
            if (!look.has("material")) look.problem("material", "missing");
            Map<String, Object> lookMap = new LinkedHashMap<>();
            for (String k : look.keys()) lookMap.put(k, look.text(k, ""));
            items.put(id, new Item(id, slot, classes, (int) n.number("level", 1, 1, 1000), rarity, set, st,
                    n.texts("description"), lookMap));
        }
        ConfigException.check(root.problems());
        return new Equipment(rarities, items, sets);
    }

    public Item item(String id) {
        return items.get(id);
    }

    public Collection<Item> items() {
        return items.values();
    }

    public ItemSet set(String id) {
        return sets.get(id);
    }

    public Map<String, Rarity> rarities() {
        return rarities;
    }

    /**
     * Modifiers given by what a player wears and holds: each item counts once per slot, only when usable by his class
     * at his level; set bonuses add up every threshold reached ({@code 2} and {@code 4} with four pieces).
     */
    public List<Modifier> modifiers(Map<String, String> equippedBySlot, String classId, int level) {
        List<Modifier> out = new ArrayList<>();
        Map<String, Integer> pieces = new HashMap<>();
        for (Map.Entry<String, String> e : equippedBySlot.entrySet()) {
            Item item = items.get(e.getValue());
            if (item == null || !item.slot().equals(e.getKey()) || !item.usableBy(classId) || item.level() > level) continue;
            item.stats().forEach((stat, v) -> out.add(Modifier.add(stat, v, "item:" + item.id())));
            if (item.set() != null) pieces.merge(item.set(), 1, Integer::sum);
        }
        pieces.forEach((setId, count) -> {
            ItemSet set = sets.get(setId);
            if (set == null) return;
            set.bonuses().forEach((needed, bonus) -> {
                if (count >= needed) bonus.forEach((stat, v) -> out.add(Modifier.add(stat, v, "set:" + setId + ":" + needed)));
            });
        });
        return out;
    }
}
