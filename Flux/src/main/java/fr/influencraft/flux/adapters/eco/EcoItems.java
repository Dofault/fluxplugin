package fr.influencraft.flux.adapters.eco;

import com.willfp.eco.core.items.Items;
import com.willfp.eco.core.items.TestableItem;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/**
 * Items of the eco plugins (EcoItems, EcoArmor...) for {@code ItemFactory}: specs {@code ecoitems:<id>},
 * {@code ecoarmor:<id>} and their eco arguments ({@code ecoarmor:set_virale_helmet item_model:...}), and
 * {@code eco:<any eco item string>}, looked up through eco. Only called when eco is enabled.
 */
public final class EcoItems {

    private EcoItems() {
    }

    public static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("eco");
    }

    /** The item of an eco lookup string, null when eco does not know it. */
    public static ItemStack lookup(String spec) {
        TestableItem item = Items.lookup(spec);
        ItemStack stack = item == null ? null : item.getItem();
        return stack == null || stack.getType().isAir() ? null : stack.clone();
    }
}
