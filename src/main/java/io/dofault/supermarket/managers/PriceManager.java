package io.dofault.supermarket.managers;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public class PriceManager {

    private static final String DEFAULT_CATEGORY = "autre";

    private final JavaPlugin plugin;
    private final File priceFile;
    private FileConfiguration priceConfig;
    private final LangManager lang;

    public PriceManager(JavaPlugin plugin, LangManager lang) {
        this.plugin = plugin;
        this.lang = lang;
        this.priceFile = new File(plugin.getDataFolder(), "prices.yml");

        if (!priceFile.exists()) {
            plugin.saveResource("prices.yml", false);
        }

        priceConfig = YamlConfiguration.loadConfiguration(priceFile);
    }

    public void reload() {
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

    public void setPrice(Material material, double buy, double sell) {
        String path = "items." + material.name();
        String category = priceConfig.getString(path + ".category", DEFAULT_CATEGORY);
        priceConfig.set(path + ".buy", buy);
        priceConfig.set(path + ".sell", sell);
        priceConfig.set(path + ".category", category);
        savePriceFile();
    }

    public boolean hasPrice(String itemID) {
        return priceConfig.contains("items." + itemID);
    }

    public boolean hasBuyPrice(String itemID) {
        return priceConfig.contains("items." + itemID + ".buy");
    }

    public boolean hasSellPrice(String itemID) {
        return priceConfig.contains("items." + itemID + ".sell");
    }

    public double getBuyPrice(String itemID) {
        return priceConfig.getDouble("items." + itemID + ".buy", 0);
    }

    public double getSellPrice(String itemID) {
        return priceConfig.getDouble("items." + itemID + ".sell", 0);
    }

    /**
     * Un item est vendable uniquement si un prix de vente strictement positif
     * est défini (un prix de vente absent ou à 0 signifie "non rachetable").
     */
    public boolean isSellable(String itemID) {
        return getSellPrice(itemID) > 0;
    }

    public String getCategory(String itemID) {
        return priceConfig.getString("items." + itemID + ".category", DEFAULT_CATEGORY);
    }

    public List<String> getItemIds() {
        ConfigurationSection section = priceConfig.getConfigurationSection("items");
        return section == null ? Collections.emptyList() : new ArrayList<>(section.getKeys(false));
    }

    public List<String> getCategories() {
        TreeSet<String> categories = new TreeSet<>();
        for (String itemID : getItemIds()) {
            categories.add(getCategory(itemID));
        }
        return new ArrayList<>(categories);
    }

    public List<String> getItemIdsByCategory(String category) {
        List<String> ids = new ArrayList<>();
        for (String itemID : getItemIds()) {
            if (getCategory(itemID).equalsIgnoreCase(category)) {
                ids.add(itemID);
            }
        }
        Collections.sort(ids);
        return ids;
    }

    /**
     * Items définis dans prices.yml dont le matériau n'existe pas sur la
     * version du serveur en cours (renommé/retiré entre versions de Minecraft).
     */
    public List<String> getInvalidItemIds() {
        List<String> invalid = new ArrayList<>();
        for (String itemID : getItemIds()) {
            if (Material.matchMaterial(itemID) == null) {
                invalid.add(itemID);
            }
        }
        Collections.sort(invalid);
        return invalid;
    }

    public void logInvalidItems() {
        List<String> invalid = getInvalidItemIds();
        if (invalid.isEmpty()) {
            return;
        }
        plugin.getLogger().warning("prices.yml contient " + invalid.size()
                + " item(s) introuvable(s) sur cette version du serveur : " + String.join(", ", invalid));
    }

    /**
     * Formate un identifiant (catégorie ou matériau) pour l'affichage :
     * underscores -> espaces, majuscule en début de chaque mot
     * (ex: "block_terrestre" -> "Block Terrestre", "GRASS_BLOCK" -> "Grass Block").
     */
    public static String humanize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }

        StringBuilder formatted = new StringBuilder();
        for (String word : raw.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (formatted.length() > 0) {
                formatted.append(" ");
            }
            formatted.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase());
        }
        return formatted.toString();
    }

    public void sendPriceToPlayer(Player player, String itemID) {
        if (hasPrice(itemID)) {
            player.sendMessage(lang.get("shop-item-price", Map.of(
                    "item", itemID,
                    "buy", String.valueOf(getBuyPrice(itemID)),
                    "sell", String.valueOf(getSellPrice(itemID)))));
        } else {
            player.sendMessage(lang.get("shop-no-price", Map.of("item", itemID)));
        }
    }
}
