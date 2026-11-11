package fr.influencraft.fluxdungeons.rpg.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Numbers of {@code balance.yml}: the defense constant, monster curves and ranks, the reference gear of each level and
 * the fight-length targets the simulator checks. Monster files give a level and a rank; their stats come from here.
 */
public final class Balance {

    /** A value growing with the level: {@code base + perLevel × (level − 1)}. */
    public record Curve(double base, double perLevel) {
        public double at(int level) {
            return base + perLevel * (Math.max(1, level) - 1);
        }

        static Curve read(Node n, String key) {
            Node c = n.required(key);
            return new Curve(c.number("base"), c.number("per-level", 0));
        }
    }

    /** Multipliers of a monster rank. */
    public record Rank(String id, double health, double damage, double defense, double xp) {
    }

    /** Fight-length targets in seconds: {@code min} and {@code max} the simulator must stay within. */
    public record Band(double min, double max) {
        public boolean contains(double v) {
            return v >= min && v <= max;
        }

        static Band read(Node n, String key) {
            Node b = n.required(key);
            double min = b.number("min"), max = b.number("max");
            if (max < min) b.problem("max", "lower than min");
            return new Band(min, max);
        }
    }

    private double kBase, kPerLevel, minimumDamage;
    private int maxLevel;
    private Curve xpToNext;
    private Map<String, Curve> monster;
    private Map<String, Rank> ranks;
    private Map<String, Map<String, Curve>> gear;
    private Map<String, Band> ttk;
    private double survivalNormal, survivalElite;
    private List<Integer> checkedLevels;

    private Balance() {
    }

    public static Balance load(Node root, StatRegistry stats, Iterable<String> classIds) throws ConfigException {
        Balance b = new Balance();
        Node d = root.required("defense");
        b.kBase = d.number("k-base", 50, 1, 1e6);
        b.kPerLevel = d.number("k-per-level", 10, 0, 1e6);
        b.minimumDamage = root.child("damage").number("minimum", 1, 0, 1e6);
        Node lv = root.required("levels");
        b.maxLevel = (int) lv.number("max", 50, 1, 1000);
        b.xpToNext = Curve.read(lv, "xp-to-next");

        Node m = root.required("monsters");
        b.monster = new LinkedHashMap<>();
        for (String stat : m.keys()) {
            if (!stats.has(stat) && !stat.equals("xp")) m.problem(stat, "unknown stat");
            b.monster.put(stat, Curve.read(m, stat));
        }
        if (!b.monster.containsKey("max_health")) m.problem("max_health", "missing");
        if (!b.monster.containsKey("physical_attack")) m.problem("physical_attack", "missing");

        Node r = root.required("ranks");
        b.ranks = new LinkedHashMap<>();
        for (String id : r.keys()) {
            Node n = r.child(id);
            b.ranks.put(id, new Rank(id, n.number("health", 1, 0.01, 1e4), n.number("damage", 1, 0.01, 1e3),
                    n.number("defense", 1, 0, 1e3), n.number("xp", 1, 0, 1e4)));
        }
        for (String needed : List.of("normal", "elite", "miniboss", "boss")) if (!b.ranks.containsKey(needed)) r.problem(needed, "missing");

        Node g = root.required("reference-gear");
        b.gear = new LinkedHashMap<>();
        for (String cls : classIds) {
            if (!g.has(cls)) g.problem(cls, "missing (one line per class)");
            Node c = g.child(cls);
            Map<String, Curve> curves = new LinkedHashMap<>();
            for (String stat : c.keys()) {
                if (!stats.has(stat)) c.problem(stat, "unknown stat");
                curves.put(stat, Curve.read(c, stat));
            }
            b.gear.put(cls, curves);
        }

        Node t = root.required("targets");
        b.ttk = new LinkedHashMap<>();
        Node solo = t.required("time-to-kill");
        for (String rank : solo.keys()) b.ttk.put(rank, Band.read(solo, rank));
        b.survivalNormal = t.number("survive-normal", 20, 0, 1e4);
        b.survivalElite = t.number("survive-elite", 8, 0, 1e4);
        b.checkedLevels = new java.util.ArrayList<>();
        for (String s : t.texts("levels")) {
            try {
                b.checkedLevels.add(Integer.parseInt(s));
            } catch (NumberFormatException e) {
                t.problem("levels", s + " is not a level");
            }
        }
        ConfigException.check(root.problems());
        return b;
    }

    public double kBase() {
        return kBase;
    }

    public double kPerLevel() {
        return kPerLevel;
    }

    public double minimumDamage() {
        return minimumDamage;
    }

    public int maxLevel() {
        return maxLevel;
    }

    public double xpToNext(int level) {
        return xpToNext.at(level);
    }

    public Rank rank(String id) {
        return ranks.get(id);
    }

    public Map<String, Rank> ranks() {
        return ranks;
    }

    /** Stats of a monster of this level and rank, before its own file's changes. */
    public List<Modifier> monster(int level, String rankId) {
        Rank rank = ranks.getOrDefault(rankId, ranks.get("normal"));
        List<Modifier> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Curve> e : monster.entrySet()) {
            if (e.getKey().equals("xp")) continue;
            double v = e.getValue().at(level);
            switch (e.getKey()) {
                case "max_health" -> v *= rank.health();
                case "physical_attack", "magic_power" -> v *= rank.damage();
                case "physical_defense", "magic_defense" -> v *= rank.defense();
                default -> {
                }
            }
            out.add(Modifier.add(e.getKey(), v, "monster"));
        }
        return out;
    }

    public double monsterXp(int level, String rankId) {
        Curve c = monster.get("xp");
        Rank rank = ranks.getOrDefault(rankId, ranks.get("normal"));
        return c == null ? 0 : c.at(level) * rank.xp();
    }

    /** What a full set of gear of that level gives a class (the budget items are made against). */
    public List<Modifier> referenceGear(String classId, int level) {
        List<Modifier> out = new java.util.ArrayList<>();
        Map<String, Curve> curves = gear.get(classId);
        if (curves == null) return out;
        for (Map.Entry<String, Curve> e : curves.entrySet()) out.add(Modifier.add(e.getKey(), e.getValue().at(level), "gear"));
        return out;
    }

    public Band timeToKill(String rank) {
        return ttk.get(rank);
    }

    public Map<String, Band> timeToKill() {
        return ttk;
    }

    public double surviveNormal() {
        return survivalNormal;
    }

    public double surviveElite() {
        return survivalElite;
    }

    public List<Integer> checkedLevels() {
        return checkedLevels;
    }
}
