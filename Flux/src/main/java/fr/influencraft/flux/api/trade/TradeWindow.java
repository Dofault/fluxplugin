package fr.influencraft.flux.api.trade;

import fr.influencraft.flux.api.inventory.Lore;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * The window a player buys or sells a given item in, the same for every plugin of the suite (server shop chests of
 * FluxShop, players' chest shops of FluxCommerce...). Flux draws it and handles the clicks; the plugin only says what
 * is offered and does the trade itself (stock, money, messages).
 * <ul>
 *   <li><b>Buying</b> ({@link #buy}): one row per {@link Offer}, the item then x1, x2, x10, x64 bundles. A first click
 *       arms a button, a second one buys; right after a purchase the same button buys straight away for a short time
 *       ({@code trade.quick-buy-seconds} of Flux's config). Menu {@code trade} (one offer) or the plugin's own menu
 *       placing the lists {@code trade_item}, {@code trade_buy_1}, {@code trade_buy_2}, {@code trade_buy_10},
 *       {@code trade_buy_64} (row N of every list = offer N, clicks {@code [trade] buy %entry_id% <bundles>}).</li>
 *   <li><b>Selling</b> ({@link #sell}): menu {@code trade_sell}, a deposit zone where the player puts what he sells
 *       and a confirm button (two clicks) showing the quote. Unsold items go back to the player.</li>
 * </ul>
 * Texts: {@code trade.*} of Flux's lang files; button icons: {@code trade} section of Flux's config.
 */
public final class TradeWindow {

    public static final String BUY_MENU = "trade";
    public static final String SELL_MENU = "trade_sell";
    /** Bundles of the buying buttons, in order. */
    public static final List<Integer> QUANTITIES = List.of(1, 2, 10, 64);

    /** An item a player can buy. Methods are called on the main thread, again on every redraw. */
    public interface Offer {
        /** The item as shown, its own name and lore kept (amount ignored). */
        ItemStack item();

        /** Items in one bundle (what x1 gives). */
        default int bundle() {
            return 1;
        }

        /** Price of one bundle; 0 or less hides the row. */
        double price();

        /** Flux currency the price is in ({@code economy.Economy.get}), null for the server's money. */
        default String currency() {
            return null;
        }

        /** Bundles that can be bought right now: -1 unknown, {@link Integer#MAX_VALUE} unlimited. */
        default int available() {
            return Integer.MAX_VALUE;
        }

        /** Lines added under the price (seller, place...). */
        default void describe(Player viewer, Lore lore) {
        }

        /** Buys {@code bundles} bundles with every check of the plugin (room, money...); its own messages. */
        boolean buy(Player player, int bundles);
    }

    /** An item someone buys from players (selling window). */
    public interface Purchase {
        ItemStack item();

        default int bundle() {
            return 1;
        }

        /** Money paid for one bundle. */
        double price();

        /** Bundles it can still take: -1 unknown, {@link Integer#MAX_VALUE} unlimited. */
        default int room() {
            return Integer.MAX_VALUE;
        }

        /** Is this item the one bought. */
        boolean matches(ItemStack item);

        default void describe(Player viewer, Lore lore) {
        }

        /**
         * Sells {@code bundles} bundles, the items being taken from {@code from} (a scratch inventory holding the deposit
         * zone); what stays in it goes back to the player. Every check of the plugin; its own messages.
         */
        boolean sell(Player player, Inventory from, int bundles);
    }

    /** Called by Flux. */
    public interface Backend {
        boolean buy(Player player, String menu, String title, List<? extends Offer> offers);

        boolean sell(Player player, String title, Purchase purchase);

        void close(Player player);
    }

    private static volatile Backend backend;

    private TradeWindow() {
    }

    /** Called by Flux. */
    public static void bind(Backend b) {
        backend = b;
    }

    /**
     * Opens a buying window.
     *
     * @param menu  menu placing the trade lists, null for Flux's {@code trade} (one offer)
     * @param title shown as {@code %flux_trade_title%} in the menu title, null for the default one
     * @return false when no offer has a price, or the menu could not open
     */
    public static boolean buy(Player player, String menu, String title, List<? extends Offer> offers) {
        Backend b = backend;
        return b != null && b.buy(player, menu == null ? BUY_MENU : menu, title, offers);
    }

    public static boolean buy(Player player, String title, Offer offer) {
        return buy(player, null, title, List.of(offer));
    }

    /** Opens the selling window of {@code purchase}. */
    public static boolean sell(Player player, String title, Purchase purchase) {
        Backend b = backend;
        return b != null && b.sell(player, title, purchase);
    }

    /** Forgets the trade of this player (the thing traded is gone): the next click does nothing. */
    public static void close(Player player) {
        Backend b = backend;
        if (b != null) b.close(player);
    }
}
