package io.dofault.supermarket.listener;

import io.dofault.supermarket.integration.AdvancedEnchantmentsHook;
import io.dofault.supermarket.managers.ChestManager;
import io.dofault.supermarket.managers.LangManager;
import io.dofault.supermarket.managers.PlayerListManager;
import io.dofault.supermarket.managers.SellManager;
import io.dofault.supermarket.managers.ShopManager;
import net.milkbowl.vault.economy.Economy;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class ShopRestrictionListener implements Listener {

    private final PlayerListManager playerListManager;
    private ChestManager chestManager;
    private ShopManager shopManager;
    private LangManager lang;
    private final Economy econ;
    private final SellManager sellManager;
    private final JavaPlugin plugin;

    private final Material buyBlockMaterial;
    private final Material sellBlockMaterial;

    public ShopRestrictionListener(JavaPlugin plugin, LangManager lang, PlayerListManager playerListManager,
            ChestManager chestManager, ShopManager shopManager, SellManager sellManager, Economy econ) {
        this.plugin = plugin;
        this.econ = econ;
        this.sellManager = sellManager;
        this.lang = lang;
        this.playerListManager = playerListManager;
        this.chestManager = chestManager;
        this.shopManager = shopManager;

        // Récupère les blocs depuis config.yml
        String buyBlockName = plugin.getConfig().getString("buy_block", "OBSIDIAN");
        String sellBlockName = plugin.getConfig().getString("sell_block", "IRON_BLOCK");

        buyBlockMaterial = Material.matchMaterial(buyBlockName.toUpperCase());
        sellBlockMaterial = Material.matchMaterial(sellBlockName.toUpperCase());

        if (buyBlockMaterial == null) {
            plugin.getLogger().warning("Invalid material for buy_block in config.yml: " + buyBlockName);
        }
        if (sellBlockMaterial == null) {
            plugin.getLogger().warning("Invalid material for sell_block in config.yml: " + sellBlockName);
        }
    }

    // ... le reste du listener
    public void playShopEntrance(Player player) {
        // Séquence harmonieuse : 3 notes
        Sound[][] sequences = {
                { Sound.BLOCK_NOTE_BLOCK_BELL, Sound.BLOCK_NOTE_BLOCK_CHIME },
                { Sound.BLOCK_NOTE_BLOCK_XYLOPHONE }
        };

        float[] pitches = { 1.0f, 1.2f }; // pitch pour chaque "accord"

        new BukkitRunnable() {
            int index = 0;

            @Override
            public void run() {
                if (index >= sequences.length) {
                    cancel();
                    return;
                }
                for (Sound sound : sequences[index]) {
                    player.playSound(player.getLocation(), sound, 1.0f, pitches[index]);
                }
                index++;
            }
        }.runTaskTimer(plugin, 0L, 6L); // chaque accord tous les 6 ticks
    }

    // Empêche toute interaction avec les blocs. Priorité LOWEST : ce handler doit s'exécuter
    // avant celui de n'importe quel autre plugin (ex. AdvancedEnchantments qui ouvre son GUI
    // "RC Book" au clic droit) pour que notre e.setCancelled(true) ait un effet — à NORMAL
    // (le défaut), AE traitait déjà le clic avant nous.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerClick(PlayerInteractEvent e) {

        if (e.getHand() != EquipmentSlot.HAND)
            return; // ignore OFF_HAND

        Player player = e.getPlayer();
        Block block = e.getClickedBlock();

        if (playerListManager.isInShop(player.getUniqueId())) {

            // --- INTERACTION AVEC UN BLOCK (coffres, distributeurs, etc.) ---
            if (block != null) {

                Location loc = block.getLocation();

                // Vérifie si c'est un coffre du shop
                boolean isShopChest = false;
                if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {

                    for (String chestId : chestManager.getChestsIds()) {
                        Location chestLoc = chestManager.getChestLocation(chestId);
                        if (chestLoc.equals(loc)) {
                            isShopChest = true;
                            break;
                        }
                    }

                }
                // Si c'est un coffre du shop et que le joueur est dans le shop → ouvre
                // l'inventaire
                if (isShopChest) {
                    if (block.getState() instanceof Container container) {

                        player.openInventory(container.getInventory());
                        e.setCancelled(true);
                        return;
                    }
                }

                // --- OBSIDIAN CHECKS (panier / paiement) ---
                if (block.getType() == buyBlockMaterial) {
                    if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
                        shopManager.showInventoryDifference(player);
                        e.setCancelled(true);
                        return;
                    }
                    if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                        shopManager.payUser(player);
                        e.setCancelled(true);
                        return;
                    }
                }

                if (block.getType() == sellBlockMaterial) {
                    if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                        sellManager.tryOpenSellBlock(player, block);
                        e.setCancelled(true);
                        return;
                    }
                    if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
                        sellManager.lookPrices(player, block);
                        e.setCancelled(true);
                        return;
                    }
                }

                // EXIT
                if (e.getAction() == Action.RIGHT_CLICK_BLOCK && block.getType().name().contains("GLASS")) {
                    Block blockBelow = player.getLocation().getBlock();

                    if (shopManager.isExit(blockBelow) && playerListManager.isInShop(player.getUniqueId())) {
                        if (shopManager.canPlayerExit(player)) {
                            UUID uuid = player.getUniqueId();
                            Location destination = playerListManager.hasReturnLocation(uuid)
                                    ? playerListManager.getReturnLocation(uuid)
                                    : shopManager.getEntryLocation(player);
                            playerListManager.clearReturnLocation(uuid);
                            playerListManager.leaveShop(player, false);
                            player.teleport(destination);
                            player.sendMessage(lang.get("shop-exit"));

                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 0.9f);
                        } else {
                            player.sendMessage(lang.get("shop-must-pay")); // aigu

                            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 2.0f, 0.5f);

                        }
                    }
                }
            }

            // Bloque tout le reste si le joueur est dans le shop
            e.setCancelled(true);
            return;

        } else { // joueur pas dans le shop

            if (block != null) {
                // Si le joueur clique sur un container
                if (block.getState() instanceof Container container) {
                    Location loc = block.getLocation();

                    boolean isShopChest = false;
                    for (String chestId : chestManager.getChestsIds()) {
                        Location chestLoc = chestManager.getChestLocation(chestId);
                        if (chestLoc.equals(loc)) {
                            isShopChest = true;
                            break;
                        }
                    }

                    // Si ce n'est pas un coffre du shop → laisse interagir normalement
                    if (isShopChest) {
                        e.setCancelled(true);
                        player.sendMessage(lang.get("shop-cannot-open-outside"));
                        return;
                    }
                }

                // ENTRY pour le shop sur block de verre
                if (e.getAction() == Action.RIGHT_CLICK_BLOCK && block.getType().name().contains("GLASS")) {
                    Block blockBelow = player.getLocation().getBlock();

                    if (shopManager.isEntry(blockBelow) && !playerListManager.isInShop(player.getUniqueId())) {
                        // Annule ce clic avant même de savoir si enterShop() réussit : le joueur
                        // n'est pas encore isInShop() à cet instant précis (c'est justement ce
                        // clic qui l'y fait entrer), donc ni le blocage LOWEST ci-dessus ni
                        // ShopItemUsePacketListener ne s'appliquent encore — sans ce
                        // setCancelled explicite, un item tenu en main (ex. livre
                        // AdvancedEnchantments) pouvait aussi traiter ce même clic droit et
                        // s'ouvrir en même temps que le joueur entre dans le shop.
                        e.setCancelled(true);
                        if (playerListManager.enterShop(player)) {
                            chestManager.reloadChests();
                            Location exitLoc = shopManager.getExitLocation(player);
                            playerListManager.enterShop(player);
                            player.teleport(exitLoc);

                            List<String> messages = lang.getList("shop-welcome-messages");
                            for (String msg : messages) {
                                msg = msg.replace("%buy_block%", buyBlockMaterial.name())
                                        .replace("%sell_block%", sellBlockMaterial.name());
                                player.sendMessage(msg);
                            }
                            playShopEntrance(player);
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 0.9f);

                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    if (!playerListManager.isInShop(player.getUniqueId())) {
                                        this.cancel();
                                        return;
                                    }
                                    double balance = shopManager.getBalance(player);
                                    player.sendActionBar(
                                            lang.get("shop-cart-actionbar",
                                                    Map.of("balance", econ.format(balance))));
                                }
                            }.runTaskTimer(plugin, 0L, 20L);
                        }
                        return;
                    }
                }
            }
        }
    }

    // Empêche d'appliquer un item spécial AdvancedEnchantments (livre, tome/orb, rune de
    // protection, parchemin, gemme d'âme...) par glisser-déposer dans le shop
    // (InventoryClickEvent/InventoryDragEvent — un canal complètement différent de
    // PlayerInteractEvent, jamais couvert par le blocage d'interaction ci-dessus). Priorité
    // LOWEST pour s'exécuter avant les listeners d'AdvancedEnchantments, même raison que
    // onPlayerClick. isAnyAeItem() couvre TOUS les items AE, pas seulement les livres
    // d'enchantement, sur l'hypothèse que n'importe quel item du plugin peut avoir sa propre
    // interaction en glisser-déposer. Ne bloque que les clics/drags qui en impliquent un
    // (curseur ou slot ciblé) : le reste de l'inventaire (dépôt d'items dans le coffre de
    // vente, etc.) continue de fonctionner normalement.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player) || !playerListManager.isInShop(player.getUniqueId())) {
            return;
        }
        // Verrouille la grille de craft 2x2 de l'inventaire : ni dépôt d'ingrédient, ni
        // récupération du résultat. Bloque au passage la fuite d'items du panier via la
        // grille, dont le contenu est jeté au sol par le client à la fermeture.
        if (e.getSlotType() == InventoryType.SlotType.CRAFTING
                || e.getSlotType() == InventoryType.SlotType.RESULT) {
            e.setCancelled(true);
            return;
        }
        ItemStack cursor = e.getCursor();
        ItemStack current = e.getCurrentItem();
        if (AdvancedEnchantmentsHook.isAnyAeItem(cursor)
                || AdvancedEnchantmentsHook.isAnyAeItem(current)) {
            e.setCancelled(true);
        }
    }

    // Empêche tout craft tant que le joueur est dans le magasin : sans ça, on pouvait
    // transformer des items du panier non encore payés en autre chose (planches en
    // boutons, etc.) et fausser le calcul de la caisse.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCraftItem(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player player) || !playerListManager.isInShop(player.getUniqueId())) {
            return;
        }
        e.setCancelled(true);
        player.sendMessage(lang.get("shop-craft-blocked"));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.5f);
    }

    // Vide le slot de résultat en amont : le joueur ne voit même pas l'item craftable
    // apparaître, et les macros/clics rapides n'ont rien à récupérer.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        for (HumanEntity viewer : e.getViewers()) {
            if (viewer instanceof Player player && playerListManager.isInShop(player.getUniqueId())) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player) || !playerListManager.isInShop(player.getUniqueId())) {
            return;
        }
        // Un drag (clic maintenu réparti sur plusieurs slots) ne passe pas par
        // InventoryClickEvent : sans ce contrôle, il restait le seul moyen de déposer
        // des items dans la grille de craft 2x2 malgré le verrou posé sur les clics.
        for (int rawSlot : e.getRawSlots()) {
            InventoryType.SlotType slotType = e.getView().getSlotType(rawSlot);
            if (slotType == InventoryType.SlotType.CRAFTING || slotType == InventoryType.SlotType.RESULT) {
                e.setCancelled(true);
                return;
            }
        }
        if (AdvancedEnchantmentsHook.isAnyAeItem(e.getOldCursor())) {
            e.setCancelled(true);
        }
    }

    // Note : le livre de recettes remplit la grille via son propre paquet, sans clic
    // d'inventaire. PlayerRecipeBookClickEvent n'est plus annulable dans l'API Paper
    // 26.2 (vérifié dans paper-api), donc ce chemin-là reste couvert en aval : le slot
    // de résultat est vidé par onPrepareCraft, le craft lui-même par onCraftItem, et ce
    // qui traîne dans la grille est nettoyé à la déconnexion par Main.onPlayerQuit.

    // Empêche de jeter des items
    @EventHandler
    public void onDropItem(PlayerDropItemEvent e) {
        if (playerListManager.isInShop(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onItemPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (playerListManager.isInShop(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    // Empêche d'interagir avec les entités
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (playerListManager.isInShop(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractAtEntityEvent e) {
        Player player = e.getPlayer();
        if (!playerListManager.isInShop(player.getUniqueId()))
            return;

        e.setCancelled(true);
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player))
            return;

        if (playerListManager.isInShop(player.getUniqueId())) {
            event.setCancelled(true); // annule tous les dégâts
        }
    }

    // Empêche d'exécuter des commandes, sauf celles listées dans "allowed-commands"
    // (config.yml). La liste est relue à chaque commande pour que /supermarket reload la
    // prenne en compte sans redémarrage. Priorité LOWEST : les alias CMI (/msg, /r...) ont un
    // listener à LOW qui réécrit le message en "/cmi msg ..." via setMessage ; à NORMAL, on
    // ne voyait plus que "cmi" et /msg était bloqué malgré la liste.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!playerListManager.isInShop(e.getPlayer().getUniqueId())) {
            return;
        }
        if (isCommandAllowed(e.getMessage())) {
            return;
        }
        e.setCancelled(true);
        e.getPlayer().sendMessage(lang.get("shop-command-blocked"));
    }

    // Extrait le nom de la commande tapée ("/cmi:msg Bob salut" -> "msg") et le compare à la
    // liste autorisée. Le préfixe de plugin ("cmi:", "minecraft:"...) est retiré pour qu'il ne
    // serve pas à contourner le filtre.
    private boolean isCommandAllowed(String message) {
        String command = message.toLowerCase();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        int space = command.indexOf(' ');
        if (space >= 0) {
            command = command.substring(0, space);
        }
        int colon = command.indexOf(':');
        if (colon >= 0) {
            command = command.substring(colon + 1);
        }
        if (command.isEmpty()) {
            return false;
        }
        List<String> allowed = plugin.getConfig().getStringList("allowed-commands");
        if (allowed.isEmpty()) {
            return command.equals("supermarket");
        }
        for (String entry : allowed) {
            if (entry != null && command.equals(entry.toLowerCase().trim())) {
                return true;
            }
        }
        return false;
    }
}
