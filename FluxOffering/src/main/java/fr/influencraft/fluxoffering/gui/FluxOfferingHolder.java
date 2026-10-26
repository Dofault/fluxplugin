package fr.influencraft.fluxoffering.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker for every inventory opened by this plugin, so the click listener can tell our
 * menus apart from any other inventory (including vanilla ones) with a single instanceof check.
 */
public interface FluxOfferingHolder extends InventoryHolder {

    void setInventory(Inventory inventory);
}
