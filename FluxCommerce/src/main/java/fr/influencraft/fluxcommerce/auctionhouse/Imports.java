package fr.influencraft.fluxcommerce.auctionhouse;

import fr.influencraft.fluxcommerce.api.Listings;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** Imports of a former auction house plugin ({@link Listings}), each source recorded once in the database. */
final class Imports implements Listings.Backend {

    private final AuctionHouseModule plugin;

    Imports(AuctionHouseModule plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean importListing(Listings.Import i) {
        if (plugin.market().get(i.id()) != null || !plugin.store().markImported(i.source())) return false;
        ItemStack lot = i.item().clone();
        Listing l = new Listing(i.id(), i.seller(), i.sellerName(), lot, i.auction(), i.price(), i.created(), i.start(), i.end());
        l.left = Math.max(0, Math.min(i.left(), lot.getAmount()));
        l.state = Listing.State.valueOf(i.state());
        l.reason = i.reason();
        for (Listings.ImportedBid b : i.bids()) l.bids.add(new Listing.Bid(b.bidder(), b.name(), b.amount(), b.date()));
        return plugin.market().add(l);
    }

    @Override
    public boolean importPayment(String source, UUID player, double amount, String reason) {
        if (amount <= 0 || !plugin.store().markImported(source)) return false;
        OfflinePlayer to = Bukkit.getOfflinePlayer(player);
        if (!plugin.money().give(to, amount, plugin.origin("migration").detail(reason))) {
            plugin.getLogger().severe("Import " + source + ": " + amount + " not paid to " + to.getName());
            return false;
        }
        return true;
    }

    @Override
    public boolean importBan(String source, UUID player, String name, long until, String reason) {
        if (until <= System.currentTimeMillis() || !plugin.store().markImported(source)) return false;
        plugin.host().bans().ban(player, name, (until - System.currentTimeMillis()) / 1000, reason, "import");
        return true;
    }

}
