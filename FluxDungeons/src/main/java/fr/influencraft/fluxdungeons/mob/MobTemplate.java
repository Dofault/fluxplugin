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
 *   equipment: {hand: IRON_AXE, head: IRON_HELMET, chest: "LEATHER_CHESTPLATE #7D7D7D"}   # leather may be dyed
 *   abilities:                     # our simple abilities, see Mobs
 *     - {type: slam, every: 7, power: 10, radius: 4, animation: attack, impact: 16}
 *   powers: [attack_blinding, invulnerability_fire]   # built-in powers, see Powers
 *   phases: [{mob: foundry_golem_p2, below: 0.5}]     # becomes another monster below this share of its life
 *   phases:                        # ...or with a transition played first (invulnerable, still, no gravity):
 *     - mob: otter_water
 *       below: 0.5
 *       animation: transition_dive   # animation of its model
 *       duration: 72                 # ticks before the next monster appears
 *       at-spawn: true               # goes back to its spawn point first
 *       flood: {crack: 33, at: 46, radius: 6, depth: 1, from: [ICE, PACKED_ICE], to: WATER}   # cracks from "crack", burst at "at"
 *       # area: connected = every block of those kinds joined to the one under it (a whole frozen pool, any shape),
 *       # up to "limit" blocks, instead of a circle of "radius"; pull: players within that many blocks left on dry
 *       # ground are thrown onto the changed blocks (into the water); pull-title: shown to them
 *   death-ticks: 36                # a boss with a death animation disappears this many ticks after it starts
 *   stationary: true               # never walks: stays where it is and faces its target (charges still move it)
 *   swim: {pass-speed: 10, ...}    # swims in the water around it instead of walking, see Swimmer
 *   attack-speed: 1                # melee hits per second at most (vanilla 1)
 *   ranged-resistance: 0.5         # share of projectile damage it shrugs off (melee is the way)
 *   ranged-rate: 2                 # archers shoot this many times as often as vanilla (2 by default)
 *   frost-arrows: true             # its arrows freeze and slow whoever they hit
 *   controlled: true               # no vanilla AI: FluxDungeons picks its target, walks it at its speed, hits it
 *   silent: true                   # no vanilla sounds (a model with its own look)
 *   cast-gap: 2.5                  # seconds between two abilities at least (the next one is drawn among those ready)
 *   bar-offset: 1.5                # life bar this many blocks higher (or lower) than the top of its body
 *   walk-speed: 1.0                # blocks per second on flat ground (instead of the raw speed attribute)
 *   abilities: [{spell: colossus_bite, every: 6}]   # a spell of spells/, values here win
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

    /** Walking speed in blocks per second ({@code walk-speed}), 0 when the raw {@code speed} attribute is used. */
    public double walkSpeed() {
        return extra.walkSpeed();
    }

    /** One ability: cast every {@code every} seconds, once the mob's life is at or below {@code below} percent. */
    public record Ability(String type, double every, double below, Map<String, Object> params) {

        public double number(String key, double fallback) {
            return params.get(key) instanceof Number n ? n.doubleValue() : fallback;
        }

        public String text(String key, String fallback) {
            Object v = params.get(key);
            return v == null ? fallback : String.valueOf(v);
        }

        public boolean flag(String key, boolean fallback) {
            return params.get(key) instanceof Boolean b ? b : fallback;
        }
    }

    /**
     * The next monster it becomes below a share of its life. With an {@code animation}, the switch waits
     * {@code duration} ticks: the monster plays it, invulnerable and still, maybe back at its spawn point first, and
     * may change the blocks under it on the way ({@code flood}).
     */
    public record Phase(String mob, double below, String animation, int duration, boolean atSpawn, Flood flood) {

        public boolean transition() {
            return animation != null && !animation.isBlank();
        }
    }

    /**
     * Blocks of the listed kinds under a monster turned into another one, {@code at} ticks into its transition, after
     * cracks drawn on them from {@code crack} (no crack when {@code crack} is negative or not before {@code at}).
     */
    public record Flood(int crack, int at, double radius, int depth, java.util.Set<Material> from, Material to,
                        boolean connected, int limit, double pull, String pullTitle) {
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
                        double attackSpeed, Map<String, org.bukkit.Color> dyes, double rangedResistance, double rangedRate,
                        boolean frostArrows, boolean controlled, boolean silent, double castGap, double barOffset,
                        double walkSpeed, int deathTicks, boolean stationary, Map<String, Object> swim) {
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
        Map<String, org.bukkit.Color> dyes = new LinkedHashMap<>();
        ConfigurationSection eq = c.getConfigurationSection("equipment");
        if (eq != null) {
            for (String slot : eq.getKeys(false)) {
                // "LEATHER_CHESTPLATE #7D7D7D": leather pieces may carry a colour
                String[] spec = eq.getString(slot, "").trim().split("\\s+");
                Material m = Material.matchMaterial(spec[0]);
                if (m == null) continue;
                equipment.put(slot.toLowerCase(Locale.ROOT), m);
                if (spec.length > 1 && spec[1].matches("#?[0-9A-Fa-f]{6}")) {
                    dyes.put(slot.toLowerCase(Locale.ROOT), org.bukkit.Color.fromRGB(Integer.parseInt(spec[1].replace("#", ""), 16)));
                }
            }
        }
        List<Ability> abilities = new ArrayList<>();
        for (Map<?, ?> raw : c.getMapList("abilities")) {
            // "- spell: <id>" takes a spell of spells/, the monster's own values win
            Map<String, Object> a = Spells.resolve(raw, id, java.util.logging.Logger.getLogger("FluxDungeons"));
            if (a.get("type") == null) continue;
            Map<String, Object> params = new LinkedHashMap<>(a);
            Object every = a.get("every"), below = a.get("below");
            abilities.add(new Ability(String.valueOf(a.get("type")).toLowerCase(Locale.ROOT),
                    every instanceof Number n ? n.doubleValue() : 8, below instanceof Number n ? n.doubleValue() : 100, params));
        }
        List<Phase> phases = new ArrayList<>();
        for (Map<?, ?> p : c.getMapList("phases")) {
            Object below = p.get("below"), duration = p.get("duration");
            phases.add(new Phase(String.valueOf(p.get("mob")), below instanceof Number n ? n.doubleValue() : 0.5,
                    p.get("animation") == null ? null : String.valueOf(p.get("animation")),
                    duration instanceof Number n ? n.intValue() : 0, Boolean.TRUE.equals(p.get("at-spawn")),
                    p.get("flood") instanceof Map<?, ?> f ? flood(f) : null));
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
                c.getDouble("attack-speed", 1), dyes, Math.max(0, Math.min(1, c.getDouble("ranged-resistance", 0))),
                Math.max(1, c.getDouble("ranged-rate", 2)), c.getBoolean("frost-arrows", false),
                c.getBoolean("controlled", false), c.getBoolean("silent", false), Math.max(0, c.getDouble("cast-gap", 0)),
                c.getDouble("bar-offset", 0), c.getDouble("walk-speed", 0), Math.max(1, c.getInt("death-ticks", 36)),
                c.getBoolean("stationary", false),
                c.isConfigurationSection("swim") ? toMap(c.getConfigurationSection("swim")) : Map.of());
        return new MobTemplate(id, type, c.getString("name", id), c.getBoolean("boss"), c.getString("model"),
                c.getDouble("health", 20), c.getDouble("damage", 3), c.getDouble("speed", -1), c.getDouble("armor", 0),
                c.getDouble("scale", 1), c.getDouble("xp", 10), equipment, abilities, extra);
    }

    private static Flood flood(Map<?, ?> f) {
        java.util.Set<Material> from = java.util.EnumSet.noneOf(Material.class);
        if (f.get("from") instanceof List<?> l) {
            for (Object o : l) {
                Material m = Material.matchMaterial(String.valueOf(o));
                if (m != null) from.add(m);
            }
        }
        Material to = Material.matchMaterial(String.valueOf(f.get("to")));
        return new Flood(f.get("crack") instanceof Number n ? n.intValue() : -1, f.get("at") instanceof Number n ? n.intValue() : 0,
                f.get("radius") instanceof Number n ? n.doubleValue() : 3,
                f.get("depth") instanceof Number n ? Math.max(1, n.intValue()) : 1, from, to == null ? Material.WATER : to,
                "connected".equalsIgnoreCase(String.valueOf(f.get("area"))),
                f.get("limit") instanceof Number n ? Math.max(1, n.intValue()) : 4096,
                f.get("pull") instanceof Number n ? n.doubleValue() : 0,
                f.get("pull-title") == null ? "" : String.valueOf(f.get("pull-title")));
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
