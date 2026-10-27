package fr.influencraft.fluxshop.integration;

import fr.influencraft.fluxshop.api.SupermarketAPI;
import fr.influencraft.fluxshop.managers.PriceManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Single place that decides what an ItemStack sells for, shared by the manual sell-chest flow
 * (SellManager) and the auto-sell pickup listener so they never drift apart: CustomFishing's
 * own price wins when the item is an actual caught fish (it always beats prices.yml, since a
 * fish species has no vanilla-Material-keyed equivalent to fall back to), otherwise the
 * material's price from prices.yml applies.
 */
public final class SellPriceResolver {

    private SellPriceResolver() {
    }

    /**
     * @return the total sell price for the full stack (unit price already multiplied by
     * amount), or 0 if the item isn't sellable at all.
     */
    public static double resolveTotalPrice(PriceManager priceManager, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0.0;
        }
        double cfPrice = CustomFishingHook.getPrice(item);
        if (cfPrice > 0) {
            return cfPrice;
        }
        String materialId = item.getType().name();
        if (priceManager.isSellable(materialId)) {
            return priceManager.getSellPrice(materialId) * item.getAmount();
        }
        return 0.0;
    }

    /**
     * Same as {@link #resolveTotalPrice(PriceManager, ItemStack)} with the seller's bonuses
     * ({@link SupermarketAPI} sell-price modifiers) applied, rounded to the cent. This is the
     * amount actually paid: every sale goes through here.
     *
     * A material that the shop also sells never resells above its buy price, whatever the
     * bonuses: no buy-then-sell loop.
     */
    public static double resolveTotalPrice(PriceManager priceManager, Player player, ItemStack item) {
        double base = resolveTotalPrice(priceManager, item);
        if (base <= 0) {
            return base;
        }
        double multiplier = SupermarketAPI.sellMultiplier(player, item);
        if (multiplier == 1.0) {
            return base;
        }
        double total = base * multiplier;
        String materialId = item.getType().name();
        if (CustomFishingHook.getPrice(item) <= 0 && priceManager.isBuyable(materialId)) {
            total = Math.min(total, Math.max(base, priceManager.getBuyPrice(materialId) * item.getAmount()));
        }
        return PriceManager.roundMoney(total);
    }
}
