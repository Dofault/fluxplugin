package fr.influencraft.fluxjobs;

import fr.influencraft.flux.api.jobs.Jobs;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * FluxJobs: what players see of their jobs, whatever job plugin Flux serves ({@code providers.jobs}): the Careers
 * menu and the paid actions menu ({@code /metier}), the job XP boss bar, the money coefficient line at each level,
 * and the data of the collections menu. Moved out of FluxTools on 2026-11-13.
 */
public final class FluxJobs extends fr.influencraft.flux.api.FluxPlugin {

    @Override
    protected void enable() {
        Lang.bind(this);
        if (!Jobs.available()) {
            getLogger().warning("No job plugin in Flux (providers.jobs): the job menus and notifications are off.");
        } else {
            JobChoiceCommand jobs = new JobChoiceCommand(this);
            command("job").aliases("metier").description("Join or leave a job, its levels and paid actions.")
                    .executor(jobs::run).completer(jobs::complete).register();
            // data of the Careers menu (%fluxjob_*%, [fluxjob])
            JobMenu.register(this, section("jobs"));
            // the job plugin's own job list opens ours instead
            Jobs.get().replaceNativeMenus(this, p -> Bukkit.dispatchCommand(p, "metiers"));
            if (section("job-xp-bossbar").getBoolean("enabled", true)) {
                ConfigurationSection c = section("job-xp-bossbar");
                JobXpBossBarListener bar = new JobXpBossBarListener(this, c.getLong("hide-after-seconds", 4),
                        c.getString("color", "GREEN"), c.getString("overlay", "NOTCHED_20"));
                Bukkit.getPluginManager().registerEvents(bar, this);
                command("jobxpbar").description("Job XP boss bar: on, off.").executor((sender, label, args) -> {
                    if (!(sender instanceof Player player)) {
                        messages().send(sender, "players-only");
                        return true;
                    }
                    String a = args.length == 0 ? "toggle" : args[0].toLowerCase(Locale.ROOT);
                    boolean on = a.equals("on") || !a.equals("off") && !bar.isEnabled(player);
                    bar.setEnabled(player, on);
                    messages().send(player, on ? "bossbar.on" : "bossbar.off");
                    return true;
                }).register();
            }
            if (section("job-coefficient").getBoolean("enabled", true)) {
                Bukkit.getPluginManager().registerEvents(new JobCoefficientListener(this), this);
            }
        }
        // data of the collections menu (EcoCollections)
        if (section("collections-menu").getBoolean("enabled", true)) CollectionInfo.register(this);
    }

    private ConfigurationSection section(String id) {
        ConfigurationSection c = getConfig().getConfigurationSection(id);
        return c != null ? c : new YamlConfiguration();
    }
}
