package fr.influencraft.fluxcommerce.auctionhouse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The unified catalog of the market: listings of the auction house and chest shops, computed again at most every
 * {@code catalog-seconds} (and as soon as a trade goes through the plugin). A search "diamant" or "DIAMOND" finds a
 * direct sale as well as a shop in a town.
 */
final class Catalog {

    /** What the market shows: everything for sale, the auction house, chest shops, shops that buy. */
    static final int ALL = 0, AUCTION_HOUSE = 1, SHOPS = 2, BUYING = 3;
    static final List<String> SOURCES = List.of("all", "auction-house", "shops", "buying");
    /** Cheapest (best buyer for buying shops), latest, ending soonest. */
    static final List<String> SORTS = List.of("cheapest", "latest", "ending");

    private final AuctionHouseModule plugin;
    private List<Offer> offers = List.of();
    private Map<String, Offer> byId = Map.of();
    private long computed;

    Catalog(AuctionHouseModule plugin) {
        this.plugin = plugin;
    }

    void invalidate() {
        computed = 0;
    }

    List<Offer> all() {
        long now = System.currentTimeMillis();
        if (now - computed > plugin.getConfig().getInt("catalog-seconds", 2) * 1000L) {
            List<Offer> l = new ArrayList<>(plugin.market().offers(now));
            if (plugin.shops() != null) l.addAll(plugin.shops().offers());
            Map<String, Offer> m = new HashMap<>();
            for (Offer o : l) m.put(o.id(), o);
            offers = l;
            byId = m;
            computed = now;
        }
        return offers;
    }

    Offer byId(String id) {
        all();
        return byId.get(id);
    }

    /** Offers of a source matching a search, sorted. */
    List<Offer> filter(int source, int sort, String search) {
        List<Offer> l = new ArrayList<>();
        for (Offer o : all()) {
            boolean kept = switch (source) {
                case AUCTION_HOUSE -> o.type().auctionHouse();
                case SHOPS -> o.type() == Offer.Type.SHOP;
                case BUYING -> o.type() == Offer.Type.SHOP_BUYING;
                default -> o.type() != Offer.Type.SHOP_BUYING;
            };
            if (kept && ItemInfo.matches(o.index(), search)) l.add(o);
        }
        l.sort(order(source, sort));
        return l;
    }

    private static Comparator<Offer> order(int source, int sort) {
        Comparator<Offer> price = Comparator.comparingDouble(Offer::unitPrice);
        return switch (sort) {
            // shops have no date: after the listings
            case 1 -> Comparator.comparingLong((Offer o) -> o.type().auctionHouse() ? 0 : 1)
                    .thenComparing(Comparator.comparingLong(Offer::created).reversed());
            case 2 -> Comparator.comparingLong((Offer o) -> o.ends() <= 0 ? Long.MAX_VALUE : o.ends());
            // buying shops: the best buyer (highest price) first, the other way round from sellers
            default -> source == BUYING ? price.reversed() : price;
        };
    }

    /** Other offers of the same item (same key), cheapest first (best buyer first for buying shops), without one. */
    List<Offer> sameItem(String key, String except, boolean buying, int max) {
        List<Offer> l = new ArrayList<>();
        for (Offer o : all()) {
            if (!o.key().equals(key) || o.id().equals(except)) continue;
            if ((o.type() == Offer.Type.SHOP_BUYING) != buying) continue;
            l.add(o);
        }
        Comparator<Offer> c = Comparator.comparingDouble(Offer::unitPrice);
        l.sort(buying ? c.reversed() : c);
        return l.size() > max ? l.subList(0, max) : l;
    }
}
