package fr.influencraft.fluxoffering.config;

import fr.influencraft.flux.api.value.Values;
import fr.influencraft.fluxoffering.model.CollectionDefinition;
import fr.influencraft.fluxoffering.model.ItemKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Offerings computed from a time budget. offerings.yml only says what each offering asks for; how much comes from here:
 * <pre>
 *   hours played up to grade g = total-hours x (g / last)^exponent
 *   the hours of a grade are shared equally between the jobs it asks for, then between the items of each job
 *   quantity = hours x points per second x speed of that job at that grade / points of the item
 * </pre>
 * The speed of a job goes from what a beginner does (bare tools) to optimal play at {@code full-speed-grade}: a beginner
 * mines 10 times slower than the optimal but fishes almost as fast, which is why the first grades ask for little stone
 * and a few fish. Before {@code area-from-grade}, items are counted without the area enchantment. A cycle asks for the
 * same items again ({@code slots}), only quantities change. Adding a grade or changing a weight moves everything.
 * The money asked follows the same curve, see {@link #money}.
 */
final class OfferingBudget {

    private OfferingBudget() {
    }

    /** The offerings, by id; null when offerings.yml is absent or values are unavailable (collections.yml applies). */
    static Map<String, CollectionDefinition> load(JavaPlugin plugin) {
        return load(plugin.getDataFolder(), plugin.getLogger());
    }

    static Map<String, CollectionDefinition> load(File folder, java.util.logging.Logger log) {
        File file = new File(folder, "offerings.yml");
        if (!file.exists() || !Values.available()) return null;
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        List<String> slots = c.getStringList("slots");
        List<String> cycles = c.getStringList("cycles");
        ConfigurationSection compositions = c.getConfigurationSection("compositions");
        if (slots.isEmpty() || compositions == null) {
            log.warning("offerings.yml has no slots or compositions: falling back on collections.yml.");
            return null;
        }
        int offerings = slots.size() * Math.max(1, cycles.size()) - 1;
        Map<String, CollectionDefinition> out = new LinkedHashMap<>();
        List<String> table = new ArrayList<>();
        table.add("# Offerings as computed (rewritten at every load, never read back)");
        for (int g = 1; g <= offerings; g++) {
            int cycle = g / slots.size();
            String slot = slots.get((g - 1) % slots.size());
            // the offering made at grade g gives grade g + 1; its id is the rank it gives, as in the rank menu
            int given = g % slots.size();
            String rank = c.getStringList("ranks").get(given);
            String id = cycle == 0 ? rank : rank + "_p" + cycle;
            CollectionDefinition def = new CollectionDefinition(id);
            String suffix = cycle < cycles.size() && !cycles.get(cycle).isBlank() ? " " + cycles.get(cycle) : "";
            ConfigurationSection comp = compositions.getConfigurationSection(slot);
            if (comp == null) {
                log.warning("offerings.yml: no composition " + slot);
                continue;
            }
            def.displayName(c.getString("names." + rank, "Offrande " + rank) + suffix);
            def.permission("offrandes.collection." + id);
            double hours = hours(c, g, offerings);
            def.money(comp.contains("money") ? comp.getLong("money") : money(c, g, offerings));
            Map<String, Long> quantities = quantities(log, c, comp, g, hours);
            StringBuilder row = new StringBuilder("\n## " + g + ". " + id + " (" + String.format(Locale.ROOT, "%.2f", hours) + " h, " + def.money() + " money)\n");
            for (Map.Entry<String, Long> q : quantities.entrySet()) {
                try {
                    def.items().put(ItemKey.parse(q.getKey()), (int) Math.min(Integer.MAX_VALUE, q.getValue()));
                    row.append("- ").append(q.getKey()).append(": ").append(q.getValue()).append('\n');
                } catch (IllegalArgumentException ex) {
                    log.warning("offerings.yml: invalid item " + q.getKey());
                }
            }
            table.add(row.toString());
            out.put(id, def);
        }
        try {
            Files.write(new File(folder, "quantities.md").toPath(), table, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warning("quantities.md: " + ex.getMessage());
        }
        log.info(out.size() + " offerings computed.");
        return out;
    }

    static double hours(YamlConfiguration c, int grade, int last) {
        double total = c.getDouble("budget.total-hours", 1000);
        double exponent = c.getDouble("budget.exponent", 2.5);
        return total * (Math.pow((double) grade / last, exponent) - Math.pow((double) (grade - 1) / last, exponent));
    }

    /**
     * Money follows the curve of the resources (hours x speed at a grade), anchored on one offering of the first cycle:
     * {@code money.anchor-rank} asks {@code money.anchor-amount}. Two significant digits. 0 when no anchor is set.
     */
    static long money(YamlConfiguration c, int grade, int last) {
        int anchor = c.getStringList("ranks").indexOf(c.getString("money.anchor-rank", ""));
        long amount = c.getLong("money.anchor-amount", 0);
        if (anchor <= 0 || amount <= 0) return 0;
        double reference = hours(c, anchor, last) * speed(c, "default", anchor);
        double value = amount * hours(c, grade, last) * speed(c, "default", grade) / reference;
        if (value < 1) return 1;
        double scale = Math.pow(10, Math.floor(Math.log10(value)) - 1);
        return Math.round(Math.round(value / scale) * scale);
    }

    /** Geometric: a job stays near its beginner speed for the first grades, tools come late. */
    static double speed(YamlConfiguration c, String job, int grade) {
        double start = c.getDouble("ramp.beginner-speed." + job, c.getDouble("ramp.beginner-speed.default", 0.1));
        int full = Math.max(2, c.getInt("ramp.full-speed-grade", 12));
        double t = Math.min(1, (grade - 1.0) / (full - 1));
        return Math.pow(start, 1 - t);
    }

    static Map<String, Long> quantities(java.util.logging.Logger log, YamlConfiguration c, ConfigurationSection comp, int grade, double hours) {
        ConfigurationSection items = comp.getConfigurationSection("items");
        Map<String, Long> out = new LinkedHashMap<>();
        if (items == null) return out;
        boolean area = grade >= c.getInt("ramp.area-from-grade", 7);
        // shares: job, then the weight of the item in its job (1 unless written otherwise)
        Map<String, Double> jobTotal = new LinkedHashMap<>();
        for (String item : items.getKeys(false)) {
            if (Values.points(item).isEmpty()) {
                log.warning("Offering item without value, asked once: " + item);
                out.put(item, 1L);
                continue;
            }
            jobTotal.merge(Values.job(item), items.getDouble(item, 1), Double::sum);
        }
        double points = hours * 3600 * Values.pointsPerSecond();
        for (String item : items.getKeys(false)) {
            OptionalDouble value = area ? Values.points(item) : Values.pointsWithoutArea(item);
            if (value.isEmpty()) continue;
            String job = Values.job(item);
            double share = points / jobTotal.size() * speed(c, job, grade) * items.getDouble(item, 1) / jobTotal.get(job);
            long q = round(share / value.getAsDouble());
            if (q > 0) out.put(item, q);
        }
        return out;
    }

    /** Under half an item is not asked at all; then whole items, 16s up to a stack, 32s up to 1024, stacks beyond. */
    static long round(double q) {
        if (q < 0.5) return 0;
        if (q < 16) return (long) Math.ceil(q);
        if (q < 64) return (long) (Math.ceil(q / 16) * 16);
        if (q < 1024) return (long) (Math.ceil(q / 32) * 32);
        return (long) (Math.ceil(q / 64) * 64);
    }
}
