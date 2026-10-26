package io.dofault.supermarket.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Sound;

import io.dofault.supermarket.main.Main;
import io.dofault.supermarket.model.BlockPos;
import net.md_5.bungee.api.ChatColor;
import net.milkbowl.vault.economy.Economy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ShopManager {

    private Main plugin;
    private BlockPos entryPosSetting;
    private BlockPos exitPosSetting;
    private BlockPos entryPos;
    private BlockPos exitPos;
    private PlayerListManager playerListManager;
    private PriceManager priceManager;
    private LangManager lang;
    private Economy economy;

    public ShopManager(Main plugin, LangManager lang, Economy economy, PlayerListManager playerlist,
            PriceManager priceManager) {
        this.economy = economy;
        this.lang = lang;
        this.plugin = plugin;
        this.priceManager = priceManager;
        this.playerListManager = playerlist;

        loadShop();
    }

    public void setEntryPosSetting(Location loc) {
        entryPosSetting = new BlockPos(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        Bukkit.getLogger().info(
                "Entry position set to " + loc.getWorld().getName() +
                        " [" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + "]");
    }

    public void setExitPosSetting(Location loc) {
        exitPosSetting = new BlockPos(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        Bukkit.getLogger().info(
                "Exit position set to " + loc.getWorld().getName() +
                        " [" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + "]");
    }

    public boolean saveShop() {
        if (entryPosSetting == null || exitPosSetting == null)
            return false;

        plugin.getConfig().set("shop.entry.world", entryPosSetting.getWorld());
        plugin.getConfig().set("shop.entry.x", entryPosSetting.getX());
        plugin.getConfig().set("shop.entry.y", entryPosSetting.getY());
        plugin.getConfig().set("shop.entry.z", entryPosSetting.getZ());

        plugin.getConfig().set("shop.exit.world", exitPosSetting.getWorld());
        plugin.getConfig().set("shop.exit.x", exitPosSetting.getX());
        plugin.getConfig().set("shop.exit.y", exitPosSetting.getY());
        plugin.getConfig().set("shop.exit.z", exitPosSetting.getZ());

        plugin.saveConfig();
        Bukkit.getLogger().info("Configuration shop saved");

        loadShop();
        return true;
    }

    public void loadShop() {
        if (plugin.getConfig().contains("shop.entry") && plugin.getConfig().contains("shop.exit")) {
            entryPos = new BlockPos(
                    plugin.getConfig().getString("shop.entry.world"),
                    plugin.getConfig().getInt("shop.entry.x"),
                    plugin.getConfig().getInt("shop.entry.y"),
                    plugin.getConfig().getInt("shop.entry.z"));
            exitPos = new BlockPos(
                    plugin.getConfig().getString("shop.exit.world"),
                    plugin.getConfig().getInt("shop.exit.x"),
                    plugin.getConfig().getInt("shop.exit.y"),
                    plugin.getConfig().getInt("shop.exit.z"));

        }

        Bukkit.getLogger().info("Chests shop reloaded");
    }

    // --- Dans ShopManager ---
    public Map<ItemStack, Integer> getInventoryDifference(Player player) {
        ItemStack[] savedInventory = playerListManager.getSavedInventory(player);
        ItemStack[] currentInventory = player.getInventory().getContents();

        Map<ItemStack, Integer> savedMap = new HashMap<>();
        Map<ItemStack, Integer> currentMap = new HashMap<>();

        for (ItemStack item : savedInventory) {
            if (item != null && item.getType() != Material.AIR) {
                ItemStack key = item.clone();
                key.setAmount(1); // normaliser la quantité pour comparaison
                savedMap.merge(key, item.getAmount(), Integer::sum);
            }
        }
        for (ItemStack item : currentInventory) {
            if (item != null && item.getType() != Material.AIR) {
                ItemStack key = item.clone();
                key.setAmount(1);
                currentMap.merge(key, item.getAmount(), Integer::sum);
            }
        }

        Set<ItemStack> allKeys = new HashSet<>();
        allKeys.addAll(savedMap.keySet());
        allKeys.addAll(currentMap.keySet());

        Map<ItemStack, Integer> diff = new HashMap<>();
        for (ItemStack key : allKeys) {
            int savedAmount = savedMap.getOrDefault(key, 0);
            int currentAmount = currentMap.getOrDefault(key, 0);
            int delta = currentAmount - savedAmount;
            if (delta != 0)
                diff.put(key, delta);
        }

        return diff;
    }

    public void showInventoryDifference(Player player) {
        Map<ItemStack, Integer> diff = getInventoryDifference(player);

        if (diff.isEmpty()) {
            player.sendMessage(lang.get("shop-no-difference"));
        } else {
            double totalPrice = 0.0;
            player.sendMessage(lang.get("shop-your-items"));

            for (Map.Entry<ItemStack, Integer> entry : diff.entrySet()) {
                ItemStack item = entry.getKey();
                int delta = entry.getValue();

                String itemName = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                        ? item.getItemMeta().getDisplayName()
                        : item.getType().name();

                if (delta > 0) {
                    double itemPrice = priceManager.hasBuyPrice(item.getType().name())
                            ? priceManager.getBuyPrice(item.getType().name())
                            : 0.0;
                    totalPrice += itemPrice * delta;
                    player.sendMessage(lang.get("shop-item-line", Map.of(
                            "item", itemName,
                            "delta", String.valueOf(delta),
                            "price", economy.format(itemPrice))));
                }

                // Log dans la console

                Bukkit.getLogger().info("Difference for " + player.getName() + ": " + itemName + " -> " + delta);

            }

            player.sendMessage(lang.get("shop-total", Map.of("total", economy.format(totalPrice))));

        }
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 0.9f);

    }

    public boolean canPlayerExit(Player player) {
        Map<ItemStack, Integer> diff = getInventoryDifference(player);
        double totalCost = 0;

        for (Map.Entry<ItemStack, Integer> entry : diff.entrySet()) {
            int amount = entry.getValue();
            if (amount > 0) {
                // Conversion en String via Material
                double price = priceManager.getBuyPrice(entry.getKey().getType().toString());
                totalCost += price * amount;
            }
        }
        if (totalCost > 0) {
            return false;
        }
        return true; // Aucun surplus détecté
    }

    public boolean payUser(Player player) {
        Map<ItemStack, Integer> diff = getInventoryDifference(player);
        double totalCost = 0;

        for (Map.Entry<ItemStack, Integer> entry : diff.entrySet()) {
            int amount = entry.getValue();
            if (amount > 0) {
                // Conversion en String via Material
                double price = priceManager.getBuyPrice(entry.getKey().getType().toString());
                totalCost += price * amount;
            }
        }

        if (totalCost > 0) {
            if (!economy.has(player, totalCost)) {
                player.sendMessage(lang.get("shop-not-enough-money", Map.of(
                        "balance", economy.format(economy.getBalance(player)))));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 2.0f, 0.5f); // plus aigu et plus fort

                return false;
            }
            economy.withdrawPlayer(player, totalCost);
            player.sendMessage(lang.get("shop-paid-success", Map.of("total", economy.format(totalCost))));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_CELEBRATE, 1f, 1f);

            playerListManager.markAsBought(player.getUniqueId());
            playerListManager.saveInventory(player, false);
        }

        return true;
    }

    public double getBalance(Player player) {
        Map<ItemStack, Integer> diff = getInventoryDifference(player);
        double total = 0.0;

        for (Map.Entry<ItemStack, Integer> entry : diff.entrySet()) {
            int amount = entry.getValue();
            if (amount > 0) {
                double price = priceManager.getBuyPrice(entry.getKey().getType().toString());
                total += price * amount;
            }
        }

        return total;
    }

    public boolean isEntry(Block block) {
        return entryPos != null && entryPos.matches(block);
    }

    public boolean isExit(Block block) {
        return exitPos != null && exitPos.matches(block);
    }

    public Location getEntryLocation(Player player) {
        return new Location(Bukkit.getWorld(entryPos.getWorld()), entryPos.getX() + 0.5, entryPos.getY(),
                entryPos.getZ() + 0.5,
                player.getLocation().getYaw(), player.getLocation().getPitch());
    }

    public Location getExitLocation(Player player) {
        return new Location(Bukkit.getWorld(exitPos.getWorld()), exitPos.getX() + 0.5, exitPos.getY(),
                exitPos.getZ() + 0.5,
                player.getLocation().getYaw(), player.getLocation().getPitch());
    }
}
