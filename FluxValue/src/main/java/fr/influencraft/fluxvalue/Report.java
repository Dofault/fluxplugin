package fr.influencraft.fluxvalue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** report.md: what the numbers mean in play and what looks wrong. Rewritten on demand, never read back. */
final class Report {

    private final Model model;

    Report(Model model) {
        this.model = model;
    }

    static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e12) return String.format(Locale.ROOT, "%,d", (long) v).replace(',', ' ');
        return String.format(Locale.ROOT, v < 1 ? "%.4f" : "%.2f", v);
    }

    void write(File file, Plugin shop) throws IOException {
        List<String> out = new ArrayList<>();
        out.add("# FluxValue report");
        out.add("");
        out.add("## Money per 2 minutes of optimal play, every job");
        for (int level : List.of(1, 25, 50, 100, 200, 300, 400)) {
            double money = model.moneyPer2Minutes * level / 100.0;
            out.add("- level " + level + ": " + number(money) + " per 2 min, " + number(money * 30) + " per hour");
        }
        out.add("- server shop: selling what those 2 minutes harvest pays " + number(model.moneyPer2Minutes * model.sellShare) + " (no level)");
        out.add("");
        out.add("## Items");
        out.add("| Item | Points | Seconds | Job | Sell | Buy |");
        out.add("|---|---|---|---|---|---|");
        for (Map.Entry<String, ValueGraph.Entry> e : new TreeMap<>(model.market().all()).entrySet()) {
            out.add("| " + e.getKey() + " | " + number(e.getValue().points()) + " | " + number(e.getValue().points() / model.pointsPerSecond())
                    + " | " + model.job(e.getKey()) + " | " + number(model.sellPrice(e.getKey()).orElse(0)) + " | " + number(model.buyPrice(e.getKey()).orElse(0)) + " |");
        }
        if (shop != null) {
            YamlConfiguration prices = YamlConfiguration.loadConfiguration(new File(shop.getDataFolder(), "prices.yml"));
            var items = prices.getConfigurationSection("items");
            if (items != null) {
                List<String> missing = new ArrayList<>();
                for (String id : items.getKeys(false)) {
                    if (model.market().get(id) == null && !items.getBoolean(id + ".fixed")) missing.add(id);
                }
                out.add("");
                out.add("## Items of the shop without a value (" + missing.size() + "): they keep the price of prices.yml");
                out.add(String.join(", ", missing));
            }
        }
        Files.write(file.toPath(), out, StandardCharsets.UTF_8);
    }
}
