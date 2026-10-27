package fr.influencraft.flux.api.shops;

import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * Chest shops of the players, whatever plugin runs them (FluxChestShop, a QuickShop fork, today:
 * {@code providers.chest-shops}). Events: {@link ChestShopPurchaseEvent} (before, cancellable),
 * {@link ChestShopTradeEvent} (after).
 */
public final class ChestShops {

    /** What an adapter provides. */
    public interface Provider {
        String name();

        /** Every shop (main thread). */
        List<ChestShop> all();

        /** The shop at this block, or null. */
        ChestShop at(Location location);
    }

    private static volatile Provider provider;
    private static final Map<Plugin, BiPredicate<ChestShop, UUID>> REMOVAL = new ConcurrentHashMap<>();

    private ChestShops() {
    }

    /** Called by Flux. */
    public static void use(Provider p) {
        provider = p;
    }

    public static boolean available() {
        return provider != null;
    }

    public static String providerName() {
        Provider p = provider;
        return p == null ? "none" : p.name();
    }

    public static List<ChestShop> all() {
        Provider p = provider;
        return p == null ? List.of() : p.all();
    }

    public static ChestShop at(Location location) {
        Provider p = provider;
        return p == null || location == null ? null : p.at(location);
    }

    /** Lets other players than the owner remove a shop when {@code rule} says so (e.g. the town mayor). */
    public static void allowRemoval(Plugin owner, BiPredicate<ChestShop, UUID> rule) {
        REMOVAL.put(owner, rule);
    }

    /** Called by the adapter. */
    public static boolean mayRemove(ChestShop shop, UUID player) {
        for (BiPredicate<ChestShop, UUID> rule : REMOVAL.values()) if (rule.test(shop, player)) return true;
        return false;
    }

    public static void unregisterAll(Plugin owner) {
        REMOVAL.remove(owner);
    }
}
