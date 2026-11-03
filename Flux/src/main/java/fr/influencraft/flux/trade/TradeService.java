package fr.influencraft.flux.trade;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.inventory.Inventories;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.text.Messages;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.trade.TradeWindow;
import fr.influencraft.flux.api.util.Sounds;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Backend of {@link TradeWindow}: lists {@code trade_item}, {@code trade_buy_<n>} (one entry per offer),
 * {@code trade_sell_item}, {@code trade_sell_confirm}, action {@code [trade] buy <row> <bundles> | sell-changed |
 * sell-confirm}, placeholder {@code %flux_trade_title%}.
 */
public final class TradeService implements TradeWindow.Backend, Listener {

    private static final List<String> BUY_LISTS = List.of("trade_item", "trade_buy_1", "trade_buy_2", "trade_buy_10", "trade_buy_64");
    private static final List<String> SELL_LISTS = List.of("trade_sell_item", "trade_sell_confirm");

    private final JavaPlugin flux;
    private final Supplier<Messages> messages;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    /** What a player trades: the offers (buying) or the purchase (selling), the armed button. */
    private static final class State {
        final String menu;
        final String title;
        final List<? extends TradeWindow.Offer> offers;
        final TradeWindow.Purchase purchase;
        String armed;
        BukkitTask disarmTask;
        String quick;
        long quickUntil;

        State(String menu, String title, List<? extends TradeWindow.Offer> offers, TradeWindow.Purchase purchase) {
            this.menu = menu;
            this.title = title;
            this.offers = offers;
            this.purchase = purchase;
        }
    }

    /** Sale of the deposit zone: matching items, bundles taken, their price, other items. */
    private record Quote(int items, int bundles, double total, int others) {
    }

    private TradeService(JavaPlugin flux, Supplier<Messages> messages) {
        this.flux = flux;
        this.messages = messages;
    }

    public static TradeService start(JavaPlugin flux, Supplier<Messages> messages) {
        TradeService service = new TradeService(flux, messages);
        Lists.register(flux, "trade_item", query -> service.rows(query.viewer(), -1));
        for (int q : TradeWindow.QUANTITIES) Lists.register(flux, "trade_buy_" + q, query -> service.rows(query.viewer(), q));
        Lists.registerItem(flux, "trade_sell_item", service::sellItem);
        Lists.registerItem(flux, "trade_sell_confirm", service::sellButton);
        FluxApi.registerAction(flux, "trade", service::click);
        Bukkit.getPluginManager().registerEvents(service, flux);
        TradeWindow.bind(service);
        return service;
    }

    public void stop() {
        TradeWindow.bind(null);
        for (State s : states.values()) if (s.disarmTask != null) s.disarmTask.cancel();
        states.clear();
    }

    /** {@code %flux_trade_title%}. */
    public String title(Player player) {
        State s = player == null ? null : states.get(player.getUniqueId());
        return s != null && s.title != null ? s.title : text("title");
    }

    // ------------------------------------------------------------------ opening

    @Override
    public boolean buy(Player player, String menu, String title, List<? extends TradeWindow.Offer> offers) {
        List<TradeWindow.Offer> shown = new ArrayList<>();
        for (TradeWindow.Offer o : offers) if (o != null && o.price() > 0) shown.add(o);
        if (shown.isEmpty()) return false;
        return open(player, new State(menu, title, List.copyOf(shown), null), BUY_LISTS);
    }

    @Override
    public boolean sell(Player player, String title, TradeWindow.Purchase purchase) {
        if (purchase == null) return false;
        return open(player, new State(TradeWindow.SELL_MENU, title, List.of(), purchase), SELL_LISTS);
    }

    private boolean open(Player player, State state, List<String> lists) {
        forget(player.getUniqueId());
        states.put(player.getUniqueId(), state);
        for (String l : lists) Lists.invalidate(player, l);
        if (!FluxApi.open(player, state.menu)) {
            states.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    @Override
    public void close(Player player) {
        forget(player.getUniqueId());
        MenuView view = FluxApi.view(player);
        if (view != null && view.isOpen()) view.close();
    }

    private void forget(UUID uuid) {
        State s = states.remove(uuid);
        if (s != null && s.disarmTask != null) s.disarmTask.cancel();
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        forget(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ buying window

    /** @param bundles -1 for the item column, else the bundles of the buttons */
    private List<ListEntry> rows(Player viewer, int bundles) {
        State state = states.get(viewer.getUniqueId());
        List<ListEntry> out = new ArrayList<>();
        if (state == null) return out;
        for (int row = 0; row < state.offers.size(); row++) {
            TradeWindow.Offer offer = state.offers.get(row);
            ItemStack item = bundles < 0 ? offerItem(viewer, offer) : buyButton(viewer, state, row, offer, bundles);
            out.add(ListEntry.of(String.valueOf(row)).item(item == null ? new ItemStack(Material.AIR) : item));
        }
        return out;
    }

    private ItemStack offerItem(Player viewer, TradeWindow.Offer offer) {
        int bundle = Math.max(1, offer.bundle());
        Lore lore = Lore.create().kv(text("price"), price(offer.price()) + (bundle > 1 ? " " + text("per-bundle", "%bundle%", String.valueOf(bundle)) : ""));
        int available = offer.available();
        lore.kv(text("stock"), available == Integer.MAX_VALUE ? text("unlimited") : available < 0 ? "?"
                : available == 0 ? text("out-of-stock") : String.valueOf((long) available * bundle));
        ItemStack model = model(offer.item());
        lore.kv(text("owned"), String.valueOf(Inventories.count(viewer, i -> i.isSimilar(model))));
        offer.describe(viewer, lore);
        return withLore(offer.item(), lore, viewer);
    }

    /** x1, x2, x10, x64 bundles; hidden beyond the known stock. */
    private ItemStack buyButton(Player viewer, State state, int row, TradeWindow.Offer offer, int bundles) {
        int available = offer.available();
        if (available >= 0 && available != Integer.MAX_VALUE && available < bundles) return null;
        int items = bundles * Math.max(1, offer.bundle());
        boolean armed = (row + ":" + bundles).equals(state.armed);
        ItemStack stack = Items.of(flux.getConfig().getString(armed ? "trade.buy-button-armed" : "trade.buy-button",
                armed ? "ORANGE_STAINED_GLASS_PANE" : "LIME_STAINED_GLASS_PANE"), viewer);
        stack.setAmount(Math.max(1, Math.min(64, items)));
        Lore lore = Lore.create().kv(text("total"), price(offer.price() * bundles));
        if (armed) lore.left(text("click-confirm"));
        else lore.left(text("click-buy"));
        return Items.dress(stack, text(armed ? "buy-armed-name" : "buy-name", "%amount%", String.valueOf(items)), lore.build(), viewer);
    }

    // ------------------------------------------------------------------ selling window

    private ItemStack sellItem(Player viewer) {
        State state = states.get(viewer.getUniqueId());
        if (state == null || state.purchase == null) return new ItemStack(Material.AIR);
        TradeWindow.Purchase p = state.purchase;
        int bundle = Math.max(1, p.bundle());
        Lore lore = Lore.create().kv(text("paid"), price(p.price()) + (bundle > 1 ? " " + text("per-bundle", "%bundle%", String.valueOf(bundle)) : ""));
        int room = p.room();
        lore.kv(text("room"), room == Integer.MAX_VALUE ? text("unlimited") : room < 0 ? "?"
                : room == 0 ? text("full") : String.valueOf((long) room * bundle));
        lore.kv(text("owned"), String.valueOf(Inventories.count(viewer, p::matches)));
        p.describe(viewer, lore);
        return withLore(p.item(), lore, viewer);
    }

    private ItemStack sellButton(Player viewer) {
        State state = states.get(viewer.getUniqueId());
        MenuView view = FluxApi.view(viewer);
        if (state == null || state.purchase == null) return new ItemStack(Material.AIR);
        Quote quote = view == null ? new Quote(0, 0, 0, 0) : quote(state.purchase, view.inputs().values());
        boolean armed = "sell".equals(state.armed);
        int bundle = Math.max(1, state.purchase.bundle());
        ItemStack stack = Items.of(flux.getConfig().getString(armed ? "trade.sell-button-armed" : "trade.sell-button",
                armed ? "itemsadder:influitems:checkmark" : "itemsadder:influitems:accept"), viewer);
        Lore lore = Lore.create().description(text("sell-help"))
                .kv(text("total"), price(quote.total()))
                .kv(text("sellable"), String.valueOf(quote.bundles() * bundle))
                .kv(text("unsellable"), String.valueOf(quote.others() + quote.items() - quote.bundles() * bundle));
        if (armed) lore.left(text("click-confirm"));
        else lore.left(text("click-sell"));
        return Items.dress(stack, text(armed ? "sell-armed-name" : "sell-name"), lore.build(), viewer);
    }

    /** What the purchase would take from the deposit zone: whole bundles, within its known room. */
    private static Quote quote(TradeWindow.Purchase p, Collection<ItemStack> deposit) {
        int items = 0, others = 0;
        for (ItemStack item : deposit) {
            if (item == null || item.getType().isAir()) continue;
            if (p.matches(item)) items += item.getAmount();
            else others += item.getAmount();
        }
        int bundle = Math.max(1, p.bundle());
        int bundles = items / bundle;
        int room = p.room();
        if (room >= 0 && room != Integer.MAX_VALUE) bundles = Math.min(bundles, room);
        return new Quote(items, bundles, p.price() * bundles, others);
    }

    // ------------------------------------------------------------------ clicks

    private void click(Player player, MenuView view, String argument) {
        String[] args = argument == null ? new String[0] : argument.trim().split("\\s+");
        if (args.length == 0) return;
        State state = states.get(player.getUniqueId());
        if (state == null) return;
        switch (args[0]) {
            case "buy" -> {
                if (args.length < 3) return;
                try {
                    buy(player, view, state, Integer.parseInt(args[1]), Integer.parseInt(args[2]));
                } catch (NumberFormatException ignored) {
                    // malformed menu line
                }
            }
            case "sell-changed" -> {
                disarm(state);
                refresh(player, view);
            }
            case "sell-confirm" -> sell(player, view, state);
            default -> {
            }
        }
    }

    private void buy(Player player, MenuView view, State state, int row, int bundles) {
        if (row < 0 || row >= state.offers.size() || bundles <= 0) return;
        String button = row + ":" + bundles;
        long now = System.currentTimeMillis();
        boolean quick = button.equals(state.quick) && now < state.quickUntil;
        if (!quick && !button.equals(state.armed)) {
            arm(player, state, button);
            return;
        }
        disarm(state);
        if (state.offers.get(row).buy(player, bundles)) {
            state.quick = button;
            state.quickUntil = now + Math.max(0L, (long) (flux.getConfig().getDouble("trade.quick-buy-seconds", 1.0) * 1000));
        } else {
            state.quick = null;
        }
        if (states.get(player.getUniqueId()) == state) refresh(player, view);
    }

    private void sell(Player player, MenuView view, State state) {
        if (view == null || state.purchase == null) return;
        Quote quote = quote(state.purchase, view.inputs().values());
        if (quote.bundles() <= 0) {
            Sounds.cue(player, "error");
            messages.get().send(player, quote.items() > 0 ? "trade.sell-no-room" : "trade.sell-nothing");
            disarm(state);
            refresh(player, view);
            return;
        }
        if (!"sell".equals(state.armed)) {
            arm(player, state, "sell");
            return;
        }
        disarm(state);
        // the deposit zone goes into a scratch inventory the plugin takes the sold items from
        List<ItemStack> taken = view.takeInputs();
        Inventory scratch = Bukkit.createInventory(null, 54);
        List<ItemStack> back = new ArrayList<>(scratch.addItem(taken.toArray(new ItemStack[0])).values());
        state.purchase.sell(player, scratch, quote.bundles());
        for (ItemStack item : scratch.getContents()) if (item != null && !item.getType().isAir()) back.add(item);
        Inventories.giveOrDrop(player, back);
        forget(player.getUniqueId());
        view.close();
    }

    private void arm(Player player, State state, String button) {
        disarm(state);
        state.quick = null;
        state.armed = button;
        long timeout = Math.max(1, flux.getConfig().getLong("trade.confirm-timeout-seconds", 5)) * 20L;
        state.disarmTask = Bukkit.getScheduler().runTaskLater(flux, () -> {
            state.disarmTask = null;
            state.armed = null;
            if (states.get(player.getUniqueId()) == state) refresh(player, FluxApi.view(player));
        }, timeout);
        // the click counts, nothing is traded yet
        Sounds.cue(player, "confirm");
        refresh(player, FluxApi.view(player));
    }

    private static void disarm(State state) {
        if (state.disarmTask != null) state.disarmTask.cancel();
        state.disarmTask = null;
        state.armed = null;
    }

    private void refresh(Player player, MenuView view) {
        State state = states.get(player.getUniqueId());
        for (String l : BUY_LISTS) Lists.invalidate(player, l);
        for (String l : SELL_LISTS) Lists.invalidate(player, l);
        if (view != null && view.isOpen() && state != null && state.menu.equals(view.menuId())) view.refreshButtons();
    }

    // ------------------------------------------------------------------ helpers

    private String text(String key, String... vars) {
        return messages.get().raw("trade." + key, vars);
    }

    private static String price(double amount) {
        Currency money = Economy.main();
        return money == null ? fr.influencraft.flux.api.util.Format.decimal(amount) : money.format(amount);
    }

    private static ItemStack model(ItemStack item) {
        ItemStack m = item.clone();
        m.setAmount(1);
        return m;
    }

    /** The traded item, its own lore then the trade lines (style of the suite). */
    private static ItemStack withLore(ItemStack item, Lore lore, Player viewer) {
        ItemStack shown = model(item);
        ItemMeta meta = shown.getItemMeta();
        if (meta == null) return shown;
        List<Component> all = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        all.addAll(Texts.lore(lore.build(), viewer));
        meta.lore(all);
        shown.setItemMeta(meta);
        return shown;
    }
}
