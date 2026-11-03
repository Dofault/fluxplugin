package fr.influencraft.fluxcommerce.auctionhouse;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * An offer of the market, whatever its source: a listing of the auction house (direct sale or auction) or a chest
 * shop (selling or buying). Snapshot taken at the last pass of the {@link Catalog}.
 *
 * @param id      listing id, or "s" + chest shop id
 * @param price   price of the whole offer (auction: highest bid or starting price; shop: one bundle)
 * @param amount  items offered (shop: items per bundle)
 * @param ends    epoch ms of the end (auction house), 0 for a shop
 * @param stock   shop: stock (selling) or room (buying), -1 never counted, {@link Integer#MAX_VALUE} unlimited
 * @param created epoch ms it went online (auction house), 0 for a shop
 */
record Offer(Type type, String id, Listing listing, ItemStack item, String key, String index, double price, int amount,
             UUID sellerId, String seller, long ends, int stock, String town, String townId, double fee, Location location,
             long created) {

    enum Type {
        SALE, AUCTION, SHOP, SHOP_BUYING;

        boolean auctionHouse() {
            return this == SALE || this == AUCTION;
        }
    }

    double unitPrice() {
        return amount > 0 ? price / amount : price;
    }

    long shopId() {
        return type.auctionHouse() ? -1 : Long.parseLong(id.substring(1));
    }
}
