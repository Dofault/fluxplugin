package io.dofault.supermarket.integration;

import net.advancedplugins.ae.api.AEAPI;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/**
 * Fine wrapper around the AdvancedEnchantments API. Every call is guarded so the plugin keeps
 * working when AdvancedEnchantments is absent (it is only a soft dependency).
 * <p>
 * Used by {@link io.dofault.supermarket.listener.ShopRestrictionListener} to block the
 * drag-and-drop application of ANY AdvancedEnchantments special item inside the shop (enchant
 * books, but also tomes/orbs, protection runes, scrolls, soul gems...): that interaction goes
 * through {@code InventoryClickEvent}/{@code InventoryDragEvent}, a channel the shop's
 * world-interaction lock (cancelling {@code PlayerInteractEvent}) never touched.
 */
public final class AdvancedEnchantmentsHook {

    private AdvancedEnchantmentsHook() {
    }

    public static boolean isEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("AdvancedEnchantments");
    }

    public static boolean isCustomEnchantBook(ItemStack stack) {
        if (!isEnabled() || stack == null || stack.getType().isAir()) {
            return false;
        }
        try {
            return AEAPI.isCustomEnchantBook(stack);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * @return true for ANY item AdvancedEnchantments recognizes as its own — not just a plain
     * enchant book, but also tomes/orbs, the protection rune, scrolls, soul gems, and any
     * future item type the plugin adds. Combines the dedicated book check (older items may
     * predate AE's use of the PersistentDataContainer) with a generic scan: every other AE
     * item type is tagged via a PDC key namespaced to AE's own plugin name (confirmed by
     * decompiling {@code PDCHandler.getNamespace()} in AdvancedEnchantments-9.23.4.jar, e.g.
     * tomes/orbs carry an "orb" key under that namespace) — so any item carrying ANY PDC key
     * under that namespace is an AE item, whatever it's called internally.
     */
    public static boolean isAnyAeItem(ItemStack stack) {
        if (isCustomEnchantBook(stack)) {
            return true;
        }
        if (!isEnabled() || stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        try {
            Plugin ae = Bukkit.getPluginManager().getPlugin("AdvancedEnchantments");
            if (ae == null) {
                return false;
            }
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) {
                return false;
            }
            String aeNamespace = ae.getName().toLowerCase(java.util.Locale.ROOT);
            for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
                if (key.getNamespace().equalsIgnoreCase(aeNamespace)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }
}
