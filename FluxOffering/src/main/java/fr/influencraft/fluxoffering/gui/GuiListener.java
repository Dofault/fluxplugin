package fr.influencraft.fluxoffering.gui;

import fr.influencraft.fluxoffering.config.ConfigManager;
import fr.influencraft.fluxoffering.config.GuiSettings;
import fr.influencraft.fluxoffering.model.CollectionDefinition;
import fr.influencraft.fluxoffering.model.ItemKey;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Single click/drag listener for every inventory this plugin opens. Everything is
 * cancelled (this is a display/interaction GUI, never a real container players move
 * items in and out of through drag &amp; drop) — actions are driven entirely by which
 * slot was clicked.
 */
public final class GuiListener implements Listener {

    private final ConfigManager configManager;
    private final CollectionMenu collectionMenu;

    public GuiListener(ConfigManager configManager, CollectionMenu collectionMenu) {
        this.configManager = configManager;
        this.collectionMenu = collectionMenu;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof FluxOfferingHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder rawHolder = event.getView().getTopInventory().getHolder();
        if (!(rawHolder instanceof FluxOfferingHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (rawHolder instanceof CollectionMenuHolder colHolder) {
            handleCollectionClick(event, player, colHolder);
        }
    }

    private boolean clickedTop(InventoryClickEvent event) {
        return event.getClickedInventory() != null && event.getClickedInventory() == event.getView().getTopInventory();
    }

    private void handleCollectionClick(InventoryClickEvent event, Player player, CollectionMenuHolder holder) {
        CollectionDefinition collection = configManager.getCollection(holder.collectionId());
        if (collection == null) {
            player.closeInventory();
            return;
        }
        GuiSettings gui = configManager.gui();
        Inventory inv = event.getInventory();

        if (!clickedTop(event)) {
            // Clicking in the player's own inventory only matters in editor mode: it's how
            // an admin registers a brand new required item type.
            if (holder.editorMode() && event.getCurrentItem() != null && event.getCurrentItem().getType() != Material.AIR) {
                collectionMenu.handleEditorAddRequest(player, collection, event.getCurrentItem());
            }
            return;
        }

        int slot = event.getSlot();
        if (slot == gui.navPreviousSlot()) {
            if (holder.page() > 0) {
                holder.page(holder.page() - 1);
                collectionMenu.refresh(player, inv, holder);
            } else {
                // 1ere page : pas de fleche "precedente" a cette place, c'est le
                // bouton retour vers le menu principal qui y est affiche a la place.
                player.closeInventory();
                player.performCommand("menu");
            }
            return;
        }
        if (slot == gui.navNextSlot()) {
            holder.page(holder.page() + 1);
            collectionMenu.refresh(player, inv, holder);
            return;
        }
        if (slot == gui.filterSlot() && !holder.editorMode()) {
            collectionMenu.cycleFilter(player, inv, holder);
            return;
        }
        if (slot == gui.editorToggleSlot() && player.hasPermission("offrandes.admin")) {
            collectionMenu.open(player, collection, !holder.editorMode());
            return;
        }
        if (slot == gui.depositAllSlot() && !holder.editorMode()) {
            collectionMenu.handleDepositAllClick(player, inv, holder, collection);
            return;
        }

        List<Integer> itemSlots = gui.itemSlots();
        boolean moneySlotActive = !itemSlots.isEmpty() && slot == itemSlots.get(0) && holder.page() == 0
                && (holder.editorMode() || collection.hasMoneyRequirement());
        if (moneySlotActive) {
            if (holder.editorMode()) {
                if (event.isRightClick()) {
                    collectionMenu.handleEditorMoneyRemove(player, inv, holder, collection);
                } else {
                    collectionMenu.handleEditorMoneyLeftClick(player, collection);
                }
            } else if (event.isRightClick()) {
                collectionMenu.handleMoneyDepositAllClick(player, inv, holder, collection);
            } else {
                collectionMenu.handleMoneyDepositCustomPrompt(player, collection);
            }
            return;
        }
        if (!itemSlots.contains(slot)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }
        ItemKey key = collectionMenu.resolveDisplayedKey(clicked);
        if (key == null || !collection.items().containsKey(key)) {
            return;
        }

        if (holder.editorMode()) {
            if (event.isRightClick()) {
                collectionMenu.handleEditorRemove(player, inv, holder, collection, key);
            } else {
                collectionMenu.handleEditorLeftClick(player, collection, key);
            }
        } else {
            // Clic gauche sur un item que le joueur n'a pas : rien a deposer, on lui donne
            // le lien wiki de l'objet (cf. CollectionMenu#handleWikiClick).
            if (!event.isRightClick() && collectionMenu.handleWikiClick(player, collection, key)) {
                return;
            }
            int maxAmount = event.isRightClick() ? 1 : Integer.MAX_VALUE;
            collectionMenu.handleDepositClick(player, inv, holder, collection, key, maxAmount);
        }
    }
}
