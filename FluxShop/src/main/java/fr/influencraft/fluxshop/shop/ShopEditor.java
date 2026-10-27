package fr.influencraft.fluxshop.shop;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.ListProvider;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.fluxshop.managers.LangManager;
import fr.influencraft.fluxshop.managers.PriceManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Editor of a shop chest (admin sneaking + right click), a Flux menu ({@code menus/shop_editor.yml}, admin inventory
 * visible). One row per item: the item, "change the price", "remove"; lists {@code editor_item},
 * {@code editor_price}, {@code editor_remove}, sharing the same order ({@code editor_price} ends with the "add" hint).
 * <ul>
 * <li>clicking an item of one's own inventory ({@code [shop] editor-add <slot>}) adds an exact copy (FluxPets booster,
 * ItemsAdder item...). A plain vanilla item priced in {@code prices.yml} follows that price; otherwise the price is
 * asked in a dialog window;</li>
 * <li>"change the price": price of this chest only, or back to {@code prices.yml} for a plain vanilla item;</li>
 * <li>"remove": double click confirmation, like buying.</li>
 * </ul>
 */
public class ShopEditor {

    public static final String MENU = "shop_editor";
    private static final List<String> LISTS = List.of("editor_item", "editor_price", "editor_remove");

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final PriceManager priceManager;
    private final ShopRegistry registry;
    private final MenuItems menuItems;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    private static final class State {
        final Shop shop;
        int armedRow = -1;
        BukkitTask disarmTask;

        State(Shop shop) {
            this.shop = shop;
        }
    }

    public ShopEditor(JavaPlugin plugin, LangManager lang, PriceManager priceManager, ShopRegistry registry) {
        this.plugin = plugin;
        this.lang = lang;
        this.priceManager = priceManager;
        this.registry = registry;
        this.menuItems = new MenuItems(plugin, lang);
    }

    public void register() {
        Lists.register(plugin, "editor_item", rows((s, i, e) -> MenuItems.withExtraLore(e, priceLore(e))));
        ListProvider prices = rows((s, i, e) -> MenuItems.decorate(
                menuItems.icon("editor-price", Material.PAPER, 10040, 1),
                lang.get("shop-editor-price-name"), lang.getList("shop-editor-price-lore")));
        // the "price" column ends with the "add an item" hint, on the first free row
        Lists.register(plugin, "editor_price", query -> {
            List<ListEntry> out = new ArrayList<>(prices.entries(query));
            State state = states.get(query.viewer().getUniqueId());
            if (state != null && state.shop.getItems().size() < Shop.MAX_ITEMS) {
                out.add(ListEntry.of("add").item(MenuItems.decorate(menuItems.icon("editor-add", Material.PAPER, 10021, 1),
                        lang.get("shop-editor-add-name"), lang.getList("shop-editor-add-lore"))));
            }
            return out;
        });
        Lists.register(plugin, "editor_remove", rows((s, i, e) -> {
            boolean armed = i == s.armedRow;
            ItemStack remove = MenuItems.decorate(menuItems.icon("editor-remove", Material.PAPER, 10024, 1),
                    lang.get(armed ? "shop-editor-remove-armed-name" : "shop-editor-remove-name"),
                    lang.getList(armed ? "shop-editor-remove-armed-lore" : "shop-editor-remove-lore"));
            if (armed) {
                ItemMeta meta = remove.getItemMeta();
                if (meta != null) {
                    meta.setEnchantmentGlintOverride(true);
                    remove.setItemMeta(meta);
                }
            }
            return remove;
        }));
    }

    @FunctionalInterface
    private interface Icon {
        ItemStack build(State state, int row, ShopEntry entry);
    }

    private ListProvider rows(Icon icon) {
        return query -> {
            State state = states.get(query.viewer().getUniqueId());
            List<ListEntry> out = new ArrayList<>();
            if (state == null) return out;
            List<ShopEntry> items = state.shop.getItems();
            for (int row = 0; row < items.size(); row++) out.add(ListEntry.of(String.valueOf(row)).item(icon.build(state, row, items.get(row))));
            return out;
        };
    }

    public void open(Player player, Shop shop) {
        State previous = states.put(player.getUniqueId(), new State(shop));
        if (previous != null && previous.disarmTask != null) previous.disarmTask.cancel();
        invalidate(player);
        if (FluxApi.open(player, MENU)) player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 0.9f);
    }

    public void forget(UUID uuid) {
        State s = states.remove(uuid);
        if (s != null && s.disarmTask != null) s.disarmTask.cancel();
    }

    /** Number of items of the chest being edited, for the menu ({@code %fluxshop_editor_count%}). */
    public int count(Player player) {
        State s = states.get(player.getUniqueId());
        return s == null ? 0 : s.shop.getItems().size();
    }

    private void invalidate(Player player) {
        for (String list : LISTS) Lists.invalidate(player, list);
    }

    private void refresh(Player player, MenuView view) {
        invalidate(player);
        if (view != null && MENU.equals(view.menuId())) view.refreshButtons();
    }

    private List<String> priceLore(ShopEntry entry) {
        double unit = entry.unitPrice(priceManager);
        String price = unit > 0 ? menuItems.price(unit) : lang.get("shop-menu-unavailable");
        return lang.getList(entry.getPrice() != null ? "shop-editor-item-lore-custom" : "shop-editor-item-lore-default",
                Map.of("price", price));
    }

    // ------------------------------------------------------------------ [shop] editor-*

    public void price(Player player, MenuView view, int row) {
        State state = editing(player);
        if (state == null || row < 0 || row >= state.shop.getItems().size()) return;
        disarm(state);
        ask(player, state.shop, state.shop.getItems().get(row), false);
    }

    public void remove(Player player, MenuView view, int row) {
        State state = editing(player);
        if (state == null || row < 0 || row >= state.shop.getItems().size()) return;
        if (state.armedRow != row) {
            arm(player, view, state, row);
            return;
        }
        disarm(state);
        ShopEntry entry = state.shop.getItems().remove(row);
        registry.save();
        MenuItems.send(player, lang.get("shop-editor-removed"), entry.getItem());
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.6f, 1.0f);
        refresh(player, view);
    }

    /** Click on an item of the admin's own inventory: a copy is offered by the chest (the item stays). */
    public void add(Player player, MenuView view, int inventorySlot) {
        State state = editing(player);
        if (state == null || inventorySlot < 0 || inventorySlot >= player.getInventory().getSize()) return;
        ItemStack clicked = player.getInventory().getItem(inventorySlot);
        if (clicked == null || clicked.getType().isAir()) return;
        List<ShopEntry> entries = state.shop.getItems();
        if (entries.stream().anyMatch(entry -> entry.matches(clicked))) {
            fail(player, lang.get("shop-editor-already"));
            return;
        }
        if (entries.size() >= Shop.MAX_ITEMS) {
            fail(player, lang.get("shop-editor-full", Map.of("max", String.valueOf(Shop.MAX_ITEMS))));
            return;
        }
        ShopEntry entry = new ShopEntry(clicked.clone(), null);
        if (entry.isPlain() && priceManager.isBuyable(clicked.getType().name())) {
            entries.add(entry);
            registry.save();
            MenuItems.send(player, lang.get("shop-editor-added-default"), entry.getItem());
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
            refresh(player, view);
            return;
        }
        ask(player, state.shop, entry, true);
    }

    private State editing(Player player) {
        State state = states.get(player.getUniqueId());
        if (state == null || !player.hasPermission("supermarket.admin")) return null;
        if (!registry.isRegistered(state.shop)) {
            player.sendMessage(lang.get("shop-admin-not-a-chest"));
            return null;
        }
        return state;
    }

    private void arm(Player player, MenuView view, State state, int row) {
        disarm(state);
        state.armedRow = row;
        long timeout = Math.max(1, plugin.getConfig().getLong("shop-menu.confirm-timeout-seconds", 5)) * 20L;
        state.disarmTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            state.disarmTask = null;
            state.armedRow = -1;
            refresh(player, FluxApi.view(player));
        }, timeout);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 0.7f);
        refresh(player, view);
    }

    private void disarm(State state) {
        if (state.disarmTask != null) state.disarmTask.cancel();
        state.disarmTask = null;
        state.armedRow = -1;
    }

    // ------------------------------------------------------------------ price dialog

    private void ask(Player player, Shop shop, ShopEntry entry, boolean isNew) {
        boolean canDefault = entry.isPlain() && priceManager.isBuyable(entry.getItem().getType().name());
        Dialogs.Form form = Dialogs.form(player, lang.get("shop-editor-title"))
                .body(lang.get(canDefault && !isNew ? "shop-editor-price-dialog-default" : "shop-editor-price-dialog"))
                .input("price", lang.get("shop-editor-price-dialog-label"), entry.getPrice() == null ? "" : String.valueOf(entry.getPrice()), 16);
        if (canDefault) form.toggle("default", lang.get("shop-editor-price-dialog-use-default"), entry.getPrice() == null && !isNew);
        form.onSubmit((p, answer) -> {
            Double price;
            if (canDefault && answer.toggle("default")) {
                price = null;
            } else {
                try {
                    double value = Double.parseDouble(answer.text("price").replace(',', '.').replace(" ", ""));
                    if (!(value > 0) || Double.isInfinite(value)) throw new NumberFormatException();
                    price = PriceManager.roundMoney(value);
                } catch (NumberFormatException e) {
                    p.sendMessage(lang.get(canDefault ? "shop-editor-price-no-default" : "shop-editor-price-invalid"));
                    open(p, shop);
                    return;
                }
            }
            save(p, shop, entry, isNew, price);
        }).onCancel(p -> {
            p.sendMessage(lang.get("shop-editor-price-cancelled"));
            open(p, shop);
        }).open();
    }

    private void save(Player player, Shop shop, ShopEntry entry, boolean isNew, Double price) {
        if (!registry.isRegistered(shop)) {
            player.sendMessage(lang.get("shop-admin-not-a-chest"));
            return;
        }
        if (isNew) {
            List<ShopEntry> entries = shop.getItems();
            if (entries.size() >= Shop.MAX_ITEMS || entries.stream().anyMatch(other -> other.matches(entry.getItem()))) {
                player.sendMessage(lang.get("shop-editor-full", Map.of("max", String.valueOf(Shop.MAX_ITEMS))));
                open(player, shop);
                return;
            }
            entry.setPrice(price);
            entries.add(entry);
        } else {
            entry.setPrice(price);
        }
        registry.save();
        MenuItems.send(player, lang.get("shop-editor-price-set", Map.of("price", menuItems.price(entry.unitPrice(priceManager)))), entry.getItem());
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        open(player, shop);
    }

    private void fail(Player player, String message) {
        player.sendMessage(message);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
    }
}
