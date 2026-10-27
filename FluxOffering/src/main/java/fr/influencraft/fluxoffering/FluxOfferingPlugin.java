package fr.influencraft.fluxoffering;

import fr.influencraft.fluxoffering.command.FluxOfferingAdminCommand;
import fr.influencraft.fluxoffering.command.FluxOfferingAdminTabCompleter;
import fr.influencraft.fluxoffering.command.OffrandeCommand;
import fr.influencraft.fluxoffering.command.OffrandeTabCompleter;
import fr.influencraft.fluxoffering.config.ConfigManager;
import fr.influencraft.fluxoffering.config.MessagesManager;
import fr.influencraft.fluxoffering.data.DataManager;
import fr.influencraft.fluxoffering.data.PlayerDataListener;
import fr.influencraft.fluxoffering.gui.OfferingMenu;
import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.fluxoffering.integration.OffrandePlaceholderExpansion;
import fr.influencraft.fluxoffering.service.DepositService;
import org.bukkit.plugin.java.JavaPlugin;

public final class FluxOfferingPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private MessagesManager messagesManager;
    private DataManager dataManager;

    @Override
    public void onLoad() {
        fr.influencraft.flux.api.util.DataFolders.migrate(this, "InfluOffrande");
    }

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();

        messagesManager = new MessagesManager(this);
        messagesManager.load();

        dataManager = new DataManager(this);
        dataManager.init();

        DepositService depositService = new DepositService(configManager, dataManager);
        OfferingMenu offeringMenu = new OfferingMenu(this, configManager, messagesManager, dataManager, depositService);
        PlayerDataListener playerDataListener = new PlayerDataListener(this, dataManager);
        getServer().getPluginManager().registerEvents(playerDataListener, this);
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
                offeringMenu.forget(event.getPlayer().getUniqueId());
            }
        }, this);

        // the offering menu is a Flux menu shipped in the jar (menus/offering.yml, French in i18n/fr_FR)
        fr.influencraft.flux.api.util.Resources.shipMenus(this, FluxApi.language());
        FluxApi.registerAction(this, "offering", offeringMenu);
        fr.influencraft.flux.api.list.Lists.register(this, OfferingMenu.LIST, offeringMenu);

        OffrandeCommand executor = new OffrandeCommand(configManager, messagesManager, dataManager, offeringMenu);
        OffrandeTabCompleter tabCompleter = new OffrandeTabCompleter(configManager, dataManager);
        Commands.create(this, "offering").aliases("offrande").description("Opens an offering.")
                .bukkit(executor, tabCompleter).register();
        FluxOfferingAdminCommand adminExecutor = new FluxOfferingAdminCommand(configManager, messagesManager, dataManager);
        FluxOfferingAdminTabCompleter adminTabCompleter = new FluxOfferingAdminTabCompleter(configManager);
        Commands.create(this, "offerings").aliases("influoffrande").description("Offerings administration.")
                .permission("offrandes.admin").bukkit(adminExecutor, adminTabCompleter).register();

        // Players already online when the plugin (re)loads (e.g. /reload) still need their data cached.
        for (var player : getServer().getOnlinePlayers()) {
            getServer().getScheduler().runTaskAsynchronously(this,
                    () -> dataManager.loadIntoCache(player.getUniqueId(), player.getName()));
        }

        OffrandePlaceholderExpansion placeholders = new OffrandePlaceholderExpansion(this, configManager, dataManager);
        placeholders.menu(offeringMenu);
        FluxApi.registerPlaceholders(this, "offrandes", placeholders::onRequest);

        if (getServer().getPluginManager().isPluginEnabled("ItemsAdder")) {
            getLogger().info("ItemsAdder detected, custom items can be used in collections.yml.");
        }

        int autosaveMinutes = Math.max(1, configManager.settings().autosaveIntervalMinutes());
        long periodTicks = autosaveMinutes * 60L * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(this, dataManager::saveAllDirty, periodTicks, periodTicks);
    }

    @Override
    public void onDisable() {
        if (dataManager != null) {
            dataManager.saveAllDirty();
        }
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public MessagesManager messagesManager() {
        return messagesManager;
    }

    public DataManager dataManager() {
        return dataManager;
    }
}
