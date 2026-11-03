package fr.influencraft.fluxcommerce.auctionhouse;

import fr.influencraft.flux.api.shops.ChestShops;
import fr.influencraft.flux.api.text.ChatHelp;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.PlayerSettings;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxcommerce.FluxCommerce;
import fr.influencraft.fluxcommerce.Module;
import fr.influencraft.fluxcommerce.api.Listings;
import fr.influencraft.fluxcommerce.compat.LegacyKeepers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Auction house of FluxCommerce: the market of the server, written for the suite (it replaces the AuctionHouse plugin).
 * The auction house (direct sales, part of a lot, auctions with a late-bid extension) and the chest shops of the
 * players (chest shops of FluxCommerce, or any provider of Flux's {@code ChestShops}) are listed together with one search; every sale feeds a history and a market
 * price ({@code /hdv history}, {@code /hdv average}). Data in Flux's database ({@link Store}, {@link History}).
 *
 * <pre>
 *   /hdv [help] | sell [price] [amount] | bid &lt;price&gt; [amount] | search &lt;text&gt; | history | average | listings | bids
 *        | collect | view &lt;id&gt; | announcements | admin ...
 *   /influmarche from &lt;back command, + for spaces&gt; &lt;source&gt; [search]   (menus of other plugins)
 * </pre>
 */
public final class AuctionHouseModule extends Module implements Listener {

    static final String USE = "fluxcommerce.ah.use", ADMIN = "fluxcommerce.ah.admin";
    private static final String SEEN = "auctionhouse.seen", ANNOUNCEMENTS = "auctionhouse.announcements";

    private final Store store = new Store(getLogger());
    private final Market market = new Market(this, store);
    private final Catalog catalog = new Catalog(this);
    private final Menus menus = new Menus(this);
    private final Forms forms = new Forms(this);
    private History history;
    private ChestShopOffers shops;

    public AuctionHouseModule(FluxCommerce host) {
        super(host, "auctionhouse", "ah");
    }

    @Override
    protected void enable() {
        try {
            store.open();
            history = new History(this);
            market.load();
        } catch (SQLException e) {
            throw new IllegalStateException("database of Flux unavailable: " + e.getMessage(), e);
        }
        labels();
        menus.register();
        if (ChestShops.available()) {
            shops = new ChestShopOffers(this);
            Bukkit.getPluginManager().registerEvents(shops, host);
            getLogger().info("Chest shops (" + ChestShops.providerName() + ") join the market.");
        }
        Bukkit.getPluginManager().registerEvents(this, host);
        LegacyKeepers legacy = new LegacyKeepers(host);
        Bukkit.getPluginManager().registerEvents(legacy, host);
        Bukkit.getScheduler().runTask(host, legacy::scanLoaded);
        command("market")
                .description("Market and auction house.")
                .aliases("marche", "market", "ah", "auctionhouse")
                .executor(this::onCommand)
                .completer(this::complete)
                .register();
        // menus of other plugins (FluxLands): /influmarche from <back> <source> [search]
        command("fluxauctionhouse")
                .description("Market opened by another plugin's menu.")
                .aliases("fluxauctionhouse")
                .executor(this::internal)
                .register();
        placeholders("influmarche", (player, key) -> player == null ? "" : switch (key) {
            case "active" -> String.valueOf(market.used(player.getUniqueId()));
            case "collect" -> String.valueOf(market.toCollect(player.getUniqueId()));
            case "slots" -> player instanceof Player p ? slotsText(p) : "";
            default -> null;
        });
        Listings.bind(new Imports(this));
        Bukkit.getScheduler().runTaskTimer(host, market::tick, 40L, 20L);
        getLogger().info(market.all().size() + " listing(s) loaded.");
    }

    @Override
    protected void disable() {
        Listings.bind(null);
        for (Player p : Bukkit.getOnlinePlayers()) PlayerSettings.set(p, SEEN, System.currentTimeMillis());
        store.close();
        if (history != null) history.close();
    }

    @Override
    public void reload() {
        super.reload();
        labels();
        catalog.invalidate();
        menus.invalidate();
    }

    private void labels() {
        ItemInfo.labels(c -> messages().raw("categories." + c.name().toLowerCase(Locale.ROOT)));
    }

    // ---------------------------------------------------------------- accessors

    Store store() {
        return store;
    }

    Market market() {
        return market;
    }

    Catalog catalog() {
        return catalog;
    }

    Menus menus() {
        return menus;
    }

    Forms forms() {
        return forms;
    }

    History history() {
        return history;
    }

    ChestShopOffers shops() {
        return shops;
    }

    int days() {
        return Math.max(1, getConfig().getInt("average.days", 30));
    }

    private String slotsText(Player p) {
        int max = market.slots(p);
        return market.used(p.getUniqueId()) + "/" + (max == Integer.MAX_VALUE ? "∞" : max);
    }

    // ---------------------------------------------------------------- texts

    /** A message of the lang file whose {@code %item%} is the item's name in the player's language (tooltip on hover). */
    void send(Player to, String key, ItemStack item, String... vars) {
        String prefix = messages().raw("prefix");
        for (String line : messages().rawList(key, vars)) {
            if (line.isEmpty()) continue;
            Component c = Texts.parseClickable(line.startsWith("!") ? line.substring(1) : prefix + line, to);
            if (item != null) c = c.replaceText(TextReplacementConfig.builder().matchLiteral("%item%").replacement(ItemInfo.component(item)).build());
            to.sendMessage(c);
        }
    }

    /**
     * Market price lines of an item, as {key, value} ({@code ""} key: a plain line): average unit price over the
     * period, number of sales, lowest and highest, auction house and shops apart, cheapest offer and best buyer now.
     */
    List<String[]> averageLines(ItemStack item, String except) {
        String key = ItemInfo.key(item);
        List<String[]> out = new ArrayList<>();
        History.Average m = history.average(key, days());
        String days = String.valueOf(days());
        if (m == null) {
            out.add(new String[]{"", messages().raw("average.none", "%days%", days)});
        } else {
            out.add(new String[]{messages().raw("average.unit"), money().format(m.unit())});
            out.add(new String[]{messages().raw("average.sales"), messages().raw("average.sales-value", "%sales%", String.valueOf(m.sales()),
                    "%amount%", String.valueOf(m.amount()), "%days%", days)});
            out.add(new String[]{messages().raw("average.range"), messages().raw("average.range-value", "%min%", money().format(m.min()),
                    "%max%", money().format(m.max()))});
            if (m.auctionHouse() >= 0) out.add(new String[]{messages().raw("average.auction-house"), money().format(m.auctionHouse())});
            if (m.shops() >= 0) out.add(new String[]{messages().raw("average.shops"), money().format(m.shops())});
        }
        List<Offer> cheapest = catalog.sameItem(key, except, false, 1);
        if (!cheapest.isEmpty()) out.add(new String[]{messages().raw("average.cheapest"), money().format(cheapest.getFirst().unitPrice())});
        List<Offer> buyer = catalog.sameItem(key, except, true, 1);
        if (!buyer.isEmpty()) out.add(new String[]{messages().raw("average.best-buyer"), money().format(buyer.getFirst().unitPrice())});
        return out;
    }

    /** {@code /hdv average}: market price of the item in hand, in the chat. */
    void averageInChat(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            messages().send(p, "hold-item");
            return;
        }
        send(p, "average.header", hand);
        for (String[] kv : averageLines(hand, null)) {
            if (kv[0].isEmpty()) messages().send(p, "average.plain", "%text%", kv[1]);
            else messages().send(p, "average.line", "%key%", kv[0], "%value%", kv[1]);
        }
        messages().send(p, "average.footer");
    }

    // ---------------------------------------------------------------- join and quit

    @EventHandler
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(host, () -> {
            if (!p.isOnline()) return;
            long since;
            try {
                since = Long.parseLong(PlayerSettings.get(p, SEEN, "0"));
            } catch (NumberFormatException ex) {
                since = 0;
            }
            PlayerSettings.set(p, SEEN, System.currentTimeMillis());
            if (since > 0) {
                long from = since;
                Bukkit.getScheduler().runTaskAsynchronously(host, () -> {
                    List<History.Sale> sold = history.soldSince(p.getUniqueId(), from);
                    if (sold.isEmpty()) return;
                    double total = 0;
                    for (History.Sale s : sold) total += s.total() - s.tax();
                    double net = total;
                    Bukkit.getScheduler().runTask(host, () -> {
                        if (p.isOnline()) messages().send(p, "away-sales", "%count%", String.valueOf(sold.size()), "%price%", money().format(net));
                    });
                });
            }
            if (market.autoDeliver()) market.collectAll(p, true);
            int left = market.toCollect(p.getUniqueId());
            if (left > 0) {
                messages().send(p, "to-collect", "%count%", String.valueOf(left));
                Sounds.cue(p, "alert");
            }
        }, 60L);
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        PlayerSettings.set(e.getPlayer(), SEEN, System.currentTimeMillis());
        menus.forget(e.getPlayer());
    }

    // ---------------------------------------------------------------- commands

    private static final List<String> SUBCOMMANDS = List.of("help", "sell", "bid", "search", "history", "average", "listings", "bids",
            "collect", "announcements", "admin");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("help", "player", "info", "remove", "delete", "ban", "pardon", "bans",
            "blacklist", "about", "reload");
    private static final List<ChatHelp.Entry> HELP = List.of(
            new ChatHelp.Entry("", "menu", null),
            new ChatHelp.Entry("search <objet>", "search", null),
            new ChatHelp.Entry("sell", "sell", null),
            new ChatHelp.Entry("sell <prix> [quantité]", "sell-price", null),
            new ChatHelp.Entry("bid <prix> [quantité]", "bid", null),
            new ChatHelp.Entry("listings", "listings", null),
            new ChatHelp.Entry("bids", "bids", null),
            new ChatHelp.Entry("collect", "collect", null),
            new ChatHelp.Entry("history", "history", null),
            new ChatHelp.Entry("average", "average", null),
            new ChatHelp.Entry("announcements", "announcements", null),
            new ChatHelp.Entry("help [page]", "help", null),
            new ChatHelp.Entry("admin help", "admin", ADMIN));
    private static final List<ChatHelp.Entry> ADMIN_HELP = List.of(
            new ChatHelp.Entry("admin player <joueur>", "admin-player", ADMIN),
            new ChatHelp.Entry("admin info <id>", "admin-info", ADMIN),
            new ChatHelp.Entry("admin remove <id> <motif>", "admin-remove", ADMIN),
            new ChatHelp.Entry("admin delete <id> <motif>", "admin-delete", ADMIN),
            // shared with all commerce (FluxCommerce.shared, also /commerce)
            new ChatHelp.Entry("admin ban <joueur> <durée> <motif>", "admin-ban", FluxCommerce.STAFF),
            new ChatHelp.Entry("admin pardon <joueur>", "admin-pardon", FluxCommerce.STAFF),
            new ChatHelp.Entry("admin bans", "admin-bans", FluxCommerce.STAFF),
            new ChatHelp.Entry("admin blacklist [add|remove <n>]", "admin-blacklist", FluxCommerce.ADMIN),
            new ChatHelp.Entry("admin about", "admin-about", ADMIN),
            new ChatHelp.Entry("admin reload", "admin-reload", ADMIN),
            new ChatHelp.Entry("admin help [page]", "admin-help", ADMIN));

    private void help(CommandSender sender, String label, String[] args, boolean admin, int pageArg) {
        int page = 1;
        if (args.length > pageArg) {
            try {
                page = Integer.parseInt(args[pageArg]);
            } catch (NumberFormatException ignored) {
                // first page
            }
        }
        ChatHelp.show(sender, messages(), label, admin ? ADMIN_HELP : HELP, admin ? "admin help" : "help", admin, page, sender::hasPermission);
    }

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("admin")) return admin(sender, label, args);
        if (sub.equals("help") || sub.equals("aide")) {
            help(sender, label, args, false, 1);
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only (console: /" + label + " admin reload).");
            return true;
        }
        if (!p.hasPermission(USE)) {
            messages().send(p, "no-permission");
            return true;
        }
        switch (sub) {
            case "", "menu" -> menus.openMarket(p, true);
            case "search", "chercher", "recherche" -> {
                if (args.length < 2) {
                    menus.openMarket(p, true);
                    forms.search(p);
                } else {
                    menus.openMarket(p, true);
                    menus.search(p, String.join(" ", Arrays.copyOfRange(args, 1, args.length)), Catalog.ALL);
                }
            }
            case "sell", "vendre", "bid", "encherir", "auction" -> quickSell(p, !(sub.equals("sell") || sub.equals("vendre")), args);
            case "history", "historique" -> menus.open(p, "history");
            case "average", "moyenne", "prix", "price" -> averageInChat(p);
            case "listings", "ventes", "mesventes" -> menus.open(p, "listings");
            case "bids", "encheres" -> menus.open(p, "bids");
            case "collect", "recuperer" -> {
                if (market.collectAll(p, false) == 0 && market.toCollect(p.getUniqueId()) == 0) messages().send(p, "nothing-to-collect");
            }
            case "view" -> {
                Listing l = args.length > 1 ? market.get(args[1]) : null;
                if (l == null) messages().send(p, "gone");
                else if (Market.toCollect(l, p.getUniqueId()) || l.ownedBy(p.getUniqueId()) && l.state != Listing.State.ACTIVE) menus.open(p, "listings");
                else menus.openListing(p, l, "marche");
            }
            case "announcements", "annonces" -> {
                boolean on = !PlayerSettings.flag(p, ANNOUNCEMENTS, true);
                PlayerSettings.set(p, ANNOUNCEMENTS, on);
                messages().send(p, on ? "announcements-on" : "announcements-off");
                Sounds.cue(p, on ? "toggle-on" : "toggle-off");
            }
            default -> messages().send(p, "usage", "%cmd%", label);
        }
        return true;
    }

    /** {@code /hdv sell}: the window for the item in hand; {@code /hdv sell|bid <price> [amount]}: listed at once. */
    private void quickSell(Player p, boolean auction, String[] args) {
        int slot = p.getInventory().getHeldItemSlot();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (args.length < 2) {
            forms.sell(p, slot, false);
            return;
        }
        if (hand.getType().isAir()) {
            messages().send(p, "hold-item");
            return;
        }
        long price = Format.parseAmount(args[1]);
        if (price <= 0) {
            messages().send(p, "bad-price");
            return;
        }
        int amount = hand.getAmount();
        if (args.length > 2) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                amount = -1;
            }
        }
        market.sell(p, slot, hand.clone(), amount, price, auction);
    }

    private boolean admin(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            messages().send(sender, "no-permission");
            return true;
        }
        String sub = args.length < 2 ? "help" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                host.reload();
                messages().send(sender, "reloaded");
            }
            case "about" -> messages().send(sender, "about", "%version%", host.getPluginMeta().getVersion(),
                    "%authors%", String.join(", ", host.getPluginMeta().getAuthors()), "%listings%", String.valueOf(market.all().size()));
            case "player" -> {
                if (args.length < 3 || !(sender instanceof Player p)) {
                    help(sender, label, new String[0], true, 2);
                    return true;
                }
                menus.openMarket(p, true);
                menus.search(p, args[2], Catalog.AUCTION_HOUSE);
            }
            case "info" -> info(sender, args.length > 2 ? market.get(args[2]) : null);
            case "remove", "delete" -> {
                Listing l = args.length > 2 ? market.get(args[2]) : null;
                if (l == null || l.state != Listing.State.ACTIVE) {
                    messages().send(sender, "gone");
                    return true;
                }
                String reason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "";
                if (reason.isBlank()) {
                    messages().send(sender, "reason-needed");
                    return true;
                }
                boolean destroy = sub.equals("delete");
                market.staffRemove(sender, l, reason, destroy);
                messages().send(sender, destroy ? "staff-deleted-done" : "staff-removed-done", "%player%", l.sellerName);
            }
            case "ban", "pardon", "bans", "blacklist" -> host.shared(sender, label, args, 1);
            default -> help(sender, label, args, true, 2);
        }
        return true;
    }

    private void info(CommandSender sender, Listing l) {
        if (l == null) {
            messages().send(sender, "gone");
            return;
        }
        long now = System.currentTimeMillis();
        Listing.Bid top = l.highest();
        messages().send(sender, "info", "%id%", l.id.toString(), "%seller%", l.sellerName, "%type%", messages().raw(l.auction ? "kind.auction" : "kind.sale"),
                "%amount%", l.left + "/" + l.initial(), "%item%", l.item.getType().getKey().toString(), "%price%", money().format(l.currentPrice()),
                "%state%", l.state.name(), "%time%", Format.duration(l.secondsLeft(now)), "%bids%", String.valueOf(l.bids.size()),
                "%leader%", top == null ? "-" : top.name(), "%reason%", l.reason == null ? "-" : l.reason);
    }

    /** {@code /influmarche from|depuis <back command, + for spaces> <source> [search]}: opened by another plugin's menu. */
    private boolean internal(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (args.length >= 3 && (args[0].equalsIgnoreCase("from") || args[0].equalsIgnoreCase("depuis"))) {
            int source;
            try {
                source = Math.floorMod(Integer.parseInt(args[2]), Catalog.SOURCES.size());
            } catch (NumberFormatException e) {
                source = 0;
            }
            menus.openFrom(p, args[1].replace('+', ' '), source, args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "");
        } else {
            menus.openMarket(p, true);
        }
        return true;
    }

    private List<String> complete(CommandSender sender, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(SUBCOMMANDS);
            if (!sender.hasPermission(ADMIN)) options.remove("admin");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin") && sender.hasPermission(ADMIN)) {
            options.addAll(ADMIN_SUBCOMMANDS);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("admin") && sender.hasPermission(ADMIN)) {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "player" -> options.addAll(Players.complete(args[2]));
                case "ban", "pardon", "blacklist" -> options.addAll(host.completeShared(sender, args, 1));
                case "info", "remove", "delete" -> {
                    for (Listing l : market.all()) if (l.state == Listing.State.ACTIVE) options.add(l.id.toString());
                }
                default -> {
                }
            }
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}
