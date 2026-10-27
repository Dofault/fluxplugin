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
        String fishing = choice(config, "fishing");
        fr.influencraft.flux.api.fishing.Fishing.use((fishing.equals("auto") || fishing.equals("customfishing")) && enabled("CustomFishing")
                ? new fr.influencraft.flux.adapters.customfishing.CustomFishingFishing() : null, flux.getConfig().getConfigurationSection("fishing.tiers"));
        String claims = choice(config, "claims");
        fr.influencraft.flux.api.claims.Claims.use((claims.equals("auto") || claims.equals("lands")) && enabled("Lands")
                ? new fr.influencraft.flux.adapters.lands.LandsClaims(flux) : null);
        String shops = choice(config, "chest-shops");
        if ((shops.equals("auto") || shops.equals("quickshop")) && (enabled("FluxChestShop") || enabled("QuickShop-Hikari"))) {
            var adapter = new fr.influencraft.flux.adapters.quickshop.QuickShopShops();
            fr.influencraft.flux.api.shops.ChestShops.use(adapter);
            Bukkit.getPluginManager().registerEvents(adapter, flux);
        } else {
            fr.influencraft.flux.api.shops.ChestShops.use(null);
        }
        // packets to one client (Flux depends on packetevents)
        fr.influencraft.flux.api.client.ClientTags.use(enabled("packetevents") ? new fr.influencraft.flux.adapters.packetevents.PacketEventsTags() : null);
        String money = choice(config, "money-events");
        if ((money.equals("auto") || money.equals("cmi")) && enabled("CMI")) {
            Bukkit.getPluginManager().registerEvents(new fr.influencraft.flux.adapters.cmi.CmiMoneyEvents(), flux);
        }
        if (enabled("eco")) {
            var world = new fr.influencraft.flux.adapters.eco.EcoWorld();
            fr.influencraft.flux.api.world.PlacedBlocks.use(world);
            Bukkit.getPluginManager().registerEvents(world, flux);
        } else {
            fr.influencraft.flux.api.world.PlacedBlocks.use(null);
        }
        if (enabled("eco") && !choice(config, "eco-vault-bridge").equals("false")) {
            // eco plugins hook Vault once at their start, before CMI joins Vault: bound again once the server is up
            Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler
                public void onServerLoad(org.bukkit.event.server.ServerLoadEvent event) {
                    fr.influencraft.flux.adapters.eco.EcoVaultBridge.bindIfNeeded();
                }
            }, flux);
        }
        flux.getLogger().info("Providers: money events = " + (money.equals("none") || !enabled("CMI") ? "none" : "CMI") + ", items = " + CustomItems.providerName() + ", jobs = " + fr.influencraft.flux.api.jobs.Jobs.providerName()
                + ", fishing = " + fr.influencraft.flux.api.fishing.Fishing.providerName()
                + ", claims = " + fr.influencraft.flux.api.claims.Claims.providerName()
                + ", chest shops = " + fr.influencraft.flux.api.shops.ChestShops.providerName());
    }

    static String choice(ConfigurationSection config, String key) {
        return config == null ? "auto" : config.getString(key, "auto").toLowerCase(Locale.ROOT);
    }

    static boolean enabled(String plugin) {
        return Bukkit.getPluginManager().isPluginEnabled(plugin);
    }
}
