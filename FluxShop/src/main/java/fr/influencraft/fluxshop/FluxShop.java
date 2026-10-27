package fr.influencraft.fluxshop;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.util.Resources;
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
import fr.influencraft.fluxshop.managers.SellMenu;
import fr.influencraft.fluxshop.placeholder.ShopPlaceholders;
import fr.influencraft.fluxshop.integration.ItemsAdderLoadListener;
import fr.influencraft.fluxshop.shop.ShopActivity;
import fr.influencraft.fluxshop.shop.ShopBlockRestorer;
import fr.influencraft.fluxshop.shop.ShopEditor;
import fr.influencraft.fluxshop.shop.ShopMenu;
import fr.influencraft.fluxshop.shop.ShopRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.command.CommandExecutor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

/**
 * FluxShop (ex VaultSupermarket): shop chests (buying menu, admin editor), sell blocks (selling menu, sell prices),
 * auto-sell item. Every menu is a Flux menu ({@code plugins/FluxShop/menus/}), payments go through the main Flux
 * currency.
 */
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

    @Override
    public void onLoad() {
        fr.influencraft.flux.api.util.DataFolders.migrate(this, "VaultSupermarket");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        LangManager lang = new LangManager(this, "fr");
        PriceManager priceManager = new PriceManager(this);
        priceManager.logInvalidItems();

        ShopActivity activity = new ShopActivity();
        ShopRegistry shopRegistry = new ShopRegistry(this);
        ShopMenu shopMenu = new ShopMenu(this, lang, priceManager, activity);
        ShopEditor shopEditor = new ShopEditor(this, lang, priceManager, shopRegistry);

        AutoSellItemManager autoSellItemManager = new AutoSellItemManager(this, lang);
        AutoSellFilter autoSellFilter = new AutoSellFilter(this);
        AutoSellExceptionManager autoSellExceptions = new AutoSellExceptionManager(this, autoSellFilter);
        autoSellFilter.setExceptionManager(autoSellExceptions);
        AutoSellMenu autoSellMenu = new AutoSellMenu(this, lang, priceManager, autoSellItemManager, autoSellExceptions);
        SellManager sellManager = new SellManager(this, lang, priceManager, activity, autoSellItemManager);

        // Flux menus: lists drawn by code, clicks through [shop] <...>
        shopMenu.register();
        shopEditor.register();
        sellManager.register();
        autoSellMenu.register();
        FluxApi.registerAction(this, "shop", (player, view, argument) ->
                click(player, view, argument, shopMenu, shopEditor, sellManager.sellMenu(), autoSellMenu));
        Resources.shipMenus(this, FluxApi.language());

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new Listener() {
            // sell price bonuses of other plugins (SupermarketAPI): removed when their plugin stops
            @EventHandler
            public void onPluginDisable(org.bukkit.event.server.PluginDisableEvent event) {
                fr.influencraft.fluxshop.api.SupermarketAPI.unregisterSellPriceModifier(event.getPlugin());
            }

            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                UUID uuid = event.getPlayer().getUniqueId();
                shopMenu.forget(uuid);
                shopEditor.forget(uuid);
                sellManager.sellMenu().forget(uuid);
                autoSellMenu.forget(uuid);
            }
        }, this);
        pm.registerEvents(activity, this);
        pm.registerEvents(new ShopBlockListener(lang, shopRegistry, shopMenu, shopEditor, sellManager), this);

        // missing shop chests are placed again once the worlds are loaded, and after ItemsAdder loaded its data
        // for custom blocks
        ShopBlockRestorer restorer = new ShopBlockRestorer(this, shopRegistry);
        pm.registerEvents(restorer, this);
        if (pm.isPluginEnabled("ItemsAdder")) {
            pm.registerEvents(new ItemsAdderLoadListener(restorer::restoreAll), this);
        }
        Bukkit.getScheduler().runTask(this, restorer::restoreAll);
        pm.registerEvents(new AutoSellToggleListener(autoSellItemManager, autoSellMenu), this);
        pm.registerEvents(autoSellExceptions, this);
        pm.registerEvents(new AutoSellPickupListener(autoSellItemManager, autoSellFilter, priceManager), this);
        // telepathy: one listener per enchantment plugin, each delivers its drops its own way
        if (pm.isPluginEnabled("AdvancedEnchantments")) {
            pm.registerEvents(new AutoSellTelepathyListener(this, autoSellItemManager, autoSellFilter, priceManager), this);
        }
        if (pm.isPluginEnabled("eco")) {
            pm.registerEvents(new AutoSellEcoTelekinesisListener(autoSellItemManager, autoSellFilter, priceManager), this);
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

        CommandExecutor executor = (sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                // only exception to "players only": the console can give the auto-sell item to a given player
                // ("/supermarket autosell <player>", same path as giveOther), used by a shop plugin after charging
                if (args.length == 2 && args[0].equalsIgnoreCase("autosell")) {
                    autoSellCommand.giveOther(sender, args[1]);
                } else {
                    sender.sendMessage("Players only.");
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
        };
        Commands.create(this, "supermarket").description("Shop chests, sell blocks and auto-sell.")
                .usage("/supermarket <chest|setprice|checkprices|tpshop|settpshop|autosell|reload>")
                .bukkit(executor, new SupermarketTabCompleter(commandManager)).register();

        // %fluxshop_*%: resolved by Flux in menus, exposed to PlaceholderAPI by Flux
        ShopPlaceholders placeholders = new ShopPlaceholders(lang, activity, tpShopCooldownManager, shopEditor);
        FluxApi.registerPlaceholders(this, "fluxshop", placeholders);

        getLogger().info(shopRegistry.getChestCount() + " coffre(s) magasin et " + shopRegistry.getSellBlockCount()
                + " bloc(s) de vente chargés.");
    }

    /** {@code [shop] <action> <arguments>} clicks of the shop menus. */
    private static void click(Player player, MenuView view, String argument, ShopMenu shopMenu, ShopEditor shopEditor,
            SellMenu sellMenu, AutoSellMenu autoSellMenu) {
        String[] args = argument.trim().split("\\s+");
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "buy" -> {
                if (args.length >= 3) shopMenu.click(player, view, number(args[1]), number(args[2]));
            }
            case "editor-price" -> {
                if (args.length >= 2) shopEditor.price(player, view, number(args[1]));
            }
            case "editor-remove" -> {
                if (args.length >= 2) shopEditor.remove(player, view, number(args[1]));
            }
            case "editor-add" -> {
                if (args.length >= 2) shopEditor.add(player, view, number(args[1]));
            }
            case "sell-changed" -> sellMenu.changed(player, view);
            case "sell-confirm" -> sellMenu.confirm(player, view);
            case "autosell" -> {
                if (args.length < 2) return;
                // [shop] autosell <button> [id...] [left|right]
                int last = args.length;
                boolean right = false;
                if (last > 2 && (args[last - 1].equalsIgnoreCase("right") || args[last - 1].equalsIgnoreCase("left"))) {
                    right = args[last - 1].equalsIgnoreCase("right");
                    last--;
                }
                String id = String.join(" ", java.util.Arrays.copyOfRange(args, 2, Math.max(2, last)));
                autoSellMenu.click(player, view, args[1].toLowerCase(java.util.Locale.ROOT), id, right);
            }
            default -> {
            }
        }
    }

    private static int number(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
