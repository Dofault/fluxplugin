package fr.influencraft.fluxshop.shop;

import fr.influencraft.fluxshop.managers.LangManager;
import fr.influencraft.fluxshop.managers.PriceManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Menu d'un coffre magasin, achat seul (la vente passe par le bloc de vente) : une rangée par
 * objet, l'objet puis ses boutons x1, x2, x10, x64, le tout centré sur les 9 colonnes.
 * <p>
 * Chaque clic est une transaction complète et vérifiée, jouée sur le thread principal (aucun
 * autre événement ne peut s'intercaler entre la vérification et l'exécution) : place libre dans
 * le stockage, puis solde, puis débit, puis remise des objets. Rien n'est débité si la place
 * manque, et le reliquat éventuel est remboursé.
 * <p>
 * Double confirmation : un premier clic arme le bouton (vitre orange), un second clic sur le même
 * bouton achète et le remet au vert. Le bouton armé se désarme seul après un délai, ou dès qu'un
 * autre bouton est armé. Juste après un achat, le même bouton achète directement pendant un court
 * délai ({@code shop-menu.quick-buy-seconds}, relancé à chaque achat) : on peut acheter en masse
 * en cliquant vite sans repasser par la confirmation.
 * <p>
 * Les prix sont relus à chaque clic : un {@code /supermarket reload} ou un prix changé dans
 * l'éditeur s'applique même à un menu déjà ouvert.
 */
public class ShopMenu implements Listener {

    private static final int[] QUANTITIES = { 1, 2, 10, 64 };
    /** 5 cases (objet + 4 boutons) centrées sur 9 : colonnes 2 à 6. */
    private static final int ITEM_COLUMN = 2;
    /** Colonnes de bordure (vitres grises) de part et d'autre de l'objet et de ses boutons. */
    private static final int[] FILLER_COLUMNS = { 0, 1, 7, 8 };
    private static final int NO_SLOT = -1;

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final PriceManager priceManager;
    private final ShopActivity activity;
    private final Economy econ;
    private final MenuItems menuItems;

    public ShopMenu(JavaPlugin plugin, LangManager lang, PriceManager priceManager, ShopActivity activity,
            Economy econ) {
        this.plugin = plugin;
        this.lang = lang;
        this.priceManager = priceManager;
        this.activity = activity;
        this.econ = econ;
        this.menuItems = new MenuItems(plugin, lang, econ);
    }

    public void open(Player player, Shop shop) {
        Session session = new Session(player, shop);
        if (session.items.isEmpty()) {
            player.sendMessage(lang.get("shop-menu-empty"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        session.inventory = Bukkit.createInventory(session, session.items.size() * 9, lang.get("shop-menu-title"));
        session.render();
        player.openInventory(session.inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
    }

    private String price(double amount) {
        return menuItems.price(amount);
    }

    // ------------------------------------------------------------------
    // Session (un menu ouvert)
    // ------------------------------------------------------------------

    private final class Session implements InventoryHolder {

        private final Player player;
        private final Shop shop;
        /** Objets affichés, figés à l'ouverture : la rangée N correspond toujours au même objet. */
        private final List<ShopEntry> items = new ArrayList<>();
        private Inventory inventory;

        private int armedSlot = NO_SLOT;
        private BukkitTask disarmTask;
        /** Bouton qui achète sans confirmation jusqu'à {@link #quickUntil}, juste après un achat. */
        private int quickSlot = NO_SLOT;
        private long quickUntil = 0L;

        private Session(Player player, Shop shop) {
            this.player = player;
            this.shop = shop;
            for (ShopEntry entry : shop.getItems()) {
                if (entry.unitPrice(priceManager) > 0) {
                    items.add(entry);
                }
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void render() {
            inventory.clear();
            ItemStack filler = menuItems.filler();
            for (int row = 0; row < items.size(); row++) {
                ShopEntry entry = items.get(row);
                int base = row * 9;
                for (int column : FILLER_COLUMNS) {
                    inventory.setItem(base + column, filler);
                }
                inventory.setItem(base + ITEM_COLUMN, displayItem(entry));

                double buy = entry.unitPrice(priceManager);
                if (buy <= 0) {
                    continue; // retiré de la vente depuis l'ouverture (reload)
                }
                for (int i = 0; i < QUANTITIES.length; i++) {
                    int slot = base + ITEM_COLUMN + 1 + i;
                    inventory.setItem(slot, button(QUANTITIES[i], buy * QUANTITIES[i], slot == armedSlot));
                }
            }
        }

        private ItemStack displayItem(ShopEntry entry) {
            double buy = entry.unitPrice(priceManager);
            return MenuItems.withExtraLore(entry, lang.getList("shop-menu-item-lore", Map.of(
                    "buy", buy > 0 ? price(buy) : lang.get("shop-menu-unavailable"),
                    "owned", String.valueOf(PlayerStorage.count(player.getInventory(), entry.getItem())))));
        }

        private ItemStack button(int quantity, double total, boolean armed) {
            ItemStack stack = armed
                    ? menuItems.icon("buy-button-armed", Material.ORANGE_STAINED_GLASS_PANE, 0, quantity)
                    : menuItems.icon("buy-button", Material.LIME_STAINED_GLASS_PANE, 0, quantity);
            Map<String, String> placeholders = Map.of(
                    "amount", String.valueOf(quantity),
                    "price", price(total));
            String key = armed ? "shop-menu-buy-armed" : "shop-menu-buy";
            return MenuItems.decorate(stack, lang.get(key + "-name", placeholders), lang.getList(key + "-lore", placeholders));
        }

        private void onClick(int slot) {
            int row = slot / 9;
            int column = slot % 9;
            int index = column - ITEM_COLUMN - 1;
            if (row >= items.size() || index < 0 || index >= QUANTITIES.length) {
                return;
            }
            long now = System.currentTimeMillis();
            boolean quick = slot == quickSlot && now < quickUntil;
            if (!quick && slot != armedSlot) {
                arm(slot);
                return;
            }

            disarm();
            if (buy(player, shop, items.get(row), QUANTITIES[index])) {
                quickSlot = slot;
                quickUntil = now + Math.max(0L,
                        (long) (plugin.getConfig().getDouble("shop-menu.quick-buy-seconds", 1.0) * 1000));
            } else {
                quickSlot = NO_SLOT;
            }
            render();
        }

        private void arm(int slot) {
            cancelDisarm();
            quickSlot = NO_SLOT;
            armedSlot = slot;
            long timeout = Math.max(1, plugin.getConfig().getLong("shop-menu.confirm-timeout-seconds", 5)) * 20L;
            disarmTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                disarmTask = null;
                armedSlot = NO_SLOT;
                if (player.getOpenInventory().getTopInventory().getHolder() == this) {
                    render();
                }
            }, timeout);
            // Son d'XP : le clic est pris en compte, rien n'est encore acheté.
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 0.7f);
            render();
        }

        private void disarm() {
            cancelDisarm();
            armedSlot = NO_SLOT;
        }

        private void cancelDisarm() {
            if (disarmTask != null) {
                disarmTask.cancel();
                disarmTask = null;
            }
        }
    }

    // ------------------------------------------------------------------
    // Transactions
    // ------------------------------------------------------------------

    /** @return {@code true} si l'achat a eu lieu. */
    private boolean buy(Player player, Shop shop, ShopEntry entry, int quantity) {
        double unitPrice = entry.unitPrice(priceManager);
        if (unitPrice <= 0) {
            fail(player, lang.get("shop-buy-unavailable"));
            return false;
        }

        ItemStack model = entry.getItem();
        PlayerInventory inventory = player.getInventory();
        int space = PlayerStorage.space(inventory, model);
        if (space < quantity) {
            fail(player, lang.get("shop-buy-no-space", Map.of(
                    "amount", String.valueOf(quantity),
                    "space", String.valueOf(space))));
            return false;
        }

        double total = PriceManager.roundMoney(unitPrice * quantity);
        if (!econ.has(player, total)) {
            fail(player, lang.get("shop-buy-not-enough-money", Map.of(
                    "price", econ.format(total),
                    "balance", econ.format(econ.getBalance(player)))));
            return false;
        }

        EconomyResponse withdrawal = econ.withdrawPlayer(player, total);
        if (!withdrawal.transactionSuccess()) {
            plugin.getLogger().warning("[Magasin] Débit refusé pour " + player.getName() + " (" + total + ") : "
                    + withdrawal.errorMessage);
            fail(player, lang.get("shop-transaction-failed"));
            return false;
        }

        int notGiven = PlayerStorage.add(inventory, model, quantity);
        if (notGiven > 0) {
            // Impossible en pratique (place vérifiée juste avant, sur le même tick) : filet de
            // sécurité, le joueur ne paie que ce qu'il a reçu.
            double refund = PriceManager.roundMoney(unitPrice * notGiven);
            econ.depositPlayer(player, refund);
            plugin.getLogger().warning("[Magasin] " + notGiven + " " + MenuItems.describe(entry) + " non rangés pour "
                    + player.getName() + ", " + refund + " remboursés.");
            quantity -= notGiven;
            total = PriceManager.roundMoney(total - refund);
        }

        activity.markBought(player.getUniqueId());
        plugin.getLogger().info("[Magasin] " + player.getName() + " a acheté " + quantity + " "
                + MenuItems.describe(entry) + " pour " + total + " (" + shop.getId() + ")");
        MenuItems.send(player, lang.get("shop-buy-success", Map.of(
                "amount", String.valueOf(quantity),
                "price", econ.format(total))), model);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        return true;
    }

    private void fail(Player player, String message) {
        player.sendMessage(message);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
    }

    // ------------------------------------------------------------------
    // Événements
    // ------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Session session)) {
            return;
        }
        // Tout est bloqué, y compris côté inventaire du joueur (shift-clic, touches numériques,
        // double-clic qui ramasse) : rien ne doit entrer dans le menu ni en sortir.
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        // Clic gauche simple uniquement : un double-clic génère en plus un événement DOUBLE_CLICK
        // qui, pendant l'achat rapide, achèterait une fois de trop.
        if (event.getClick() != ClickType.LEFT || !event.getWhoClicked().equals(session.player)) {
            return;
        }
        session.onClick(event.getSlot());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Session session) {
            session.disarm();
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Session) {
            event.setCancelled(true);
        }
    }
}
