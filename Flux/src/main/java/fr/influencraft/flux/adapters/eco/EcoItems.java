package fr.influencraft.flux.adapters.eco;

import com.willfp.eco.core.items.Items;
import com.willfp.eco.core.items.TestableItem;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/**
 * Items of the eco plugins (EcoItems, EcoArmor...) for {@code ItemFactory}: specs {@code ecoitems:<id>},
 * {@code ecoarmor:<id>} and their arguments ({@code ecoarmor:set_virale_helmet item_model:...}), looked up through
 * eco, and the id of such an item ({@code CustomItems.id}). Only called when eco is enabled.
 */
public final class EcoItems {

    private EcoItems() {
    }

    public static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("eco");
    }

    /** Key of the eco custom item ({@code ecoarmor:virale_chestplate}), null for any other item. */
    public static String id(ItemStack item) {
        com.willfp.eco.core.items.CustomItem custom = Items.getCustomItem(item);
        return custom == null ? null : custom.getKey().toString();
    }

    /** The item of an eco lookup string, null when eco does not know it. */
    public static ItemStack lookup(String spec) {
        TestableItem item = Items.lookup(spec);
        ItemStack stack = item == null ? null : item.getItem();
        return stack == null || stack.getType().isAir() ? null : stack.clone();
    }
}
