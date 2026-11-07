package fr.influencraft.fluxdungeons.hero;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A class of {@code classes.yml}: stats at level 1 and per level, mana, starting kit, three abilities.
 *
 * <pre>
 * warrior:
 *   name: "Warrior"
 *   color: "#FF5C5C"
 *   icon: IRON_SWORD
 *   description: ["Holds the front line."]
 *   stats: {max-health: 6, attack: 1, armor: 2, speed: 0}     # added to the player's own values
 *   per-level: {max-health: 0.4, attack: 0.08}
 *   mana: {max: 100, regen: 5}                                # regen per second
 *   kit: [fluxdungeons:warrior_sword, ...]
 *   abilities:
 *     - {type: charge, name: "Charge", icon: FEATHER, mana: 25, cooldown: 8, power: 6}
 * </pre>
 */
public record HeroClass(String id, String name, String color, Material icon, List<String> description,
                        Map<String, Double> stats, Map<String, Double> perLevel, double mana, double manaRegen,
                        List<String> kit, List<AbilitySpec> abilities) {

    /** One ability of a class: its behaviour ({@code type}, see {@link Abilities}) and its numbers. */
    public record AbilitySpec(String type, String name, Material icon, List<String> description, double mana,
                              double cooldown, Map<String, Double> params) {

        public double param(String key, double fallback) {
            return params.getOrDefault(key, fallback);
        }
    }

    public static HeroClass read(String id, ConfigurationSection c) {
        List<AbilitySpec> abilities = new ArrayList<>();
        for (Map<?, ?> a : c.getMapList("abilities")) {
            Map<String, Double> params = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : a.entrySet()) {
                if (e.getValue() instanceof Number n) params.put(String.valueOf(e.getKey()), n.doubleValue());
            }
            Object desc = a.get("description");
            List<String> lines = desc instanceof List<?> l ? l.stream().map(String::valueOf).toList()
                    : desc == null ? List.of() : List.of(String.valueOf(desc));
            abilities.add(new AbilitySpec(String.valueOf(a.get("type")).toLowerCase(Locale.ROOT),
                    String.valueOf(a.containsKey("name") ? a.get("name") : a.get("type")),
                    material(a.get("icon") == null ? null : String.valueOf(a.get("icon")), Material.BOOK), lines,
                    params.getOrDefault("mana", 0.0), params.getOrDefault("cooldown", 5.0), params));
        }
        return new HeroClass(id, c.getString("name", id), c.getString("color", "#FFD94D"),
                material(c.getString("icon"), Material.IRON_SWORD), c.getStringList("description"),
                numbers(c.getConfigurationSection("stats")), numbers(c.getConfigurationSection("per-level")),
                c.getDouble("mana.max", 100), c.getDouble("mana.regen", 5), c.getStringList("kit"), abilities);
    }

    /** A stat at this level: base + per-level × (level - 1). */
    public double stat(String key, int level) {
        return stats.getOrDefault(key, 0.0) + perLevel.getOrDefault(key, 0.0) * (level - 1);
    }

    /** Name in the class colour. */
    public String colored() {
        return "&" + color + name;
    }

    private static Map<String, Double> numbers(ConfigurationSection s) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (s != null) for (String k : s.getKeys(false)) out.put(k.toLowerCase(Locale.ROOT), s.getDouble(k));
        return out;
    }

    static Material material(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name);
        return m == null ? fallback : m;
    }
}
