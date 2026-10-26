package fr.influencraft.influshop.gui;

import fr.influencraft.influshop.config.ConfigManager;
import fr.influencraft.influshop.config.GuiSettings;
import fr.influencraft.influshop.model.ShopEntry;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Single click/drag listener for the inventory this plugin opens. Everything is cancelled
 * (a display/interaction GUI, never a real container players move items in and out of) —
 * actions are driven entirely by which slot was clicked/what's on the cursor. Adding a new
 * catalog item works two ways, both perfectly safe since nothing is ever actually placed:
 * clicking the real item in the player's own inventory (InfluOffrande's convention), or
 * actually picking it up and clicking/dropping it into the shop's own inventory.
 */
public final class GuiListener implements Listener {

    private final ConfigManager configManager;
    private final ShopMenu shopMenu;

    public GuiListener(ConfigManager configManager, ShopMenu shopMenu) {
        this.configManager = configManager;
        this.shopMenu = shopMenu;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof InfluShopHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder rawHolder = event.getView().getTopInventory().getHolder();
        if (rawHolder instanceof ConfirmMenuHolder confirmHolder) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                handleConfirmMenuClick(event, player, confirmHolder);
            }
            return;
        }
        if (!(rawHolder instanceof ShopMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        handleClick(event, player, holder);
    }

    private void handleConfirmMenuClick(InventoryClickEvent event, Player player, ConfirmMenuHolder holder) {
        if (!clickedTop(event)) {
            return;
        }
        int slot = event.getSlot();
        var confirm = configManager.confirm();
        if (slot == confirm.confirmSlot()) {
            shopMenu.handleConfirm(player, holder);
        } else if (slot == confirm.cancelSlot()) {
            shopMenu.handleCancel(player, holder);
        }
    }

    private boolean clickedTop(InventoryClickEvent event) {
        return event.getClickedInventory() != null && event.getClickedInventory() == event.getView().getTopInventory();
    }

    private void handleClick(InventoryClickEvent event, Player player, ShopMenuHolder holder) {
        GuiSettings gui = configManager.gui();
        Inventory inv = event.getInventory();

        if (!clickedTop(event)) {
            // Clicking in the player's own inventory only matters in editor mode: it's how an
            // admin can also register a brand new item, by clicking the real one they're
            // holding without ever picking it up.
            if (holder.editorMode() && player.hasPermission("influshop.admin")
                    && event.getCurrentItem() != null && event.getCurrentItem().getType() != Material.AIR) {
                shopMenu.handleEditorAddRequest(player, event.getCurrentItem());
            }
            return;
        }

        if (holder.editorMode() && player.hasPermission("influshop.admin")) {
            // The actual "drag an item in and drop it in the shop window" gesture: the item
            // sits on the cursor (picked up beforehand) and the player clicks a slot in OUR
            // inventory to place it. Checked before any per-slot logic below — an item on the
            // cursor always means "add this", regardless of which slot (an existing entry's,
            // a control button's, or empty) it was dropped on, since the click is cancelled
            // and nothing is actually placed either way.
            var cursor = event.getCursor();
            if (cursor != null && cursor.getType() != Material.AIR) {
                shopMenu.handleEditorAddRequest(player, cursor);
                return;
            }
        }

        int slot = event.getSlot();
        if (slot == gui.navPreviousSlot()) {
            if (holder.page() > 0) {
                holder.page(holder.page() - 1);
                shopMenu.refresh(player, inv, holder);
            } else {
                player.closeInventory();
                player.performCommand("menu");
            }
            return;
        }
        if (slot == gui.navNextSlot()) {
            holder.page(holder.page() + 1);
            shopMenu.refresh(player, inv, holder);
            return;
        }
        if (slot == gui.sortSlot()) {
            holder.sortMode(holder.sortMode().next());
            holder.page(0);
            shopMenu.refresh(player, inv, holder);
            return;
        }
        if (slot == gui.editorToggleSlot() && player.hasPermission("influshop.admin")) {
            shopMenu.openFresh(player, 0, holder.sortMode(), !holder.editorMode());
            return;
        }
        if (slot == gui.balanceSlot()) {
            return; // read-only display
        }

        if (!gui.itemSlots().contains(slot)) {
            return;
        }
        String entryId = holder.slotToEntryId().get(slot);
        if (entryId == null) {
            return;
        }
        ShopEntry entry = configManager.entries().get(entryId);
        if (entry == null) {
            return;
        }

        if (holder.editorMode()) {
            if (!player.hasPermission("influshop.admin")) {
                return;
            }
            if (event.isRightClick()) {
                shopMenu.handleEditorRemove(player, inv, holder, entry);
            } else {
                shopMenu.handleEditorLeftClick(player, entry);
            }
        } else {
            shopMenu.handlePurchaseClick(player, holder, entry);
        }
    }
}
