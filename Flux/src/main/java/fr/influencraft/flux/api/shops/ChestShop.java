package fr.influencraft.flux.api.shops;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.UUID;

/**
 * A chest shop of a player (see {@link ChestShops}).
 *
 * @param id        id given by the shop plugin
 * @param item      the item sold or bought back, its amount being the bundle size
 * @param price     price of one bundle
 * @param buying    the shop buys from players (instead of selling)
 * @param unlimited admin shop without stock
 * @param owner     owner (null for a non player owner)
 * @param stock     items left (selling) or room left in items (buying), as last counted (the chest is counted again
 *                  whenever its chunk is loaded); -1 never counted, {@link Integer#MAX_VALUE} unlimited
 * @param options   ids of the options turned on ({@link ChestShops#option})
 */
public record ChestShop(long id, Location location, ItemStack item, double price, boolean buying, boolean unlimited,
                        UUID owner, String ownerName, int stock, Set<String> options) {

    public boolean has(String option) {
        return options.contains(option);
    }
}
