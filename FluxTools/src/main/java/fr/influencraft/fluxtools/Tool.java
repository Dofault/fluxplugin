package fr.influencraft.fluxtools;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * A tool (one file {@code tools/<id>.yml}). With {@code level.thresholds}, its level (0 to {@link #maxLevel()}) climbs
 * with the tool XP earned by using it ({@code points}, per resource), and each enchantment
 * ({@code enchantments.<id>.<tool level>: <enchantment level>}) climbs step by step. Without, its enchantments are fixed.
 */
final class Tool {

    final String id;
    final String name;
    final List<String> description;
    final String tier;
    final String family;
    final Material material;
    /** Model of the resource pack (item_model component), null: the material's look. */
    final NamespacedKey model;
    final int durability;
    final boolean repairable;
    final Map<String, Integer> fixedEnchantments = new LinkedHashMap<>();
    /** Enchantments with levels: tool level -> enchantment level unlocked, in display order. */
    final Map<String, TreeMap<Integer, Integer>> levelledEnchantments = new LinkedHashMap<>();
    /** Total XP of each level (index 0 = level 1). */
    final double[] thresholds;
    /** Tool XP per unit of resource (block, crop, monster). */
    final Map<String, Double> points = new HashMap<>();

    private Tool(String id, YamlConfiguration c) {
        this.id = id;
        name = c.getString("name", id);
        description = c.getStringList("description");
        tier = c.getString("tier", "ordinaire");
        family = c.getString("family", "pioche");
        material = Material.matchMaterial(c.getString("material", "iron_pickaxe"));
        String key = c.getString("model");
        model = key == null || key.isBlank() ? null : NamespacedKey.fromString(key);
        durability = c.getInt("durability", 0);
        repairable = c.getBoolean("repairable", true);
        ConfigurationSection e = c.getConfigurationSection("enchantments");
        if (e != null) {
            for (String k : e.getKeys(false)) {
                String ench = k.toLowerCase(Locale.ROOT);
                ConfigurationSection steps = e.getConfigurationSection(k);
                if (steps == null) {
                    fixedEnchantments.put(ench, e.getInt(k));
                    continue;
                }
                TreeMap<Integer, Integer> levels = new TreeMap<>();
                for (String n : steps.getKeys(false)) levels.put(Integer.parseInt(n), steps.getInt(n));
                levelledEnchantments.put(ench, levels);
            }
        }
        thresholds = c.getDoubleList("level.thresholds").stream().mapToDouble(Double::doubleValue).toArray();
        ConfigurationSection pt = c.getConfigurationSection("points");
        if (pt != null) for (String k : pt.getKeys(false)) points.put(k.toLowerCase(Locale.ROOT), pt.getDouble(k));
    }

    static Tool load(File file, Logger log) {
        String id = file.getName().replaceFirst("\\.yml$", "");
        Tool t = new Tool(id, YamlConfiguration.loadConfiguration(file));
        if (t.material == null) {
            log.warning("Tool " + id + ": unknown material, skipped.");
            return null;
        }
        Stream.concat(t.fixedEnchantments.keySet().stream(), t.levelledEnchantments.keySet().stream()).forEach(k -> {
            if (enchantment(k) == null) log.warning("Tool " + id + ": unknown enchantment " + k + ".");
        });
        return t;
    }

    /** Vanilla or EcoEnchants enchantment (EcoEnchants registers its own in the {@code minecraft} namespace). */
    static Enchantment enchantment(String id) {
        NamespacedKey key = id.contains(":") ? NamespacedKey.fromString(id) : NamespacedKey.minecraft(id);
        return key == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);
    }

    boolean hasLevels() {
        return thresholds.length > 0;
    }

    int maxLevel() {
        return thresholds.length;
    }

    int level(double xp) {
        int n = 0;
        while (n < thresholds.length && xp >= thresholds[n]) n++;
        return n;
    }

    /** Total XP of level n (0 for level 0). */
    double threshold(int n) {
        return n <= 0 ? 0 : thresholds[Math.min(n, thresholds.length) - 1];
    }

    /** Level of an enchantment unlocked at this tool level (0 = not yet). */
    int enchantmentLevel(String id, int level) {
        TreeMap<Integer, Integer> steps = levelledEnchantments.get(id);
        Map.Entry<Integer, Integer> e = steps == null ? null : steps.floorEntry(level);
        return e == null ? 0 : e.getValue();
    }

    int maxEnchantmentLevel(String id) {
        TreeMap<Integer, Integer> steps = levelledEnchantments.get(id);
        return steps == null || steps.isEmpty() ? 0 : steps.lastEntry().getValue();
    }
}
