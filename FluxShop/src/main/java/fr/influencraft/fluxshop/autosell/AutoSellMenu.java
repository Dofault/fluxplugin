package fr.influencraft.fluxshop.autosell;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.ListProvider;
import fr.influencraft.flux.api.list.ListQuery;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.fluxshop.integration.CustomFishingHook;
import fr.influencraft.fluxshop.managers.LangManager;
import fr.influencraft.fluxshop.managers.PriceManager;
import fr.influencraft.fluxshop.shop.MenuItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Auto-sell menu, opened by a right click with the auto-sell item in hand: a Flux menu ({@code menus/shop_autosell.yml})
 * listing everything auto-sell can sell ({@code prices.yml} + CustomFishing fish tiers) with its sell price. Clicking
 * an entry adds it to the player's exceptions or removes it.
 * <ul>
 * <li>list {@code autosell}: filters = "all" then each category, sort orders = state (all, exceptions, sold);</li>
 * <li>toolbar buttons are one-item lists built here: {@code autosell_category}, {@code autosell_view},
 * {@code autosell_bulk}, {@code autosell_toggle}, {@code autosell_reset}, {@code autosell_info};</li>
 * <li>clicks: {@code [shop] autosell <entry|category|view|bulk|toggle|reset> [id] [left|right]}.</li>
 * </ul>
 * Icons are ItemsAdder custom_model_data on PAPER, overridable in {@code autosell-menu.icons}.
 */
public class AutoSellMenu implements ListProvider {

    public static final String MENU = "shop_autosell";
    public static final String LIST = "autosell";
    private static final List<String> BUTTONS = List.of("autosell_category", "autosell_view", "autosell_bulk",
            "autosell_toggle", "autosell_reset", "autosell_info");
    private static final int PAGE_SIZE = 45;

    private static final long TOGGLE_COOLDOWN_MILLIS = 3000L;
    private static final long RESET_CONFIRM_MILLIS = 5000L;

    /** Pseudo-category of the CustomFishing fish tiers (not in prices.yml). */
    private static final String FISH_CATEGORY = "peche";
    private static final TextColor GOLD = TextColor.color(0xFFD94D);

    /** State filter, the "sort" of the list. */
    private enum View { ALL, EXCEPTIONS, SOLD }

    /** One line: a material of prices.yml or a fish tier. */
    private record Entry(String key, String category, ItemStack sample, double unitPrice, boolean fish) {
    }

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final PriceManager priceManager;
    private final AutoSellItemManager itemManager;
    private final AutoSellExceptionManager exceptions;
    private final MenuItems menuItems;
    private final Map<UUID, Long> lastToggleAt = new HashMap<>();
    private final Map<UUID, Long> resetArmedAt = new HashMap<>();

    public AutoSellMenu(JavaPlugin plugin, LangManager lang, PriceManager priceManager,
            AutoSellItemManager itemManager, AutoSellExceptionManager exceptions) {
        this.plugin = plugin;
        this.lang = lang;
        this.priceManager = priceManager;
        this.itemManager = itemManager;
        this.exceptions = exceptions;
        this.menuItems = new MenuItems(plugin, lang);
        itemManager.setLoreInfo(this::itemLorePlaceholders);
    }

    public void register() {
        Lists.register(plugin, LIST, this);
        Lists.registerItem(plugin, "autosell_category", this::categoryButton);
        Lists.registerItem(plugin, "autosell_view", this::viewButton);
        Lists.registerItem(plugin, "autosell_bulk", p -> MenuItems.decorate(icon("bulk", 10021), lang.get("autosell-menu-bulk-name"),
                lang.getList("autosell-menu-bulk-lore", Map.of("count", String.valueOf(Lists.entries(p, LIST).size())))));
        Lists.registerItem(plugin, "autosell_toggle", this::toggleButton);
        Lists.registerItem(plugin, "autosell_reset", this::resetButton);
        Lists.registerItem(plugin, "autosell_info", p -> MenuItems.decorate(icon("info", 10181),
                lang.get("autosell-menu-info-name"), lang.getList("autosell-menu-info-lore")));
    }

    // ------------------------------------------------------------------ entry points

    public void open(Player player) {
        Lists.reset(player, LIST);
        invalidate(player);
        if (FluxApi.open(player, MENU)) fr.influencraft.flux.api.util.Sounds.cue(player, "page");
    }

    public void forget(UUID uuid) {
        lastToggleAt.remove(uuid);
        resetArmedAt.remove(uuid);
    }

    /**
     * Toggles the player's auto-sell item (the one in hand, else the first found). Limited to one change every
     * {@link #TOGGLE_COOLDOWN_MILLIS} ms, so it cannot be switched on right when a valuable drop falls then off again.
     *
     * @return true when the item was toggled
     */
    public boolean toggle(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastToggleAt.get(uuid);
        if (last != null && now - last < TOGGLE_COOLDOWN_MILLIS) {
            player.sendMessage(lang.get("shop-autosell-toggle-cooldown"));
            return false;
        }
        int slot = findItemSlot(player);
        if (slot == NO_SLOT) return false;
        lastToggleAt.put(uuid, now);

        ItemStack toggled = itemManager.toggled(player, itemAt(player, slot));
        if (slot == OFF_HAND_SLOT) player.getInventory().setItemInOffHand(toggled);
        else player.getInventory().setItem(slot, toggled);

        boolean nowActive = itemManager.isActive(toggled);
        player.sendMessage(lang.get(nowActive ? "shop-autosell-toggled-on" : "shop-autosell-toggled-off"));
        fr.influencraft.flux.api.util.Sounds.cue(player, nowActive ? "toggle-on" : "toggle-off");
        return true;
    }

    private static final int NO_SLOT = -1;
    private static final int OFF_HAND_SLOT = -2;

    private int findItemSlot(Player player) {
        int held = player.getInventory().getHeldItemSlot();
        if (itemManager.isAutoSellItem(player.getInventory().getItem(held))) return held;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            if (itemManager.isAutoSellItem(contents[i])) return i;
        }
        return itemManager.isAutoSellItem(player.getInventory().getItemInOffHand()) ? OFF_HAND_SLOT : NO_SLOT;
    }

    private ItemStack itemAt(Player player, int slot) {
        return slot == OFF_HAND_SLOT ? player.getInventory().getItemInOffHand() : player.getInventory().getItem(slot);
    }

    private boolean isActive(Player player) {
        int slot = findItemSlot(player);
        return slot != NO_SLOT && itemManager.isActive(itemAt(player, slot));
    }

    // ------------------------------------------------------------------ data

    /** Everything auto-sell can sell: fish tiers first, then prices.yml. */
    private List<Entry> all(Player player) {
        List<Entry> entries = new ArrayList<>();
        if (CustomFishingHook.isEnabled()) {
            for (Map.Entry<String, ItemStack> fish : CustomFishingHook.getSellableFishSamplesByTier(player).entrySet()) {
                ItemStack sample = fish.getValue();
                entries.add(new Entry(AutoSellFilter.FISH_KEY_PREFIX + fish.getKey(), FISH_CATEGORY, sample,
                        CustomFishingHook.getPrice(sample) / Math.max(1, sample.getAmount()), true));
            }
        }
        List<Entry> materials = new ArrayList<>();
        for (String id : priceManager.getItemIds()) {
            Material material = Material.matchMaterial(id);
            if (material != null && material.isItem() && priceManager.isSellable(id)) {
                materials.add(new Entry(material.name(), priceManager.getCategory(id), new ItemStack(material),
                        priceManager.getSellPrice(id), false));
            }
        }
        materials.sort((a, b) -> a.key().compareTo(b.key()));
        entries.addAll(materials);
        return entries;
    }

    private List<String> categories() {
        List<String> categories = new ArrayList<>();
        if (CustomFishingHook.isEnabled()) categories.add(FISH_CATEGORY);
        categories.addAll(priceManager.getCategories());
        return categories;
    }

    /** Exceptions that really concern a sellable item (the others have no effect). */
    private int exceptionCount(Player player) {
        Set<String> playerExceptions = exceptions.getExceptions(player.getUniqueId());
        return (int) all(player).stream().filter(entry -> playerExceptions.contains(entry.key())).count();
    }

    private Map<String, String> itemLorePlaceholders(Player player) {
        return Map.of("exceptions", String.valueOf(exceptionCount(player)));
    }

    private String categoryLabel(String category) {
        return FISH_CATEGORY.equals(category) ? lang.get("autosell-menu-category-fish") : PriceManager.humanize(category);
    }

    // ------------------------------------------------------------------ list autosell

    @Override
    public List<String> filters() {
        List<String> labels = new ArrayList<>();
        labels.add(lang.get("autosell-menu-category-all"));
        for (String category : categories()) labels.add(categoryLabel(category));
        return labels;
    }

    @Override
    public List<String> sorts() {
        List<String> labels = new ArrayList<>();
        for (View view : View.values()) labels.add(lang.get("autosell-menu-view-" + view.name().toLowerCase()));
        return labels;
    }

    @Override
    public List<ListEntry> entries(ListQuery query) {
        Player player = query.viewer();
        List<String> categories = categories();
        String category = query.filter() <= 0 || query.filter() > categories.size() ? null : categories.get(query.filter() - 1);
        View view = View.values()[Math.floorMod(query.sort(), View.values().length)];
        Set<String> playerExceptions = exceptions.getExceptions(player.getUniqueId());
        List<ListEntry> out = new ArrayList<>();
        for (Entry entry : all(player)) {
            if (category != null && !category.equalsIgnoreCase(entry.category())) continue;
            boolean exception = playerExceptions.contains(entry.key());
            if (view != View.ALL && (view == View.EXCEPTIONS) != exception) continue;
            out.add(ListEntry.of(entry.key()).set("category", categoryLabel(entry.category()))
                    .set("name", PriceManager.humanize(entry.key())).item(entryItem(entry, exception)));
        }
        return out;
    }

    private ItemStack entryItem(Entry entry, boolean exception) {
        ItemStack stack = entry.sample().clone();
        stack.setAmount(1);
        List<String> lore = new ArrayList<>();
        if (entry.fish()) {
            lore.addAll(lang.getList("autosell-menu-entry-fish-lore", Map.of("price", menuItems.price(entry.unitPrice()))));
        } else {
            int stackSize = stack.getMaxStackSize();
            lore.addAll(lang.getList(stackSize > 1 ? "autosell-menu-entry-lore" : "autosell-menu-entry-lore-unstackable",
                    Map.of("price", menuItems.price(entry.unitPrice()),
                            "stack_price", menuItems.price(entry.unitPrice() * stackSize),
                            "stack", String.valueOf(stackSize),
                            "category", categoryLabel(entry.category()))));
        }
        lore.add(lang.get(exception ? "autosell-menu-entry-exception" : "autosell-menu-entry-sold"));
        lore.add("");
        lore.add(lang.get(exception ? "autosell-menu-entry-action-remove" : "autosell-menu-entry-action-add"));

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (!entry.fish()) {
                // name translated by the client rather than the English material name; fish keep their tier name
                meta.displayName(Component.translatable(stack.getType().getItemTranslationKey())
                        .color(GOLD).decoration(TextDecoration.ITALIC, false));
            }
            meta.setLore(fr.influencraft.flux.api.text.Texts.wrapLines(lore));
            meta.setEnchantmentGlintOverride(exception);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // ------------------------------------------------------------------ toolbar

    private ItemStack categoryButton(Player player) {
        int selected = Lists.state(player, LIST).filter();
        List<String> labels = filters();
        List<String> lore = new ArrayList<>();
        lore.add(lang.get("autosell-menu-category-previous"));
        lore.add("");
        for (int i = 0; i < labels.size(); i++) {
            lore.add(lang.get(i == Math.floorMod(selected, labels.size()) ? "autosell-menu-option-selected" : "autosell-menu-option",
                    Map.of("option", labels.get(i))));
        }
        lore.add("");
        return MenuItems.decorate(icon("category", 10017), lang.get("autosell-menu-category-name"), lore);
    }

    private ItemStack viewButton(Player player) {
        int selected = Math.floorMod(Lists.state(player, LIST).sort(), View.values().length);
        List<String> labels = sorts();
        List<String> lore = new ArrayList<>();
        lore.add("");
        for (int i = 0; i < labels.size(); i++) {
            lore.add(lang.get(i == selected ? "autosell-menu-option-selected" : "autosell-menu-option", Map.of("option", labels.get(i))));
        }
        lore.add("");
        return MenuItems.decorate(icon("view", 10019), lang.get("autosell-menu-view-name"), lore);
    }

    private ItemStack toggleButton(Player player) {
        boolean active = isActive(player);
        return MenuItems.decorate(new ItemStack(active ? Material.CYAN_DYE : Material.LIGHT_BLUE_DYE),
                lang.get(active ? "autosell-menu-toggle-off-name" : "autosell-menu-toggle-on-name"),
                lang.getList("autosell-menu-toggle-lore", Map.of(
                        "state", lang.get(active ? "autosell-menu-state-on" : "autosell-menu-state-off"),
                        "exceptions", String.valueOf(exceptionCount(player)))));
    }

    private ItemStack resetButton(Player player) {
        boolean armed = resetArmed(player.getUniqueId());
        List<String> lore = new ArrayList<>(lang.getList(armed ? "autosell-menu-reset-armed-lore" : "autosell-menu-reset-lore"));
        if (exceptions.isDefault(player.getUniqueId())) lore.addAll(lang.getList("autosell-menu-reset-already-default"));
        return MenuItems.decorate(icon("reset", 10024), lang.get(armed ? "autosell-menu-reset-armed-name" : "autosell-menu-reset-name"), lore);
    }

    private boolean resetArmed(UUID uuid) {
        Long at = resetArmedAt.get(uuid);
        return at != null && System.currentTimeMillis() - at < RESET_CONFIRM_MILLIS;
    }

    private ItemStack icon(String key, int fallbackModelData) {
        return menuItems.iconAt("autosell-menu.icons." + key, Material.PAPER, fallbackModelData, 1);
    }

    // ------------------------------------------------------------------ [shop] autosell ...

    public void click(Player player, MenuView view, String button, String argument, boolean right) {
        UUID uuid = player.getUniqueId();
        switch (button) {
            case "entry" -> {
                if (argument.isEmpty()) return;
                boolean nowException = !exceptions.getExceptions(uuid).contains(argument);
                exceptions.setExceptions(uuid, List.of(argument), nowException);
                itemManager.refreshLore(player);
                fr.influencraft.flux.api.util.Sounds.cue(player, nowException ? "toggle-off" : "toggle-on");
            }
            case "category" -> {
                Lists.act(player, LIST, right ? "filter-prev" : "filter", PAGE_SIZE);
                fr.influencraft.flux.api.util.Sounds.cue(player, "click");
            }
            case "view" -> {
                Lists.act(player, LIST, right ? "sort-prev" : "sort", PAGE_SIZE);
                fr.influencraft.flux.api.util.Sounds.cue(player, "click");
            }
            case "bulk" -> {
                List<String> shown = Lists.entries(player, LIST).stream().map(ListEntry::id).toList();
                if (shown.isEmpty()) return;
                // left click: everything shown becomes an exception; right click: everything shown is sold again
                exceptions.setExceptions(uuid, shown, !right);
                itemManager.refreshLore(player);
                fr.influencraft.flux.api.util.Sounds.cue(player, "success");
            }
            case "toggle" -> {
                if (!toggle(player)) return;
            }
            case "reset" -> {
                if (resetArmed(uuid)) {
                    resetArmedAt.remove(uuid);
                    exceptions.resetToDefault(uuid);
                    itemManager.refreshLore(player);
                    fr.influencraft.flux.api.util.Sounds.cue(player, "success");
                    player.sendMessage(lang.get("autosell-menu-reset-done"));
                } else {
                    resetArmedAt.put(uuid, System.currentTimeMillis());
                    fr.influencraft.flux.api.util.Sounds.cue(player, "confirm");
                }
            }
            default -> {
                return;
            }
        }
        invalidate(player);
        if (view != null && MENU.equals(view.menuId())) view.refresh();
    }

    private void invalidate(Player player) {
        Lists.invalidate(player, LIST);
        for (String button : BUTTONS) Lists.invalidate(player, button);
    }
}
