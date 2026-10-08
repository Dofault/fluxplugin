package io.dofault.supermarket.main;

import io.dofault.supermarket.autosell.AutoSellFilter;
import io.dofault.supermarket.autosell.AutoSellItemManager;
import io.dofault.supermarket.autosell.AutoSellMenu;
import io.dofault.supermarket.autosell.AutoSellExceptionManager;
import io.dofault.supermarket.commands.*;
import com.comphenix.protocol.ProtocolLibrary;
import io.dofault.supermarket.listener.AutoSellPickupListener;
import io.dofault.supermarket.listener.AutoSellEcoTelekinesisListener;
import io.dofault.supermarket.listener.AutoSellTelepathyListener;
import io.dofault.supermarket.listener.AutoSellToggleListener;
import io.dofault.supermarket.listener.ShopItemUsePacketListener;
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
    private AutoSellItemManager autoSellItemManager;
    private AutoSellFilter autoSellFilter;

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
        autoSellItemManager = new AutoSellItemManager(this, lang);
        autoSellFilter = new AutoSellFilter(this);
        AutoSellExceptionManager autoSellExceptions = new AutoSellExceptionManager(this, autoSellFilter);
        autoSellFilter.setExceptionManager(autoSellExceptions);
        AutoSellMenu autoSellMenu = new AutoSellMenu(this, lang, priceManager, playerList, autoSellItemManager,
                autoSellExceptions, econ);
        sellManager = new SellManager(this, lang, shopManager, priceManager, playerList, econ, autoSellItemManager);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(
                new ShopRestrictionListener(this, lang, playerList, chestManager, shopManager, sellManager, econ),
                this);
        getServer().getPluginManager().registerEvents(
                new AutoSellToggleListener(autoSellItemManager, autoSellMenu), this);
        getServer().getPluginManager().registerEvents(autoSellExceptions, this);
        getServer().getPluginManager().registerEvents(autoSellMenu, this);
        getServer().getPluginManager().registerEvents(
                new AutoSellPickupListener(autoSellItemManager, autoSellFilter, priceManager, econ), this);
        // Télépathie : un listener par plugin d'enchantement, chacun ne livre pas ses drops de la même façon.
        if (Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments")) {
            getServer().getPluginManager().registerEvents(
                    new AutoSellTelepathyListener(this, autoSellItemManager, autoSellFilter, priceManager, econ), this);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("eco")) {
            getServer().getPluginManager().registerEvents(
                    new AutoSellEcoTelekinesisListener(autoSellItemManager, autoSellFilter, priceManager, econ), this);
        }

        if (Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
            ProtocolLibrary.getProtocolManager()
                    .addPacketListener(new ShopItemUsePacketListener(this, playerList, shopManager));
        }

        // Initialisation du CommandManager
        commandManager = new CommandManager();
        commandManager.register(new EntryCommand(lang, shopManager, chestManager));
        commandManager.register(new ExitCommand(lang, shopManager));
        commandManager.register(new SaveCommand(lang, shopManager));
        commandManager.register(new SetPriceCommand(lang, priceManager));
        commandManager.register(new DifferenceCommand(lang, shopManager, playerList));
        commandManager.register(new PayCommand(lang, shopManager, playerList));
        commandManager.register(new AddChestCommand(lang, chestManager));
        commandManager.register(new RemoveChestCommand(lang, chestManager));
        commandManager.register(new AddItemChestCommand(lang, chestManager));
        tpShopCooldownManager = new TpShopCooldownManager(this);
        commandManager.register(
                new TpShopCommand(this, lang, shopManager, chestManager, playerList, tpShopCooldownManager));
        commandManager.register(new CheckPricesCommand(lang, priceManager));
        commandManager.register(new ReloadCommand(this, lang, priceManager, chestManager, autoSellFilter));
        AutoSellCommand autoSellCommand = new AutoSellCommand(lang, autoSellItemManager);
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
                player.sendMessage(lang.get("shop-command-reload"));
                player.sendMessage(lang.get("shop-command-autosell"));

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
                if (cmdName.equals("tpshop") || cmdName.equals("autosell")) {
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
        if (!playerList.isInShop(player.getUniqueId()))
            return;

        // Le joueur s'est déconnecté à l'intérieur du shop : on ne peut rien faire de
        // fiable au moment du quit (un teleport() y est ignoré par le serveur), donc
        // tout se joue ici. Le teleport est décalé d'un tick pour passer après les
        // plugins qui repositionnent le joueur à la connexion (CMI spawn, etc.).
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline() || !playerList.isInShop(player.getUniqueId()))
                return;

            org.bukkit.Location destination = null;
            try {
                destination = resolveShopExitLocation(player);
            } catch (Exception ex) {
                getLogger().warning("Position de sortie du shop introuvable pour " + player.getName()
                        + ", il est sorti du shop sur place : " + ex.getMessage());
            }

            // L'inventaire est rendu en premier : c'est la partie qui efface les items
            // non payés emportés dans le panier. Si le teleport échoue ensuite (position
            // absente de la config, monde non chargé, annulation par un autre plugin),
            // le joueur se retrouve au pire debout dans le magasin sans y être inscrit,
            // ce qui ne donne accès à rien : les coffres du shop sont bloqués depuis
            // l'extérieur.
            playerList.leaveShop(player, true);
            if (destination != null && destination.getWorld() != null) {
                player.teleport(destination);
            }
            player.sendMessage(lang.get("shop-exit"));
        }, 5L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Le joueur reste volontairement marqué « dans le shop » (état persisté en base
        // avec son inventaire d'origine) : c'est onPlayerJoin qui le sortira, le
        // téléportera et lui rendra son inventaire à la reconnexion.
        Player player = event.getPlayer();
        if (!playerList.isInShop(player.getUniqueId()))
            return;

        // En revanche, la grille de craft 2x2 et l'item tenu au curseur doivent être
        // vidés ici : à la déconnexion, le serveur les jette au sol dans le magasin
        // (Player.remove -> InventoryMenu.removed), et n'importe qui pouvait ensuite
        // ramasser des items non payés. Désassemblage de PlayerList.remove (Paper 26.2)
        // confirmé : closeContainer -> PlayerQuitEvent -> save -> removePlayerImmediately,
        // donc ce nettoyage passe bien avant le drop. Aucune perte pour le joueur : sa
        // reconnexion lui rend l'inventaire complet sauvegardé à l'entrée du magasin.
        org.bukkit.inventory.InventoryView view = player.getOpenInventory();
        org.bukkit.inventory.Inventory top = view.getTopInventory();
        if (top.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING
                || top.getType() == org.bukkit.event.inventory.InventoryType.WORKBENCH) {
            top.clear();
        }
        player.setItemOnCursor(null);
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
