package fr.influencraft.fluxcommerce.auctionhouse;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An offer of the auction house: a direct sale (bought at once, possibly part of the lot) or an auction (highest bid
 * wins at the end). Kept in memory and written to {@link Store}; changed on the main thread only.
 *
 * <p>Life of a listing: {@link State#ACTIVE} (online once {@link #start} is reached), then one of: sold out (row
 * deleted, the sale is in the history), {@link State#EXPIRED} or {@link State#REMOVED} (the seller takes his items
 * back), {@link State#DELETED} (staff destroyed the item, the seller only reads the reason), {@link State#WON} (the
 * winner of an auction takes the item; the seller was paid at the end). The row is deleted once that is done.</p>
 */
final class Listing {

    enum State { ACTIVE, EXPIRED, REMOVED, DELETED, WON }

    final UUID id;
    final UUID seller;
    String sellerName;
    /** The lot as listed: its amount is the number of items listed. */
    final ItemStack item;
    final boolean auction;
    /** Direct sale: price of the whole lot as listed. Auction: starting price. */
    final double price;
    final long created;
    /** Online from (end of the setup delay), epoch ms. */
    final long start;
    /** End, epoch ms (pushed back by a late bid). */
    long end;
    /** Items still for sale (direct sale), or to give back (expired, removed). */
    int left;
    State state = State.ACTIVE;
    /** Staff reason (removed, deleted). */
    String reason;
    /** Every bid, oldest first; the last one is the highest (its money is held by the plugin). */
    final List<Bid> bids = new ArrayList<>();

    Listing(UUID id, UUID seller, String sellerName, ItemStack item, boolean auction, double price, long created, long start, long end) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.item = item;
        this.auction = auction;
        this.price = price;
        this.created = created;
        this.start = start;
        this.end = end;
        this.left = item.getAmount();
    }

    record Bid(UUID bidder, String name, double amount, long date) {
    }

    int initial() {
        return item.getAmount();
    }

    /** Price of one item of a direct sale. */
    double unitPrice() {
        return price / Math.max(1, initial());
    }

    Bid highest() {
        return bids.isEmpty() ? null : bids.getLast();
    }

    /** Current price shown: the highest bid or the starting price (auction), the price of what is left (sale). */
    double currentPrice() {
        if (auction) return bids.isEmpty() ? price : highest().amount();
        return unitPrice() * left;
    }

    boolean pending(long now) {
        return state == State.ACTIVE && now < start;
    }

    boolean live(long now) {
        return state == State.ACTIVE && now >= start && now < end;
    }

    boolean ownedBy(UUID player) {
        return seller.equals(player);
    }

    /** Whether this player bid on this auction. */
    boolean hasBid(UUID player) {
        for (Bid b : bids) if (b.bidder().equals(player)) return true;
        return false;
    }

    /** The lot with {@code amount} items. */
    ItemStack copy(int amount) {
        ItemStack c = item.clone();
        c.setAmount(Math.max(1, amount));
        return c;
    }

    /** Seconds before the end (or before going online when pending), at least 0. */
    long secondsLeft(long now) {
        return Math.max(0, ((now < start ? start : end) - now) / 1000);
    }
}
