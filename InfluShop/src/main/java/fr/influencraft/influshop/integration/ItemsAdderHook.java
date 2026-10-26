package fr.influencraft.influshop.integration;

import dev.lone.itemsadder.api.CustomStack;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/**
 * Thin wrapper around the ItemsAdder API, used only to resolve the "influitems:xxx" control
 * button materials configured in config.yml (nav arrows, sort, balance, back). Every call is
 * guarded by {@link #isEnabled()} so the plugin keeps working when ItemsAdder is absent.
 */
public final class ItemsAdderHook {

    private ItemsAdderHook() {
    }

    public static boolean isEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("ItemsAdder");
    }

    /**
     * @return a fresh ItemStack for the given namespaced id, or null if it doesn't exist
     * (or ItemsAdder isn't installed).
     */
    public static ItemStack createItemStack(String namespacedId) {
        if (!isEnabled() || namespacedId == null) {
            return null;
        }
        CustomStack custom = CustomStack.getInstance(namespacedId);
        return custom == null ? null : custom.getItemStack();
    }
}
