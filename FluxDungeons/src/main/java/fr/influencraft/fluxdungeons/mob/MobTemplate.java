package fr.influencraft.fluxdungeons.mob;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A monster of {@code mobs.yml}: a vanilla mob with its own name, numbers, equipment and abilities. A boss has a
 * boss bar and may wear an animated BetterModel model ({@code model}), with the animation names of its fiche.
 *
 * <pre>
 * foundry_golem:
 *   type: ZOMBIE
 *   name: "Foundry Golem"
 *   boss: true
 *   model: golem_fonderie          # BetterModel; the vanilla mob becomes invisible
 *   health: 400
 *   damage: 9
 *   speed: 0.22
 *   armor: 8
 *   scale: 1.6
 *   xp: 120
 *   equipment: {hand: IRON_AXE, head: IRON_HELMET}
 *   abilities:
 *     - {type: slam, every: 7, power: 10, radius: 4, animation: attack, impact: 16}
 *     - {type: summon, every: 20, mob: forge_worker, count: 2, below: 50}
 * </pre>
 */
public record MobTemplate(String id, EntityType type, String name, boolean boss, String model, double health,
                          double damage, double speed, double armor, double scale, double xp,
                          Map<String, Material> equipment, List<Ability> abilities) {

    /** One ability: cast every {@code every} seconds, once the mob's life is at or below {@code below} percent. */
    public record Ability(String type, double every, double below, Map<String, Object> params) {

        public double number(String key, double fallback) {
            return params.get(key) instanceof Number n ? n.doubleValue() : fallback;
        }

        public String text(String key, String fallback) {
            Object v = params.get(key);
            return v == null ? fallback : String.valueOf(v);
        }
    }

    public static MobTemplate read(String id, ConfigurationSection c) {
        EntityType type;
        try {
            type = EntityType.valueOf(c.getString("type", "ZOMBIE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = EntityType.ZOMBIE;
        }
        Map<String, Material> equipment = new LinkedHashMap<>();
        ConfigurationSection eq = c.getConfigurationSection("equipment");
        if (eq != null) {
            for (String slot : eq.getKeys(false)) {
                Material m = Material.matchMaterial(eq.getString(slot, ""));
                if (m != null) equipment.put(slot.toLowerCase(Locale.ROOT), m);
            }
        }
        List<Ability> abilities = new ArrayList<>();
        for (Map<?, ?> a : c.getMapList("abilities")) {
            Map<String, Object> params = new LinkedHashMap<>();
            a.forEach((k, v) -> params.put(String.valueOf(k), v));
            Object every = a.get("every"), below = a.get("below");
            abilities.add(new Ability(String.valueOf(a.get("type")).toLowerCase(Locale.ROOT),
                    every instanceof Number n ? n.doubleValue() : 8, below instanceof Number n ? n.doubleValue() : 100, params));
        }
        return new MobTemplate(id, type, c.getString("name", id), c.getBoolean("boss"), c.getString("model"),
                c.getDouble("health", 20), c.getDouble("damage", 3), c.getDouble("speed", 0.23), c.getDouble("armor", 0),
                c.getDouble("scale", 1), c.getDouble("xp", 10), equipment, abilities);
    }
}
