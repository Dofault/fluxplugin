package fr.influencraft.fluxshop.integration;

import fr.influencraft.flux.api.fishing.Fishing;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Custom fish of the shop through the fishing service of Flux (CustomFishing today, providers.fishing; rarity tiers in
 * the fishing section of Flux's config): prices baked into caught fish, one sample per tier for the price lists.
 */
public final class CustomFishingHook {

    private CustomFishingHook() {
    }

    public static boolean isEnabled() {
        return Fishing.available();
    }

    /** Total price (unit price x amount) written on a caught fish, 0 when it has none. */
    public static double getPrice(ItemStack stack) {
        return Fishing.price(stack);
    }

    public static boolean isCustomFishingItem(ItemStack stack) {
        return Fishing.isFishingItem(stack);
    }

    /** Known rarity tiers, from the most common. */
    public static List<String> getFishTiers() {
        return Fishing.tiers();
    }

    /** Tier of a fish, or null (junk, other items). */
    public static String getFishTier(ItemStack stack) {
        return Fishing.tier(stack);
    }

    public static List<ItemStack> getSellableFishSamples(Player context) {
        return Fishing.samples(context);
    }

    public static Map<String, ItemStack> getSellableFishSamplesByTier(Player context) {
        return Fishing.samplesByTier(context);
    }
}
