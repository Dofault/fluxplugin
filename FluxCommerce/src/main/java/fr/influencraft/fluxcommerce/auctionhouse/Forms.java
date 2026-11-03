package fr.influencraft.fluxcommerce.auctionhouse;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * The windows of the market (Flux dialogs): listing an item (amount, price, auction), buying any amount of a direct
 * sale, bidding, taking a listing back, searching, staff removal. Every amount goes through {@code Form.amount}.
 * Escape goes back to the menu the window was opened from.
 */
final class Forms {

    private final AuctionHouseModule plugin;

    Forms(AuctionHouseModule plugin) {
        this.plugin = plugin;
    }

    private String raw(String key, String... vars) {
        return plugin.messages().raw(key, vars);
    }

    private String price(double amount) {
        return plugin.money().format(amount);
    }

    /** Reopens the menu the player has open now (after the window), or nothing when it was opened by a command. */
    private Runnable back(Player p) {
        MenuView v = FluxApi.view(p);
        String menu = v != null && v.isOpen() ? v.menuId() : null;
        return () -> {
            if (menu == null || !p.isOnline()) return;
            Listing l = plugin.menus().current(p);
            if ((menu.equals("marche_offre") || menu.equals("marche_detail")) && l != null) plugin.menus().openListing(p, l, null);
            else if (menu.equals("marche")) plugin.menus().openMarket(p, false);
            else FluxApi.open(p, menu);
        };
    }

    // ---------------------------------------------------------------- search

    /** A text, or one click on one of the player's towns (its chest shops at once). Empty text = no search. */
    void search(Player p) {
        Runnable back = back(p);
        Dialogs.Form f = Dialogs.form(p, raw("form.search-title")).body(raw("form.search-body"));
        List<String> towns = Towns.of(p);
        if (!towns.isEmpty()) {
            f.body(raw("form.search-towns"));
            String[] choices = new String[towns.size() * 2];
            for (int i = 0; i < towns.size(); i++) {
                choices[2 * i] = "town:" + towns.get(i);
                choices[2 * i + 1] = towns.get(i);
            }
            f.choiceLine(choices);
        }
        String current = fr.influencraft.flux.api.list.Lists.state(p, Menus.MARKET).search();
        f.input("value", raw("form.search-field"), current, 48)
                .buttons(raw("form.search-confirm"), raw("form.cancel"))
                .onSubmit((pl, a) -> {
                    if (a.button().startsWith("town:")) plugin.menus().search(pl, a.button().substring(5), Catalog.SHOPS);
                    else plugin.menus().search(pl, a.text("value"), -1);
                })
                .onCancel(pl -> back.run())
                .open();
    }

    // ---------------------------------------------------------------- listing an item

    /**
     * Listing the item of an inventory slot: amount (when the slot holds more than one), total price (the market
     * price for the whole slot offered), auction or not. The window shows what the seller will get; its button lists
     * the item at once.
     */
    void sell(Player p, int slot, boolean fromMenu) {
        Market m = plugin.market();
        ItemStack item = slot >= 0 && slot < 36 ? p.getInventory().getItem(slot) : null;
        if (item == null || item.getType().isAir()) {
            plugin.messages().send(p, "hold-item");
            Sounds.cue(p, "error");
            return;
        }
        if (m.blacklisted(item)) {
            plugin.messages().send(p, "blacklisted");
            Sounds.cue(p, "error");
            return;
        }
        if (m.refusedBan(p)) return;
        int max = m.slots(p);
        if (m.used(p.getUniqueId()) >= max) {
            plugin.messages().send(p, max <= 0 ? "no-slots" : "slots-full", "%limit%", String.valueOf(max));
            Sounds.cue(p, "error");
            return;
        }
        ItemStack expected = item.clone();
        int amount = item.getAmount();
        Runnable back = fromMenu ? back(p) : () -> { };
        boolean direct = m.allows(false), auction = m.allows(true);
        if (!direct && !auction) {
            plugin.messages().send(p, "direct-sales-disabled");
            return;
        }
        History.Average avg = plugin.history().average(ItemInfo.key(item), plugin.days());
        long suggested = avg != null && avg.unit() > 0 ? Math.max(1, Math.round(avg.unit() * amount)) : (long) m.minPrice(false);
        Dialogs.Form f = Dialogs.form(p, raw("form.sell-title")).width(280)
                .item(item, raw("form.sell-item", "%amount%", String.valueOf(amount), "%item%", ItemInfo.display(item)))
                .body(raw("form.balance", "%balance%", price(plugin.money().balance(p))));
        if (avg != null && avg.unit() > 0) f.body(raw("form.sell-average", "%price%", price(avg.unit()), "%days%", String.valueOf(plugin.days())));
        f.body(raw("form.sell-tax", "%tax%", Format.percent(m.taxRate() * 100)));
        f.body(raw("form.sell-durations", "%sale%", Format.duration(m.duration(p, false)), "%auction%", Format.duration(m.duration(p, true)),
                "%delay%", Format.duration(plugin.getConfig().getLong("listing.setup-seconds", 30))));
        if (amount > 1) f.amount("amount", raw("form.sell-amount", "%max%", String.valueOf(amount)), amount, 1, amount);
        f.amount("price", raw("form.sell-price"), suggested, 1, Long.MAX_VALUE);
        if (direct && auction) f.toggle("auction", raw("form.sell-auction"), false, raw("form.sell-auction-help"));
        f.check(a -> {
            boolean auc = direct && auction ? a.toggle("auction") : auction;
            long priceTyped = a.amount("price");
            double min = m.minPrice(auc), most = m.maxPrice(auc);
            if (priceTyped < min) return raw("form.price-min", "%price%", price(min));
            if (most > 0 && priceTyped > most) return raw("form.price-max", "%price%", price(most));
            return null;
        });
        f.buttons(raw("form.sell-confirm"), raw("form.cancel")).onSubmit((pl, a) -> {
            boolean auc = direct && auction ? a.toggle("auction") : auction;
            int qty = amount > 1 ? (int) a.amount("amount") : 1;
            if (m.sell(pl, slot, expected, qty, a.amount("price"), auc)) {
                plugin.menus().invalidate();
                if (fromMenu) plugin.menus().open(pl, "listings");
            } else {
                back.run();
            }
        }).onCancel(pl -> back.run()).open();
    }

    // ---------------------------------------------------------------- buying any amount

    void buyAmount(Player p, Listing l) {
        Runnable back = back(p);
        Market m = plugin.market();
        Dialogs.form(p, raw("form.buy-title"))
                .item(l.copy(1), raw("form.buy-item", "%item%", ItemInfo.display(l.item), "%seller%", l.sellerName))
                .body(raw("form.buy-unit", "%price%", price(l.unitPrice()), "%left%", String.valueOf(l.left)))
                .body(raw("form.balance", "%balance%", price(plugin.money().balance(p))))
                .amount("amount", raw("form.buy-amount", "%max%", String.valueOf(l.left)), l.left, 1, l.left)
                .check(a -> plugin.money().has(p, Market.cost(l, (int) a.amount("amount"))) ? null
                        : raw("form.too-expensive", "%price%", price(Market.cost(l, (int) a.amount("amount")))))
                .buttons(raw("form.buy-confirm"), raw("form.cancel"))
                .onSubmit((pl, a) -> {
                    m.buy(pl, l, (int) a.amount("amount"));
                    if (l.state == Listing.State.ACTIVE && l.left > 0) plugin.menus().openListing(pl, l, null);
                    else back.run();
                })
                .onCancel(pl -> back.run())
                .open();
    }

    // ---------------------------------------------------------------- bidding

    void bid(Player p, Listing l) {
        Runnable back = back(p);
        Market m = plugin.market();
        double min = m.minimumBid(l);
        Listing.Bid top = l.highest();
        Dialogs.Form f = Dialogs.form(p, raw("form.bid-title"))
                .item(l.copy(l.initial()), raw("form.bid-item", "%item%", ItemInfo.display(l.item), "%seller%", l.sellerName))
                .body(raw(top == null ? "form.bid-start" : "form.bid-current", "%price%", price(l.currentPrice()),
                        "%player%", top == null ? "" : top.name()))
                .body(raw("form.bid-min", "%price%", price(min)))
                .body(raw("form.balance", "%balance%", price(plugin.money().balance(p))))
                .body(raw("form.bid-help"));
        f.amount("amount", raw("form.bid-amount"), (long) Math.ceil(min), (long) Math.ceil(min), Long.MAX_VALUE)
                .check(a -> plugin.money().has(p, a.amount("amount")) ? null : raw("form.too-expensive", "%price%", price(a.amount("amount"))))
                .buttons(raw("form.bid-confirm"), raw("form.cancel"))
                .onSubmit((pl, a) -> {
                    m.bid(pl, l, a.amount("amount"));
                    plugin.menus().openListing(pl, l, null);
                })
                .onCancel(pl -> back.run())
                .open();
    }

    // ---------------------------------------------------------------- taking a listing back

    void cancel(Player p, Listing l) {
        Runnable back = back(p);
        Dialogs.confirm(p, raw("form.cancel-title"), raw("form.cancel-question", "%amount%", String.valueOf(l.left),
                "%item%", ItemInfo.display(l.item)), pl -> {
            plugin.market().cancel(pl, l);
            back.run();
        }, pl -> back.run());
    }

    // ---------------------------------------------------------------- staff

    /** Ends a listing for the staff, with a reason shown to the seller; {@code destroy}: the item is destroyed. */
    void staffRemove(Player p, Listing l, boolean destroy) {
        Runnable back = back(p);
        Dialogs.form(p, raw(destroy ? "form.staff-delete-title" : "form.staff-remove-title"))
                .item(l.copy(Math.max(1, l.left)), raw("form.staff-item", "%amount%", String.valueOf(l.left), "%item%", ItemInfo.display(l.item),
                        "%seller%", l.sellerName))
                .body(raw(destroy ? "form.staff-delete-body" : "form.staff-remove-body"))
                .requiredInput("reason", raw("form.staff-reason"), "", 100)
                .buttons(raw(destroy ? "form.staff-delete-confirm" : "form.staff-remove-confirm"), raw("form.cancel"))
                .onSubmit((pl, a) -> {
                    if (plugin.market().staffRemove(pl, l, a.text("reason"), destroy)) {
                        plugin.messages().send(pl, destroy ? "staff-deleted-done" : "staff-removed-done", "%player%", l.sellerName);
                        Sounds.cue(pl, "remove");
                    } else {
                        plugin.messages().send(pl, "gone");
                    }
                    plugin.menus().openMarket(pl, false);
                })
                .onCancel(pl -> back.run())
                .open();
    }
}
