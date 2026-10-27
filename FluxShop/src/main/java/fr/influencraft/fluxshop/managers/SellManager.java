package fr.influencraft.fluxshop.managers;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.ListProvider;
import fr.influencraft.flux.api.list.ListQuery;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.fluxshop.autosell.AutoSellItemManager;
import fr.influencraft.fluxshop.integration.CustomFishingHook;
import fr.influencraft.fluxshop.shop.MenuItems;
import fr.influencraft.fluxshop.shop.ShopActivity;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sell block: right click opens the selling menu ({@link SellMenu}), left click the sell price list, a Flux menu
 * ({@code menus/shop_prices.yml}) showing the list {@code shop_prices}. Its filters are "every item" then each category of
 * {@code prices.yml}; CustomFishing fish are added to the food category (no tab of their own).
 */
public class SellManager implements ListProvider {

    public static final String PRICES_MENU = "shop_prices";
    public static final String PRICES_LIST = "shop_prices";
    private static final String FOOD_CATEGORY = "nourriture";

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final PriceManager priceManager;
    private final MenuItems menuItems;
    private final SellMenu sellMenu;

    public SellManager(JavaPlugin plugin, LangManager lang, PriceManager priceManager, ShopActivity activity,
            AutoSellItemManager autoSellItemManager) {
        this.plugin = plugin;
        this.lang = lang;
        this.priceManager = priceManager;
        this.menuItems = new MenuItems(plugin, lang);
        this.sellMenu = new SellMenu(plugin, lang, priceManager, activity, autoSellItemManager);
    }

    public void register() {
        sellMenu.register();
        Lists.register(plugin, PRICES_LIST, this);
    }

    public SellMenu sellMenu() {
        return sellMenu;
    }

    /** Right click on the sell block: deposit menu. */
    public void openSellMenu(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
        sellMenu.open(player);
    }

    /** Left click on the sell block: sell prices. */
    public void openPriceList(Player player) {
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f);
        Lists.reset(player, PRICES_LIST);
        FluxApi.open(player, PRICES_MENU);
    }

    // ------------------------------------------------------------------ list shop_prices

    @Override
    public List<String> filters() {
        List<String> labels = new ArrayList<>();
        labels.add(lang.get("shop-all-items-label"));
        for (String category : priceManager.getCategories()) labels.add(PriceManager.humanize(category));
        return labels;
    }

    @Override
    public List<ListEntry> entries(ListQuery query) {
        List<String> categories = priceManager.getCategories();
        String category = query.filter() <= 0 || query.filter() > categories.size() ? null : categories.get(query.filter() - 1);
        List<String> ids = category == null ? new ArrayList<>(priceManager.getItemIds()) : priceManager.getItemIdsByCategory(category);
        ids.removeIf(id -> Material.matchMaterial(id) == null || !priceManager.isSellable(id));
        ids.sort(String::compareTo);

        List<ListEntry> out = new ArrayList<>();
        for (String id : ids) {
            Material material = Material.matchMaterial(id);
            String name = PriceManager.humanize(id);
            out.add(ListEntry.of(id).set("name", name).item(priceItem(new ItemStack(material), name, priceManager.getSellPrice(id))));
        }
        if (FOOD_CATEGORY.equalsIgnoreCase(category) && CustomFishingHook.isEnabled()) {
            List<ItemStack> fish = CustomFishingHook.getSellableFishSamples(query.viewer());
            for (int i = 0; i < fish.size(); i++) {
                ItemStack sample = fish.get(i).clone();
                sample.setAmount(1);
                ItemMeta meta = sample.getItemMeta();
                String name = meta != null && meta.hasDisplayName() ? meta.getDisplayName() : PriceManager.humanize(sample.getType().name());
                out.add(ListEntry.of("fish:" + i).set("name", name).item(priceItem(sample, name, CustomFishingHook.getPrice(fish.get(i)))));
            }
        }
        return out;
    }

    private ItemStack priceItem(ItemStack stack, String name, double price) {
        return MenuItems.decorate(stack, lang.get("shop-item-name", Map.of("item", name)),
                List.of(lang.get("shop-item-lore-sell-price", Map.of("price", menuItems.price(price)))));
    }
}
