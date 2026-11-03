package fr.influencraft.flux.api.shops;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * Chest shops of the players, kept by the plugin of the suite that owns them (FluxCommerce), seen by the others
 * (FluxLands: town, market fee; auction house of FluxCommerce: market). Events: {@link ChestShopPurchaseEvent} (before, cancellable),
 * {@link ChestShopTradeEvent} (after). Plugins may offer options on shops ({@link #option}): the shop plugin shows them
 * at creation and in the shop's settings, and keeps which ones are on.
 */
public final class ChestShops {

    /** What the shop plugin provides. */
    public interface Provider {
        /** Every shop (main thread). */
        List<ChestShop> all();

        /** The shop at this block (either half of a double chest), or null. */
        ChestShop at(Location location);

        /** Turns an option of a shop on or off (main thread). */
        void setOption(long shopId, String option, boolean on);

        /** Draws the shop again (its seller name may have changed, see {@link ChestShops#sellerName}). */
        default void refresh(long shopId) {
        }
    }

    /** What a plugin offers to a player on a shop. */
    public interface Option {
        /**
         * @param shop null while the shop is being created
         * @return the offer shown to this player for a shop at this place, null when not offered here
         */
        Offer offer(Player player, Location location, ChestShop shop);
    }

    /** Name shown as the seller of a shop instead of its owner's (e.g. the town of a town shop). */
    public interface SellerName {
        /** @return the name for this shop, null to keep the owner's */
        Component name(ChestShop shop);
    }

    /**
     * @param label       short name (check box)
     * @param description what it does, one sentence (may be null)
     */
    public record Offer(Component label, Component description) {
    }

    private static volatile Plugin providerOwner;
    private static volatile Provider provider;
    private static final Map<Plugin, BiPredicate<ChestShop, UUID>> REMOVAL = new ConcurrentHashMap<>();
    private static final Map<Plugin, Map<String, Option>> OPTIONS = new ConcurrentHashMap<>();
    private static final Map<Plugin, SellerName> SELLER_NAMES = new ConcurrentHashMap<>();

    private ChestShops() {
    }

    /** The plugin owning the chest shops registers itself (one at a time). */
    public static void register(Plugin owner, Provider p) {
        providerOwner = owner;
        provider = p;
    }

    public static boolean available() {
        return provider != null;
    }

    public static String providerName() {
        Plugin owner = providerOwner;
        return owner == null ? "none" : owner.getName();
    }

    public static List<ChestShop> all() {
        Provider p = provider;
        return p == null ? List.of() : p.all();
    }

    public static ChestShop at(Location location) {
        Provider p = provider;
        return p == null || location == null ? null : p.at(location);
    }

    /** The shop of this id, as it is now, or null. */
    public static ChestShop byId(long shopId) {
        for (ChestShop shop : all()) if (shop.id() == shopId) return shop;
        return null;
    }

    /** Lets other players than the owner remove a shop when {@code rule} says so (e.g. the town mayor). */
    public static void allowRemoval(Plugin owner, BiPredicate<ChestShop, UUID> rule) {
        REMOVAL.put(owner, rule);
    }

    /** Called by the shop plugin. */
    public static boolean mayRemove(ChestShop shop, UUID player) {
        for (BiPredicate<ChestShop, UUID> rule : REMOVAL.values()) if (rule.test(shop, player)) return true;
        return false;
    }

    /** Offers an option on chest shops; {@code id} (letters, digits, '_', '-') is what {@link ChestShop#options()} holds. */
    public static void option(Plugin owner, String id, Option option) {
        OPTIONS.computeIfAbsent(owner, k -> new LinkedHashMap<>()).put(id, option);
    }

    /** Called by the shop plugin: options offered to this player, by id. */
    public static Map<String, Offer> offers(Player player, Location location, ChestShop shop) {
        Map<String, Offer> out = new LinkedHashMap<>();
        for (Map<String, Option> options : OPTIONS.values()) {
            for (Map.Entry<String, Option> e : options.entrySet()) {
                Offer offer = e.getValue().offer(player, location, shop);
                if (offer != null) out.put(e.getKey(), offer);
            }
        }
        return out;
    }

    public static void setOption(ChestShop shop, String option, boolean on) {
        Provider p = provider;
        if (p != null) p.setOption(shop.id(), option, on);
    }

    /** Gives another seller name than the owner's to some shops (e.g. the town of a town shop). */
    public static void sellerName(Plugin owner, SellerName name) {
        SELLER_NAMES.put(owner, name);
    }

    /** Called by the shop plugin: the seller shown for this shop, or null for the owner's name. */
    public static Component sellerName(ChestShop shop) {
        for (SellerName name : SELLER_NAMES.values()) {
            Component c = name.name(shop);
            if (c != null) return c;
        }
        return null;
    }

    /** Draws the shop again (after its seller name changed). */
    public static void refresh(ChestShop shop) {
        Provider p = provider;
        if (p != null) p.refresh(shop.id());
    }

    public static void unregisterAll(Plugin owner) {
        REMOVAL.remove(owner);
        OPTIONS.remove(owner);
        SELLER_NAMES.remove(owner);
        if (providerOwner == owner) {
            providerOwner = null;
            provider = null;
        }
    }
}
