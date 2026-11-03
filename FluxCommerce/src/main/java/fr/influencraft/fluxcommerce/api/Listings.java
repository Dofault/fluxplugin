package fr.influencraft.fluxcommerce.api;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Public API of the auction house of FluxCommerce for other plugins of the suite: today the import of a former auction house plugin
 * (FluxMigration). Every import is recorded under its {@code source}: the same source is never imported twice.
 */
public final class Listings {

    /** A bid of an imported auction. */
    public record ImportedBid(UUID bidder, String name, double amount, long date) {
    }

    /**
     * A listing of a former plugin.
     *
     * @param id      kept as the listing id (links already sent in the chat keep working)
     * @param item    the lot as listed (its amount = items listed)
     * @param left    items still for sale (direct sale) or to give back
     * @param price   direct sale: price of the whole lot as listed; auction: starting price
     * @param state   ACTIVE, EXPIRED (seller takes his items back), REMOVED (idem, staff reason), DELETED (item destroyed
     *                by staff, reason only), WON (the highest bidder collects the item; the seller must be paid apart)
     * @param bids    oldest first; the money of the last one is held by the auction house
     */
    public record Import(String source, UUID id, UUID seller, String sellerName, ItemStack item, int left, boolean auction,
                         double price, long created, long start, long end, String state, String reason, List<ImportedBid> bids) {
    }

    /** Called by FluxCommerce (auction house). */
    public interface Backend {
        /** @return false when this source or this id is already there */
        boolean importListing(Import listing);

        /** Pays money a former plugin owed a player (sale not collected, bid to refund); false when already done. */
        boolean importPayment(String source, UUID player, double amount, String reason);

        /** Bans a player from commerce until {@code until} (epoch ms); false when already imported. */
        boolean importBan(String source, UUID player, String name, long until, String reason);
    }

    private static volatile Backend backend;

    private Listings() {
    }

    /** Called by FluxCommerce (auction house). */
    public static void bind(Backend b) {
        backend = b;
    }

    public static boolean available() {
        return backend != null;
    }

    private static Backend backend() {
        Backend b = backend;
        if (b == null) throw new IllegalStateException("the auction house of FluxCommerce is not running");
        return b;
    }

    /** Main thread. */
    public static boolean importListing(Import listing) {
        return backend().importListing(listing);
    }

    /** Main thread. */
    public static boolean importPayment(String source, UUID player, double amount, String reason) {
        return backend().importPayment(source, player, amount, reason);
    }

    /** Main thread. */
    public static boolean importBan(String source, UUID player, String name, long until, String reason) {
        return backend().importBan(source, player, name, until, reason);
    }
}
