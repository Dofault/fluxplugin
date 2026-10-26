package fr.influencraft.influoffrande.integration;

import dev.lone.itemsadder.api.CustomStack;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Thin wrapper around the ItemsAdder API. Every call is guarded by
 * {@link #isEnabled()} so the plugin keeps working when ItemsAdder is absent
 * (it is only a soft dependency).
 */
public final class ItemsAdderHook {

    private ItemsAdderHook() {
    }

    public static boolean isEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("ItemsAdder");
    }

    /**
     * @return the namespaced id ("namespace:id") of the custom item backing this stack,
     * or null if the stack is not an ItemsAdder item (or ItemsAdder is not installed).
     */
    public static String getNamespacedId(ItemStack stack) {
        if (!isEnabled() || stack == null) {
            return null;
        }
        CustomStack custom = CustomStack.byItemStack(stack);
        return custom == null ? null : custom.getNamespacedID();
    }

    public static boolean exists(String namespacedId) {
        if (!isEnabled() || namespacedId == null) {
            return false;
        }
        return CustomStack.getInstance(namespacedId) != null;
    }

    /**
     * @return a fresh ItemStack for the given namespaced id, or null if it doesn't exist.
     */
    public static ItemStack createItemStack(String namespacedId) {
        if (!isEnabled() || namespacedId == null) {
            return null;
        }
        CustomStack custom = CustomStack.getInstance(namespacedId);
        return custom == null ? null : custom.getItemStack();
    }

    public static String getDisplayName(String namespacedId) {
        if (!isEnabled() || namespacedId == null) {
            return null;
        }
        CustomStack custom = CustomStack.getInstance(namespacedId);
        return custom == null ? null : custom.getDisplayName();
    }

    /**
     * Reverse lookup: given an item that doesn't carry ItemsAdder's own tag (e.g. built by a
     * third-party plugin like CustomFishing, which reuses an ItemsAdder model's Material +
     * CustomModelData for the resource pack look without going through ItemsAdder's item
     * factory), scan every registered ItemsAdder id for one whose reference stack has the same
     * Material + CustomModelData. Used only by the in-game editor's "add item from my
     * inventory" flow (a rare, deliberate admin action) — not cheap enough to call per deposit.
     *
     * @return the matching namespaced id, or null if nothing in the registry matches.
     */
    public static String findNamespacedIdByModelData(ItemStack stack) {
        if (!isEnabled() || stack == null) {
            return null;
        }
        ItemMeta stackMeta = stack.getItemMeta();
        if (stackMeta == null || !stackMeta.hasCustomModelData()) {
            return null;
        }
        int modelData = stackMeta.getCustomModelData();
        // ItemsAdder exposes this as a raw (unparameterized) Set in its compiled API.
        for (Object rawId : CustomStack.getNamespacedIdsInRegistry()) {
            String id = (String) rawId;
            CustomStack candidate = CustomStack.getInstance(id);
            if (candidate == null) {
                continue;
            }
            ItemStack candidateStack = candidate.getItemStack();
            if (candidateStack == null || candidateStack.getType() != stack.getType()) {
                continue;
            }
            ItemMeta candidateMeta = candidateStack.getItemMeta();
            if (candidateMeta != null && candidateMeta.hasCustomModelData()
                    && candidateMeta.getCustomModelData() == modelData) {
                return id;
            }
        }
        return null;
    }
}
