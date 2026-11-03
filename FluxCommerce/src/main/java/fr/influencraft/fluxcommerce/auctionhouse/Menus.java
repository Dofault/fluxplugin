package fr.influencraft.fluxcommerce.auctionhouse;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.ListProvider;
import fr.influencraft.flux.api.list.ListQuery;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.trade.TradeWindow;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Sounds;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The market menus, built in Java and placed by the menu files (Flux lists, {@code material: entry}):
 * <ul>
 *   <li>{@code marche}: the unified market (listings and chest shops), source filter, sort, search;</li>
 *   <li>{@code marche_offre}: buying a direct sale, in Flux's trade window (x1, x2, x10, x64 and any amount);</li>
 *   <li>{@code marche_detail}: an auction, one's own listing, staff tools;</li>
 *   <li>{@code marche_vendre}: picking an item of the inventory to list (then a window asks amount and price);</li>
 *   <li>{@code marche_ventes}, {@code marche_encheres}, {@code marche_historique}: own listings, bids, history;</li>
 *   <li>{@code marche_contenu}: what a shulker box or a bundle for sale holds.</li>
 * </ul>
 * Buttons run {@code [hdv] <verb> [argument]}.
 */
final class Menus {

    static final String MARKET = "hdv_market", LISTINGS = "hdv_listings", BIDS = "hdv_bids", HISTORY = "hdv_history";
    private static final List<String> LISTS = List.of(MARKET, "hdv_market_info", "hdv_source", "hdv_sort", "hdv_search", "hdv_sell",
            "hdv_mine", "hdv_average", "hdv_offer_info", "hdv_balance", "hdv_offer_price", "hdv_offer_contents", "hdv_offer_custom",
            "hdv_offer_staff", "hdv_others", "hdv_detail_item", "hdv_detail_actions", "hdv_contents", "hdv_inventory", "hdv_sell_info",
            "hdv_sell_hand", LISTINGS, "hdv_listings_info", BIDS, "hdv_bids_info", HISTORY, "hdv_history_info", "hdv_history_filter",
            "hdv_collect");
    /** Size of the market grid (pages of the search). */
    static final int MARKET_PAGE = 28;

    /** What a player looks at besides the list states kept by Flux. */
    private static final class View {
        /** Command of the market's back button when another plugin opened it (FluxLands); empty: main menu. */
        String back = "";
        /** Listing shown by marche_offre / marche_detail, and the menu its back button returns to. */
        UUID listing;
        String listingBack = "marche";
    }

    private final AuctionHouseModule plugin;
    private final Map<UUID, View> views = new HashMap<>();

    Menus(AuctionHouseModule plugin) {
        this.plugin = plugin;
    }

    private String raw(String key, String... vars) {
        return plugin.messages().raw(key, vars);
    }

    private String icon(String key) {
        return plugin.getConfig().getString("icons." + key, "PAPER");
    }

    private String price(double amount) {
        return plugin.money().format(amount);
    }

    private View view(Player p) {
        return views.computeIfAbsent(p.getUniqueId(), k -> new View());
    }

    void forget(Player p) {
        views.remove(p.getUniqueId());
    }

    void invalidate() {
        for (String id : LISTS) Lists.invalidate(id);
    }

    // ---------------------------------------------------------------- registration

    void register() {
        Lists.register(plugin.host(), MARKET, new ListProvider() {
            public List<ListEntry> entries(ListQuery q) {
                List<ListEntry> out = new ArrayList<>();
                for (Offer o : plugin.catalog().filter(q.filter(), q.sort(), q.search())) out.add(offerEntry(q.viewer(), o));
                return out;
            }

            public List<String> filters() {
                return Catalog.SOURCES.stream().map(s -> raw("sources." + s)).toList();
            }

            public List<String> sorts() {
                return Catalog.SORTS.stream().map(s -> raw("sorts." + s)).toList();
            }

            public boolean handlesSearch() {
                return true;
            }
        });
        Lists.registerItem(plugin.host(), "hdv_market_info", this::marketInfo);
        Lists.registerItem(plugin.host(), "hdv_source", p -> selector(p, MARKET, "source", true));
        Lists.registerItem(plugin.host(), "hdv_sort", p -> selector(p, MARKET, "sort", false));
        Lists.registerItem(plugin.host(), "hdv_search", this::searchButton);
        Lists.registerItem(plugin.host(), "hdv_sell", this::sellButton);
        Lists.registerItem(plugin.host(), "hdv_mine", this::mineButton);
        Lists.registerItem(plugin.host(), "hdv_average", this::averageButton);

        Lists.registerItem(plugin.host(), "hdv_offer_info", this::offerInfo);
        Lists.registerItem(plugin.host(), "hdv_balance", this::balance);
        Lists.registerItem(plugin.host(), "hdv_offer_price", this::marketPrice);
        Lists.registerItem(plugin.host(), "hdv_offer_contents", this::contentsButton);
        Lists.registerItem(plugin.host(), "hdv_offer_custom", this::customAmountButton);
        Lists.registerItem(plugin.host(), "hdv_offer_staff", this::staffButton);
        Lists.register(plugin.host(), "hdv_others", q -> others(q.viewer()));
        Lists.registerItem(plugin.host(), "hdv_detail_item", this::detailItem);
        Lists.register(plugin.host(), "hdv_detail_actions", q -> detailActions(q.viewer()));
        Lists.register(plugin.host(), "hdv_contents", q -> contents(q.viewer()));

        Lists.register(plugin.host(), "hdv_inventory", q -> inventory(q.viewer()));
        Lists.registerItem(plugin.host(), "hdv_sell_info", this::sellInfo);
        Lists.registerItem(plugin.host(), "hdv_sell_hand", this::sellHand);

        Lists.register(plugin.host(), LISTINGS, q -> listings(q.viewer()));
        Lists.registerItem(plugin.host(), "hdv_listings_info", this::listingsInfo);
        Lists.register(plugin.host(), BIDS, q -> bids(q.viewer()));
        Lists.registerItem(plugin.host(), "hdv_bids_info", this::bidsInfo);
        Lists.register(plugin.host(), HISTORY, new ListProvider() {
            public List<ListEntry> entries(ListQuery q) {
                return history(q.viewer(), q.filter());
            }

            public List<String> filters() {
                return List.of(raw("history.all"), raw("history.sales"), raw("history.purchases"));
            }
        });
        Lists.registerItem(plugin.host(), "hdv_history_info", this::historyInfo);
        Lists.registerItem(plugin.host(), "hdv_history_filter", p -> selector(p, HISTORY, "history-filter", true));
        Lists.registerItem(plugin.host(), "hdv_collect", this::collectButton);
        FluxApi.registerAction(plugin.host(), "hdv", (player, view, argument) -> act(player, argument == null ? "" : argument.trim()));
    }

    // ---------------------------------------------------------------- opening

    /** The market; {@code fresh}: a new visit (no search, everything shown, cheapest first, back to the main menu). */
    void openMarket(Player p, boolean fresh) {
        if (fresh) {
            Lists.reset(p, MARKET);
            view(p).back = "";
        }
        Lists.invalidate(p, MARKET);
        FluxApi.open(p, "marche");
    }

    /** Opened from another plugin's menu: back command, source, search. */
    void openFrom(Player p, String back, int source, String search) {
        Lists.reset(p, MARKET);
        view(p).back = back;
        if (source > 0) Lists.act(p, MARKET, "filter " + source, MARKET_PAGE);
        if (!search.isBlank()) Lists.act(p, MARKET, "search " + search, MARKET_PAGE);
        FluxApi.open(p, "marche");
    }

    /** The market searching a text (keeps the source unless {@code source} >= 0). */
    void search(Player p, String text, int source) {
        if (source >= 0) Lists.act(p, MARKET, "filter " + source, MARKET_PAGE);
        Lists.act(p, MARKET, text.isBlank() ? "clear" : "search " + text, MARKET_PAGE);
        FluxApi.open(p, "marche");
    }

    void open(Player p, String menu) {
        switch (menu) {
            case "listings" -> FluxApi.open(p, "marche_ventes");
            case "bids" -> FluxApi.open(p, "marche_encheres");
            case "history" -> FluxApi.open(p, "marche_historique");
            case "sell" -> FluxApi.open(p, "marche_vendre");
            default -> openMarket(p, !inMarket(p));
        }
    }

    /** The player has a menu of the market open right now (coming back from one of its menus keeps the filters). */
    private static boolean inMarket(Player p) {
        MenuView v = FluxApi.view(p);
        return v != null && v.isOpen() && v.menuId() != null && v.menuId().startsWith("marche");
    }

    /** A listing: the trade window for a direct sale of somebody else, else its detail menu. */
    void openListing(Player p, Listing l, String back) {
        if (l == null) {
            plugin.messages().send(p, "gone");
            Sounds.cue(p, "error");
            return;
        }
        View v = view(p);
        v.listing = l.id;
        if (back != null) v.listingBack = back;
        for (String id : List.of("hdv_offer_info", "hdv_offer_price", "hdv_offer_contents", "hdv_offer_custom", "hdv_offer_staff",
                "hdv_others", "hdv_detail_item", "hdv_detail_actions")) Lists.invalidate(p, id);
        long now = System.currentTimeMillis();
        if (!l.auction && l.live(now) && !l.ownedBy(p.getUniqueId())) {
            String title = raw("trade-title", "%seller%", l.sellerName);
            if (TradeWindow.buy(p, "marche_offre", title, List.of(offer(l)))) return;
        }
        FluxApi.open(p, "marche_detail");
    }

    Listing current(Player p) {
        UUID id = view(p).listing;
        return id == null ? null : plugin.market().get(id);
    }

    private void backFromListing(Player p) {
        String menu = view(p).listingBack;
        if (menu.equals("marche")) openMarket(p, false);
        else FluxApi.open(p, menu);
    }

    private void refresh(Player p) {
        invalidate();
        MenuView v = FluxApi.view(p);
        if (v != null && v.isOpen()) v.refresh();
    }

    // ---------------------------------------------------------------- actions

    private void act(Player p, String argument) {
        String[] parts = argument.split("\\s+", 2);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        Market market = plugin.market();
        switch (verb) {
            case "open" -> open(p, arg);
            case "back" -> {
                String back = view(p).back;
                if (back.isEmpty()) FluxApi.open(p, "main");
                else p.performCommand(back);
            }
            case "search" -> plugin.forms().search(p);
            case "find" -> search(p, arg, -1);
            case "myshops" -> search(p, p.getName(), Catalog.SHOPS);
            case "view" -> openListing(p, market.get(arg), inMarketMenu(p));
            case "shop" -> {
                Offer o = plugin.catalog().byId(arg);
                if (o != null && plugin.shops() != null) plugin.shops().goTo(p, o);
            }
            case "town" -> {
                Offer o = plugin.catalog().byId(arg);
                if (o != null) Towns.openPage(p, o.townId());
            }
            case "listing-back" -> backFromListing(p);
            case "same" -> {
                Listing l = current(p);
                if (l != null) search(p, ItemInfo.plain(l.item), Catalog.ALL);
            }
            case "seller" -> {
                Listing l = current(p);
                if (l != null) search(p, l.sellerName, Catalog.ALL);
            }
            case "custom" -> {
                Listing l = current(p);
                if (l != null) plugin.forms().buyAmount(p, l);
            }
            case "bid" -> {
                Listing l = current(p);
                if (l != null) plugin.forms().bid(p, l);
            }
            case "cancel" -> {
                Listing l = arg.isEmpty() ? current(p) : market.get(arg);
                if (l != null) plugin.forms().cancel(p, l);
            }
            case "collect" -> {
                if (arg.isEmpty()) {
                    if (market.collectAll(p, false) == 0 && market.toCollect(p.getUniqueId()) == 0) plugin.messages().send(p, "nothing-to-collect");
                } else {
                    market.collect(p, market.get(arg));
                }
                refresh(p);
            }
            case "staff" -> {
                Listing l = current(p);
                if (l != null && p.hasPermission(AuctionHouseModule.ADMIN)) plugin.forms().staffRemove(p, l, arg.equals("delete"));
            }
            case "contents" -> {
                Lists.reset(p, "hdv_contents");
                FluxApi.open(p, "marche_contenu");
            }
            case "contents-back" -> {
                Listing l = current(p);
                if (l == null) openMarket(p, false);
                else openListing(p, l, null);
            }
            case "sell-slot" -> {
                try {
                    plugin.forms().sell(p, Integer.parseInt(arg), true);
                } catch (NumberFormatException ignored) {
                    // malformed menu line
                }
            }
            case "sell-hand" -> plugin.forms().sell(p, p.getInventory().getHeldItemSlot(), true);
            case "average" -> {
                if (arg.equals("search")) {
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (!hand.getType().isAir()) search(p, ItemInfo.plain(hand), Catalog.ALL);
                } else {
                    p.closeInventory();
                    plugin.averageInChat(p);
                }
            }
            default -> {
            }
        }
    }

    /** Menu a listing opened from the current menu goes back to. */
    private static String inMarketMenu(Player p) {
        MenuView v = FluxApi.view(p);
        String id = v == null || !v.isOpen() ? null : v.menuId();
        if (id == null || id.equals("marche_detail") || id.equals("marche_offre") || id.equals("marche_contenu")) return null;
        return id.startsWith("marche") ? id : "marche";
    }

    // ---------------------------------------------------------------- shared look

    /** The real item (its name translated by the client, its own lore kept), then the market lines below. */
    private static ItemStack withLore(ItemStack item, Lore lore, Player viewer, boolean glow) {
        ItemStack shown = item.clone();
        ItemMeta meta = shown.getItemMeta();
        if (meta == null) return shown;
        List<Component> all = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        all.addAll(Texts.lore(lore.build(), viewer));
        meta.lore(all);
        if (glow) meta.setEnchantmentGlintOverride(true);
        shown.setItemMeta(meta);
        return shown;
    }

    private ItemStack button(Player p, String iconKey, String nameKey, Lore lore) {
        return Items.of(icon(iconKey), raw(nameKey), lore.build(), p);
    }

    /** One entry with its clicks (every key set: a missing one would be run as text). */
    private static ListEntry entry(String id, ItemStack item, String left, String right, String shiftLeft) {
        String none = "[hdv] none";
        return ListEntry.of(id).item(item).set("left", left == null ? none : left).set("right", right == null ? none : right)
                .set("shift_left", shiftLeft == null ? none : shiftLeft);
    }

    /** First line of an offer's lore, coloured by its source ({@code line.*}: "◆ Vente directe (HDV)", "◆ Échoppe à ..."). */
    private String kindLine(Offer o) {
        return switch (o.type()) {
            case SALE -> raw("line.sale");
            case AUCTION -> raw("line.auction");
            case SHOP -> raw(o.town() != null ? "line.shop-town" : "line.shop", "%town%", String.valueOf(o.town()));
            case SHOP_BUYING -> raw(o.town() != null ? "line.buying-town" : "line.buying", "%town%", String.valueOf(o.town()));
        };
    }

    /** Market average per item and how far this unit price is from it (key/value lines, kept beside the price). */
    private void compare(Lore lore, String key, double unit) {
        History.Average m = plugin.history().average(key, plugin.days());
        if (m == null || m.unit() <= 0) return;
        lore.kv(raw("menu.average-unit", "%days%", String.valueOf(plugin.days())), price(m.unit()));
        double gap = (unit - m.unit()) / m.unit() * 100;
        if (gap <= -10) lore.kv(raw("menu.gap"), raw("menu.cheaper", "%percent%", String.valueOf(Math.round(-gap))));
        else if (gap >= 10) lore.kv(raw("menu.gap"), raw("menu.dearer", "%percent%", String.valueOf(Math.round(gap))));
    }

    /**
     * An offer of the market, the same lines in the same order whatever its source: kind, price (for the shown amount),
     * unit price, market average, seller (buyer), stock (room), then what only that source has.
     */
    private ListEntry offerEntry(Player p, Offer o) {
        Lore lore = Lore.create();
        lore.description(kindLine(o));
        String priceKey = switch (o.type()) {
            case AUCTION -> o.listing() != null && !o.listing().bids.isEmpty() ? "menu.highest-bid" : "menu.start-price";
            case SHOP_BUYING -> "menu.buys-for";
            default -> "menu.price";
        };
        lore.kv(raw(priceKey), price(o.price()) + (o.amount() > 1 ? raw("menu.for-amount", "%amount%", String.valueOf(o.amount())) : ""));
        if (o.amount() > 1) lore.kv(raw("menu.unit-price"), price(o.unitPrice()));
        compare(lore, o.key(), o.unitPrice());
        boolean buying = o.type() == Offer.Type.SHOP_BUYING;
        lore.kv(raw(buying ? "menu.buyer" : "menu.seller"), o.seller() == null ? raw("menu.server") : o.seller());
        if (o.type() == Offer.Type.SHOP || buying) {
            lore.kv(raw(buying ? "menu.room" : "menu.stock"), o.stock() == Integer.MAX_VALUE ? raw("menu.unlimited")
                    : o.stock() < 0 ? raw("menu.not-counted") : String.valueOf(o.stock()));
            if (o.townId() != null && o.fee() > 0) lore.kv(raw("menu.fee"), Format.percent(o.fee()) + " %");
        } else {
            Listing l = o.listing();
            if (o.type() == Offer.Type.AUCTION) {
                lore.kv(raw("menu.bids"), String.valueOf(l.bids.size()));
                lore.kv(raw("menu.minimum-bid"), price(plugin.market().minimumBid(l)));
            }
            lore.kv(raw("menu.ends-in"), Format.duration(l.secondsLeft(System.currentTimeMillis())));
        }
        String id = o.id();
        boolean mine = p.getUniqueId().equals(o.sellerId());
        if (o.type().auctionHouse()) {
            lore.left(raw(mine ? "menu.manage" : o.type() == Offer.Type.SALE ? "menu.buy" : "menu.bid"));
            lore.right(raw("menu.same-item"));
            return entry(id, withLore(o.item(), lore, p, mine), "[hdv] view " + id, "[hdv] find " + ItemInfo.plain(o.item()), null);
        }
        lore.left(raw(buying ? "menu.go-sell" : "menu.go-buy"));
        if (o.townId() != null) lore.right(raw("menu.town-page"));
        return entry(id, withLore(o.item(), lore, p, mine), "[hdv] shop " + id, o.townId() != null ? "[hdv] town " + id : null, null);
    }

    // ---------------------------------------------------------------- market menu

    private ItemStack marketInfo(Player p) {
        Lore lore = Lore.create().description(raw("menu.market-description"));
        lore.kv(raw("menu.tax"), Format.percent(plugin.market().taxRate() * 100) + " %");
        lore.kv(raw("menu.offers"), String.valueOf(plugin.catalog().all().size()));
        lore.kv(raw("menu.balance"), price(plugin.money().balance(p)));
        lore.line(raw("menu.beta"));
        return button(p, "market", "menu.market-name", lore);
    }

    /** A filter button in the suite's format (current option marked); clicks set in the menu file. */
    private ItemStack selector(Player p, String list, String key, boolean filter) {
        ListProvider provider = Lists.provider(list);
        if (provider == null) return null;
        List<String> labels = filter ? provider.filters() : provider.sorts();
        Lists.State s = Lists.state(p, list);
        int selected = Math.floorMod(filter ? s.filter() : s.sort(), Math.max(1, labels.size()));
        Lore lore = Lore.create().description(raw("menu." + key + "-description")).filter(labels, selected);
        return button(p, filter ? "filter" : "sort", "menu." + key, lore);
    }

    private ItemStack searchButton(Player p) {
        String search = Lists.state(p, MARKET).search();
        Lore lore = Lore.create().description(raw("menu.search-description"));
        if (!search.isEmpty()) lore.kv(raw("menu.search-current"), search);
        lore.left(raw("menu.search"));
        if (!search.isEmpty()) lore.right(raw("menu.search-clear"));
        return button(p, "search", "menu.search-name", lore);
    }

    private ItemStack sellButton(Player p) {
        Lore lore = Lore.create().description(raw("menu.sell-description"));
        lore.kv(raw("menu.slots"), slots(p));
        lore.left(raw("menu.sell-pick"));
        return button(p, "add", "menu.sell-name", lore);
    }

    private String slots(Player p) {
        int max = plugin.market().slots(p);
        return plugin.market().used(p.getUniqueId()) + " / " + (max == Integer.MAX_VALUE ? raw("menu.unlimited") : String.valueOf(max));
    }

    private ItemStack mineButton(Player p) {
        int collect = plugin.market().toCollect(p.getUniqueId());
        Lore lore = Lore.create().description(raw("menu.mine-description"));
        lore.kv(raw("menu.slots"), slots(p));
        if (collect > 0) lore.info(raw("menu.to-collect", "%count%", String.valueOf(collect)));
        lore.left(raw("menu.open"));
        if (collect > 0) lore.right(raw("menu.collect-all"));
        ItemStack item = button(p, collect > 0 ? "collect" : "listings", "menu.mine-name", lore);
        return glow(item, collect > 0);
    }

    private static ItemStack glow(ItemStack item, boolean on) {
        if (!on || item == null) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack averageButton(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        Lore lore = Lore.create();
        if (hand.getType().isAir()) {
            lore.description(raw("menu.average-empty"));
            return button(p, "average", "menu.average-name", lore);
        }
        lore.kv(raw("menu.item"), ItemInfo.display(hand));
        for (String[] kv : plugin.averageLines(hand, null)) {
            if (kv[0].isEmpty()) lore.line(kv[1]);
            else lore.kv(kv[0], kv[1]);
        }
        lore.left(raw("menu.average-chat"));
        lore.right(raw("menu.same-item"));
        return button(p, "average", "menu.average-name", lore);
    }

    // ---------------------------------------------------------------- one listing (trade window, detail)

    /** A direct sale in Flux's trade window: one item per bundle (the whole lot when partial selling is off). */
    private TradeWindow.Offer offer(Listing l) {
        return new TradeWindow.Offer() {
            public ItemStack item() {
                return l.item;
            }

            public int bundle() {
                return plugin.market().partialSelling() ? 1 : Math.max(1, l.left);
            }

            public double price() {
                return l.state == Listing.State.ACTIVE && l.left > 0 ? l.unitPrice() * bundle() : 0;
            }

            public int available() {
                if (l.state != Listing.State.ACTIVE || l.left <= 0) return 0;
                return plugin.market().partialSelling() ? l.left : 1;
            }

            public void describe(Player viewer, Lore lore) {
                lore.kv(raw("menu.seller"), l.sellerName);
                lore.kv(raw("menu.ends-in"), Format.duration(l.secondsLeft(System.currentTimeMillis())));
            }

            public boolean buy(Player player, int bundles) {
                boolean done = plugin.market().buy(player, l, bundles * bundle());
                if (done && (l.state != Listing.State.ACTIVE || l.left <= 0)) {
                    TradeWindow.close(player);
                    backFromListing(player);
                }
                return done;
            }
        };
    }

    private ItemStack offerInfo(Player p) {
        Listing l = current(p);
        if (l == null) return null;
        Lore lore = Lore.create().description(raw(l.auction ? "menu.auction-description" : "menu.sale-description"));
        lore.kv(raw("menu.tax"), Format.percent(plugin.market().taxRate() * 100) + " %");
        if (l.auction) lore.kv(raw("menu.increase"), Format.percent(plugin.getConfig().getDouble("auction.increase-percent", 10)) + " %");
        return button(p, l.auction ? "auction" : "market", l.auction ? "menu.auction-name" : "menu.sale-name", lore);
    }

    private ItemStack balance(Player p) {
        Lore lore = Lore.create().kv(raw("menu.balance"), price(plugin.money().balance(p)));
        return button(p, "balance", "menu.balance-name", lore);
    }

    private ItemStack marketPrice(Player p) {
        Listing l = current(p);
        if (l == null) return null;
        Lore lore = Lore.create();
        for (String[] kv : plugin.averageLines(l.item, l.id.toString())) {
            if (kv[0].isEmpty()) lore.line(kv[1]);
            else lore.kv(kv[0], kv[1]);
        }
        return button(p, "average", "menu.market-price", lore);
    }

    private ItemStack contentsButton(Player p) {
        Listing l = current(p);
        if (l == null) return null;
        List<ItemStack> inside = ItemInfo.contents(l.item);
        if (inside.isEmpty()) return null;
        Lore lore = Lore.create().description(raw("menu.contents-description"));
        lore.kv(raw("menu.contents-count"), String.valueOf(inside.stream().mapToInt(ItemStack::getAmount).sum()));
        lore.left(raw("menu.contents-see"));
        return button(p, "contents", "menu.contents-name", lore);
    }

    private ItemStack customAmountButton(Player p) {
        Listing l = current(p);
        if (l == null || l.auction || !plugin.market().partialSelling() || l.left <= 1) return null;
        Lore lore = Lore.create().description(raw("menu.custom-description"));
        lore.kv(raw("menu.left"), String.valueOf(l.left));
        lore.kv(raw("menu.unit-price"), price(l.unitPrice()));
        lore.left(raw("menu.custom-choose"));
        return button(p, "buy", "menu.custom-name", lore);
    }

    private ItemStack staffButton(Player p) {
        Listing l = current(p);
        if (l == null || l.state != Listing.State.ACTIVE || !p.hasPermission(AuctionHouseModule.ADMIN)) return null;
        Lore lore = Lore.create().description(raw("menu.staff-description"));
        lore.kv(raw("menu.seller"), l.sellerName);
        lore.left(raw("menu.staff-remove"));
        lore.right(raw("menu.staff-delete"));
        return button(p, "staff", "menu.staff-name", lore);
    }

    /** The cheapest other offers of the same item, all sources. */
    private List<ListEntry> others(Player p) {
        Listing l = current(p);
        if (l == null) return List.of();
        List<ListEntry> out = new ArrayList<>();
        for (Offer o : plugin.catalog().sameItem(ItemInfo.key(l.item), l.id.toString(), false, 7)) out.add(offerEntry(p, o));
        return out;
    }

    private ItemStack detailItem(Player p) {
        Listing l = current(p);
        if (l == null) return null;
        long now = System.currentTimeMillis();
        boolean mine = l.ownedBy(p.getUniqueId());
        Lore lore = Lore.create();
        lore.description(raw(l.auction ? "line.auction" : "line.sale"));
        lore.kv(raw("menu.seller"), l.sellerName + (mine ? raw("menu.you") : ""));
        if (l.auction) {
            Listing.Bid top = l.highest();
            lore.kv(raw(top == null ? "menu.start-price" : "menu.highest-bid"), price(l.currentPrice()));
            lore.kv(raw("menu.bids"), String.valueOf(l.bids.size()));
            if (top != null) lore.kv(raw("menu.leader"), top.name() + (top.bidder().equals(p.getUniqueId()) ? raw("menu.you") : ""));
            if (l.state == Listing.State.ACTIVE) lore.kv(raw("menu.minimum-bid"), price(plugin.market().minimumBid(l)));
        } else {
            lore.kv(raw("menu.price"), price(l.currentPrice()) + (l.left > 1 ? raw("menu.for-amount", "%amount%", String.valueOf(l.left)) : ""));
            if (l.left > 1) lore.kv(raw("menu.unit-price"), price(l.unitPrice()));
            if (l.left < l.initial()) lore.kv(raw("menu.sold-already"), (l.initial() - l.left) + " / " + l.initial());
        }
        lore.kv(raw("menu.state"), state(l, p, now));
        if (l.state == Listing.State.ACTIVE) lore.kv(raw(l.pending(now) ? "menu.online-in" : "menu.ends-in"), Format.duration(l.secondsLeft(now)));
        if (l.reason != null && !l.reason.isBlank()) lore.kv(raw("menu.reason"), l.reason);
        return withLore(l.copy(Math.min(99, Math.max(1, l.left))), lore, p, false);
    }

    private String state(Listing l, Player p, long now) {
        return switch (l.state) {
            case ACTIVE -> raw(l.pending(now) ? "state.pending" : l.auction && !l.bids.isEmpty() ? "state.bidding" : "state.active");
            case EXPIRED -> raw("state.expired");
            case REMOVED -> raw("state.removed");
            case DELETED -> raw("state.deleted");
            case WON -> raw(l.highest() != null && l.highest().bidder().equals(p.getUniqueId()) ? "state.won" : "state.sold");
        };
    }

    /** The button under the item of the detail menu: what the player can do with this listing now. */
    private List<ListEntry> detailActions(Player p) {
        Listing l = current(p);
        if (l == null) return List.of();
        long now = System.currentTimeMillis();
        boolean mine = l.ownedBy(p.getUniqueId());
        ListEntry action;
        if (Market.toCollect(l, p.getUniqueId())) {
            Lore lore = Lore.create().description(raw("menu.collect-description")).left(raw("menu.collect"));
            action = entry("collect", button(p, "collect", "menu.collect-name", lore), "[hdv] collect " + l.id, null, null);
        } else if (l.state != Listing.State.ACTIVE) {
            action = entry("ended", button(p, "locked", "menu.ended-name", Lore.create().ko(state(l, p, now))), null, null, null);
        } else if (mine) {
            if (l.auction && !l.bids.isEmpty()) {
                action = entry("locked", button(p, "locked", "menu.cancel-name", Lore.create().ko(raw("menu.cancel-locked"))), null, null, null);
            } else {
                Lore lore = Lore.create().description(raw("menu.cancel-description")).left(raw("menu.cancel"));
                action = entry("cancel", button(p, "remove", "menu.cancel-name", lore), "[hdv] cancel", null, null);
            }
        } else if (l.pending(now)) {
            action = entry("pending", button(p, "locked", "menu.pending-name",
                    Lore.create().ko(raw("menu.pending", "%delay%", Format.duration(l.secondsLeft(now))))), null, null, null);
        } else if (l.auction) {
            Listing.Bid top = l.highest();
            if (top != null && top.bidder().equals(p.getUniqueId())) {
                action = entry("leading", button(p, "leading", "menu.leading-name", Lore.create().ok(raw("menu.leading"))), null, null, null);
            } else {
                double min = plugin.market().minimumBid(l);
                Lore lore = Lore.create().description(raw("menu.bid-description"));
                lore.kv(raw("menu.minimum-bid"), price(min));
                if (!plugin.money().has(p, min)) lore.ko(raw("menu.no-money"));
                lore.left(raw("menu.bid"));
                action = entry("bid", button(p, "auction", "menu.bid-name", lore), "[hdv] bid", null, null);
            }
        } else {
            action = entry("view", button(p, "buy", "menu.buy-name", Lore.create().left(raw("menu.buy"))), "[hdv] view " + l.id, null, null);
        }
        return List.of(action);
    }

    private List<ListEntry> contents(Player p) {
        Listing l = current(p);
        if (l == null) return List.of();
        List<ListEntry> out = new ArrayList<>();
        int i = 0;
        for (ItemStack s : ItemInfo.contents(l.item)) out.add(entry("c" + i++, s, null, null, null));
        return out;
    }

    // ---------------------------------------------------------------- selling

    /** The player's inventory: storage rows, then the hotbar (order of the hidden inventory of the menu). */
    private List<ListEntry> inventory(Player p) {
        List<ListEntry> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            int slot = i < 27 ? i + 9 : i - 27;
            ItemStack s = p.getInventory().getItem(slot);
            if (s == null || s.getType().isAir()) {
                out.add(ListEntry.gap());
                continue;
            }
            Lore lore = Lore.create();
            if (plugin.market().blacklisted(s)) {
                lore.ko(raw("menu.cannot-sell"));
                out.add(entry("i" + slot, withLore(s, lore, p, false), "[sound] @error", null, null));
                continue;
            }
            History.Average m = plugin.history().average(ItemInfo.key(s), plugin.days());
            if (m != null && m.unit() > 0) lore.kv(raw("menu.average-unit", "%days%", String.valueOf(plugin.days())), price(m.unit()));
            lore.left(raw("menu.sell-this"));
            out.add(entry("i" + slot, withLore(s, lore, p, false), "[hdv] sell-slot " + slot, null, null));
        }
        return out;
    }

    private ItemStack sellInfo(Player p) {
        Market m = plugin.market();
        Lore lore = Lore.create().description(raw("menu.sell-info-description"));
        lore.kv(raw("menu.slots"), slots(p));
        lore.kv(raw("menu.tax"), Format.percent(m.taxRate() * 100) + " %");
        lore.kv(raw("menu.sale-duration"), Format.duration(m.duration(p, false)));
        lore.kv(raw("menu.auction-duration"), Format.duration(m.duration(p, true)));
        lore.kv(raw("menu.setup"), Format.duration(plugin.getConfig().getLong("listing.setup-seconds", 30)));
        return button(p, "add", "menu.sell-name", lore);
    }

    private ItemStack sellHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        Lore lore = Lore.create().description(raw("menu.hand-description"));
        if (hand.getType().isAir()) lore.ko(raw("menu.hand-empty"));
        else {
            lore.kv(raw("menu.item"), ItemInfo.display(hand));
            lore.left(raw("menu.sell-this"));
        }
        return button(p, "hand", "menu.hand-name", lore);
    }

    // ---------------------------------------------------------------- own listings, bids, history

    private List<ListEntry> listings(Player p) {
        List<ListEntry> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Listing l : plugin.market().of(p.getUniqueId())) {
            Lore lore = Lore.create();
            lore.info(state(l, p, now));
            lore.kv(raw("menu.type"), raw(l.auction ? "kind.auction" : "kind.sale"));
            if (l.auction) {
                lore.kv(raw(l.bids.isEmpty() ? "menu.start-price" : "menu.highest-bid"), price(l.currentPrice()));
                lore.kv(raw("menu.bids"), String.valueOf(l.bids.size()));
            } else {
                lore.kv(raw("menu.price"), price(l.currentPrice()) + (l.left > 1 ? raw("menu.for-amount", "%amount%", String.valueOf(l.left)) : ""));
                if (l.left < l.initial()) lore.kv(raw("menu.sold-already"), (l.initial() - l.left) + " / " + l.initial());
            }
            if (l.state == Listing.State.ACTIVE) lore.kv(raw(l.pending(now) ? "menu.online-in" : "menu.ends-in"), Format.duration(l.secondsLeft(now)));
            if (l.reason != null && !l.reason.isBlank()) lore.kv(raw("menu.reason"), l.reason);
            String left, right = null;
            boolean collect = Market.toCollect(l, p.getUniqueId());
            if (collect) {
                lore.left(raw(l.state == Listing.State.DELETED ? "menu.read" : "menu.collect"));
                left = "[hdv] collect " + l.id;
            } else {
                lore.left(raw("menu.see"));
                left = "[hdv] view " + l.id;
                if (l.state == Listing.State.ACTIVE && (!l.auction || l.bids.isEmpty())) {
                    lore.right(raw("menu.cancel"));
                    right = "[hdv] cancel " + l.id;
                }
            }
            int shown = l.state == Listing.State.WON ? l.initial() : Math.max(1, l.left);
            out.add(entry(l.id.toString(), withLore(l.copy(Math.min(99, shown)), lore, p, collect), left, right, null));
        }
        return out;
    }

    private ItemStack listingsInfo(Player p) {
        Lore lore = Lore.create().description(raw("menu.listings-description"));
        lore.kv(raw("menu.slots"), slots(p));
        lore.kv(raw("menu.tax"), Format.percent(plugin.market().taxRate() * 100) + " %");
        return button(p, "listings", "menu.mine-name", lore);
    }

    private ItemStack collectButton(Player p) {
        int n = plugin.market().toCollect(p.getUniqueId());
        Lore lore = Lore.create().description(raw("menu.collect-all-description"));
        if (n > 0) {
            lore.info(raw("menu.to-collect", "%count%", String.valueOf(n)));
            lore.left(raw("menu.collect-all"));
        } else {
            lore.ok(raw("menu.nothing-to-collect"));
        }
        return glow(button(p, n > 0 ? "collect" : "collect-none", "menu.collect-all-name", lore), n > 0);
    }

    private List<ListEntry> bids(Player p) {
        List<ListEntry> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Listing l : plugin.market().bidsOf(p.getUniqueId())) {
            Listing.Bid top = l.highest();
            boolean leading = top != null && top.bidder().equals(p.getUniqueId());
            Lore lore = Lore.create();
            double mine = 0;
            for (Listing.Bid b : l.bids) if (b.bidder().equals(p.getUniqueId())) mine = Math.max(mine, b.amount());
            if (l.state == Listing.State.WON) lore.ok(raw("state.won"));
            else if (leading) lore.ok(raw("menu.leading"));
            else lore.ko(raw("menu.outbid"));
            lore.kv(raw("menu.your-bid"), price(mine));
            lore.kv(raw("menu.highest-bid"), price(l.currentPrice()));
            lore.kv(raw("menu.seller"), l.sellerName);
            if (l.state == Listing.State.ACTIVE) lore.kv(raw("menu.ends-in"), Format.duration(l.secondsLeft(now)));
            String left;
            if (l.state == Listing.State.WON) {
                lore.left(raw("menu.collect"));
                left = "[hdv] collect " + l.id;
            } else {
                lore.left(raw(leading ? "menu.see" : "menu.bid-again"));
                left = "[hdv] view " + l.id;
            }
            out.add(entry(l.id.toString(), withLore(l.copy(l.initial()), lore, p, l.state == Listing.State.WON), left, null, null));
        }
        return out;
    }

    private ItemStack bidsInfo(Player p) {
        Lore lore = Lore.create().description(raw("menu.bids-description"));
        lore.kv(raw("menu.increase"), Format.percent(plugin.getConfig().getDouble("auction.increase-percent", 10)) + " %");
        lore.kv(raw("menu.extra-time"), Format.duration(plugin.getConfig().getLong("auction.extra-seconds", 120)));
        return button(p, "auction", "menu.bids-name", lore);
    }

    private List<ListEntry> history(Player p, int filter) {
        List<ListEntry> out = new ArrayList<>();
        int i = 0;
        for (History.Sale s : plugin.history().of(p.getUniqueId(), Math.floorMod(filter, 3), 500)) {
            boolean sold = p.getUniqueId().equals(s.seller());
            ItemStack item = s.itemStack();
            if (item == null) item = new ItemStack(Material.PAPER);
            item.setAmount(Math.max(1, Math.min(99, s.amount())));
            Lore lore = Lore.create();
            if (sold) lore.ok(raw("history.sold"));
            else lore.ko(raw("history.bought"));
            lore.kv(raw("history.where"), switch (s.source()) {
                case History.SALE -> raw("history.where-sale");
                case History.AUCTION -> raw("history.where-auction");
                case History.SHOP_BUYING -> raw(s.town() != null ? "history.where-buying-town" : "history.where-buying", "%town%", String.valueOf(s.town()));
                default -> raw(s.town() != null ? "history.where-shop-town" : "history.where-shop", "%town%", String.valueOf(s.town()));
            });
            lore.kv(raw("history.amount"), String.valueOf(s.amount()));
            lore.kv(raw("menu.unit-price"), price(s.unit()));
            lore.kv(raw("history.total"), price(s.total()));
            if (sold && s.tax() > 0 && s.auctionHouse()) {
                lore.kv(raw("menu.tax"), price(s.tax()));
                lore.kv(raw("history.received"), price(s.total() - s.tax()));
            }
            String other = sold ? s.buyerName() : s.sellerName();
            lore.kv(raw(sold ? "menu.buyer" : "menu.seller"), other == null ? "?" : other);
            lore.kv(raw("history.date"), Format.date(s.date()) + " (" + Format.ago(s.date()) + ")");
            lore.left(raw("menu.same-item"));
            out.add(entry("h" + i++, withLore(item, lore, p, false), "[hdv] find " + (s.name() == null ? "" : s.name()), null, null));
        }
        return out;
    }

    private ItemStack historyInfo(Player p) {
        Lore lore = Lore.create().description(raw("menu.history-description"));
        lore.kv(raw("menu.entries"), String.valueOf(Lists.entries(p, HISTORY).size()));
        return button(p, "history", "menu.history-name", lore);
    }
}
