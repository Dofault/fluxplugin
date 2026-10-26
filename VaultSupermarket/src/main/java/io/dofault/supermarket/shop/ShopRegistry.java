package io.dofault.supermarket.shop;

import io.dofault.supermarket.integration.ItemsAdderHook;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Les blocs assignés par commande ({@code shops.yml}) : les coffres magasins (avec leurs objets)
 * et les blocs de vente. Indexés par position pour que la recherche faite à chaque clic reste en
 * temps constant.
 * <p>
 * Un bloc est reconnu par sa position, pas par son contenu : un coffre magasin n'est jamais
 * ouvert, son inventaire réel n'a aucune importance.
 */
public class ShopRegistry {

    private static final String ITEMSADDER_PREFIX = "itemsadder:";

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Shop> chestsByKey = new LinkedHashMap<>();
    private final Set<String> sellBlockKeys = new LinkedHashSet<>();
    private List<String> blockTypes = List.of();

    public ShopRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "shops.yml");
        reload();
    }

    public void reload() {
        blockTypes = plugin.getConfig().getStringList("shop.block-types");
        chestsByKey.clear();
        sellBlockKeys.clear();

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("chests");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(id);
                if (entry == null || entry.getString("world") == null) {
                    continue;
                }
                List<ShopEntry> items = loadItems(id, entry);
                Shop shop = new Shop(id, entry.getString("world"), entry.getInt("x"), entry.getInt("y"),
                        entry.getInt("z"), entry.getString("block"), items);
                chestsByKey.put(shop.key(), shop);
            }
        }
        // Format "monde;x;y;z", la même clé que celle de l'index.
        sellBlockKeys.addAll(yaml.getStringList("sell-blocks"));
    }

    private List<ShopEntry> loadItems(String id, ConfigurationSection entry) {
        List<ShopEntry> items = new ArrayList<>();
        ConfigurationSection section = entry.getConfigurationSection("items");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ItemStack item = section.getItemStack(key + ".item");
                if (item == null || item.getType().isAir()) {
                    plugin.getLogger().warning("shops.yml : objet illisible (ligne " + key + ") dans le coffre " + id);
                    continue;
                }
                Double price = section.contains(key + ".price") ? section.getDouble(key + ".price") : null;
                items.add(new ShopEntry(item, price));
            }
            return items;
        }
        // Ancien format : une simple liste de noms de matériaux, au prix de prices.yml.
        for (String name : entry.getStringList("items")) {
            Material material = Material.matchMaterial(name);
            if (material != null && material.isItem()) {
                items.add(new ShopEntry(new ItemStack(material), null));
            }
        }
        return items;
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Shop shop : chestsByKey.values()) {
            String path = "chests." + shop.getId();
            yaml.set(path + ".world", shop.getWorld());
            yaml.set(path + ".x", shop.getX());
            yaml.set(path + ".y", shop.getY());
            yaml.set(path + ".z", shop.getZ());
            yaml.set(path + ".block", shop.getBlock());
            List<ShopEntry> items = shop.getItems();
            for (int i = 0; i < items.size(); i++) {
                yaml.set(path + ".items." + i + ".item", items.get(i).getItem());
                yaml.set(path + ".items." + i + ".price", items.get(i).getPrice());
            }
        }
        yaml.set("sell-blocks", new ArrayList<>(sellBlockKeys));
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder shops.yml : " + e.getMessage());
        }
    }

    public int getChestCount() {
        return chestsByKey.size();
    }

    public int getSellBlockCount() {
        return sellBlockKeys.size();
    }

    // ------------------------------------------------------------------
    // Coffres magasins
    // ------------------------------------------------------------------

    /** @return le coffre magasin de ce bloc (ou de l'autre moitié d'un coffre double), ou {@code null}. */
    public Shop getShop(Block block) {
        if (chestsByKey.isEmpty()) {
            return null;
        }
        String key = resolve(block, chestsByKey::containsKey);
        return key == null ? null : chestsByKey.get(key);
    }

    /**
     * Le bloc fait-il partie des types autorisés pour un coffre magasin ({@code shop.block-types}) :
     * matériau vanilla ({@code CHEST}) ou bloc ItemsAdder ({@code itemsadder:namespace:id}).
     */
    public boolean isAllowedBlock(Block block) {
        String itemsAdderId = null;
        boolean itemsAdderResolved = false;
        for (String type : blockTypes) {
            if (type == null || type.isBlank()) {
                continue;
            }
            if (!itemsAdderResolved) {
                itemsAdderId = ItemsAdderHook.blockId(block);
                itemsAdderResolved = true;
            }
            if (type.toLowerCase().startsWith(ITEMSADDER_PREFIX)) {
                if (itemsAdderId != null && itemsAdderId.equalsIgnoreCase(type.substring(ITEMSADDER_PREFIX.length()))) {
                    return true;
                }
            } else {
                // Un matériau qui porte un bloc ItemsAdder (bloc de notes, champignon...) ne
                // compte pas comme le bloc vanilla : sinon "NOTE_BLOCK" accepterait aussi tous
                // les blocs custom posés sur cette base.
                Material material = Material.matchMaterial(type);
                if (material != null && block.getType() == material && itemsAdderId == null) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<String> getBlockTypes() {
        return blockTypes;
    }

    public Shop addChest(Block block) {
        int next = 1;
        for (Shop shop : chestsByKey.values()) {
            try {
                next = Math.max(next, Integer.parseInt(shop.getId().replace("chest", "")) + 1);
            } catch (NumberFormatException ignored) {
                // identifiant écrit à la main, hors du format "chestN"
            }
        }
        Shop shop = new Shop("chest" + next, block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                describe(block), List.of());
        chestsByKey.put(shop.key(), shop);
        save();
        return shop;
    }

    /** @return l'identifiant à reposer pour ce bloc : id ItemsAdder, sinon BlockData vanilla. */
    private static String describe(Block block) {
        String itemsAdderId = ItemsAdderHook.blockId(block);
        return itemsAdderId != null ? ITEMSADDER_PREFIX + itemsAdderId : block.getBlockData().getAsString();
    }

    /** Les coffres magasins, pour la remise en place au démarrage. */
    public Collection<Shop> getShops() {
        return Collections.unmodifiableCollection(chestsByKey.values());
    }

    /** Mémorise le bloc actuel d'un coffre assigné avant que cette information n'existe. */
    public void rememberBlock(Shop shop, Block block) {
        shop.setBlock(describe(block));
        save();
    }

    /** Le coffre est-il toujours assigné (il a pu être désassigné pendant une saisie de prix) ? */
    public boolean isRegistered(Shop shop) {
        return chestsByKey.get(shop.key()) == shop;
    }

    public void removeChest(Shop shop) {
        chestsByKey.remove(shop.key());
        save();
    }

    // ------------------------------------------------------------------
    // Blocs de vente
    // ------------------------------------------------------------------

    public boolean isSellBlock(Block block) {
        return !sellBlockKeys.isEmpty() && resolve(block, sellBlockKeys::contains) != null;
    }

    public void addSellBlock(Block block) {
        sellBlockKeys.add(Shop.key(block));
        save();
    }

    /** @return {@code false} si ce bloc n'était pas un bloc de vente. */
    public boolean removeSellBlock(Block block) {
        String key = resolve(block, sellBlockKeys::contains);
        if (key == null) {
            return false;
        }
        sellBlockKeys.remove(key);
        save();
        return true;
    }

    // ------------------------------------------------------------------

    /** Un bloc assigné, de quelque type qu'il soit (protection contre la casse). */
    public boolean isAssigned(Block block) {
        return getShop(block) != null || isSellBlock(block);
    }

    public boolean isEmpty() {
        return chestsByKey.isEmpty() && sellBlockKeys.isEmpty();
    }

    /**
     * @return la clé assignée correspondant à ce bloc, ou {@code null}. Un coffre double est
     * reconnu par ses deux moitiés : sinon la moitié non assignée s'ouvrirait normalement.
     */
    private String resolve(Block block, Predicate<String> assigned) {
        String key = Shop.key(block);
        if (assigned.test(key)) {
            return key;
        }
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) {
            return null;
        }
        if (block.getState(false) instanceof Chest chest
                && chest.getInventory() instanceof DoubleChestInventory doubleChest) {
            for (Inventory side : List.of(doubleChest.getLeftSide(), doubleChest.getRightSide())) {
                Location location = side.getLocation();
                if (location != null) {
                    String half = Shop.key(location.getBlock());
                    if (assigned.test(half)) {
                        return half;
                    }
                }
            }
        }
        return null;
    }
}
