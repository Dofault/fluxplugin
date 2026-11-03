package fr.influencraft.fluxcommerce.auctionhouse;

import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.inventory.Inventories;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.PlayerSettings;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The auction house itself: listings in memory (written through {@link Store}), and every operation of a player with
 * all its checks, on the main thread: list an item, buy (part of) a direct sale, bid, take a listing back, collect
 * what is owed, end listings when their time is up; staff removal. Bans and forbidden items are shared by all
 * commerce (FluxCommerce).
 *
 * <p>Money: a buyer pays at once and the seller is paid at once, tax deducted, even offline. A bidder pays his bid
 * when he makes it; the bidder he outbids gets his bid back at once. When an auction ends, the seller gets the highest
 * bid (tax deducted) and the winner collects the item.</p>
 */
final class Market {

    static final String SLOTS = "fluxcommerce.ah.slots.", DURATION = "fluxcommerce.ah.duration.";

    private final AuctionHouseModule plugin;
    private final Store store;
    private final Map<UUID, Listing> listings = new LinkedHashMap<>();
    /** Listings already announced in the chat (announcements go when a listing goes online). */
    private final Set<UUID> announced = new HashSet<>();

    Market(AuctionHouseModule plugin, Store store) {
        this.plugin = plugin;
        this.store = store;
    }

    void load() throws SQLException {
        listings.clear();
        listings.putAll(store.loadListings());
        long now = System.currentTimeMillis();
        for (Listing l : listings.values()) if (!l.pending(now)) announced.add(l.id);
    }

    private Currency money() {
        return plugin.money();
    }

    // ---------------------------------------------------------------- reading

    Listing get(UUID id) {
        return listings.get(id);
    }

    Listing get(String id) {
        try {
            return listings.get(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    Collection<Listing> all() {
        return listings.values();
    }

    /** Live listings as market offers. */
    List<Offer> offers(long now) {
        List<Offer> out = new ArrayList<>();
        for (Listing l : listings.values()) {
            if (!l.live(now)) continue;
            ItemStack shown = l.copy(Math.min(l.left, 99));
            String index = ItemInfo.index(shown) + " " + ItemInfo.normalize(l.sellerName);
            out.add(new Offer(l.auction ? Offer.Type.AUCTION : Offer.Type.SALE, l.id.toString(), l, shown, ItemInfo.key(shown), index,
                    l.currentPrice(), l.auction ? l.initial() : l.left, l.seller, l.sellerName, l.end, l.left, null, null, 0, null, l.start));
        }
        return out;
    }

    /** Listings of a seller, latest first (every state). */
    List<Listing> of(UUID seller) {
        List<Listing> out = new ArrayList<>();
        for (Listing l : listings.values()) if (l.ownedBy(seller)) out.add(l);
        out.sort(Comparator.comparingLong((Listing l) -> l.created).reversed());
        return out;
    }

    /** Auctions this player bid on that are still running, or won and not collected yet. */
    List<Listing> bidsOf(UUID player) {
        List<Listing> out = new ArrayList<>();
        for (Listing l : listings.values()) {
            if (!l.auction || !l.hasBid(player)) continue;
            if (l.state == Listing.State.ACTIVE || l.state == Listing.State.WON && l.highest().bidder().equals(player)) out.add(l);
        }
        out.sort(Comparator.comparingLong(l -> l.end));
        return out;
    }

    /** Whether this player has to act on this listing (take items back, read a staff reason, collect a won item). */
    static boolean toCollect(Listing l, UUID player) {
        return switch (l.state) {
            case EXPIRED, REMOVED, DELETED -> l.ownedBy(player);
            case WON -> l.highest() != null && l.highest().bidder().equals(player);
            default -> false;
        };
    }

    int toCollect(UUID player) {
        int n = 0;
        for (Listing l : listings.values()) if (toCollect(l, player)) n++;
        return n;
    }

    /** Listings taking a slot: active ones and those to take back. */
    int used(UUID player) {
        int n = 0;
        for (Listing l : listings.values()) {
            if (l.ownedBy(player) && (l.state == Listing.State.ACTIVE || l.state == Listing.State.EXPIRED || l.state == Listing.State.REMOVED)) n++;
        }
        return n;
    }

    /** Most listings this player may have: the highest fluxcommerce.ah.slots.&lt;n&gt;, else limits.default. */
    int slots(Player p) {
        if (p.hasPermission(AuctionHouseModule.ADMIN) || p.hasPermission(SLOTS + "unlimited")) return Integer.MAX_VALUE;
        return Math.max(plugin.getConfig().getInt("limits.default", 0), highest(p, SLOTS));
    }

    /** Duration of a new listing in seconds: the highest fluxcommerce.ah.duration.&lt;sale|auction&gt;.&lt;hours&gt;, else the config. */
    long duration(Player p, boolean auction) {
        String kind = auction ? "auction" : "sale";
        long base = Format.parseDuration(plugin.getConfig().getString("listing." + kind + "-duration", auction ? "24h" : "72h"));
        long hours = highest(p, DURATION + kind + ".");
        return Math.max(60, Math.max(base, hours * 3600));
    }

    private static int highest(Player p, String prefix) {
        int best = -1;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            String node = info.getPermission();
            if (!info.getValue() || !node.startsWith(prefix)) continue;
            try {
                best = Math.max(best, Integer.parseInt(node.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // another suffix (unlimited...)
            }
        }
        return best;
    }

    double taxRate() {
        return Math.max(0, Math.min(100, plugin.getConfig().getDouble("tax-percent", 15))) / 100.0;
    }

    /** What the seller gets for a sale of {@code total}, tax deducted (rounded down to the cent). */
    double net(double total) {
        return Math.floor(total * 100 * (1 - taxRate())) / 100;
    }

    /** Price of {@code amount} items of a direct sale (cents). */
    static double cost(Listing l, int amount) {
        return Math.round(l.price * amount / Math.max(1, l.initial()) * 100) / 100.0;
    }

    /** Lowest bid accepted now. */
    double minimumBid(Listing l) {
        Listing.Bid top = l.highest();
        if (top == null) return Math.ceil(l.price);
        double step = Math.max(0, plugin.getConfig().getDouble("auction.increase-percent", 10)) / 100.0;
        return Math.max(top.amount() + 1, Math.ceil(top.amount() * (1 + step)));
    }

    boolean partialSelling() {
        return plugin.getConfig().getBoolean("listing.partial-selling", true);
    }

    boolean allows(boolean auction) {
        return plugin.getConfig().getBoolean(auction ? "listing.auctions" : "listing.direct-sales", true);
    }

    double minPrice(boolean auction) {
        return Math.max(1, plugin.getConfig().getDouble("price." + (auction ? "auction-min" : "sale-min"), 1));
    }

    /** Highest price, 0 = none. */
    double maxPrice(boolean auction) {
        return Math.max(0, plugin.getConfig().getDouble("price." + (auction ? "auction-max" : "sale-max"), 0));
    }

    // ---------------------------------------------------------------- rules

    /** Items that can never change hands (shared by all commerce, {@code blacklist} of the main config). */
    boolean blacklisted(ItemStack item) {
        return plugin.host().rules().forbidden(item);
    }

    /** True (and the player is told why) when the player is banned from commerce. */
    boolean refusedBan(Player p) {
        return plugin.host().bans().refused(p);
    }

    // ---------------------------------------------------------------- listing an item

    /**
     * Lists {@code amount} items of the inventory slot {@code slot}. {@code expected} is the item seen when the player
     * chose it: when the slot changed since, nothing is listed.
     */
    boolean sell(Player p, int slot, ItemStack expected, int amount, double price, boolean auction) {
        if (!allows(auction)) return refuse(p, auction ? "auctions-disabled" : "direct-sales-disabled");
        if (refusedBan(p)) return false;
        int max = slots(p);
        if (used(p.getUniqueId()) >= max) {
            return refuse(p, max <= 0 ? "no-slots" : "slots-full", "%limit%", String.valueOf(max));
        }
        ItemStack item = p.getInventory().getItem(slot);
        if (item == null || item.getType().isAir() || expected == null || !item.isSimilar(expected)) return refuse(p, "item-moved");
        if (amount < 1 || amount > item.getAmount()) return refuse(p, "bad-amount", "%max%", String.valueOf(item.getAmount()));
        double min = minPrice(auction), most = maxPrice(auction);
        if (price < min) return refuse(p, "price-too-low", "%price%", money().format(min));
        if (most > 0 && price > most) return refuse(p, "price-too-high", "%price%", money().format(most));
        if (blacklisted(item)) return refuse(p, "blacklisted");
        ItemStack lot = item.clone();
        lot.setAmount(amount);
        item.setAmount(item.getAmount() - amount);
        p.getInventory().setItem(slot, item.getAmount() <= 0 ? null : item);
        long now = System.currentTimeMillis();
        long start = now + Math.max(0, plugin.getConfig().getLong("listing.setup-seconds", 30)) * 1000;
        Listing l = new Listing(UUID.randomUUID(), p.getUniqueId(), p.getName(), lot, auction, price, now, start,
                start + duration(p, auction) * 1000);
        listings.put(l.id, l);
        store.save(l);
        plugin.getLogger().info(p.getName() + " listed " + amount + " " + lot.getType().getKey() + " for " + price + (auction ? " (auction)" : "") + " [" + l.id + "]");
        plugin.send(p, auction ? "listed-auction" : "listed-sale", lot, "%amount%", String.valueOf(amount), "%price%", money().format(price),
                "%delay%", Format.duration(l.secondsLeft(now)), "%duration%", Format.duration(duration(p, auction)),
                "%net%", money().format(net(price)), "%tax%", Format.percent(taxRate() * 100));
        Sounds.cue(p, "success");
        changed();
        return true;
    }

    // ---------------------------------------------------------------- buying (direct sale)

    boolean buy(Player p, Listing l, int amount) {
        long now = System.currentTimeMillis();
        if (l == null || l.state != Listing.State.ACTIVE || l.left <= 0) return refuse(p, "gone");
        if (l.auction) return refuse(p, "is-auction");
        if (l.ownedBy(p.getUniqueId())) return refuse(p, "own-listing");
        if (now < l.start) return refuse(p, "not-yet", "%delay%", Format.duration(l.secondsLeft(now)));
        if (now >= l.end) return refuse(p, "gone");
        if (refusedBan(p)) return false;
        if (!partialSelling()) amount = l.left;
        amount = Math.min(amount, l.left);
        if (amount < 1) return false;
        ItemStack bought = l.copy(amount);
        if (!Inventories.fits(p, bought)) return refuse(p, "inventory-full");
        double cost = amount == l.left ? Math.round((l.price - cost(l, l.initial() - l.left)) * 100) / 100.0 : cost(l, amount);
        if (!money().has(p, cost)) return refuse(p, "no-money", "%price%", money().format(cost));
        String name = ItemInfo.plain(bought);
        if (!money().take(p, cost, plugin.origin("buy").detail(name))) return refuse(p, "no-money", "%price%", money().format(cost));
        Inventories.give(p, List.of(bought));
        l.left -= amount;
        double net = net(cost);
        OfflinePlayer seller = Bukkit.getOfflinePlayer(l.seller);
        if (!money().give(seller, net, plugin.origin("sale").detail(name))) {
            plugin.getLogger().severe("Seller " + l.sellerName + " not paid " + net + " for listing " + l.id + " (economy refused)");
        }
        if (l.left <= 0) remove(l);
        else store.save(l);
        plugin.history().record(new History.Sale(now, History.SALE, l.id.toString(), l.seller, l.sellerName, p.getUniqueId(), p.getName(),
                ItemInfo.key(bought), name, amount, cost, cost - net, ChestShopOffers.bytes(bought), null));
        plugin.send(p, "bought", bought, "%amount%", String.valueOf(amount), "%seller%", l.sellerName, "%price%", money().format(cost));
        Sounds.cue(p, "success");
        Player online = seller.getPlayer();
        if (online != null) {
            plugin.send(online, "sold", bought, "%amount%", String.valueOf(amount), "%buyer%", p.getName(), "%price%", money().format(net),
                    "%tax%", Format.percent(taxRate() * 100));
            Sounds.cue(online, "complete");
        }
        changed();
        return true;
    }

    // ---------------------------------------------------------------- bidding

    boolean bid(Player p, Listing l, double amount) {
        long now = System.currentTimeMillis();
        if (l == null || l.state != Listing.State.ACTIVE || now >= l.end) return refuse(p, "gone");
        if (!l.auction) return refuse(p, "not-auction");
        if (l.ownedBy(p.getUniqueId())) return refuse(p, "own-listing");
        if (now < l.start) return refuse(p, "not-yet", "%delay%", Format.duration(l.secondsLeft(now)));
        Listing.Bid top = l.highest();
        if (top != null && top.bidder().equals(p.getUniqueId())) return refuse(p, "already-highest");
        if (refusedBan(p)) return false;
        double min = minimumBid(l);
        if (amount < min) return refuse(p, "bid-too-low", "%price%", money().format(min));
        double most = maxPrice(true);
        if (most > 0 && amount > most) return refuse(p, "price-too-high", "%price%", money().format(most));
        String name = ItemInfo.plain(l.item);
        if (!money().has(p, amount) || !money().take(p, amount, plugin.origin("bid").detail(name))) {
            return refuse(p, "no-money", "%price%", money().format(amount));
        }
        Listing.Bid bid = new Listing.Bid(p.getUniqueId(), p.getName(), amount, now);
        l.bids.add(bid);
        store.addBid(l, bid);
        // a late bid gives the others time to answer
        long extra = Math.max(0, plugin.getConfig().getLong("auction.extra-seconds", 120)) * 1000;
        if (l.end - now < extra) {
            l.end = now + extra;
            store.save(l);
        }
        plugin.send(p, "bid-placed", l.item, "%price%", money().format(amount), "%time%", Format.duration(l.secondsLeft(now)));
        Sounds.cue(p, "success");
        if (top != null) {
            OfflinePlayer former = Bukkit.getOfflinePlayer(top.bidder());
            money().give(former, top.amount(), plugin.origin("bid_refund").detail(name));
            Player online = former.getPlayer();
            if (online != null) {
                plugin.send(online, "outbid", l.item, "%player%", p.getName(), "%price%", money().format(amount),
                        "%refund%", money().format(top.amount()), "%id%", l.id.toString());
                Sounds.cue(online, "alert");
            }
        }
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) plugin.send(seller, "bid-received", l.item, "%player%", p.getName(), "%price%", money().format(amount));
        changed();
        return true;
    }

    // ---------------------------------------------------------------- the seller takes his listing back

    boolean cancel(Player p, Listing l) {
        if (l == null || l.state != Listing.State.ACTIVE) return refuse(p, "gone");
        if (!l.ownedBy(p.getUniqueId())) return refuse(p, "not-yours");
        if (l.auction && !l.bids.isEmpty()) return refuse(p, "has-bids");
        ItemStack back = l.copy(l.left);
        if (!Inventories.fits(p, back)) return refuse(p, "inventory-full");
        Inventories.give(p, List.of(back));
        remove(l);
        plugin.getLogger().info(p.getName() + " took back listing " + l.id);
        plugin.send(p, "cancelled", back, "%amount%", String.valueOf(back.getAmount()));
        Sounds.cue(p, "remove");
        changed();
        return true;
    }

    // ---------------------------------------------------------------- collecting

    /** Takes what this listing owes the player (items back, won item, staff notice). */
    boolean collect(Player p, Listing l) {
        if (l == null || !toCollect(l, p.getUniqueId())) return refuse(p, "nothing-to-collect");
        switch (l.state) {
            case DELETED -> {
                remove(l);
                plugin.send(p, "deleted-read", l.item, "%reason%", reason(l));
                return true;
            }
            case WON -> {
                ItemStack won = l.copy(l.initial());
                if (!Inventories.fits(p, won)) return refuse(p, "inventory-full");
                Inventories.give(p, List.of(won));
                remove(l);
                plugin.send(p, "won-collected", won, "%amount%", String.valueOf(won.getAmount()));
            }
            default -> {
                ItemStack back = l.copy(l.left);
                if (!Inventories.fits(p, back)) return refuse(p, "inventory-full");
                Inventories.give(p, List.of(back));
                remove(l);
                plugin.send(p, l.state == Listing.State.REMOVED ? "removed-collected" : "expired-collected", back,
                        "%amount%", String.valueOf(back.getAmount()), "%reason%", reason(l));
            }
        }
        Sounds.cue(p, "success");
        changed();
        return true;
    }

    /** Everything that can be collected now (stops at a full inventory); returns how many. */
    int collectAll(Player p, boolean quiet) {
        int n = 0;
        for (Listing l : new ArrayList<>(listings.values())) {
            if (!toCollect(l, p.getUniqueId())) continue;
            ItemStack stack = l.state == Listing.State.WON ? l.copy(l.initial()) : l.copy(l.left);
            if (l.state != Listing.State.DELETED && !Inventories.fits(p, stack)) {
                if (!quiet) refuse(p, "inventory-full");
                break;
            }
            if (collect(p, l)) n++;
        }
        return n;
    }

    private static String reason(Listing l) {
        return l.reason == null || l.reason.isBlank() ? "-" : l.reason;
    }

    // ---------------------------------------------------------------- staff

    /**
     * Staff removal: the listing ends at once; {@code destroy} = the item is destroyed (the seller only reads the
     * reason), else the seller takes it back. Bids are refunded.
     */
    boolean staffRemove(CommandSender by, Listing l, String reason, boolean destroy) {
        if (l == null || l.state != Listing.State.ACTIVE) return false;
        refundBids(l);
        l.state = destroy ? Listing.State.DELETED : Listing.State.REMOVED;
        l.reason = reason;
        l.end = System.currentTimeMillis();
        store.save(l);
        plugin.getLogger().info(by.getName() + (destroy ? " deleted" : " removed") + " listing " + l.id + " of " + l.sellerName + " ("
                + l.left + " " + l.item.getType().getKey() + "): " + reason);
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) {
            plugin.send(seller, destroy ? "staff-deleted" : "staff-removed", l.item, "%reason%", reason(l));
            Sounds.cue(seller, "alert");
        }
        changed();
        return true;
    }

    private void refundBids(Listing l) {
        Listing.Bid top = l.highest();
        if (top == null) return;
        OfflinePlayer bidder = Bukkit.getOfflinePlayer(top.bidder());
        money().give(bidder, top.amount(), plugin.origin("bid_refund").detail(ItemInfo.plain(l.item)));
        Player online = bidder.getPlayer();
        if (online != null) plugin.send(online, "bid-refunded", l.item, "%price%", money().format(top.amount()));
        l.bids.clear();
    }

    // ---------------------------------------------------------------- time passing (every second)

    void tick() {
        long now = System.currentTimeMillis();
        boolean any = false;
        for (Listing l : new ArrayList<>(listings.values())) {
            if (l.state != Listing.State.ACTIVE) continue;
            if (!announced.contains(l.id) && now >= l.start) {
                announced.add(l.id);
                announce(l);
            }
            if (now < l.end) continue;
            any = true;
            Listing.Bid top = l.highest();
            if (l.auction && top != null) endWon(l, top, now);
            else endExpired(l);
        }
        if (any) changed();
    }

    private void endWon(Listing l, Listing.Bid top, long now) {
        l.state = Listing.State.WON;
        store.save(l);
        String name = ItemInfo.plain(l.item);
        double net = net(top.amount());
        OfflinePlayer seller = Bukkit.getOfflinePlayer(l.seller);
        if (!money().give(seller, net, plugin.origin("auction_sale").detail(name))) {
            plugin.getLogger().severe("Seller " + l.sellerName + " not paid " + net + " for auction " + l.id + " (economy refused)");
        }
        plugin.history().record(new History.Sale(now, History.AUCTION, l.id.toString(), l.seller, l.sellerName, top.bidder(), top.name(),
                ItemInfo.key(l.item), name, l.initial(), top.amount(), top.amount() - net, ChestShopOffers.bytes(l.item), null));
        Player online = seller.getPlayer();
        if (online != null) {
            plugin.send(online, "auction-sold", l.item, "%buyer%", top.name(), "%price%", money().format(net), "%tax%", Format.percent(taxRate() * 100));
            Sounds.cue(online, "complete");
        }
        Player winner = Bukkit.getPlayer(top.bidder());
        if (winner != null) {
            if (autoDeliver() && Inventories.fits(winner, l.copy(l.initial()))) {
                collect(winner, l);
            } else {
                plugin.send(winner, "auction-won", l.item, "%price%", money().format(top.amount()));
                Sounds.cue(winner, "complete");
            }
        }
    }

    private void endExpired(Listing l) {
        l.state = Listing.State.EXPIRED;
        store.save(l);
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller == null) return;
        if (autoDeliver() && Inventories.fits(seller, l.copy(l.left))) collect(seller, l);
        else plugin.send(seller, "expired", l.item, "%amount%", String.valueOf(l.left));
    }

    boolean autoDeliver() {
        return plugin.getConfig().getBoolean("auto-deliver", true);
    }

    private void announce(Listing l) {
        if (!plugin.getConfig().getBoolean("announcements.enabled", false)) return;
        if (l.price < plugin.getConfig().getDouble("announcements.min-price", 0)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(l.seller) || !PlayerSettings.flag(p, "auctionhouse.announcements", true)) continue;
            plugin.send(p, l.auction ? "announce-auction" : "announce-sale", l.item, "%player%", l.sellerName, "%amount%",
                    String.valueOf(l.initial()), "%price%", money().format(l.price), "%id%", l.id.toString());
        }
    }

    // ---------------------------------------------------------------- import (FluxMigration)

    /** Adds a listing of a former plugin as it is; false when its id is already known. */
    boolean add(Listing l) {
        if (listings.containsKey(l.id)) return false;
        listings.put(l.id, l);
        store.save(l);
        for (Listing.Bid b : l.bids) store.addBid(l, b);
        if (!l.pending(System.currentTimeMillis())) announced.add(l.id);
        changed();
        return true;
    }

    // ---------------------------------------------------------------- tools

    private void remove(Listing l) {
        listings.remove(l.id);
        announced.remove(l.id);
        store.delete(l);
    }

    private void changed() {
        plugin.catalog().invalidate();
        plugin.menus().invalidate();
    }

    private boolean refuse(Player p, String key, String... vars) {
        plugin.messages().send(p, key, vars);
        Sounds.cue(p, "error");
        return false;
    }
}
