package fr.influencraft.flux.adapters.eco;

import com.willfp.eco.core.items.Items;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/** The id of an item made by an eco plugin ({@code CustomItems.id}), such items remain in the game. Only called when eco is enabled. */
public final class EcoItems {

    private EcoItems() {
    }

    public static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("eco");
    }

    /** Key of the eco custom item ({@code ecoitems:pioche_virale}), null for any other item. */
    public static String id(ItemStack item) {
        com.willfp.eco.core.items.CustomItem custom = Items.getCustomItem(item);
        return custom == null ? null : custom.getKey().toString();
    }

}
