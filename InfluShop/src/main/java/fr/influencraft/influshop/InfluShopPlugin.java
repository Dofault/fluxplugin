package fr.influencraft.influshop;

import fr.influencraft.influshop.command.ShopAdminCommand;
import fr.influencraft.influshop.command.ShopCommand;
import fr.influencraft.influshop.config.ConfigManager;
import fr.influencraft.influshop.config.MessagesManager;
import fr.influencraft.influshop.gui.GuiListener;
import fr.influencraft.influshop.gui.PriceChatListener;
import fr.influencraft.influshop.gui.ShopMenu;
import fr.influencraft.influshop.integration.PlayerPointsHook;
import org.bukkit.plugin.java.JavaPlugin;

public final class InfluShopPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private MessagesManager messagesManager;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();

        messagesManager = new MessagesManager(this);
        messagesManager.load();

        PriceChatListener priceChatListener = new PriceChatListener(this, messagesManager);
        ShopMenu shopMenu = new ShopMenu(this, configManager, messagesManager, priceChatListener);
        GuiListener guiListener = new GuiListener(configManager, shopMenu);

        getServer().getPluginManager().registerEvents(guiListener, this);
        getServer().getPluginManager().registerEvents(priceChatListener, this);

        var shopCommand = getCommand("shop");
        if (shopCommand != null) {
            shopCommand.setExecutor(new ShopCommand(this, shopMenu));
        }
        var adminCommand = getCommand("influshop");
        if (adminCommand != null) {
            adminCommand.setExecutor(new ShopAdminCommand(configManager, messagesManager));
        }

        if (!PlayerPointsHook.isEnabled()) {
            getLogger().warning("PlayerPoints not found — the shop will always show a 0 Influence balance and every purchase will fail until it's installed.");
        }
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public MessagesManager messagesManager() {
        return messagesManager;
    }
}
