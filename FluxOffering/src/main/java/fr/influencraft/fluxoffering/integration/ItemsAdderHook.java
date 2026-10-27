package fr.influencraft.fluxoffering.integration;

import fr.influencraft.flux.api.items.CustomItems;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Custom items of offerings through the custom items service of Flux (ItemsAdder today, providers.items): this
 * plugin never talks to the custom item plugin itself.
 */
public final class ItemsAdderHook {

    private ItemsAdderHook() {
    }

    public static boolean isEnabled() {
        return CustomItems.available();
    }

    /** Namespaced id ("namespace:id") of the custom item behind this stack, or null. */
    public static String getNamespacedId(ItemStack stack) {
        return CustomItems.id(stack);
    }

    public static boolean exists(String namespacedId) {
        return namespacedId != null && CustomItems.exists(namespacedId);
    }

    /** A fresh stack of the custom item, or null when it does not exist. */
    public static ItemStack createItemStack(String namespacedId) {
        return namespacedId == null ? null : CustomItems.item(namespacedId);
    }

    public static String getDisplayName(String namespacedId) {
        ItemStack stack = createItemStack(namespacedId);
        ItemMeta meta = stack == null ? null : stack.getItemMeta();
        return meta == null || meta.displayName() == null ? null : fr.influencraft.flux.api.text.Texts.ampersand(meta.displayName());
    }

    /**
     * Reverse lookup for an item that does not carry the custom item tag (built by another plugin like CustomFishing,
     * which reuses a model's material + custom model data): scans every custom item for the same material and custom
     * model data. Only used by the editor's "add item from my inventory" (rare admin action).
     */
    public static String findNamespacedIdByModelData(ItemStack stack) {
        if (stack == null) return null;
        ItemMeta stackMeta = stack.getItemMeta();
        if (stackMeta == null || !stackMeta.hasCustomModelData()) return null;
        int modelData = stackMeta.getCustomModelData();
        for (String id : CustomItems.ids()) {
            ItemStack candidate = CustomItems.item(id);
            if (candidate == null || candidate.getType() != stack.getType()) continue;
            ItemMeta candidateMeta = candidate.getItemMeta();
            if (candidateMeta != null && candidateMeta.hasCustomModelData() && candidateMeta.getCustomModelData() == modelData) return id;
        }
        return null;
    }
}
