package io.dofault.supermarket.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import net.milkbowl.vault.economy.Economy;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SellManager {

    private final JavaPlugin plugin;
    private final LangManager langManager;
    private final PlayerListManager playerListManager;
    private final ShopManager shopManager;
    private final PriceManager priceManager;
    private final Economy econ;

    private int sellCounter = 0;
    private final double sellPercentage;

    public SellManager(JavaPlugin plugin, LangManager langManager,
            ShopManager shopManager, PriceManager priceManager, PlayerListManager playerListManager, Economy econ) {
        this.plugin = plugin;
        this.priceManager = priceManager;
        this.econ = econ;
        this.langManager = langManager;
        this.playerListManager = playerListManager;
        this.shopManager = shopManager;

        this.sellPercentage = plugin.getConfig().getDouble("sell-percentage", 0.8);

    }

    public void tryOpenSellBlock(Player player, Block targetBlock) {
        if (!playerListManager.isInShop(player.getUniqueId())) {
            player.sendMessage(langManager.get("shop-not-in-shop"));
            return;
        }

        if (!shopManager.canPlayerExit(player)) {
            player.sendMessage(langManager.get("shop-sell-unpaid-items"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 2.0f, 0.5f);
            return;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);

        // Crée un inventaire temporaire pour vendre
        Inventory sellInv = Bukkit.createInventory(null, InventoryType.CHEST, "§6Sell Items");
        player.openInventory(sellInv);

        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onClose(InventoryCloseEvent event) {
                if (!event.getPlayer().equals(player))
                    return;
                if (!event.getInventory().equals(sellInv))
                    return;

                double total = 0.0;
                ItemStack[] toRestore = new ItemStack[sellInv.getSize()];
                int idx = 0;

                for (ItemStack item : sellInv.getContents()) {
                    if (item == null || item.getType() == Material.AIR)
                        continue;

                    if (priceManager.hasPrice(item.getType().name())) {
                        double price = priceManager.getPrice(item.getType().name());
                        total += price * item.getAmount() * 0.8;
                    } else {
                        toRestore[idx++] = item.clone();
                    }
                }

                if (total > 0) {
                    econ.depositPlayer(player, total);
                    player.sendMessage(langManager.get(
                            "shop-sell-success",
                            Map.of("amount", econ.format(total))));
                    player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);

                    // Sauvegarde l'inventaire du joueur en base après la vente

                    playerListManager.saveInventory(player, false);
                }

                sellInv.clear();

                int restoredCount = 0;
                for (ItemStack item : toRestore) {
                    if (item != null) {
                        player.getInventory().addItem(item);
                        restoredCount += item.getAmount();
                    }
                }

                if (restoredCount > 0) {
                    player.sendMessage(langManager.get("shop-unsellable-restored",
                            Map.of("amount", String.valueOf(restoredCount))));
                }

                org.bukkit.event.HandlerList.unregisterAll(this);
            }
        }, plugin);
    }

    public void lookPrices(Player player, Block block) {

        if (!playerListManager.isInShop(player.getUniqueId())) {
            player.sendMessage(langManager.get("shop-not-in-shop"));
            return;
        }
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f);

        List<String> items = new ArrayList<>(priceManager.priceConfig.getConfigurationSection("items").getKeys(false));
        int itemsPerPage = 45; // slots pour les items
        int totalPages = (int) Math.ceil((double) items.size() / itemsPerPage);

        openPricePage(player, items, 1, totalPages, itemsPerPage);
    }

    private void openPricePage(Player player, List<String> items, int page, int totalPages, int itemsPerPage) {
        int size = 54; // double coffre
        String title = langManager.get("shop-prices-inventory-title", Map.of(
                "page", String.valueOf(page),
                "totalPages", String.valueOf(totalPages)));

        Inventory inv = Bukkit.createInventory(null, size, title);
        int start = (page - 1) * itemsPerPage;
        int end = Math.min(start + itemsPerPage, items.size());

        for (int i = start; i < end; i++) {
            String itemID = items.get(i);
            Material mat = Material.getMaterial(itemID);
            if (mat == null)
                continue;

            ItemStack stack = new ItemStack(mat);
            var meta = stack.getItemMeta();
            if (meta != null) {
                double price = priceManager.getPrice(itemID);
                double salePrice = price * sellPercentage; // prix de vente
                meta.setDisplayName(langManager.get("shop-item-name", Map.of(
                        "item", mat.name())));
                meta.setLore(List.of(
                        langManager.get("shop-item-lore-buy-price", Map.of("price", econ.format(price))),
                        langManager.get("shop-item-lore-sell-price", Map.of("price", econ.format(salePrice)))));
                stack.setItemMeta(meta);
            }
            inv.addItem(stack);
        }

        // Boutons pagination
        if (page > 1) {
            ItemStack prev = new ItemStack(Material.ARROW);
            var pm = prev.getItemMeta();
            pm.setDisplayName(langManager.get("shop-inventory-previous-page"));
            prev.setItemMeta(pm);
            inv.setItem(45, prev);
        }
        if (page < totalPages) {
            ItemStack next = new ItemStack(Material.ARROW);
            var nm = next.getItemMeta();
            nm.setDisplayName(langManager.get("shop-inventory-next-page"));
            next.setItemMeta(nm);
            inv.setItem(53, next);
        }

        player.openInventory(inv);

        // Listener lecture seule + navigation
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onClick(org.bukkit.event.inventory.InventoryClickEvent e) {
                if (!e.getInventory().equals(inv))
                    return;
                e.setCancelled(true);
                if (e.getCurrentItem() == null || !e.getCurrentItem().hasItemMeta())
                    return;

                String name = e.getCurrentItem().getItemMeta().getDisplayName();
                if (name.equals(langManager.get("shop-inventory-previous-page"))) {
                    player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f);
                    openPricePage(player, items, page - 1, totalPages, itemsPerPage);

                }

                else if (name.equals(langManager.get("shop-inventory-next-page"))) {
                    player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f);
                    openPricePage(player, items, page + 1, totalPages, itemsPerPage);

                }
            }

            @org.bukkit.event.EventHandler
            public void onDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
                if (e.getInventory().equals(inv))
                    e.setCancelled(true);
            }

            @org.bukkit.event.EventHandler
            public void onClose(InventoryCloseEvent e) {
                if (e.getInventory().equals(inv))
                    org.bukkit.event.HandlerList.unregisterAll(this);
            }
        }, plugin);
    }

}
