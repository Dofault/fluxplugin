package io.dofault.supermarket.managers;

import io.dofault.supermarket.autosell.AutoSellItemManager;
import io.dofault.supermarket.integration.SellPriceResolver;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Le menu de vente manuelle : un double coffre où le joueur dépose ce qu'il veut vendre.
 * <p>
 * Rien n'est vendu tant que le joueur n'a pas cliqué DEUX fois sur le bouton de confirmation
 * de la dernière rangée (premier clic = armement + son d'XP, second clic = vente + fermeture).
 * Fermer le menu par n'importe quel autre chemin rend l'intégralité du contenu : une vente ne
 * peut donc jamais partir d'un simple réflexe de fermeture, contrairement à l'ancien
 * fonctionnement où le seul fait de fermer l'inventaire vendait tout.
 * <p>
 * La dernière rangée entière (slots 45-53) est remplie de vitres, bouton compris : elle sert
 * de zone morte, aucun item ne peut y être déposé (tout clic et tout drag qui la vise est
 * annulé), donc le contenu vendable est exactement les slots 0-44.
 */
public class SellMenu {

    private static final int SIZE = 54;
    private static final int SELLABLE_END_SLOT = 44; // slots 0-44 : contenu du joueur
    private static final int DEFAULT_CONFIRM_SLOT = 49;

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final PriceManager priceManager;
    private final PlayerListManager playerListManager;
    private final AutoSellItemManager autoSellItemManager;
    private final Economy econ;

    public SellMenu(JavaPlugin plugin, LangManager lang, PriceManager priceManager,
            PlayerListManager playerListManager, AutoSellItemManager autoSellItemManager, Economy econ) {
        this.plugin = plugin;
        this.lang = lang;
        this.priceManager = priceManager;
        this.playerListManager = playerListManager;
        this.autoSellItemManager = autoSellItemManager;
        this.econ = econ;
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, SIZE, lang.get("shop-sell-inventory-title"));
        Session session = new Session(player, inv);
        session.renderBottomRow();
        player.openInventory(inv);
        Bukkit.getPluginManager().registerEvents(session, plugin);
    }

    /** Total de la vente et décompte des items concernés, recalculé à chaque changement. */
    private record Quote(double total, int sellableCount, int unsellableCount) {
    }

    private Quote quote(Inventory inv) {
        double total = 0.0;
        int sellable = 0;
        int unsellable = 0;
        for (int slot = 0; slot <= SELLABLE_END_SLOT; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            // L'objet de vente auto n'est jamais vendable, même s'il a un prix de matériau.
            double price = autoSellItemManager.isAutoSellItem(item)
                    ? 0.0
                    : SellPriceResolver.resolveTotalPrice(priceManager, item);
            if (price > 0) {
                total += price;
                sellable += item.getAmount();
            } else {
                unsellable += item.getAmount();
            }
        }
        return new Quote(total, sellable, unsellable);
    }

    private int confirmSlot() {
        int slot = plugin.getConfig().getInt("sell-menu.confirm-slot", DEFAULT_CONFIRM_SLOT);
        return slot > SELLABLE_END_SLOT && slot < SIZE ? slot : DEFAULT_CONFIRM_SLOT;
    }

    private int confirmTimeoutTicks() {
        return Math.max(1, plugin.getConfig().getInt("sell-menu.confirm-timeout-seconds", 5)) * 20;
    }

    private ItemStack filler() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("sell-menu.filler");
        Material material = material(section, "material", Material.GRAY_STAINED_GLASS_PANE);
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            applyModelData(meta, section);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * Le bouton de confirmation. Rendu par ItemsAdder via un {@code custom_model_data} sur un
     * matériau vanilla (PAPER 10020 = {@code influitems:accept}, 10032 =
     * {@code influitems:checkmark}) plutôt que par l'API ItemsAdder : le plugin n'a pas de
     * hook ItemsAdder, et c'est la méthode déjà retenue pour les plugins dans ce cas
     * (cf. CLAUDE.md, {@code ItemsAdder/storage/items_ids_cache.yml}).
     */
    private ItemStack confirmButton(Quote quote, boolean armed) {
        String path = armed ? "sell-menu.confirm-button-armed" : "sell-menu.confirm-button";
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
        Material material = material(section, "material", Material.PAPER);

        Map<String, String> placeholders = Map.of(
                "price", econ.format(quote.total()),
                "sellable", String.valueOf(quote.sellableCount()),
                "unsellable", String.valueOf(quote.unsellableCount()));

        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(lang.get(armed ? "shop-sell-confirm-armed-name" : "shop-sell-confirm-name",
                    placeholders));
            meta.setLore(lang.getList(armed ? "shop-sell-confirm-armed-lore" : "shop-sell-confirm-lore",
                    placeholders));
            applyModelData(meta, section);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private Material material(ConfigurationSection section, String key, Material fallback) {
        if (section == null) {
            return fallback;
        }
        Material material = Material.matchMaterial(section.getString(key, fallback.name()));
        return material == null ? fallback : material;
    }

    private void applyModelData(ItemMeta meta, ConfigurationSection section) {
        if (section == null) {
            return;
        }
        int modelData = section.getInt("custom-model-data", 0);
        if (modelData > 0) {
            meta.setCustomModelData(modelData);
        }
    }

    /**
     * Un menu ouvert. Écouteur enregistré à l'ouverture et désenregistré à la fermeture, comme
     * le reste des menus du plugin.
     */
    private final class Session implements Listener {

        private final Player player;
        private final Inventory inv;
        private final int confirmSlot = confirmSlot();

        private boolean armed = false;
        private boolean sold = false;
        private BukkitTask disarmTask;

        private Session(Player player, Inventory inv) {
            this.player = player;
            this.inv = inv;
        }

        private void renderBottomRow() {
            ItemStack filler = filler();
            for (int slot = SELLABLE_END_SLOT + 1; slot < SIZE; slot++) {
                inv.setItem(slot, slot == confirmSlot ? confirmButton(quote(inv), armed) : filler);
            }
        }

        private void refreshButton() {
            inv.setItem(confirmSlot, confirmButton(quote(inv), armed));
        }

        private void disarm() {
            if (disarmTask != null) {
                disarmTask.cancel();
                disarmTask = null;
            }
            if (armed) {
                armed = false;
                refreshButton();
            }
        }

        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!e.getInventory().equals(inv) || !e.getWhoClicked().equals(player)) {
                return;
            }

            // Toute la dernière rangée est inerte : ni dépôt, ni retrait, ni échange.
            if (e.getRawSlot() > SELLABLE_END_SLOT && e.getRawSlot() < SIZE) {
                e.setCancelled(true);
                if (e.getRawSlot() == confirmSlot) {
                    handleConfirm();
                }
                return;
            }

            // Un déplacement d'item invalide le montant affiché : on désarme et on recalcule
            // au tick suivant, une fois le déplacement effectivement appliqué.
            Bukkit.getScheduler().runTask(plugin, () -> {
                disarm();
                refreshButton();
            });
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (!e.getInventory().equals(inv) || !e.getWhoClicked().equals(player)) {
                return;
            }
            for (int rawSlot : e.getRawSlots()) {
                if (rawSlot > SELLABLE_END_SLOT && rawSlot < SIZE) {
                    e.setCancelled(true);
                    return;
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                disarm();
                refreshButton();
            });
        }

        private void handleConfirm() {
            Quote quote = quote(inv);

            if (quote.total() <= 0) {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                player.sendMessage(lang.get("shop-sell-nothing-to-sell"));
                disarm();
                return;
            }

            if (!armed) {
                armed = true;
                refreshButton();
                // Son d'XP : signale que le clic a bien été pris en compte mais que rien n'est
                // encore vendu, et que le prochain clic, lui, est définitif.
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                if (disarmTask != null) {
                    disarmTask.cancel();
                }
                disarmTask = Bukkit.getScheduler().runTaskLater(plugin, this::disarm, confirmTimeoutTicks());
                return;
            }

            sell(quote);
        }

        private void sell(Quote quote) {
            sold = true;
            if (disarmTask != null) {
                disarmTask.cancel();
                disarmTask = null;
            }

            List<ItemStack> toRestore = new ArrayList<>();
            for (int slot = 0; slot <= SELLABLE_END_SLOT; slot++) {
                ItemStack item = inv.getItem(slot);
                if (item == null || item.getType() == Material.AIR) {
                    continue;
                }
                boolean sellable = !autoSellItemManager.isAutoSellItem(item)
                        && SellPriceResolver.resolveTotalPrice(priceManager, item) > 0;
                if (!sellable) {
                    toRestore.add(item.clone());
                }
                inv.setItem(slot, null);
            }

            econ.depositPlayer(player, quote.total());
            player.sendMessage(lang.get("shop-sell-success", Map.of("amount", econ.format(quote.total()))));
            player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);

            playerListManager.markAsSold(player.getUniqueId());
            playerListManager.saveInventory(player, false);

            int restored = giveBack(toRestore);
            if (restored > 0) {
                player.sendMessage(lang.get("shop-unsellable-restored", Map.of("amount", String.valueOf(restored))));
            }

            player.closeInventory();
        }

        @EventHandler
        public void onClose(InventoryCloseEvent e) {
            if (!e.getInventory().equals(inv) || !e.getPlayer().equals(player)) {
                return;
            }
            if (disarmTask != null) {
                disarmTask.cancel();
                disarmTask = null;
            }

            if (!sold) {
                List<ItemStack> contents = new ArrayList<>();
                for (int slot = 0; slot <= SELLABLE_END_SLOT; slot++) {
                    ItemStack item = inv.getItem(slot);
                    if (item != null && item.getType() != Material.AIR) {
                        contents.add(item.clone());
                        inv.setItem(slot, null);
                    }
                }
                if (giveBack(contents) > 0) {
                    player.sendMessage(lang.get("shop-sell-cancelled"));
                }
            }

            inv.clear();
            HandlerList.unregisterAll(this);
        }

        /**
         * @return le nombre d'items effectivement rendus. Les items déposés venaient de
         * l'inventaire du joueur, la place existe donc toujours ; le reliquat n'est là que par
         * sécurité (inventaire rempli entre-temps par un autre plugin) et tombe au sol.
         */
        private int giveBack(List<ItemStack> items) {
            int count = 0;
            for (ItemStack item : items) {
                count += item.getAmount();
                for (ItemStack leftover : player.getInventory().addItem(item).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
            return count;
        }
    }
}
