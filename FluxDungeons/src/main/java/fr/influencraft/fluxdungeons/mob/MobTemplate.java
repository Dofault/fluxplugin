package fr.influencraft.fluxdungeons.mob;

import fr.influencraft.fluxdungeons.script.Script;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A monster of {@code mobs.yml} or {@code mobs/<dungeon>.yml}: a vanilla mob with its own name, numbers, equipment,
 * abilities, built-in powers and scripts. A boss has a boss bar and may wear an animated BetterModel model.
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
 *   baby: false
 *   frozen: false                  # no AI until a script wakes it up
 *   follow-range: 32
 *   leash: 0                       # pulled back to its spawn beyond this distance (0: never)
 *   equipment: {hand: IRON_AXE, head: IRON_HELMET}
 *   abilities:                     # our simple abilities, see Mobs
 *     - {type: slam, every: 7, power: 10, radius: 4, animation: attack, impact: 16}
 *   powers: [attack_blinding, invulnerability_fire]   # built-in powers, see Powers
 *   phases: [{mob: foundry_golem_p2, below: 0.5}]     # becomes another monster below this share of its life
 *   attack-speed: 1                # melee hits per second at most (vanilla 1)
 *   mount: forge_horse
 *   messages: {death: [...], hit: [...], hurt: [...]}
 *   blocks: {spawn: ["-2,0,3/minecraft:iron_bars"], remove: [...]}   # relative to its spawn point
 *   scripts: { ... }               # EliteScript format, see ScriptEngine
 * </pre>
 *
 * <b>Imported monsters</b> ({@code level} above 0): life and attack are the vanilla creature's, times
 * {@code health-multiplier} and {@code damage-multiplier}; the level is only shown for now. Players fight with their
 * own gear, so vanilla armour, enchantments and libreforge effects apply as anywhere else.
 */
public record MobTemplate(String id, EntityType type, String name, boolean boss, String model, double health,
                          double damage, double speed, double armor, double scale, double xp,
                          Map<String, Material> equipment, List<Ability> abilities, Extra extra) {

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

    public record Phase(String mob, double below) {
    }

    /**
     * An item a monster drops. Entries of the same {@code group} give one item per player, picked among those of his
     * class ({@code classes}, empty: any class), with the chance of the group's first entry.
     */
    public record Loot(String item, double chance, int amount, String group, List<String> classes) {
    }

    /** A block changed when the monster spawns or disappears, relative to its spawn point. */
    public record BlockState(Vector offset, String data) {
    }

    /** Everything a monster brought from EliteMobs may need beyond the basics. */
    public record Extra(int level, double healthMultiplier, double damageMultiplier,
                        boolean baby, boolean frozen, double followRange, double leash, List<String> powers,
                        List<Phase> phases, String mount, List<Loot> loot, List<String> deathMessages,
                        List<String> hitMessages, List<String> hurtMessages, List<BlockState> spawnBlocks,
                        List<BlockState> removeBlocks, Map<String, Script> scripts, String rank,
                        double attackSpeed) {
        /** Ticks between two melee hits of this monster ({@code attack-speed}: hits per second, vanilla 1). */
        public int attackTicks() {
            return (int) Math.max(4, Math.round(20 / Math.max(0.05, attackSpeed)));
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
        List<Phase> phases = new ArrayList<>();
        for (Map<?, ?> p : c.getMapList("phases")) {
            Object below = p.get("below");
            phases.add(new Phase(String.valueOf(p.get("mob")), below instanceof Number n ? n.doubleValue() : 0.5));
        }
        List<Loot> loot = new ArrayList<>();
        for (Map<?, ?> l : c.getMapList("loot")) {
            Object chance = l.get("chance"), amount = l.get("amount");
            List<String> classes = new ArrayList<>();
            if (l.get("classes") instanceof List<?> cl) for (Object o : cl) classes.add(String.valueOf(o));
            loot.add(new Loot(String.valueOf(l.get("item")), chance instanceof Number n ? n.doubleValue() : 1,
                    amount instanceof Number n ? n.intValue() : 1, l.get("group") == null ? null : String.valueOf(l.get("group")), classes));
        }
        Object scriptSection = c.isConfigurationSection("scripts") ? toMap(c.getConfigurationSection("scripts")) : null;
        Extra extra = new Extra(c.getInt("level", 0), c.getDouble("health-multiplier", 1), c.getDouble("damage-multiplier", 1),
                c.getBoolean("baby"), c.getBoolean("frozen"), c.getDouble("follow-range", 32),
                c.getDouble("leash", 0), c.getStringList("powers"), phases, c.getString("mount"), loot,
                c.getStringList("messages.death"), c.getStringList("messages.hit"), c.getStringList("messages.hurt"),
                blocks(c.getStringList("blocks.spawn")), blocks(c.getStringList("blocks.remove")),
                Script.parse(scriptSection), c.getString("rank", "TRASH").toUpperCase(Locale.ROOT),
                c.getDouble("attack-speed", 1));
        return new MobTemplate(id, type, c.getString("name", id), c.getBoolean("boss"), c.getString("model"),
                c.getDouble("health", 20), c.getDouble("damage", 3), c.getDouble("speed", -1), c.getDouble("armor", 0),
                c.getDouble("scale", 1), c.getDouble("xp", 10), equipment, abilities, extra);
    }

    private static List<BlockState> blocks(List<String> lines) {
        List<BlockState> out = new ArrayList<>();
        for (String line : lines) {
            int slash = line.indexOf('/');
            if (slash < 0) continue;
            String[] p = line.substring(0, slash).split(",");
            if (p.length < 3) continue;
            try {
                out.add(new BlockState(new Vector(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])),
                        line.substring(slash + 1)));
            } catch (NumberFormatException ignored) {
                // a malformed line is skipped
            }
        }
        return out;
    }

    /** A config section as plain maps and lists, the way scripts are read. */
    static Map<String, Object> toMap(ConfigurationSection s) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : s.getKeys(false)) {
            Object v = s.get(k);
            out.put(k, v instanceof ConfigurationSection cs ? toMap(cs) : plain(v));
        }
        return out;
    }

    private static Object plain(Object v) {
        if (v instanceof ConfigurationSection cs) return toMap(cs);
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(plain(o));
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), plain(x)));
            return out;
        }
        return v;
    }
}
