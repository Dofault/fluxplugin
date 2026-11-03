package fr.influencraft.fluxcommerce.auctionhouse;

import fr.influencraft.flux.api.shops.ChestShop;
import fr.influencraft.flux.api.shops.ChestShopTradeEvent;
import fr.influencraft.flux.api.shops.ChestShops;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Chest shops of the players (Flux's chest shops service, the chest shops of FluxCommerce) in the market: selling and buying shops join
 * the catalog, and every trade is written to the history (one market price for the auction house and the shops).
 * Town and market fee come from FluxLands ({@link Towns}) when it is there.
 */
final class ChestShopOffers implements Listener {

    private final AuctionHouseModule plugin;

    ChestShopOffers(AuctionHouseModule plugin) {
        this.plugin = plugin;
    }

    /**
     * Active shops (main thread). An empty selling shop or a full buying shop is left out, even when its area is not
     * loaded (last count kept by the shop plugin).
     */
    List<Offer> offers() {
        List<Offer> l = new ArrayList<>();
        for (ChestShop shop : ChestShops.all()) {
            try {
                Location loc = shop.location();
                if (loc == null || loc.getWorld() == null) continue;
                int stock = shop.stock();
                if (stock == 0) continue;
                ItemStack item = shop.item().clone();
                String town = Towns.nameAt(loc);
                String index = ItemInfo.index(item) + " " + ItemInfo.normalize(shop.ownerName() + " " + (town == null ? "" : town));
                l.add(new Offer(shop.buying() ? Offer.Type.SHOP_BUYING : Offer.Type.SHOP, "s" + shop.id(), null, item, ItemInfo.key(item),
                        index, shop.price(), Math.max(1, item.getAmount()), shop.owner(), shop.ownerName(), 0, stock, town,
                        Towns.idAt(loc), Towns.feeAt(loc), loc.clone(), 0));
            } catch (RuntimeException e) {
                // broken shop or missing world: skipped until the next pass
            }
        }
        return l;
    }

    /** A successful chest shop trade goes to the history ({@link ChestShopTradeEvent#amount()}: items traded). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void trade(ChestShopTradeEvent e) {
        try {
            ChestShop shop = e.shop();
            int amount = e.amount();
            if (amount <= 0) return;
            ItemStack item = shop.item().clone();
            item.setAmount(Math.min(amount, item.getMaxStackSize()));
            boolean buying = shop.buying();
            plugin.history().record(new History.Sale(System.currentTimeMillis(), buying ? History.SHOP_BUYING : History.SHOP,
                    String.valueOf(shop.id()),
                    buying ? e.customer() : shop.owner(), buying ? e.customerName() : shop.ownerName(),
                    buying ? shop.owner() : e.customer(), buying ? shop.ownerName() : e.customerName(),
                    ItemInfo.key(item), ItemInfo.plain(item), amount, e.total(), e.tax(), bytes(item), Towns.nameAt(shop.location())));
            plugin.catalog().invalidate();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Chest shop trade not recorded: " + ex);
        }
    }

    /** Sends a player to a shop: through FluxLands (nearest warp, town spawn), else its coordinates. */
    void goTo(Player p, Offer o) {
        p.closeInventory();
        if (Towns.goToShop(p, o.shopId())) return;
        Location l = o.location();
        plugin.send(p, "shop-location", null, "%owner%", o.seller(), "%x%", String.valueOf(l.getBlockX()),
                "%y%", String.valueOf(l.getBlockY()), "%z%", String.valueOf(l.getBlockZ()), "%world%", l.getWorld().getName());
    }

    static byte[] bytes(ItemStack item) {
        try {
            return item.serializeAsBytes();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
