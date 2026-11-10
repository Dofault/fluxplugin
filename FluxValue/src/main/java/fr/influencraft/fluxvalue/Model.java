package fr.influencraft.fluxvalue;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * economy.yml (coefficients) and sources.yml (weights), turned into values. Two graphs: with Tranchee (the market: what
 * a geared player really harvests) and without (what the item costs before the enchantment).
 */
public final class Model {

    /** One action of a job. */
    public record Source(String job, String id, String trigger, List<String> blocks, double weight, boolean fixedValue,
                         Map<String, Object> filters, Map<String, Double> yields, boolean area, String label,
                         String placeholder, List<?> conditions) {
    }

    /** A job as generated into the job plugin. */
    public record Job(String id, List<?> conditions, double curveBase, double curveExponent, List<Source> sources) {
    }

    double pointsPer2Minutes;
    double moneyPer2Minutes;
    double areaFactor;
    double sellShare;
    double buyMultiplier;
    double curveHours;
    int maxLevel;
    double collectionHours;
    int collectionTiers;
    double collectionSecondExponent;
    /** Points of a smelt for its job: pays XP and money, adds nothing to the value of the item ({@link #smeltCost}). */
    double smeltPoints;
    double smeltCost;
    double meterWindowSeconds;
    String meterGlyph;
    int meterSteps;
    /** Points of one health point of a mob: the time it takes to kill it, before its weight. */
    double pointsPerHealth;

    final Map<String, Job> jobs = new LinkedHashMap<>();
    final Map<String, Double> mobWeights = new LinkedHashMap<>();
    final Map<String, String> aliases = new LinkedHashMap<>();
    final List<String> warnings = new ArrayList<>();
    private final ValueGraph market = new ValueGraph();
    private final ValueGraph early = new ValueGraph();

    double pointsPerSecond() {
        return pointsPer2Minutes / 120.0;
    }

    /** BitBlock per point at level 100. */
    double moneyPerPoint() {
        return moneyPer2Minutes / pointsPer2Minutes;
    }

    ValueGraph market() {
        return market;
    }

    static Model load(YamlConfiguration economy, YamlConfiguration sources, List<ValueGraph.Recipe> recipes) {
        Model m = new Model();
        m.pointsPer2Minutes = economy.getDouble("points-per-2-minutes", 7050);
        m.moneyPer2Minutes = economy.getDouble("money-per-2-minutes-at-level-100", 705);
        m.areaFactor = economy.getDouble("area-factor", 14);
        m.sellShare = economy.getDouble("shop.sell-share", 0.25);
        m.buyMultiplier = economy.getDouble("shop.buy-multiplier", 62.5);
        m.curveHours = economy.getDouble("jobs.hours-to-max-level", 400);
        m.maxLevel = economy.getInt("jobs.max-level", 400);
        m.collectionHours = economy.getDouble("collections.hours-of-last-tier", 2);
        m.collectionTiers = economy.getInt("collections.tiers", 7);
        m.collectionSecondExponent = economy.getDouble("collections.second-tier-exponent", 0.455);
        m.meterWindowSeconds = Math.max(1, economy.getDouble("rate-meter.window-seconds", 10));
        m.meterGlyph = economy.getString("rate-meter.bar.glyph", "fluxafk:bar_{step}");
        m.meterSteps = Math.max(1, economy.getInt("rate-meter.bar.steps", 20));
        m.smeltCost = economy.getDouble("recipes.smelt-cost", 0);
        m.pointsPerHealth = economy.getDouble("mobs.points-per-health", 4.23);
        m.readMobWeights(sources);
        m.readJobs(sources);
        m.readDrops(sources);
        m.readFishing(sources);
        m.readEnchantments(sources);
        ConfigurationSection others = sources.getConfigurationSection("others");
        if (others != null) {
            for (String item : others.getKeys(false)) {
                m.market.source(item, others.getDouble(item), "divers");
                m.early.source(item, others.getDouble(item), "divers");
            }
        }
        ConfigurationSection aliases = sources.getConfigurationSection("same-value-as");
        if (aliases != null) for (String item : aliases.getKeys(false)) m.aliases.put(item, aliases.getString(item));
        List<ValueGraph.Recipe> all = new ArrayList<>();
        for (ValueGraph.Recipe r : recipes) {
            all.add(r.cooked() ? new ValueGraph.Recipe(r.id(), r.output(), r.count(), r.slots(), m.smeltCost, true) : r);
        }
        // an alias is a recipe of one ingredient with no cost: it follows that item in both directions of a value change
        for (Map.Entry<String, String> a : m.aliases.entrySet()) {
            all.add(new ValueGraph.Recipe("alias_" + a.getKey(), a.getKey(), 1,
                    List.of(new ValueGraph.Slot(List.of(a.getValue()), 1)), 0, false));
        }
        m.market.recipes(all);
        m.early.recipes(all);
        m.market.solve();
        m.early.solve();
        return m;
    }

    @SuppressWarnings("unchecked")
    private void readJobs(YamlConfiguration file) {
        ConfigurationSection root = file.getConfigurationSection("jobs");
        if (root == null) return;
        for (String jobId : root.getKeys(false)) {
            ConfigurationSection j = root.getConfigurationSection(jobId);
            List<Source> sources = new ArrayList<>();
            ConfigurationSection ss = j.getConfigurationSection("sources");
            for (String id : ss.getKeys(false)) {
                ConfigurationSection s = ss.getConfigurationSection(id);
                Map<String, Double> yields = new LinkedHashMap<>();
                ConfigurationSection y = s.getConfigurationSection("yields");
                boolean isBlock = !s.contains("yields") || y != null;
                String trigger = s.getString("trigger", "mine_block");
                if (y != null) for (String item : y.getKeys(false)) yields.put(item, y.getDouble(item));
                else if (!s.contains("yields")) yields.put(id.toUpperCase(Locale.ROOT), 1.0);
                List<String> blocks = s.contains("blocks") ? s.getStringList("blocks")
                        : (s.contains("filters.custom_fish_type") || !isBlockTrigger(trigger) ? List.of() : List.of(id));
                Map<String, Object> filters = s.isConfigurationSection("filters")
                        ? toPlain(s.getConfigurationSection("filters")) : (blocks.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(Map.of("player_placed", false)));
                Source src = new Source(jobId, id, trigger, blocks, s.getDouble("weight"), s.getBoolean("fixed-value"),
                        filters, yields, s.getBoolean("area"), s.getString("label"), s.getString("placeholder"),
                        s.contains("conditions") ? s.getList("conditions") : null);
                sources.add(src);
                for (Map.Entry<String, Double> e : yields.entrySet()) {
                    if (e.getValue() <= 0) continue;
                    market.source(e.getKey(), src.weight() / (e.getValue() * (src.area() ? areaFactor : 1)), jobId);
                    early.source(e.getKey(), src.weight() / e.getValue(), jobId);
                }
                if (trigger.equals("smelt")) smeltPoints = src.weight();
            }
            jobs.put(jobId, new Job(jobId, j.getList("conditions", List.of()), j.getDouble("curve.base", 10),
                    j.getDouble("curve.exponent", 3), sources));
        }
    }

    /**
     * Points of one kill of a hostile mob = base health x points per health x weight. The health is the time it takes
     * to kill it; the weight (relative, 1 = zombie, from the workbook) is how hard it is to find (a blaze has a
     * zombie's health, but lives in a fortress). The base health comes from the server (default attributes), never
     * from the config: it values the drops, offerings and collections.
     */
    private void readMobWeights(YamlConfiguration file) {
        ConfigurationSection mobs = file.getConfigurationSection("mobs");
        if (mobs == null) return;
        for (String id : mobs.getKeys(false)) {
            OptionalDouble health = baseHealth(id);
            if (health.isEmpty()) {
                warnings.add("Unknown mob or no health in mobs: " + id + " (no value, not paid)");
                continue;
            }
            mobWeights.put(id, health.getAsDouble() * pointsPerHealth * mobs.getDouble(id + ".weight", 1));
        }
    }

    private static OptionalDouble baseHealth(String id) {
        EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(id));
        if (type == null || !type.hasDefaultAttributes()) return OptionalDouble.empty();
        AttributeInstance health = type.getDefaultAttributes().getAttribute(Attribute.MAX_HEALTH);
        return health == null ? OptionalDouble.empty() : OptionalDouble.of(health.getBaseValue());
    }

    private static boolean isBlockTrigger(String trigger) {
        return trigger.equals("mine_block") || trigger.equals("mine_block_cascade") || trigger.equals("click_block");
    }

    private static Map<String, Object> toPlain(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : section.getKeys(false)) out.put(k, section.get(k));
        return out;
    }

    /** The weight of a kill is shared between what it drops: one kill is never worth more than its weight in total. */
    private void readDrops(YamlConfiguration file) {
        for (String group : List.of("mobs", "animals")) {
            ConfigurationSection root = file.getConfigurationSection(group);
            if (root == null) continue;
            String job = group.equals("mobs") ? "defenseur" : "divers";
            for (String id : root.getKeys(false)) {
                double weight = group.equals("mobs") ? mobWeights.getOrDefault(id, 0.0) : root.getDouble(id + ".weight");
                ConfigurationSection y = root.getConfigurationSection(id + ".yields");
                if (y == null || y.getKeys(false).isEmpty()) continue;
                int shares = y.getKeys(false).size();
                for (String item : y.getKeys(false)) {
                    double count = y.getDouble(item);
                    if (count <= 0) continue;
                    market.source(item, weight / shares / count, job);
                    early.source(item, weight / shares / count, job);
                }
            }
        }
    }

    /** A fish costs the catches it takes to get its rarity. */
    private void readFishing(YamlConfiguration file) {
        ConfigurationSection f = file.getConfigurationSection("fishing");
        if (f == null) return;
        double weight = f.getDouble("catch-weight");
        ConfigurationSection rarities = f.getConfigurationSection("rarities");
        if (rarities == null) return;
        for (String rarity : rarities.getKeys(false)) {
            double share = rarities.getDouble(rarity);
            if (share <= 0) continue;
            for (String species : f.getStringList("species")) {
                String id = "customfishing:" + rarity + "_" + species;
                market.source(id, weight / share, "pecheur");
                early.source(id, weight / share, "pecheur");
            }
        }
    }

    /** A level of an enchantment costs the table uses it takes to get it on a book. */
    private void readEnchantments(YamlConfiguration file) {
        ConfigurationSection e = file.getConfigurationSection("enchantments");
        if (e == null) return;
        double weight = e.getDouble("use-weight");
        ConfigurationSection chance = e.getConfigurationSection("levels-per-use");
        if (chance == null) return;
        for (String id : chance.getKeys(false)) {
            String key = "enchant:" + id;
            market.source(key, weight / chance.getDouble(id), "enchanteur");
            early.source(key, weight / chance.getDouble(id), "enchanteur");
        }
    }

    OptionalDouble points(String item) {
        ValueGraph.Entry e = market.get(item);
        return e == null ? OptionalDouble.empty() : OptionalDouble.of(e.points());
    }

    OptionalDouble pointsWithoutArea(String item) {
        ValueGraph.Entry e = early.get(item);
        return e == null ? OptionalDouble.empty() : OptionalDouble.of(e.points());
    }

    String job(String item) {
        return market.origin(item, smeltPoints);
    }

    OptionalDouble sellPrice(String item) {
        ValueGraph.Entry e = market.get(item);
        return e == null ? OptionalDouble.empty() : OptionalDouble.of(e.points() * moneyPerPoint() * sellShare);
    }

    OptionalDouble buyPrice(String item) {
        OptionalDouble sell = sellPrice(item);
        return sell.isEmpty() ? sell : OptionalDouble.of(sell.getAsDouble() * buyMultiplier);
    }

    /** XP of a level, as the workbook computes it: a base cost plus a power curve that sums to the hours of the job. */
    List<Integer> curve(Job job) {
        double total = pointsPer2Minutes * 30 * curveHours;
        double sum = 0;
        for (int k = 0; k < maxLevel; k++) sum += Math.pow(k, job.curveExponent());
        List<Integer> out = new ArrayList<>(maxLevel);
        for (int level = 1; level <= maxLevel; level++) {
            out.add((int) Math.round(job.curveBase() + (total - job.curveBase() * maxLevel) * Math.pow(level - 1, job.curveExponent()) / sum));
        }
        return out;
    }
}
