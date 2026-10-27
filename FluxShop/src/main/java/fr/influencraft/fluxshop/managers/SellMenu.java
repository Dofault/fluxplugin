package fr.influencraft.fluxshop.managers;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.inventory.Inventories;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.fluxshop.autosell.AutoSellItemManager;
import fr.influencraft.fluxshop.integration.SellPriceResolver;
import fr.influencraft.fluxshop.shop.MenuItems;
import fr.influencraft.fluxshop.shop.ShopActivity;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manual selling menu, a Flux menu ({@code menus/shop_sell.yml}) whose deposit zone ({@code input-slots}) receives what
 * the player wants to sell. The confirm button is the list {@code sell_confirm}, recomputed whenever the deposit zone
 * changes ({@code input-change-actions: [shop] sell-changed}).
 * <p>
 * Nothing is sold until the player clicked the confirm button TWICE (first click arms it with an XP sound, second
 * click sells and closes). Closing the menu any other way gives everything back (Flux returns the deposit zone).
 */
public class SellMenu {

    public static final String MENU = "shop_sell";
    public static final String CONFIRM_LIST = "sell_confirm";

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final PriceManager priceManager;
    private final ShopActivity activity;
    private final AutoSellItemManager autoSellItemManager;
    private final MenuItems menuItems;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    private static final class State {
        boolean armed;
        BukkitTask disarmTask;
    }

    public SellMenu(JavaPlugin plugin, LangManager lang, PriceManager priceManager,
            ShopActivity activity, AutoSellItemManager autoSellItemManager) {
        this.plugin = plugin;
        this.lang = lang;
        this.priceManager = priceManager;
        this.activity = activity;
        this.autoSellItemManager = autoSellItemManager;
        this.menuItems = new MenuItems(plugin, lang);
    }

    public void register() {
        Lists.registerItem(plugin, CONFIRM_LIST, player -> {
            MenuView view = FluxApi.view(player);
            Quote quote = view == null ? new Quote(0, 0, 0) : quote(player, view.inputs().values());
            State state = states.get(player.getUniqueId());
            return confirmButton(quote, state != null && state.armed);
        });
    }

    public void open(Player player) {
        forget(player.getUniqueId());
        states.put(player.getUniqueId(), new State());
        Lists.invalidate(player, CONFIRM_LIST);
        FluxApi.open(player, MENU);
    }

    public void forget(UUID uuid) {
        State s = states.remove(uuid);
        if (s != null && s.disarmTask != null) s.disarmTask.cancel();
    }

    /** Total of the sale and number of items concerned. */
    private record Quote(double total, int sellableCount, int unsellableCount) {
    }

    private boolean sellable(Player player, ItemStack item) {
        // the auto-sell item is never sellable, even when its material has a price
        return !autoSellItemManager.isAutoSellItem(item) && SellPriceResolver.resolveTotalPrice(priceManager, player, item) > 0;
    }

    private Quote quote(Player player, Collection<ItemStack> items) {
        double total = 0.0;
        int sellable = 0;
        int unsellable = 0;
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            double price = autoSellItemManager.isAutoSellItem(item) ? 0.0 : SellPriceResolver.resolveTotalPrice(priceManager, player, item);
            if (price > 0) {
                total += price;
                sellable += item.getAmount();
            } else {
                unsellable += item.getAmount();
            }
        }
        return new Quote(PriceManager.roundMoney(total), sellable, unsellable);
    }

    /** Confirm button: ItemsAdder icon through a custom_model_data on a vanilla material (config {@code sell-menu}). */
    private ItemStack confirmButton(Quote quote, boolean armed) {
        String key = armed ? "confirm-button-armed" : "confirm-button";
        Map<String, String> placeholders = Map.of(
                "price", menuItems.price(quote.total()),
                "sellable", String.valueOf(quote.sellableCount()),
                "unsellable", String.valueOf(quote.unsellableCount()));
        ItemStack stack = icon(key, armed ? 10032 : 10020);
        return MenuItems.decorate(stack, lang.get(armed ? "shop-sell-confirm-armed-name" : "shop-sell-confirm-name", placeholders),
                lang.getList(armed ? "shop-sell-confirm-armed-lore" : "shop-sell-confirm-lore", placeholders));
    }

    private ItemStack icon(String key, int fallbackModelData) {
        Material material = Material.matchMaterial(plugin.getConfig().getString("sell-menu." + key + ".material", "PAPER"));
        ItemStack stack = new ItemStack(material == null ? Material.PAPER : material);
        int modelData = plugin.getConfig().getInt("sell-menu." + key + ".custom-model-data", fallbackModelData);
        if (modelData > 0) {
            var meta = stack.getItemMeta();
            if (meta != null) {
                meta.setCustomModelData(modelData);
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    private void refresh(Player player, MenuView view) {
        Lists.invalidate(player, CONFIRM_LIST);
        if (view != null && view.isOpen() && MENU.equals(view.menuId())) view.refreshButtons();
    }

    private void disarm(State state) {
        if (state.disarmTask != null) state.disarmTask.cancel();
        state.disarmTask = null;
        state.armed = false;
    }

    // ------------------------------------------------------------------ [shop] sell-changed / sell-confirm

    /** The deposit zone changed: the displayed amount is no longer the one armed. */
    public void changed(Player player, MenuView view) {
        State state = states.get(player.getUniqueId());
        if (state != null) disarm(state);
        refresh(player, view);
    }

    public void confirm(Player player, MenuView view) {
        State state = states.computeIfAbsent(player.getUniqueId(), u -> new State());
        if (view == null) return;
        Quote quote = quote(player, view.inputs().values());
        if (quote.total() <= 0) {
            fr.influencraft.flux.api.util.Sounds.cue(player, "error");
            player.sendMessage(lang.get("shop-sell-nothing-to-sell"));
            disarm(state);
            refresh(player, view);
            return;
        }
        if (!state.armed) {
            disarm(state);
            state.armed = true;
            // XP sound: the click counts, nothing is sold yet, the next click is final
            fr.influencraft.flux.api.util.Sounds.cue(player, "confirm");
            long timeout = Math.max(1, plugin.getConfig().getInt("sell-menu.confirm-timeout-seconds", 5)) * 20L;
            state.disarmTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                state.disarmTask = null;
                state.armed = false;
                refresh(player, FluxApi.view(player));
            }, timeout);
            refresh(player, view);
            return;
        }
        disarm(state);
        sell(player, view);
    }

    private void sell(Player player, MenuView view) {
        Currency money = Economy.main();
        if (money == null) {
            player.sendMessage(lang.get("shop-transaction-failed"));
            return;
        }
        // the deposit zone now belongs to the sale: prices are computed on what was really taken
        List<ItemStack> taken = view.takeInputs();
        List<ItemStack> giveBack = new ArrayList<>();
        double total = 0.0;
        for (ItemStack item : taken) {
            if (sellable(player, item)) total += SellPriceResolver.resolveTotalPrice(priceManager, player, item);
            else giveBack.add(item);
        }
        total = PriceManager.roundMoney(total);
        if (total > 0 && money.give(player, total)) {
            player.sendMessage(lang.get("shop-sell-success", Map.of("amount", fr.influencraft.flux.api.util.Format.decimal(total))));
            fr.influencraft.flux.api.util.Sounds.cue(player, "complete");
            activity.markSold(player.getUniqueId());
            plugin.getLogger().info("[Sell] " + player.getName() + " sold " + (taken.size() - giveBack.size()) + " stack(s) for " + total);
        } else {
            // payment refused: nothing is lost
            giveBack = taken;
            player.sendMessage(lang.get("shop-transaction-failed"));
        }
        int restored = giveBack.stream().mapToInt(ItemStack::getAmount).sum();
        Inventories.giveOrDrop(player, giveBack);
        if (restored > 0 && giveBack != taken) {
            player.sendMessage(lang.get("shop-unsellable-restored", Map.of("amount", String.valueOf(restored))));
        }
        view.close();
    }
}
