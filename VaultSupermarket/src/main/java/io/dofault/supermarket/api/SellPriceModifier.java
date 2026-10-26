package io.dofault.supermarket.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Sell-price bonus supplied by another plugin (pet, rank perk, event...), registered through
 * {@link SupermarketAPI#registerSellPriceModifier}.
 */
@FunctionalInterface
public interface SellPriceModifier {

    /**
     * @param player the seller
     * @param item   the stack being sold (do not modify it)
     * @return the factor applied to this stack's sell price: 1.0 = unchanged, 1.1 = +10 %.
     */
    double multiplier(Player player, ItemStack item);
}
