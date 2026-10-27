package fr.influencraft.flux.api.shops;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * A chest shop of a player.
 *
 * @param id        id given by the shop plugin
 * @param item      the item sold or bought back, its amount being the bundle size
 * @param price     price of one bundle
 * @param buying    the shop buys from players (instead of selling)
 * @param unlimited admin shop without stock
 * @param owner     owner (null for a non player owner)
 * @param stock     items left (selling) or room left (buying), -1 when unknown (chunk not loaded)
 */
public record ChestShop(long id, Location location, ItemStack item, double price, boolean buying, boolean unlimited,
                        UUID owner, String ownerName, int stock) {
}
