package io.dofault.supermarket.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Public entry point for other plugins. Sell prices: any plugin can register one
 * {@link SellPriceModifier}; every sale (sell block, auto-sell) multiplies the price of each
 * stack by the product of all registered modifiers.
 *
 * <pre>
 * if (Bukkit.getPluginManager().isPluginEnabled("VaultSupermarket")) {
 *     SupermarketAPI.registerSellPriceModifier(this, (player, item) -> hasPerk(player) ? 1.10 : 1.0);
 * }
 * </pre>
 *
 * A modifier is dropped automatically when its plugin is disabled. Supermarket itself never
 * depends on the plugins that register here: with no modifier, prices are the ones of prices.yml.
 */
public final class SupermarketAPI {

    private static final Map<Plugin, SellPriceModifier> MODIFIERS = new ConcurrentHashMap<>();

    private SupermarketAPI() {
    }

    /** Registers (or replaces) the sell-price modifier of {@code owner}. */
    public static void registerSellPriceModifier(Plugin owner, SellPriceModifier modifier) {
        if (owner == null || modifier == null) {
            throw new IllegalArgumentException("owner and modifier are required");
        }
        MODIFIERS.put(owner, modifier);
    }

    public static void unregisterSellPriceModifier(Plugin owner) {
        if (owner != null) {
            MODIFIERS.remove(owner);
        }
    }

    /**
     * Product of all registered modifiers for this sale, 1.0 with none. A modifier that throws,
     * returns a negative value or NaN is ignored (and logged): a faulty add-on never breaks a sale.
     */
    public static double sellMultiplier(Player player, ItemStack item) {
        if (MODIFIERS.isEmpty() || player == null || item == null) {
            return 1.0;
        }
        double result = 1.0;
        for (Map.Entry<Plugin, SellPriceModifier> entry : MODIFIERS.entrySet()) {
            try {
                double factor = entry.getValue().multiplier(player, item);
                if (factor >= 0 && !Double.isNaN(factor) && !Double.isInfinite(factor)) {
                    result *= factor;
                }
            } catch (Throwable t) {
                entry.getKey().getLogger().log(Level.WARNING, "Sell price modifier failed", t);
            }
        }
        return result;
    }
}
