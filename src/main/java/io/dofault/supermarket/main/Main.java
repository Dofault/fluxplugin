package io.dofault.supermarket.main;

import io.dofault.supermarket.commands.*;
import io.dofault.supermarket.listener.ShopRestrictionListener;
import io.dofault.supermarket.managers.ChestManager;
import io.dofault.supermarket.managers.LangManager;
import io.dofault.supermarket.managers.PlayerListManager;
import io.dofault.supermarket.managers.PriceManager;
import io.dofault.supermarket.managers.SellManager;
import io.dofault.supermarket.managers.ShopManager;
import io.dofault.supermarket.managers.TpShopCooldownManager;
import io.dofault.supermarket.placeholder.SupermarketPlaceholderExpansion;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class Main extends JavaPlugin implements Listener {

    private ShopManager shopManager;
    private PriceManager priceManager;
    private PlayerListManager playerList;
    private ChestManager chestManager;
    private Economy econ;
    private LangManager lang;
    private SellManager sellManager;
    private TpShopCooldownManager tpShopCooldownManager;

    private CommandManager commandManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!setupEconomy()) {
            getLogger().severe("Vault not found or economy plugin missing!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        String langCode = getConfig().getString("language"); // récupère la langue du config
        lang = new LangManager(this, "fr");

        playerList = new PlayerListManager(this, lang);
        priceManager = new PriceManager(this, lang);
        priceManager.logInvalidItems();
        shopManager = new ShopManager(this, lang, econ, playerList, priceManager);
        chestManager = new ChestManager(this, lang, priceManager);
        sellManager = new SellManager(this, lang, shopManager, priceManager, playerList, econ);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(
                new ShopRestrictionListener(this, lang, playerList, chestManager, shopManager, sellManager, econ),
                this);

        // Initialisation du CommandManager
        commandManager = new CommandManager();
        commandManager.register(new EntryCommand(lang, shopManager, chestManager));
        commandManager.register(new ExitCommand(lang, shopManager));
        commandManager.register(new SaveCommand(lang, shopManager));
        commandManager.register(new SetPriceCommand(lang, priceManager));
        commandManager.register(new DifferenceCommand(lang, shopManager, playerList));
        commandManager.register(new PayCommand(lang, shopManager, playerList));
        commandManager.register(new AddChestCommand(lang, chestManager));
        commandManager.register(new RemoveChestCommand(chestManager));
        commandManager.register(new AddItemChestCommand(lang, chestManager));
        tpShopCooldownManager = new TpShopCooldownManager(this);
        commandManager.register(
                new TpShopCommand(lang, shopManager, chestManager, playerList, tpShopCooldownManager));
        commandManager.register(new CheckPricesCommand(lang, priceManager));

        getCommand("supermarket").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Uniquement pour les joueurs.");
                return true;
            }
            if (!commandManager.execute(player, args)) {

                player.sendMessage(lang.get("shop-commands-header"));
                player.sendMessage(lang.get("shop-command-setprice"));
                player.sendMessage(lang.get("shop-command-additemchest"));
                player.sendMessage(lang.get("shop-command-addchest"));
                player.sendMessage(lang.get("shop-command-removechest"));
                player.sendMessage(lang.get("shop-command-entry-exit"));
                player.sendMessage(lang.get("shop-command-save"));
                player.sendMessage(lang.get("shop-command-difference"));
                player.sendMessage(lang.get("shop-command-pay"));
                player.sendMessage(lang.get("shop-command-tpshop"));
                player.sendMessage(lang.get("shop-command-checkprices"));

            }
            return true;
        });

        getCommand("supermarket").setTabCompleter((sender, command, alias, args) -> {
            if (!(sender instanceof Player))
                return Collections.emptyList();

            if (args.length == 1) {
                return commandManager.getCommandNames(); // noms dynamiques
            }

            if (args.length == 2) {
                String cmdName = args[0].toLowerCase();
                if (cmdName.equals("setprice") || cmdName.equals("additemchest")) {
                    String current = args[1].toLowerCase();
                    return Arrays.stream(Material.values())
                            .filter(Material::isItem)
                            .map(Material::name)
                            .map(String::toLowerCase)
                            .filter(name -> name.startsWith(current))
                            .toList();
                }
                if (cmdName.equals("tpshop")) {
                    String current = args[1].toLowerCase();
                    return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .filter(name -> name.toLowerCase().startsWith(current))
                            .toList();
                }
            }

            return Collections.emptyList();
        });

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new SupermarketPlaceholderExpansion(playerList, tpShopCooldownManager).register();
            getLogger().info("PlaceholderAPI expansion enregistrée.");
        }

        getLogger().info("Supermarket plugin activé !");
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

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (playerList.isInShop(player.getUniqueId())) {
            player.teleport(resolveShopExitLocation(player));
            playerList.leaveShop(player, true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (playerList.isInShop(player.getUniqueId())) {
            // Téléporte éventuellement le joueur à l'entrée ou sauvegarde son état
            player.teleport(resolveShopExitLocation(player));
            playerList.leaveShop(player, true);
        }
    }

    private org.bukkit.Location resolveShopExitLocation(Player player) {
        java.util.UUID uuid = player.getUniqueId();
        org.bukkit.Location destination = playerList.hasReturnLocation(uuid)
                ? playerList.getReturnLocation(uuid)
                : shopManager.getEntryLocation(player);
        playerList.clearReturnLocation(uuid);
        return destination;
    }

}
