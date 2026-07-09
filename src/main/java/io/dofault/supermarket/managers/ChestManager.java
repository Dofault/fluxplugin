package io.dofault.supermarket.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ChestManager {

    private final JavaPlugin plugin;
    private final File chestFile;
    private final FileConfiguration chestConfig;
    private PriceManager priceManager;
    private int chestCounter = 0;
    private LangManager lang;

    public ChestManager(JavaPlugin plugin, LangManager lang, PriceManager priceManager) {
        this.plugin = plugin;
        this.lang = lang;
        this.priceManager = priceManager;
        chestFile = new File(plugin.getDataFolder(), "chest.yml");
        if (!chestFile.exists()) {
            chestFile.getParentFile().mkdirs();
            try {
                chestFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        chestConfig = YamlConfiguration.loadConfiguration(chestFile);

        if (chestConfig.contains("chests")) {
            // Le compteur doit être basé sur le plus grand numéro existant, pas sur le
            // nombre d'entrées : après une suppression il peut y avoir des "trous"
            // dans la numérotation (chest1, chest3, chest4...), et réutiliser un
            // numéro libre écraserait un coffre existant.
            for (String chestId : chestConfig.getConfigurationSection("chests").getKeys(false)) {
                try {
                    int id = Integer.parseInt(chestId.replace("chest", ""));
                    chestCounter = Math.max(chestCounter, id);
                } catch (NumberFormatException ignored) {
                    // ignore les clés qui ne suivent pas le format "chestN"
                }
            }
        }
    }

    public void saveConfig() {
        try {
            chestConfig.save(chestFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void removeChest(Player player) {
        Location loc = player.getLocation().getBlock().getLocation();

        if (!chestConfig.contains("chests")) {
            player.sendMessage(lang.get("shop-no-chest"));

            return;
        }

        String targetChestId = null;

        for (String chestId : chestConfig.getConfigurationSection("chests").getKeys(false)) {
            String worldName = chestConfig.getString("chests." + chestId + ".world");
            int x = chestConfig.getInt("chests." + chestId + ".x");
            int y = chestConfig.getInt("chests." + chestId + ".y");
            int z = chestConfig.getInt("chests." + chestId + ".z");

            if (worldName.equals(loc.getWorld().getName())
                    && x == loc.getBlockX()
                    && y == loc.getBlockY()
                    && z == loc.getBlockZ()) {
                targetChestId = chestId;
                break;
            }
        }

        if (targetChestId == null) {
            player.sendMessage(lang.get("shop-not-registered-chest"));

            return;
        }

        // Supprime uniquement ce coffre. Les autres coffres gardent leur identifiant
        // (pas de réindexation en cascade : chestCounter ne se base que sur le plus
        // grand numéro existant, donc les "trous" laissés ici n'ont aucun impact).
        chestConfig.set("chests." + targetChestId, null);

        saveConfig();
        player.sendMessage(lang.get("shop-chest-unlinked"));

    }

    public boolean addChest(Block block) {
        if (!(block.getState() instanceof Chest)) {
            throw new IllegalArgumentException(lang.get("shop-no-chest-here"));
        }

        Location loc = block.getLocation();

        if (chestConfig.contains("chests")) {
            for (String chestId : chestConfig.getConfigurationSection("chests").getKeys(false)) {
                String worldName = chestConfig.getString("chests." + chestId + ".world");
                int x = chestConfig.getInt("chests." + chestId + ".x");
                int y = chestConfig.getInt("chests." + chestId + ".y");
                int z = chestConfig.getInt("chests." + chestId + ".z");

                if (worldName.equals(loc.getWorld().getName())
                        && x == loc.getBlockX()
                        && y == loc.getBlockY()
                        && z == loc.getBlockZ()) {
                    throw new IllegalArgumentException(lang.get("shop-chest-already-registered"));
                }
            }
        }

        chestCounter++;
        String chestId = "chest" + chestCounter;

        chestConfig.set("chests." + chestId + ".world", loc.getWorld().getName());
        chestConfig.set("chests." + chestId + ".x", loc.getBlockX());
        chestConfig.set("chests." + chestId + ".y", loc.getBlockY());
        chestConfig.set("chests." + chestId + ".z", loc.getBlockZ());
        chestConfig.set("chests." + chestId + ".items", new ArrayList<String>());

        saveConfig();
        return true;
    }

    public boolean addItemToChest(Location loc, Material material, int amount) {

        if (!chestConfig.contains("chests")) {
            throw new IllegalArgumentException(lang.get("shop-no-chest"));
        }

        if (!priceManager.hasBuyPrice(material.name())) {
            throw new IllegalArgumentException(lang.get("shop-material-no-price"));
        }

        String targetChestId = null;

        for (String chestId : chestConfig.getConfigurationSection("chests").getKeys(false)) {
            String worldName = chestConfig.getString("chests." + chestId + ".world");
            int x = chestConfig.getInt("chests." + chestId + ".x");
            int y = chestConfig.getInt("chests." + chestId + ".y");
            int z = chestConfig.getInt("chests." + chestId + ".z");

            if (worldName.equals(loc.getWorld().getName())
                    && x == loc.getBlockX()
                    && y == loc.getBlockY()
                    && z == loc.getBlockZ()) {
                targetChestId = chestId;
                break;
            }
        }

        if (targetChestId == null) {
            throw new IllegalArgumentException(lang.get("shop-not-registered-chest"));
        }

        // Chaque coffre est traité indépendamment, peu importe s'il est physiquement
        // collé à un autre coffre : pas de détection/contrainte de double-coffre.
        List<String> items = chestConfig.getStringList("chests." + targetChestId + ".items");
        items.add(material.name() + ":" + amount);
        chestConfig.set("chests." + targetChestId + ".items", items);
        saveConfig();

        reloadChests();
        return true;
    }

    // Retourne true si une location correspond à un coffre enregistré
    public boolean isChest(Location loc) {
        for (String chestId : getChestsIds()) {
            if (getChestLocation(chestId).equals(loc))
                return true;
        }
        return false;
    }

    // Retourne l'inventaire du coffre à une location, ou null si ce n'est pas un
    // coffre
    public Inventory getChestInventory(Location loc) {
        for (String chestId : getChestsIds()) {
            if (getChestLocation(chestId).equals(loc)) {
                Block block = loc.getBlock();
                if (block.getState() instanceof Container container) {
                    return container.getInventory();
                }
            }
        }
        return null;
    }

    public Set<String> getChestsIds() {
        if (!chestConfig.contains("chests") || chestConfig.getConfigurationSection("chests") == null) {
            return Collections.emptySet();
        }
        return chestConfig.getConfigurationSection("chests").getKeys(false);
    }

    public Location getChestLocation(String chestId) {
        String worldName = chestConfig.getString("chests." + chestId + ".world");
        int x = chestConfig.getInt("chests." + chestId + ".x");
        int y = chestConfig.getInt("chests." + chestId + ".y");
        int z = chestConfig.getInt("chests." + chestId + ".z");
        return new Location(worldName == null ? null : Bukkit.getWorld(worldName), x, y, z);
    }

    public void reloadChests() {
        if (!chestConfig.contains("chests"))
            return;

        for (String chestId : chestConfig.getConfigurationSection("chests").getKeys(false)) {
            reloadChest(chestId);
        }
    }

    public void reloadChest(String chestId) {
        if (!chestConfig.contains("chests." + chestId))
            return;

        String worldName = chestConfig.getString("chests." + chestId + ".world");
        int x = chestConfig.getInt("chests." + chestId + ".x");
        int y = chestConfig.getInt("chests." + chestId + ".y");
        int z = chestConfig.getInt("chests." + chestId + ".z");

        if (worldName == null || Bukkit.getWorld(worldName) == null)
            return;

        Location loc = new Location(Bukkit.getWorld(worldName), x, y, z);
        Block block = loc.getBlock();

        if (!(block.getState() instanceof Container container))
            return;

        Inventory inv = container.getInventory();
        inv.clear();

        List<String> items = chestConfig.getStringList("chests." + chestId + ".items");
        for (String itemStr : items) {
            String[] parts = itemStr.split(":");
            if (parts.length != 2)
                continue;

            Material mat = Material.matchMaterial(parts[0]);
            if (mat == null)
                continue;

            int amount;
            try {
                amount = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }

            inv.addItem(new ItemStack(mat, amount));
        }
    }

    public int getChestCounter() {
        return chestCounter;
    }
}
