package fr.influencraft.fluxshop.shop;

import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.trade.TradeWindow;
import fr.influencraft.fluxshop.managers.LangManager;
import fr.influencraft.fluxshop.managers.PriceManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Buying menu of a shop chest: the trade window of Flux ({@link TradeWindow}) in this plugin's menu
 * {@code menus/shop.yml} (one row per item: the item then its x1, x2, x10, x64 buttons; double click and quick buy
 * handled by Flux). Only items with a buying price are shown; prices are read again on every redraw and click.
 * <p>
 * Every buy is a complete, checked transaction on the main thread: room in the storage slots, then balance, then
 * payment, then the items. Nothing is taken when room is missing, and what could not be given is refunded.
 */
public class ShopMenu {

    public static final String MENU = "shop";

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final PriceManager priceManager;
    private final ShopActivity activity;

    public ShopMenu(JavaPlugin plugin, LangManager lang, PriceManager priceManager, ShopActivity activity) {
        this.plugin = plugin;
        this.lang = lang;
        this.priceManager = priceManager;
        this.activity = activity;
    }

    public void open(Player player, Shop shop) {
        List<TradeWindow.Offer> offers = new ArrayList<>();
        for (ShopEntry entry : shop.getItems()) {
            if (entry.unitPrice(priceManager) > 0) offers.add(offer(shop, entry));
        }
        if (offers.isEmpty() || !TradeWindow.buy(player, MENU, lang.get("shop-menu-title"), offers)) {
            player.sendMessage(lang.get("shop-menu-empty"));
            fr.influencraft.flux.api.util.Sounds.cue(player, "error");
        }
    }

    /** One item of the chest, sold one by one (bundle of 1) at its current price. */
    private TradeWindow.Offer offer(Shop shop, ShopEntry entry) {
        return new TradeWindow.Offer() {
            @Override
            public ItemStack item() {
                return entry.getItem();
            }

            @Override
            public double price() {
                return entry.unitPrice(priceManager);
            }

            @Override
            public boolean buy(Player player, int quantity) {
                return ShopMenu.this.buy(player, shop, entry, quantity);
            }
        };
    }

    // ------------------------------------------------------------------ transactions

    /** @return true when the purchase happened */
    private boolean buy(Player player, Shop shop, ShopEntry entry, int quantity) {
        double unitPrice = entry.unitPrice(priceManager);
        if (unitPrice <= 0) {
            fail(player, lang.get("shop-buy-unavailable"));
            return false;
        }
        ItemStack model = entry.getItem();
        PlayerInventory inventory = player.getInventory();
        int space = PlayerStorage.space(inventory, model);
        if (space < quantity) {
            fail(player, lang.get("shop-buy-no-space", Map.of("amount", String.valueOf(quantity), "space", String.valueOf(space))));
            return false;
        }
        Currency money = Economy.main();
        double total = PriceManager.roundMoney(unitPrice * quantity);
        if (money == null || !money.has(player, total)) {
            fail(player, lang.get("shop-buy-not-enough-money", Map.of("price", money == null ? "?" : fr.influencraft.flux.api.util.Format.decimal(total),
                    "balance", money == null ? "?" : fr.influencraft.flux.api.util.Format.decimal(money.balance(player)))));
            return false;
        }
        if (!money.take(player, total, fr.influencraft.fluxshop.managers.Money.origin("buy").detail(quantity + " " + MenuItems.describe(entry)))) {
            plugin.getLogger().warning("[Shop] Payment refused for " + player.getName() + " (" + total + ")");
            fail(player, lang.get("shop-transaction-failed"));
            return false;
        }
        int notGiven = PlayerStorage.add(inventory, model, quantity);
        if (notGiven > 0) {
            // cannot happen (room checked on the same tick): the player only pays what he received
            double refund = PriceManager.roundMoney(unitPrice * notGiven);
            money.give(player, refund, fr.influencraft.fluxshop.managers.Money.origin("refund"));
            plugin.getLogger().warning("[Shop] " + notGiven + " " + MenuItems.describe(entry) + " not stored for "
                    + player.getName() + ", " + refund + " refunded.");
            quantity -= notGiven;
            total = PriceManager.roundMoney(total - refund);
        }
        activity.markBought(player.getUniqueId());
        plugin.getLogger().info("[Shop] " + player.getName() + " bought " + quantity + " " + MenuItems.describe(entry)
                + " for " + total + " (" + shop.getId() + ")");
        MenuItems.send(player, lang.get("shop-buy-success", Map.of("amount", String.valueOf(quantity), "price", fr.influencraft.flux.api.util.Format.decimal(total))), model);
        fr.influencraft.flux.api.util.Sounds.cue(player, "success");
        return true;
    }

    private void fail(Player player, String message) {
        player.sendMessage(message);
        fr.influencraft.flux.api.util.Sounds.cue(player, "error");
    }
}
