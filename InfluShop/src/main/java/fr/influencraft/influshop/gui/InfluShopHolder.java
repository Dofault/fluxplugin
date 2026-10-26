package fr.influencraft.influshop.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker for the inventory opened by this plugin, so the click listener can tell it apart
 * from any other inventory (including vanilla ones) with a single instanceof check.
 */
public interface InfluShopHolder extends InventoryHolder {

    void setInventory(Inventory inventory);
}
