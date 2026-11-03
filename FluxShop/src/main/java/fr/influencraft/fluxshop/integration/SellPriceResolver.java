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

    /** Line of prices.yml for an item: its custom id ({@code fluxtools:pioche_virale}) when priced, else its material. */
    private static String priceId(PriceManager priceManager, ItemStack item) {
        String custom = fr.influencraft.flux.api.items.CustomItems.id(item);
        return custom != null && (priceManager.isSellable(custom) || priceManager.isBuyable(custom)) ? custom : item.getType().name();
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
        String priceId = priceId(priceManager, item);
        if (priceManager.isSellable(priceId)) {
            return priceManager.getSellPrice(priceId) * item.getAmount();
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
        String priceId = priceId(priceManager, item);
        if (CustomFishingHook.getPrice(item) <= 0 && priceManager.isBuyable(priceId)) {
            total = Math.min(total, Math.max(base, priceManager.getBuyPrice(priceId) * item.getAmount()));
        }
        return PriceManager.roundMoney(total);
    }
}
