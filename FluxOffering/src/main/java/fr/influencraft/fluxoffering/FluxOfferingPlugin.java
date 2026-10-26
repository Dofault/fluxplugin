package fr.influencraft.fluxoffering;

import fr.influencraft.fluxoffering.command.FluxOfferingAdminCommand;
import fr.influencraft.fluxoffering.command.FluxOfferingAdminTabCompleter;
import fr.influencraft.fluxoffering.command.OffrandeCommand;
import fr.influencraft.fluxoffering.command.OffrandeTabCompleter;
import fr.influencraft.fluxoffering.config.ConfigManager;
import fr.influencraft.fluxoffering.config.MessagesManager;
import fr.influencraft.fluxoffering.data.DataManager;
import fr.influencraft.fluxoffering.data.PlayerDataListener;
import fr.influencraft.fluxoffering.gui.CollectionMenu;
import fr.influencraft.fluxoffering.gui.EditorChatListener;
import fr.influencraft.fluxoffering.gui.GuiListener;
import fr.influencraft.fluxoffering.integration.OffrandePlaceholderExpansion;
import fr.influencraft.fluxoffering.integration.VaultEconomyHook;
import fr.influencraft.fluxoffering.service.DepositService;
import org.bukkit.plugin.java.JavaPlugin;

public final class FluxOfferingPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private MessagesManager messagesManager;
    private DataManager dataManager;

    @Override
    public void onLoad() {
        LegacyDataFolder.migrate(this, "InfluOffrande");
    }

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();

        messagesManager = new MessagesManager(this);
        messagesManager.load();

        dataManager = new DataManager(this);
        dataManager.init();

        if (VaultEconomyHook.setup(this)) {
            getLogger().info("Vault detected, BitBlock deposits (offrande money) are enabled.");
        } else {
            getLogger().warning("Vault (or an economy plugin behind it) not found — offrande money deposits will fail until it's installed.");
        }

        DepositService depositService = new DepositService(configManager, dataManager);
        EditorChatListener editorChatListener = new EditorChatListener(this, configManager, messagesManager);
        CollectionMenu collectionMenu = new CollectionMenu(configManager, messagesManager, dataManager, depositService, editorChatListener);
        GuiListener guiListener = new GuiListener(configManager, collectionMenu);
        PlayerDataListener playerDataListener = new PlayerDataListener(this, dataManager);

        getServer().getPluginManager().registerEvents(guiListener, this);
        getServer().getPluginManager().registerEvents(editorChatListener, this);
        getServer().getPluginManager().registerEvents(playerDataListener, this);

        OffrandeCommand executor = new OffrandeCommand(configManager, messagesManager, dataManager, collectionMenu);
        OffrandeTabCompleter tabCompleter = new OffrandeTabCompleter(configManager, dataManager);
        var command = getCommand("offrande");
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(tabCompleter);
        }

        FluxOfferingAdminCommand adminExecutor = new FluxOfferingAdminCommand(configManager, messagesManager, dataManager);
        FluxOfferingAdminTabCompleter adminTabCompleter = new FluxOfferingAdminTabCompleter(configManager);
        var adminCommand = getCommand("influoffrande");
        if (adminCommand != null) {
            adminCommand.setExecutor(adminExecutor);
            adminCommand.setTabCompleter(adminTabCompleter);
        }

        // Players already online when the plugin (re)loads (e.g. /reload) still need their data cached.
        for (var player : getServer().getOnlinePlayers()) {
            getServer().getScheduler().runTaskAsynchronously(this,
                    () -> dataManager.loadIntoCache(player.getUniqueId(), player.getName()));
        }

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new OffrandePlaceholderExpansion(this, configManager, dataManager).register();
            getLogger().info("PlaceholderAPI detected, %offrandes_*% placeholders registered.");
        } else {
            getLogger().info("PlaceholderAPI not found, placeholders are disabled (the plugin still works fine otherwise).");
        }

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
