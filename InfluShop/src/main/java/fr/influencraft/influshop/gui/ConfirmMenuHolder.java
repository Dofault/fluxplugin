package fr.influencraft.influshop.gui;

import fr.influencraft.influshop.model.ShopEntry;
import fr.influencraft.influshop.model.SortMode;
import org.bukkit.inventory.Inventory;

/**
 * The small "confirm this purchase?" inventory opened on top of the shop when a catalog item
 * is clicked. Remembers which entry is pending and where in the shop to return to (page/sort)
 * once the player confirms or cancels.
 */
public final class ConfirmMenuHolder implements InfluShopHolder {

    private Inventory inventory;
    private final ShopEntry entry;
    private final int returnPage;
    private final SortMode returnSortMode;

    public ConfirmMenuHolder(ShopEntry entry, int returnPage, SortMode returnSortMode) {
        this.entry = entry;
        this.returnPage = returnPage;
        this.returnSortMode = returnSortMode;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public ShopEntry entry() {
        return entry;
    }

    public int returnPage() {
        return returnPage;
    }

    public SortMode returnSortMode() {
        return returnSortMode;
    }
}
