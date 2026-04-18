package io.dofault.supermarket.managers;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import net.milkbowl.vault.economy.Economy;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class PriceManager {

    private final JavaPlugin plugin;
    private File priceFile;
    FileConfiguration priceConfig;
    private Economy econ;
    private LangManager lang;

    public PriceManager(JavaPlugin plugin, LangManager lang, Economy econ) {
        this.plugin = plugin;
        this.econ = econ;
        this.lang = lang;
        this.priceFile = new File(plugin.getDataFolder(), "prices.yml");

        if (!priceFile.exists()) {
            try {
                plugin.getDataFolder().mkdirs(); // crée le dossier plugin si nécessaire
                priceFile.createNewFile(); // crée prices.yml vide
            } catch (IOException e) {
                plugin.getLogger().severe("Impossible de créer prices.yml !");
                e.printStackTrace();
            }
        }

        priceConfig = YamlConfiguration.loadConfiguration(priceFile);
    }

    public void savePriceFile() {
        try {
            priceConfig.save(priceFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder prices.yml !");
            e.printStackTrace();
        }
    }

    public void setPrice(Material material, double price) {
        if (material.name() == null) {
            throw new IllegalArgumentException("Item invalide");
        }
        priceConfig.set("items." + material.name() + ".price", price);
        savePriceFile();
    }

    public double getPrice(String itemID) {
        return priceConfig.getDouble("items." + itemID + ".price", 0); // -1 si pas trouvé
    }

    public boolean hasPrice(String itemID) {
        return priceConfig.contains("items." + itemID + ".price");
    }

    public void sendPriceToPlayer(Player player, String itemID) {
        if (hasPrice(itemID)) {
            double price = getPrice(itemID);
            player.sendMessage(lang.get("shop-item-price", Map.of(
                    "item", itemID,
                    "price", String.valueOf(price))));
        } else {
            player.sendMessage(lang.get("shop-no-price", Map.of("item", itemID)));

        }
    }
}