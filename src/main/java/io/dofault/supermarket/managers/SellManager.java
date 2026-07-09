package io.dofault.supermarket.managers;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import net.milkbowl.vault.economy.Economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SellManager {

    // Disposition du menu de consultation des prix (double coffre, 54 slots) :
    // - slot 2             : bouton "tous les articles"
    // - slots 3-6, 11-15   : catégories
    // - slots 18-44        : contenu paginé de la catégorie sélectionnée (ou de tout le shop)
    // - slot 45 / 53       : pagination précédente / suivante
    // - le reste des bordures (côtés + bas) est rempli de vitres décoratives
    private static final int ALL_ITEMS_SLOT = 2;
    private static final int[] CATEGORY_SLOTS = { 3, 4, 5, 6, 11, 12, 13, 14, 15 };
    private static final int ITEMS_START_SLOT = 18;
    private static final int ITEMS_END_SLOT = 44;
    private static final int ITEMS_PER_PAGE = ITEMS_END_SLOT - ITEMS_START_SLOT + 1;
    private static final int PREV_PAGE_SLOT = 45;
    private static final int NEXT_PAGE_SLOT = 53;

    private final JavaPlugin plugin;
    private final LangManager langManager;
    private final PlayerListManager playerListManager;
    private final ShopManager shopManager;
    private final PriceManager priceManager;
    private final Economy econ;

    public SellManager(JavaPlugin plugin, LangManager langManager,
            ShopManager shopManager, PriceManager priceManager, PlayerListManager playerListManager, Economy econ) {
        this.plugin = plugin;
        this.priceManager = priceManager;
        this.econ = econ;
        this.langManager = langManager;
        this.playerListManager = playerListManager;
        this.shopManager = shopManager;
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

        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onClose(InventoryCloseEvent event) {
                if (!event.getPlayer().equals(player))
                    return;
                if (!event.getInventory().equals(sellInv))
                    return;

                double total = 0.0;
                List<ItemStack> toRestore = new ArrayList<>();

                for (ItemStack item : sellInv.getContents()) {
                    if (item == null || item.getType() == Material.AIR)
                        continue;

                    if (priceManager.isSellable(item.getType().name())) {
                        total += priceManager.getSellPrice(item.getType().name()) * item.getAmount();
                    } else {
                        toRestore.add(item.clone());
                    }
                }

                if (total > 0) {
                    econ.depositPlayer(player, total);
                    player.sendMessage(langManager.get(
                            "shop-sell-success",
                            Map.of("amount", econ.format(total))));
                    player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);

                    playerListManager.markAsSold(player.getUniqueId());
                    playerListManager.saveInventory(player, false);
                }

                sellInv.clear();

                int restoredCount = 0;
                for (ItemStack item : toRestore) {
                    player.getInventory().addItem(item);
                    restoredCount += item.getAmount();
                }

                if (restoredCount > 0) {
                    player.sendMessage(langManager.get("shop-unsellable-restored",
                            Map.of("amount", String.valueOf(restoredCount))));
                }

                HandlerList.unregisterAll(this);
            }
        }, plugin);
    }

    public void lookPrices(Player player, Block block) {
        if (!playerListManager.isInShop(player.getUniqueId())) {
            player.sendMessage(langManager.get("shop-not-in-shop"));
            return;
        }
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f);
        openShopMenu(player);
    }

    private static final class MenuState {
        String category; // null = tous les articles
        int page = 1;
    }

    private void openShopMenu(Player player) {
        List<String> categories = priceManager.getCategories();

        MenuState state = new MenuState();
        List<String> items = resolveItems(state.category);
        int totalPages = totalPages(items);

        Inventory inv = Bukkit.createInventory(null, 54, buildTitle(state.category, state.page, totalPages));
        fillBorder(inv);
        fillCategoryButtons(inv, categories);
        fillItemSlots(inv, items, state.page);
        fillNavSlots(inv, state.page, totalPages);

        player.openInventory(inv);

        Runnable refresh = () -> {
            List<String> currentItems = resolveItems(state.category);
            int pages = totalPages(currentItems);
            state.page = Math.max(1, Math.min(state.page, pages));
            fillItemSlots(inv, currentItems, state.page);
            fillNavSlots(inv, state.page, pages);
            player.getOpenInventory().setTitle(buildTitle(state.category, state.page, pages));
        };

        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onClick(InventoryClickEvent e) {
                if (!e.getInventory().equals(inv) || !e.getWhoClicked().equals(player))
                    return;
                e.setCancelled(true);

                if (e.getClickedInventory() == null || !e.getClickedInventory().equals(inv))
                    return;

                int slot = e.getSlot();
                boolean changed = false;

                if (slot == ALL_ITEMS_SLOT) {
                    state.category = null;
                    state.page = 1;
                    changed = true;
                } else if (slot == PREV_PAGE_SLOT) {
                    state.page--;
                    changed = true;
                } else if (slot == NEXT_PAGE_SLOT) {
                    state.page++;
                    changed = true;
                } else {
                    int categoryIndex = categoryIndexForSlot(slot);
                    if (categoryIndex >= 0 && categoryIndex < categories.size()) {
                        state.category = categories.get(categoryIndex);
                        state.page = 1;
                        changed = true;
                    }
                }

                if (changed) {
                    player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f);
                    refresh.run();
                }
            }

            @EventHandler
            public void onDrag(InventoryDragEvent e) {
                if (e.getInventory().equals(inv))
                    e.setCancelled(true);
            }

            @EventHandler
            public void onClose(InventoryCloseEvent e) {
                if (e.getInventory().equals(inv))
                    HandlerList.unregisterAll(this);
            }
        }, plugin);
    }

    private List<String> resolveItems(String category) {
        List<String> ids = category == null
                ? new ArrayList<>(priceManager.getItemIds())
                : priceManager.getItemIdsByCategory(category);
        ids.removeIf(id -> Material.matchMaterial(id) == null || !priceManager.isSellable(id));
        ids.sort(String::compareTo);
        return ids;
    }

    private int totalPages(List<String> items) {
        return Math.max(1, (int) Math.ceil((double) items.size() / ITEMS_PER_PAGE));
    }

    private int categoryIndexForSlot(int slot) {
        for (int i = 0; i < CATEGORY_SLOTS.length; i++) {
            if (CATEGORY_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private String buildTitle(String category, int page, int totalPages) {
        String label = category == null ? langManager.get("shop-all-items-label") : PriceManager.humanize(category);
        return langManager.get("shop-prices-inventory-title", Map.of(
                "category", label,
                "page", String.valueOf(page),
                "totalPages", String.valueOf(totalPages)));
    }

    // Bordures décoratives autour de la zone de catégories (côtés) et toute la
    // dernière rangée (bas), sauf les slots de pagination.
    private static final int[] BORDER_SLOTS = { 0, 1, 7, 8, 9, 10, 16, 17, 46, 47, 48, 49, 50, 51, 52 };

    private void fillBorder(Inventory inv) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            pane.setItemMeta(meta);
        }
        for (int slot : BORDER_SLOTS) {
            inv.setItem(slot, pane);
        }
    }

    private void fillCategoryButtons(Inventory inv, List<String> categories) {
        ItemStack allItems = new ItemStack(Material.NETHER_STAR);
        ItemMeta allMeta = allItems.getItemMeta();
        if (allMeta != null) {
            allMeta.setDisplayName(langManager.get("shop-all-items-button"));
            allItems.setItemMeta(allMeta);
        }
        inv.setItem(ALL_ITEMS_SLOT, allItems);

        for (int i = 0; i < categories.size() && i < CATEGORY_SLOTS.length; i++) {
            String category = categories.get(i);
            ItemStack stack = new ItemStack(getCategoryIcon(category));
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(langManager.get("shop-category-item-name",
                        Map.of("category", PriceManager.humanize(category))));
                meta.setLore(List.of(langManager.get("shop-category-item-lore",
                        Map.of("count", String.valueOf(resolveItems(category).size())))));
                stack.setItemMeta(meta);
            }
            inv.setItem(CATEGORY_SLOTS[i], stack);
        }
    }

    private void fillItemSlots(Inventory inv, List<String> items, int page) {
        for (int slot = ITEMS_START_SLOT; slot <= ITEMS_END_SLOT; slot++) {
            inv.setItem(slot, null);
        }

        int start = (page - 1) * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, items.size());

        for (int i = start; i < end; i++) {
            String itemID = items.get(i);
            Material mat = Material.matchMaterial(itemID);
            if (mat == null)
                continue;

            ItemStack stack = new ItemStack(mat);
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(langManager.get("shop-item-name", Map.of("item", PriceManager.humanize(itemID))));
                meta.setLore(List.of(
                        langManager.get("shop-item-lore-sell-price",
                                Map.of("price", econ.format(priceManager.getSellPrice(itemID))))));
                stack.setItemMeta(meta);
            }
            inv.setItem(ITEMS_START_SLOT + (i - start), stack);
        }
    }

    private void fillNavSlots(Inventory inv, int page, int totalPages) {
        inv.setItem(PREV_PAGE_SLOT, page > 1 ? navItem(langManager.get("shop-inventory-previous-page")) : null);
        inv.setItem(NEXT_PAGE_SLOT, page < totalPages ? navItem(langManager.get("shop-inventory-next-page")) : null);
    }

    private ItemStack navItem(String name) {
        ItemStack stack = new ItemStack(Material.ARROW);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static Material getCategoryIcon(String category) {
        return switch (category.toLowerCase()) {
            case "block_terrestre" -> Material.GRASS_BLOCK;
            case "pierres" -> Material.STONE;
            case "bois" -> Material.OAK_LOG;
            case "utilitaire" -> Material.CRAFTING_TABLE;
            case "nourriture" -> Material.COOKED_BEEF;
            case "agriculture" -> Material.WHEAT;
            case "minerais" -> Material.DIAMOND;
            case "decoration" -> Material.GLASS;
            default -> Material.CHEST;
        };
    }
}
