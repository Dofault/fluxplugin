package fr.influencraft.flux.providers;

import fr.influencraft.flux.api.items.CustomItems;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * Chooses the adapters behind the services of the suite ({@code providers} section of config.yml): the only place
 * that knows which third-party plugin provides custom items, jobs or money events. {@code auto} takes the known
 * plugin that is installed, {@code none} turns the service off.
 */
public final class Providers {

    private Providers() {
    }

    public static void start(JavaPlugin flux, ConfigurationSection config) {
        String items = choice(config, "items");
        if ((items.equals("auto") || items.equals("itemsadder")) && enabled("ItemsAdder")) {
            var adapter = new fr.influencraft.flux.adapters.itemsadder.ItemsAdderItems();
            CustomItems.use(adapter);
            Bukkit.getPluginManager().registerEvents(adapter, flux);
        } else {
            CustomItems.use(null);
        }
        String jobs = choice(config, "jobs");
        if ((jobs.equals("auto") || jobs.equals("ecojobs")) && enabled("EcoJobs")) {
            fr.influencraft.flux.adapters.ecojobs.EcoJobsJobs adapter = new fr.influencraft.flux.adapters.ecojobs.EcoJobsJobs(flux);
            fr.influencraft.flux.api.jobs.Jobs.use(adapter);
            Bukkit.getPluginManager().registerEvents(adapter, flux);
        } else {
            fr.influencraft.flux.api.jobs.Jobs.use(null);
        }
        flux.getLogger().info("Providers: items = " + CustomItems.providerName() + ", jobs = " + fr.influencraft.flux.api.jobs.Jobs.providerName());
    }

    static String choice(ConfigurationSection config, String key) {
        return config == null ? "auto" : config.getString(key, "auto").toLowerCase(Locale.ROOT);
    }

    static boolean enabled(String plugin) {
        return Bukkit.getPluginManager().isPluginEnabled(plugin);
    }
}
