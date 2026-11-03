package fr.influencraft.fluxcommerce.chestshop;

import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.inventory.Inventories;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.shops.ChestShopPurchaseEvent;
import fr.influencraft.flux.api.shops.ChestShopTradeEvent;
import fr.influencraft.flux.api.shops.ChestShops;
import fr.influencraft.flux.api.trade.TradeWindow;
import fr.influencraft.flux.api.util.Sounds;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Trading with a shop, in Flux's trade window ({@link TradeWindow}): a selling shop opens the buying window (x1, x2,
 * x10, x64 bundles), a buying shop the selling window (deposit zone). Each trade is checked and done on the main
 * thread in one go: stock or room read from the container, room or items of the customer, money of the payer,
 * {@link ChestShopPurchaseEvent} (FluxLands may refuse it), then money and items move; {@link ChestShopTradeEvent}
 * after (market fee of the town, market history).
 */
final class Trades {

    private final ChestShopModule plugin;

    Trades(ChestShopModule plugin) {
        this.plugin = plugin;
    }

    /** Opens the window of this shop for a customer. */
    void open(Player player, Shop shop) {
        if (shop.price <= 0 || plugin.host().bans().refused(player)) return;
        plugin.registry().count(shop);
        String title = plugin.messages().raw(shop.buying ? "trade.title-buying" : "trade.title-selling", "%seller%", seller(shop));
        boolean opened = shop.buying ? TradeWindow.sell(player, title, purchase(shop)) : TradeWindow.buy(player, title, offer(shop));
        if (opened) Sounds.cue(player, "page");
    }

    private TradeWindow.Offer offer(Shop shop) {
        return new TradeWindow.Offer() {
            @Override
            public ItemStack item() {
                return shop.item;
            }

            @Override
            public int bundle() {
                return shop.bundle;
            }

            @Override
            public double price() {
                return shop.price;
            }

            @Override
            public int available() {
                int stock = plugin.registry().count(shop);
                return stock == Integer.MAX_VALUE || stock < 0 ? stock : stock / shop.bundle;
            }

            @Override
            public void describe(Player viewer, Lore lore) {
                lore.kv(plugin.messages().raw("trade.seller"), seller(shop));
            }

            @Override
            public boolean buy(Player player, int bundles) {
                return Trades.this.buy(player, shop, bundles);
            }
        };
    }

    private TradeWindow.Purchase purchase(Shop shop) {
        return new TradeWindow.Purchase() {
            @Override
            public ItemStack item() {
                return shop.item;
            }

            @Override
            public int bundle() {
                return shop.bundle;
            }

            @Override
            public double price() {
                return shop.price;
            }

            @Override
            public int room() {
                int room = plugin.registry().count(shop);
                return room == Integer.MAX_VALUE || room < 0 ? room : room / shop.bundle;
            }

            @Override
            public boolean matches(ItemStack item) {
                return shop.matches(item);
            }

            @Override
            public void describe(Player viewer, Lore lore) {
                lore.kv(plugin.messages().raw("trade.buyer"), seller(shop));
            }

            @Override
            public boolean sell(Player player, Inventory from, int bundles) {
                return Trades.this.sell(player, shop, from, bundles);
            }
        };
    }

    /** Seller shown: the name given by another plugin (town of a town shop), else the owner's. */
    String seller(Shop shop) {
        if (shop.unlimited) return plugin.messages().raw("display.server-name");
        Component name = ChestShops.sellerName(shop.view());
        if (name != null) return fr.influencraft.flux.api.text.Texts.ampersand(name);
        return shop.ownerName == null ? "?" : shop.ownerName;
    }

    // ---------------------------------------------------------------- buying from a selling shop

    boolean buy(Player player, Shop shop, int bundles) {
        if (!usable(player, shop) || shop.buying || bundles <= 0) return false;
        int items = bundles * shop.bundle;
        Inventory chest = shop.unlimited ? null : Containers.inventory(shop.block());
        if (!shop.unlimited) {
            if (chest == null) return fail(player, "trade.gone");
            int stock = Containers.count(chest, shop);
            if (stock < items) return fail(player, "trade.no-stock", "%stock%", String.valueOf(stock));
        }
        List<ItemStack> goods = Containers.stacks(shop.item, items);
        if (!Inventories.fits(player, goods)) return fail(player, "trade.no-room");
        Currency money = Economy.main();
        double total = Economy.round(shop.price * bundles);
        if (money == null) return fail(player, "trade.failed");
        if (!money.has(player, total)) return fail(player, "trade.no-money", "%price%", money.format(total));
        ChestShopPurchaseEvent event = new ChestShopPurchaseEvent(shop.view(), player.getUniqueId(), total);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            if (event.reason() != null) player.sendMessage(event.reason());
            Sounds.cue(player, "error");
            return false;
        }
        String detail = items + " " + plugin.itemName(shop.item);
        if (!money.take(player, total, plugin.origin("buy").detail(detail))) return fail(player, "trade.failed");
        OfflinePlayer owner = shop.unlimited || shop.owner == null ? null : Bukkit.getOfflinePlayer(shop.owner);
        if (owner != null && !money.give(owner, total, plugin.origin("sale").detail(detail))) {
            money.give(player, total, plugin.origin("refund"));
            plugin.getLogger().warning("Shop " + shop.id + ": payment of " + shop.ownerName + " refused, " + player.getName() + " refunded");
            return fail(player, "trade.failed");
        }
        if (chest != null) Containers.take(chest, shop, items);
        Inventories.giveOrDrop(player, goods);
        done(player, shop, items, total, false);
        return true;
    }

    // ---------------------------------------------------------------- selling to a buying shop

    boolean sell(Player player, Shop shop, Inventory from, int bundles) {
        if (!usable(player, shop) || !shop.buying || bundles <= 0) return false;
        int items = bundles * shop.bundle;
        if (Containers.count(from, shop) < items) return fail(player, "trade.sell-missing");
        Inventory chest = shop.unlimited ? null : Containers.inventory(shop.block());
        if (!shop.unlimited) {
            if (chest == null) return fail(player, "trade.gone");
            if (Containers.room(chest, shop) < items) return fail(player, "trade.shop-full");
        }
        Currency money = Economy.main();
        double total = Economy.round(shop.price * bundles);
        if (money == null) return fail(player, "trade.failed");
        OfflinePlayer owner = shop.unlimited || shop.owner == null ? null : Bukkit.getOfflinePlayer(shop.owner);
        if (owner != null && !money.has(owner, total)) return fail(player, "trade.owner-no-money");
        ChestShopPurchaseEvent event = new ChestShopPurchaseEvent(shop.view(), player.getUniqueId(), total);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            if (event.reason() != null) player.sendMessage(event.reason());
            Sounds.cue(player, "error");
            return false;
        }
        String detail = items + " " + plugin.itemName(shop.item);
        if (owner != null && !money.take(owner, total, plugin.origin("purchase").detail(detail))) {
            return fail(player, "trade.owner-no-money");
        }
        if (!money.give(player, total, plugin.origin("sold").detail(detail))) {
            if (owner != null) money.give(owner, total, plugin.origin("refund"));
            return fail(player, "trade.failed");
        }
        Containers.take(from, shop, items);
        if (chest != null) {
            List<ItemStack> left = Containers.add(chest, Containers.stacks(shop.item, items));
            // cannot happen (room counted on the same tick): kept by the seller rather than lost
            if (!left.isEmpty()) Inventories.giveOrDrop(player, left);
        }
        done(player, shop, items, total, true);
        return true;
    }

    // ---------------------------------------------------------------- shared

    /** The shop still exists, is loaded and the customer is not its owner. */
    private boolean usable(Player player, Shop shop) {
        if (plugin.registry().get(shop.id) != shop || !shop.loaded()) {
            fail(player, "trade.gone");
            TradeWindow.close(player);
            return false;
        }
        if (shop.isOwner(player.getUniqueId()) && !shop.unlimited) return fail(player, "trade.own-shop");
        if (plugin.host().bans().refused(player)) {
            TradeWindow.close(player);
            return false;
        }
        // a rule added after the shop was made (forbidden items of all commerce)
        if (plugin.host().rules().forbidden(shop.item)) return fail(player, "trade.item-refused");
        return true;
    }

    private void done(Player player, Shop shop, int items, double total, boolean sold) {
        String price = plugin.price(total);
        plugin.send(player, sold ? "trade.sold" : "trade.bought", shop.item, "%amount%", String.valueOf(items),
                "%price%", price, "%seller%", seller(shop));
        Sounds.cue(player, "success");
        Player owner = shop.owner == null || shop.unlimited ? null : Bukkit.getPlayer(shop.owner);
        if (owner != null && plugin.getConfig().getBoolean("notify-owner", true)) {
            plugin.send(owner, sold ? "trade.owner-bought" : "trade.owner-sold", shop.item, "%amount%", String.valueOf(items),
                    "%price%", price, "%player%", player.getName());
        }
        plugin.getLogger().info(player.getName() + (sold ? " sold " : " bought ") + items + " "
                + shop.item.getType().getKey() + " for " + total + (sold ? " to" : " from") + " shop " + shop.id
                + " (" + (shop.unlimited ? "server" : shop.ownerName) + ")");
        plugin.registry().count(shop);
        plugin.displays().update(shop);
        Bukkit.getPluginManager().callEvent(new ChestShopTradeEvent(shop.view(), player.getUniqueId(), player.getName(), items, total, 0));
    }

    private boolean fail(Player player, String key, String... vars) {
        plugin.messages().send(player, key, vars);
        Sounds.cue(player, "error");
        return false;
    }
}
