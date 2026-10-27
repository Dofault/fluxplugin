package fr.influencraft.fluxshop.shop;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.ListProvider;
import fr.influencraft.flux.api.list.ListQuery;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.fluxshop.managers.LangManager;
import fr.influencraft.fluxshop.managers.PriceManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buying menu of a shop chest, a Flux menu ({@code menus/shop.yml}, player inventory visible): one row per item, the
 * item then its x1, x2, x10, x64 buttons. Each row is drawn by lists sharing the same order: {@code shop_item},
 * {@code shop_buy_1}, {@code shop_buy_2}, {@code shop_buy_10}, {@code shop_buy_64}. Clicks:
 * {@code [shop] buy <row> <quantity>}.
 * <p>
 * Every buy is a complete, checked transaction on the main thread: room in the storage slots, then balance, then
 * payment, then the items. Nothing is taken when room is missing, and what could not be given is refunded.
 * <p>
 * Double confirmation: a first click arms the button (orange pane), a second click on the same button buys and turns
 * it green again. An armed button disarms itself after a delay, or when another one is armed. Right after a buy, the
 * same button buys straight away for a short time ({@code shop-menu.quick-buy-seconds}, restarted on every buy), so
 * one can buy many in a row. Prices are read again on every click.
 */
public class ShopMenu {

    public static final String MENU = "shop";
    static final int[] QUANTITIES = {1, 2, 10, 64};
    static final List<String> LISTS = List.of("shop_item", "shop_buy_1", "shop_buy_2", "shop_buy_10", "shop_buy_64");

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final PriceManager priceManager;
    private final ShopActivity activity;
    private final MenuItems menuItems;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    /** What a player has open: the shop, its items frozen at opening (row N is always the same item), armed button. */
    private static final class State {
        final Shop shop;
        final List<ShopEntry> items;
        String armed;
        BukkitTask disarmTask;
        String quick;
        long quickUntil;

        State(Shop shop, List<ShopEntry> items) {
            this.shop = shop;
            this.items = items;
        }
    }

    public ShopMenu(JavaPlugin plugin, LangManager lang, PriceManager priceManager, ShopActivity activity) {
        this.plugin = plugin;
        this.lang = lang;
        this.priceManager = priceManager;
        this.activity = activity;
        this.menuItems = new MenuItems(plugin, lang);
    }

    /** Registers the lists of the menu. */
    public void register() {
        Lists.register(plugin, "shop_item", provider((p, s, i, e) -> displayItem(p, e)));
        for (int q : QUANTITIES) {
            Lists.register(plugin, "shop_buy_" + q, provider((p, s, i, e) -> {
                double buy = e.unitPrice(priceManager);
                return buy <= 0 ? null : button(q, buy * q, (i + ":" + q).equals(s.armed));
            }));
        }
    }

    @FunctionalInterface
    private interface Icon {
        ItemStack build(Player player, State state, int row, ShopEntry entry);
    }

    private ListProvider provider(Icon icon) {
        return query -> {
            State state = states.get(query.viewer().getUniqueId());
            List<ListEntry> out = new ArrayList<>();
            if (state == null) return out;
            for (int row = 0; row < state.items.size(); row++) {
                ItemStack item = icon.build(query.viewer(), state, row, state.items.get(row));
                out.add(ListEntry.of(String.valueOf(row)).item(item == null ? new ItemStack(Material.AIR) : item));
            }
            return out;
        };
    }

    public void open(Player player, Shop shop) {
        List<ShopEntry> items = new ArrayList<>();
        for (ShopEntry entry : shop.getItems()) {
            if (entry.unitPrice(priceManager) > 0) items.add(entry);
        }
        if (items.isEmpty()) {
            player.sendMessage(lang.get("shop-menu-empty"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        State previous = states.put(player.getUniqueId(), new State(shop, items));
        if (previous != null && previous.disarmTask != null) previous.disarmTask.cancel();
        invalidate(player);
        if (FluxApi.open(player, MENU)) player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
    }

    public void forget(UUID uuid) {
        State s = states.remove(uuid);
        if (s != null && s.disarmTask != null) s.disarmTask.cancel();
    }

    private void invalidate(Player player) {
        for (String list : LISTS) Lists.invalidate(player, list);
    }

    private ItemStack displayItem(Player player, ShopEntry entry) {
        double buy = entry.unitPrice(priceManager);
        return MenuItems.withExtraLore(entry, lang.getList("shop-menu-item-lore", Map.of(
                "buy", buy > 0 ? menuItems.price(buy) : lang.get("shop-menu-unavailable"),
                "owned", String.valueOf(PlayerStorage.count(player.getInventory(), entry.getItem())))));
    }

    private ItemStack button(int quantity, double total, boolean armed) {
        ItemStack stack = armed
                ? menuItems.icon("buy-button-armed", Material.ORANGE_STAINED_GLASS_PANE, 0, quantity)
                : menuItems.icon("buy-button", Material.LIME_STAINED_GLASS_PANE, 0, quantity);
        Map<String, String> placeholders = Map.of("amount", String.valueOf(quantity), "price", menuItems.price(total));
        String key = armed ? "shop-menu-buy-armed" : "shop-menu-buy";
        return MenuItems.decorate(stack, lang.get(key + "-name", placeholders), lang.getList(key + "-lore", placeholders));
    }

    // ------------------------------------------------------------------ [shop] buy <row> <quantity>

    public void click(Player player, MenuView view, int row, int quantity) {
        State state = states.get(player.getUniqueId());
        if (state == null || row < 0 || row >= state.items.size()) return;
        String button = row + ":" + quantity;
        long now = System.currentTimeMillis();
        boolean quick = button.equals(state.quick) && now < state.quickUntil;
        if (!quick && !button.equals(state.armed)) {
            arm(player, view, state, button);
            return;
        }
        disarm(state);
        if (buy(player, state.shop, state.items.get(row), quantity)) {
            state.quick = button;
            state.quickUntil = now + Math.max(0L, (long) (plugin.getConfig().getDouble("shop-menu.quick-buy-seconds", 1.0) * 1000));
        } else {
            state.quick = null;
        }
        refresh(player, view);
    }

    private void arm(Player player, MenuView view, State state, String button) {
        disarm(state);
        state.quick = null;
        state.armed = button;
        long timeout = Math.max(1, plugin.getConfig().getLong("shop-menu.confirm-timeout-seconds", 5)) * 20L;
        state.disarmTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            state.disarmTask = null;
            state.armed = null;
            refresh(player, FluxApi.view(player));
        }, timeout);
        // XP sound: the click counts, nothing is bought yet
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 0.7f);
        refresh(player, view);
    }

    private void disarm(State state) {
        if (state.disarmTask != null) state.disarmTask.cancel();
        state.disarmTask = null;
        state.armed = null;
    }

    private void refresh(Player player, MenuView view) {
        invalidate(player);
        if (view != null && MENU.equals(view.menuId())) view.refreshButtons();
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
        if (!money.take(player, total)) {
            plugin.getLogger().warning("[Shop] Payment refused for " + player.getName() + " (" + total + ")");
            fail(player, lang.get("shop-transaction-failed"));
            return false;
        }
        int notGiven = PlayerStorage.add(inventory, model, quantity);
        if (notGiven > 0) {
            // cannot happen (room checked on the same tick): the player only pays what he received
            double refund = PriceManager.roundMoney(unitPrice * notGiven);
            money.give(player, refund);
            plugin.getLogger().warning("[Shop] " + notGiven + " " + MenuItems.describe(entry) + " not stored for "
                    + player.getName() + ", " + refund + " refunded.");
            quantity -= notGiven;
            total = PriceManager.roundMoney(total - refund);
        }
        activity.markBought(player.getUniqueId());
        plugin.getLogger().info("[Shop] " + player.getName() + " bought " + quantity + " " + MenuItems.describe(entry)
                + " for " + total + " (" + shop.getId() + ")");
        MenuItems.send(player, lang.get("shop-buy-success", Map.of("amount", String.valueOf(quantity), "price", fr.influencraft.flux.api.util.Format.decimal(total))), model);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        return true;
    }

    private void fail(Player player, String message) {
        player.sendMessage(message);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
    }
}
