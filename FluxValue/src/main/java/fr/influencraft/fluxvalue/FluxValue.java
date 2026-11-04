package fr.influencraft.fluxvalue;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.value.Values;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * FluxValue: the engine of the economy. One file of weights ({@code sources.yml}), one of coefficients
 * ({@code economy.yml}); job XP and money, shop prices, offering quantities and collection tiers all come from them.
 *
 * <pre>
 *   /fluxvalue worth &lt;item&gt;   what an item is worth and why
 *   /fluxvalue rates           money per 2 minutes
 *   /fluxvalue report          writes report.md (prices, checks)
 *   /fluxvalue reload          reads the files again: shop and offerings follow at once
 *   /fluxvalue apply           writes the job plugin and the collections, which have no live link to us
 * </pre>
 */
public final class FluxValue extends FluxPlugin {

    private Model model;

    @Override
    protected void enable() {
        if (!new File(getDataFolder(), "economy.yml").exists()) saveResource("economy.yml", false);
        if (!new File(getDataFolder(), "sources.yml").exists()) saveResource("sources.yml", false);
        compute();
        Values.provider(new Values.Provider() {
            public OptionalDouble points(String item) {
                return model.points(item);
            }

            public OptionalDouble pointsWithoutArea(String item) {
                return model.pointsWithoutArea(item);
            }

            public String job(String item) {
                return model.job(item);
            }

            public OptionalDouble sellPrice(String item) {
                return model.sellPrice(item);
            }

            public OptionalDouble buyPrice(String item) {
                return model.buyPrice(item);
            }

            public double pointsPerSecond() {
                return model.pointsPerSecond();
            }
        });
        command("fluxvalue")
                .description("Values of the economy: weights, prices, generated files.")
                .permission("fluxvalue.admin")
                .suggest(false)
                .executor(this::onCommand)
                .completer((sender, args) -> args.length == 1
                        ? List.of("worth", "rates", "report", "reload", "apply").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList()
                        : List.of())
                .register();
    }

    private void compute() {
        YamlConfiguration economy = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "economy.yml"));
        YamlConfiguration sources = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "sources.yml"));
        model = Model.load(economy, sources, ServerRecipes.read());
        if (model.jobs.isEmpty()) getLogger().warning("sources.yml has no job: every value will be missing.");
        getLogger().info(model.market().all().size() + " item values from " + model.jobs.size() + " jobs.");
    }

    @Override
    public void reload() {
        super.reload();
        compute();
        Values.changed();
    }

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "reload" -> {
                reload();
                messages().send(sender, "reloaded", "%count%", String.valueOf(model.market().all().size()));
            }
            case "worth" -> worth(sender, args);
            case "rates" -> rates(sender);
            case "report" -> {
                try {
                    File file = new File(getDataFolder(), "report.md");
                    new Report(model).write(file, Bukkit.getPluginManager().getPlugin("FluxShop"));
                    messages().send(sender, "report-written", "%file%", file.getPath());
                } catch (IOException e) {
                    getLogger().warning("report: " + e.getMessage());
                }
            }
            case "apply" -> apply(sender);
            default -> messages().send(sender, "usage");
        }
        return true;
    }

    private void worth(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages().send(sender, "usage");
            return;
        }
        String item = args[1].contains(":") ? args[1] : args[1].toUpperCase(Locale.ROOT);
        ValueGraph.Entry e = model.market().get(item);
        if (e == null) {
            messages().send(sender, "no-value", "%item%", item);
            return;
        }
        messages().send(sender, "worth", "%item%", item,
                "%points%", Report.number(e.points()),
                "%seconds%", Report.number(e.points() / model.pointsPerSecond()),
                "%job%", model.job(item),
                "%sell%", Report.number(model.sellPrice(item).orElse(0)),
                "%buy%", Report.number(model.buyPrice(item).orElse(0)));
    }

    private void rates(CommandSender sender) {
        for (int level : List.of(1, 50, 100, 200, 400)) {
            messages().send(sender, "rate", "%level%", String.valueOf(level),
                    "%money%", Report.number(model.moneyPer2Minutes * level / 100.0));
        }
    }

    private void apply(CommandSender sender) {
        File plugins = getDataFolder().getParentFile();
        try {
            int jobs = new JobsWriter(model, getLogger()).write(new File(plugins, "EcoJobs/jobs"));
            int collections = new CollectionsWriter(model, getLogger()).write(new File(plugins, "EcoCollections/collections"));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ecojobs reload");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ecocollections reload");
            messages().send(sender, "applied", "%jobs%", String.valueOf(jobs), "%collections%", String.valueOf(collections));
        } catch (IOException e) {
            getLogger().severe("apply: " + e.getMessage());
            messages().send(sender, "apply-failed");
        }
    }
}
