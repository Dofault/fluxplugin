package fr.influencraft.fluxshop;

import fr.influencraft.fluxshop.autosell.AutoSellExceptionManager;
import fr.influencraft.fluxshop.autosell.AutoSellFilter;
import fr.influencraft.fluxshop.autosell.AutoSellItemManager;
import fr.influencraft.fluxshop.autosell.AutoSellMenu;
import fr.influencraft.fluxshop.commands.*;
import fr.influencraft.fluxshop.listener.AutoSellEcoTelekinesisListener;
import fr.influencraft.fluxshop.listener.AutoSellPickupListener;
import fr.influencraft.fluxshop.listener.AutoSellTelepathyListener;
import fr.influencraft.fluxshop.listener.AutoSellToggleListener;
import fr.influencraft.fluxshop.listener.ShopBlockListener;
import fr.influencraft.fluxshop.managers.LangManager;
import fr.influencraft.fluxshop.managers.PriceManager;
import fr.influencraft.fluxshop.managers.SellManager;
import fr.influencraft.fluxshop.managers.TpShopCooldownManager;
import fr.influencraft.fluxshop.placeholder.SupermarketPlaceholderExpansion;
import fr.influencraft.fluxshop.integration.ItemsAdderLoadListener;
import fr.influencraft.fluxshop.shop.ShopActivity;
import fr.influencraft.fluxshop.shop.ShopBlockRestorer;
import fr.influencraft.fluxshop.shop.ShopEditor;
import fr.influencraft.fluxshop.shop.ShopMenu;
import fr.influencraft.fluxshop.shop.ShopRegistry;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class FluxShop extends JavaPlugin {

    private static final List<String> HELP_KEYS = List.of(
            "shop-command-chest",
            "shop-command-editor",
            "shop-command-sellblock",
            "shop-command-setprice",
            "shop-command-checkprices",
            "shop-command-tpshop",
            "shop-command-settpshop",
            "shop-command-autosell",
            "shop-command-reload");

    private Economy econ;

    @Override
    public void onLoad() {
        fr.influencraft.flux.api.util.DataFolders.migrate(this, "VaultSupermarket");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!setupEconomy()) {
            getLogger().severe("Vault introuvable ou aucun plugin d'économie !");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        LangManager lang = new LangManager(this, "fr");
        PriceManager priceManager = new PriceManager(this);
        priceManager.logInvalidItems();

        ShopActivity activity = new ShopActivity();
        ShopRegistry shopRegistry = new ShopRegistry(this);
        ShopMenu shopMenu = new ShopMenu(this, lang, priceManager, activity, econ);
        ShopEditor shopEditor = new ShopEditor(this, lang, priceManager, shopRegistry, econ);

        AutoSellItemManager autoSellItemManager = new AutoSellItemManager(this, lang);
        AutoSellFilter autoSellFilter = new AutoSellFilter(this);
        AutoSellExceptionManager autoSellExceptions = new AutoSellExceptionManager(this, autoSellFilter);
        autoSellFilter.setExceptionManager(autoSellExceptions);
        AutoSellMenu autoSellMenu = new AutoSellMenu(this, lang, priceManager, autoSellItemManager,
                autoSellExceptions, econ);
        SellManager sellManager = new SellManager(this, lang, priceManager, activity, econ, autoSellItemManager);

        PluginManager pm = getServer().getPluginManager();
        // Bonus de prix de vente des autres plugins (SupermarketAPI) : retirés quand leur plugin s'arrête.
        pm.registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onPluginDisable(org.bukkit.event.server.PluginDisableEvent event) {
                fr.influencraft.fluxshop.api.SupermarketAPI.unregisterSellPriceModifier(event.getPlugin());
            }
        }, this);
        pm.registerEvents(activity, this);
        pm.registerEvents(shopMenu, this);
        pm.registerEvents(shopEditor, this);
        pm.registerEvents(new ShopBlockListener(lang, shopRegistry, shopMenu, shopEditor, sellManager), this);

        // Coffres magasins absents : reposés une fois les mondes chargés, et après le chargement
        // des données ItemsAdder pour les blocs custom.
        ShopBlockRestorer restorer = new ShopBlockRestorer(this, shopRegistry);
        pm.registerEvents(restorer, this);
        if (pm.isPluginEnabled("ItemsAdder")) {
            pm.registerEvents(new ItemsAdderLoadListener(restorer::restoreAll), this);
        }
        Bukkit.getScheduler().runTask(this, restorer::restoreAll);
        pm.registerEvents(new AutoSellToggleListener(autoSellItemManager, autoSellMenu), this);
        pm.registerEvents(autoSellExceptions, this);
        pm.registerEvents(autoSellMenu, this);
        pm.registerEvents(new AutoSellPickupListener(autoSellItemManager, autoSellFilter, priceManager, econ), this);
        // Télépathie : un listener par plugin d'enchantement, chacun ne livre pas ses drops de la même façon.
        if (pm.isPluginEnabled("AdvancedEnchantments")) {
            pm.registerEvents(
                    new AutoSellTelepathyListener(this, autoSellItemManager, autoSellFilter, priceManager, econ), this);
        }
        if (pm.isPluginEnabled("eco")) {
            pm.registerEvents(
                    new AutoSellEcoTelekinesisListener(autoSellItemManager, autoSellFilter, priceManager, econ), this);
        }

        TpShopCooldownManager tpShopCooldownManager = new TpShopCooldownManager(this);
        TpShopCommand tpShopCommand = new TpShopCommand(this, lang, tpShopCooldownManager);
        AutoSellCommand autoSellCommand = new AutoSellCommand(lang, autoSellItemManager);

        CommandManager commandManager = new CommandManager();
        for (ShopAdminCommand.Action action : ShopAdminCommand.Action.values()) {
            commandManager.register(new ShopAdminCommand(action, lang, shopRegistry));
        }
        commandManager.register(new SetPriceCommand(lang, priceManager));
        commandManager.register(new CheckPricesCommand(lang, priceManager));
        commandManager.register(tpShopCommand);
        commandManager.register(new SetTpShopCommand(lang, tpShopCommand));
        commandManager.register(new ReloadCommand(this, lang, priceManager, shopRegistry, autoSellFilter));
        commandManager.register(autoSellCommand);

        getCommand("supermarket").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                // Seule exception au "joueurs uniquement" : la console peut donner l'objet de
                // vente auto a un joueur precis ("/supermarket autosell <joueur>", meme chemin
                // que giveOther) — utilise par un plugin de shop qui l'appelle apres avoir fait
                // payer le joueur, sans que celui-ci ait besoin de la permission lui-meme.
                if (args.length == 2 && args[0].equalsIgnoreCase("autosell")) {
                    autoSellCommand.giveOther(sender, args[1]);
                } else {
                    sender.sendMessage("Uniquement pour les joueurs.");
                }
                return true;
            }
            if (!commandManager.execute(player, args)) {
                player.sendMessage(lang.get("shop-commands-header"));
                for (String key : HELP_KEYS) {
                    player.sendMessage(lang.get(key));
                }
            }
            return true;
        });
        getCommand("supermarket").setTabCompleter(new SupermarketTabCompleter(commandManager));

        if (pm.getPlugin("PlaceholderAPI") != null) {
            new SupermarketPlaceholderExpansion(activity, tpShopCooldownManager).register();
            getLogger().info("PlaceholderAPI expansion enregistrée.");
        }

        getLogger().info(shopRegistry.getChestCount() + " coffre(s) magasin et " + shopRegistry.getSellBlockCount()
                + " bloc(s) de vente chargés.");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null)
            return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null)
            return false;
        econ = rsp.getProvider();
        return econ != null;
    }
}
