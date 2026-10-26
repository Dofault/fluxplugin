package fr.influencraft.fluxmenu.session;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Porteur de l'inventaire d'un menu : permet de reconnaître nos fenêtres dans les événements. */
public final class MenuHolder implements InventoryHolder {

    private MenuSession session;
    private Inventory inventory;

    void bind(MenuSession session, Inventory inventory) {
        this.session = session;
        this.inventory = inventory;
    }

    public MenuSession session() {
        return session;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
