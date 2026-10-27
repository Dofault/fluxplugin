package fr.influencraft.fluxshop.integration;

import fr.influencraft.flux.api.items.CustomItems;
import org.bukkit.block.Block;

/**
 * Custom blocks used as shop blocks ({@code itemsadder:<namespace>:<id>} in {@code shop.block-types}), through the
 * custom items service of Flux (ItemsAdder today, providers.items). Only real custom blocks are covered: furniture
 * are entities, they cannot be shops.
 */
public final class ItemsAdderHook {

    private ItemsAdderHook() {
    }

    public static boolean isEnabled() {
        return CustomItems.available();
    }

    /** @return {@code namespace:id} of the custom block placed there, or {@code null}. */
    public static String blockId(Block block) {
        try {
            return CustomItems.blockId(block);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Custom blocks are loaded after start-up: nothing can be placed before. */
    public static boolean areItemsLoaded() {
        return CustomItems.available() && CustomItems.loaded();
    }

    /** @return {@code true} when the custom block {@code namespace:id} was placed. */
    public static boolean place(String namespacedId, Block block) {
        try {
            return CustomItems.placeBlock(namespacedId, block.getLocation());
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
