package io.dofault.supermarket.autosell;

import io.dofault.supermarket.integration.CustomFishingHook;
import io.dofault.supermarket.managers.LangManager;
import io.dofault.supermarket.managers.PlayerListManager;
import io.dofault.supermarket.managers.PriceManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
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
 * Menu de la vente automatique, ouvert par un clic droit avec l'objet en main : la liste de
 * tout ce que la vente auto peut vendre (prices.yml + paliers de poissons CustomFishing), avec
 * le prix de vente, filtrable par catégorie et par état. Un clic sur un objet l'ajoute aux
 * exceptions du joueur ou l'en retire. La barre du bas porte aussi l'activation de l'objet et
 * la remise aux exceptions par défaut.
 * <p>
 * Les icônes sont des {@code custom_model_data} ItemsAdder posés sur du PAPER (le plugin n'a
 * pas de hook ItemsAdder, cf. {@code SellMenu}), surchargeables dans {@code autosell-menu.icons}.
 */
public class AutoSellMenu implements Listener {

    private static final long TOGGLE_COOLDOWN_MILLIS = 3000L;
    private static final long RESET_CONFIRM_MILLIS = 5000L;

    // 6 rangées : 5 rangées d'objets, barre d'outils en bas
    private static final int SIZE = 54;
    private static final int ITEMS_PER_PAGE = 45;
    private static final int PREV_SLOT = 45;
    private static final int CATEGORY_SLOT = 46;
    private static final int VIEW_SLOT = 47;
    private static final int BULK_SLOT = 48;
    private static final int TOGGLE_SLOT = 49;
    private static final int RESET_SLOT = 50;
    private static final int NEXT_SLOT = 52;
    private static final int INFO_SLOT = 53;

    /** Pseudo-catégorie des paliers de poissons CustomFishing (absente de prices.yml). */
    private static final String FISH_CATEGORY = "peche";

    private static final TextColor GOLD = TextColor.color(0xFFD94D);

    /** Filtre d'état. */
    private enum View { ALL, EXCEPTIONS, SOLD }

    /** Une ligne de la liste : un matériau de prices.yml ou un palier de poissons. */
    private record Entry(String key, String category, ItemStack sample, double unitPrice, boolean fish) {
    }

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final PriceManager priceManager;
    private final PlayerListManager playerListManager;
    private final AutoSellItemManager itemManager;
    private final AutoSellExceptionManager exceptions;
    private final Economy econ;
    private final Map<UUID, Long> lastToggleAt = new HashMap<>();

    public AutoSellMenu(JavaPlugin plugin, LangManager lang, PriceManager priceManager,
            PlayerListManager playerListManager, AutoSellItemManager itemManager,
            AutoSellExceptionManager exceptions, Economy econ) {
        this.plugin = plugin;
        this.lang = lang;
        this.priceManager = priceManager;
        this.playerListManager = playerListManager;
        this.itemManager = itemManager;
        this.exceptions = exceptions;
        this.econ = econ;
        itemManager.setLoreInfo(this::itemLorePlaceholders);
    }

    // ------------------------------------------------------------------
    // Points d'entrée
    // ------------------------------------------------------------------

    public void open(Player player) {
        if (playerListManager.isInShop(player.getUniqueId())) {
            player.sendMessage(lang.get("shop-autosell-toggle-in-shop"));
            return;
        }
        Session session = new Session(player);
        session.inventory = Bukkit.createInventory(session, SIZE, session.title());
        session.render();
        player.openInventory(session.inventory);
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f);
    }

    /**
     * Bascule l'objet de vente auto du joueur (celui en main, sinon le premier trouvé).
     * Limité à un changement toutes les {@link #TOGGLE_COOLDOWN_MILLIS} ms, pour qu'on ne puisse
     * pas l'allumer pile au moment où un drop de valeur tombe puis l'éteindre aussitôt, en
     * triant ses ventes une par une au lieu de s'en servir comme d'un « mode farm ».
     *
     * @return {@code true} si l'objet a été basculé
     */
    public boolean toggle(Player player) {
        if (playerListManager.isInShop(player.getUniqueId())) {
            player.sendMessage(lang.get("shop-autosell-toggle-in-shop"));
            return false;
        }

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastToggleAt.get(uuid);
        if (last != null && now - last < TOGGLE_COOLDOWN_MILLIS) {
            player.sendMessage(lang.get("shop-autosell-toggle-cooldown"));
            return false;
        }

        int slot = findItemSlot(player);
        if (slot == NO_SLOT) {
            return false;
        }
        lastToggleAt.put(uuid, now);

        ItemStack toggled = itemManager.toggled(player, itemAt(player, slot));
        if (slot == OFF_HAND_SLOT) {
            player.getInventory().setItemInOffHand(toggled);
        } else {
            player.getInventory().setItem(slot, toggled);
        }

        boolean nowActive = itemManager.isActive(toggled);
        player.sendMessage(lang.get(nowActive ? "shop-autosell-toggled-on" : "shop-autosell-toggled-off"));
        player.playSound(player.getLocation(),
                nowActive ? Sound.BLOCK_AMETHYST_BLOCK_CHIME : Sound.BLOCK_AMETHYST_BLOCK_HIT,
                1.0f, nowActive ? 1.4f : 0.8f);
        return true;
    }

    private static final int NO_SLOT = -1;
    private static final int OFF_HAND_SLOT = -2;

    private int findItemSlot(Player player) {
        int held = player.getInventory().getHeldItemSlot();
        if (itemManager.isAutoSellItem(player.getInventory().getItem(held))) {
            return held;
        }
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            if (itemManager.isAutoSellItem(contents[i])) {
                return i;
            }
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

    // ------------------------------------------------------------------
    // Données
    // ------------------------------------------------------------------

    /** Tout ce que la vente auto peut vendre : paliers de poissons d'abord, puis prices.yml. */
    private List<Entry> entries(Player player) {
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

    /** Nombre d'exceptions qui concernent réellement un objet vendable (le reste est sans effet). */
    private int exceptionCount(Player player) {
        Set<String> playerExceptions = exceptions.getExceptions(player.getUniqueId());
        return (int) entries(player).stream().filter(entry -> playerExceptions.contains(entry.key())).count();
    }

    private Map<String, String> itemLorePlaceholders(Player player) {
        return Map.of("exceptions", String.valueOf(exceptionCount(player)));
    }

    private String categoryLabel(String category) {
        return FISH_CATEGORY.equals(category) ? lang.get("autosell-menu-category-fish") : PriceManager.humanize(category);
    }

    // ------------------------------------------------------------------
    // Menu
    // ------------------------------------------------------------------

    private final class Session implements InventoryHolder {
        private final Player player;
        private final List<Entry> all;
        private final List<String> categories = new ArrayList<>();
        private Inventory inventory;

        private int categoryIndex = -1; // -1 = toutes les catégories
        private View view = View.ALL;
        private int page = 1;
        private long resetArmedAt = 0L;
        private List<Entry> shown = List.of();

        private Session(Player player) {
            this.player = player;
            this.all = entries(player);
            if (all.stream().anyMatch(Entry::fish)) {
                categories.add(FISH_CATEGORY);
            }
            categories.addAll(priceManager.getCategories());
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private List<Entry> filtered() {
            Set<String> playerExceptions = exceptions.getExceptions(player.getUniqueId());
            String category = categoryIndex < 0 ? null : categories.get(categoryIndex);
            List<Entry> result = new ArrayList<>();
            for (Entry entry : all) {
                if (category != null && !category.equalsIgnoreCase(entry.category())) {
                    continue;
                }
                boolean exception = playerExceptions.contains(entry.key());
                if (view == View.ALL || (view == View.EXCEPTIONS) == exception) {
                    result.add(entry);
                }
            }
            return result;
        }

        private int totalPages() {
            return Math.max(1, (int) Math.ceil((double) shown.size() / ITEMS_PER_PAGE));
        }

        private String title() {
            return lang.get("autosell-menu-title", Map.of(
                    "page", String.valueOf(page),
                    "totalPages", String.valueOf(totalPages())));
        }

        private void render() {
            shown = filtered();
            page = Math.max(1, Math.min(page, totalPages()));
            inventory.clear();

            Set<String> playerExceptions = exceptions.getExceptions(player.getUniqueId());
            int start = (page - 1) * ITEMS_PER_PAGE;
            int end = Math.min(start + ITEMS_PER_PAGE, shown.size());
            for (int i = start; i < end; i++) {
                Entry entry = shown.get(i);
                inventory.setItem(i - start, entryItem(entry, playerExceptions.contains(entry.key())));
            }

            if (page > 1) {
                ItemStack prev = icon("previous", Material.PAPER, 10012);
                decorate(prev, lang.get("shop-inventory-previous-page"), List.of(), false);
                inventory.setItem(PREV_SLOT, prev);
            }
            if (page < totalPages()) {
                ItemStack next = icon("next", Material.PAPER, 10011);
                decorate(next, lang.get("shop-inventory-next-page"), List.of(), false);
                inventory.setItem(NEXT_SLOT, next);
            }

            inventory.setItem(CATEGORY_SLOT, categoryButton());
            inventory.setItem(VIEW_SLOT, viewButton());

            ItemStack bulk = icon("bulk", Material.PAPER, 10021);
            decorate(bulk, lang.get("autosell-menu-bulk-name"),
                    lang.getList("autosell-menu-bulk-lore", Map.of("count", String.valueOf(shown.size()))), false);
            inventory.setItem(BULK_SLOT, bulk);

            boolean active = isActive(player);
            ItemStack toggle = new ItemStack(active ? Material.CYAN_DYE : Material.LIGHT_BLUE_DYE);
            decorate(toggle, lang.get(active ? "autosell-menu-toggle-off-name" : "autosell-menu-toggle-on-name"),
                    lang.getList("autosell-menu-toggle-lore", Map.of(
                            "state", lang.get(active ? "autosell-menu-state-on" : "autosell-menu-state-off"),
                            "exceptions", String.valueOf(exceptionCount(player)))),
                    false);
            inventory.setItem(TOGGLE_SLOT, toggle);

            boolean armed = System.currentTimeMillis() - resetArmedAt < RESET_CONFIRM_MILLIS;
            ItemStack reset = icon("reset", Material.PAPER, 10024);
            List<String> resetLore = new ArrayList<>(lang.getList(
                    armed ? "autosell-menu-reset-armed-lore" : "autosell-menu-reset-lore"));
            if (exceptions.isDefault(player.getUniqueId())) {
                resetLore.addAll(lang.getList("autosell-menu-reset-already-default"));
            }
            decorate(reset, lang.get(armed ? "autosell-menu-reset-armed-name" : "autosell-menu-reset-name"),
                    resetLore, false);
            inventory.setItem(RESET_SLOT, reset);

            ItemStack info = icon("info", Material.PAPER, 10181);
            decorate(info, lang.get("autosell-menu-info-name"), lang.getList("autosell-menu-info-lore"), false);
            inventory.setItem(INFO_SLOT, info);

            // Le titre porte la page : on ne le change qu'une fois le menu ouvert (au premier
            // rendu, il est posé par createInventory).
            if (player.getOpenInventory().getTopInventory().getHolder() == this) {
                player.getOpenInventory().setTitle(title());
            }
        }

        private ItemStack entryItem(Entry entry, boolean exception) {
            ItemStack stack = entry.sample().clone();
            stack.setAmount(1);

            List<String> lore = new ArrayList<>();
            if (entry.fish()) {
                lore.addAll(lang.getList("autosell-menu-entry-fish-lore", Map.of(
                        "price", econ.format(entry.unitPrice()))));
            } else {
                int stackSize = stack.getMaxStackSize();
                lore.addAll(lang.getList(stackSize > 1 ? "autosell-menu-entry-lore" : "autosell-menu-entry-lore-unstackable",
                        Map.of("price", econ.format(entry.unitPrice()),
                                "stack_price", econ.format(entry.unitPrice() * stackSize),
                                "stack", String.valueOf(stackSize),
                                "category", categoryLabel(entry.category()))));
            }
            lore.add(lang.get(exception ? "autosell-menu-entry-exception" : "autosell-menu-entry-sold"));
            lore.add("");
            lore.add(lang.get(exception ? "autosell-menu-entry-action-remove" : "autosell-menu-entry-action-add"));

            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                if (!entry.fish()) {
                    // Nom traduit par le client (français pour un joueur en français) plutôt
                    // que le nom du matériau en anglais. Les poissons gardent leur nom de
                    // palier coloré, posé par CustomFishingHook.
                    meta.displayName(Component.translatable(stack.getType().getItemTranslationKey())
                            .color(GOLD).decoration(TextDecoration.ITALIC, false));
                }
                meta.setLore(lore);
                meta.setEnchantmentGlintOverride(exception);
                stack.setItemMeta(meta);
            }
            return stack;
        }

        private ItemStack categoryButton() {
            List<String> lore = new ArrayList<>();
            lore.add(lang.get("autosell-menu-category-previous"));
            lore.add("");
            lore.add(lang.get(categoryIndex < 0 ? "autosell-menu-option-selected" : "autosell-menu-option",
                    Map.of("option", lang.get("autosell-menu-category-all"))));
            for (int i = 0; i < categories.size(); i++) {
                lore.add(lang.get(i == categoryIndex ? "autosell-menu-option-selected" : "autosell-menu-option",
                        Map.of("option", categoryLabel(categories.get(i)))));
            }
            lore.add("");
            ItemStack stack = icon("category", Material.PAPER, 10017);
            decorate(stack, lang.get("autosell-menu-category-name"), lore, false);
            return stack;
        }

        private ItemStack viewButton() {
            List<String> lore = new ArrayList<>();
            lore.add("");
            for (View option : View.values()) {
                lore.add(lang.get(option == view ? "autosell-menu-option-selected" : "autosell-menu-option",
                        Map.of("option", lang.get("autosell-menu-view-" + option.name().toLowerCase()))));
            }
            lore.add("");
            ItemStack stack = icon("view", Material.PAPER, 10019);
            decorate(stack, lang.get("autosell-menu-view-name"), lore, false);
            return stack;
        }

        private void onClick(int slot, ClickType click) {
            if (slot < ITEMS_PER_PAGE) {
                int index = (page - 1) * ITEMS_PER_PAGE + slot;
                if (index < shown.size()) {
                    toggleException(shown.get(index));
                }
                return;
            }

            UUID uuid = player.getUniqueId();
            switch (slot) {
                case PREV_SLOT -> page--;
                case NEXT_SLOT -> page++;
                case CATEGORY_SLOT -> {
                    int options = categories.size() + 1; // + "toutes"
                    int step = click.isRightClick() ? -1 : 1;
                    categoryIndex = Math.floorMod(categoryIndex + 1 + step, options) - 1;
                    page = 1;
                }
                case VIEW_SLOT -> {
                    view = View.values()[(view.ordinal() + 1) % View.values().length];
                    page = 1;
                }
                case BULK_SLOT -> {
                    if (shown.isEmpty()) {
                        return;
                    }
                    // Clic gauche : tout ce qui est affiché devient exception ; clic droit :
                    // tout ce qui est affiché repart en vente auto.
                    exceptions.setExceptions(uuid, shown.stream().map(Entry::key).toList(), !click.isRightClick());
                    itemManager.refreshLore(player);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f);
                }
                case TOGGLE_SLOT -> {
                    if (!toggle(player)) {
                        return;
                    }
                }
                case RESET_SLOT -> {
                    long now = System.currentTimeMillis();
                    if (now - resetArmedAt < RESET_CONFIRM_MILLIS) {
                        resetArmedAt = 0L;
                        exceptions.resetToDefault(uuid);
                        itemManager.refreshLore(player);
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                        player.sendMessage(lang.get("autosell-menu-reset-done"));
                    } else {
                        resetArmedAt = now;
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
                    }
                }
                default -> {
                    return;
                }
            }
            if (slot == PREV_SLOT || slot == NEXT_SLOT || slot == CATEGORY_SLOT || slot == VIEW_SLOT) {
                player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f);
            }
            render();
        }

        private void toggleException(Entry entry) {
            UUID uuid = player.getUniqueId();
            boolean nowException = !exceptions.getExceptions(uuid).contains(entry.key());
            exceptions.setExceptions(uuid, List.of(entry.key()), nowException);
            itemManager.refreshLore(player);
            player.playSound(player.getLocation(),
                    nowException ? Sound.BLOCK_AMETHYST_BLOCK_HIT : Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                    1.0f, nowException ? 0.8f : 1.4f);
            render();
        }
    }

    // ------------------------------------------------------------------
    // Événements
    // ------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Session session)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        session.onClick(event.getSlot(), event.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Session) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // Icônes
    // ------------------------------------------------------------------

    private ItemStack icon(String key, Material fallbackMaterial, int fallbackModelData) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("autosell-menu.icons." + key);
        Material material = fallbackMaterial;
        int modelData = fallbackModelData;
        if (section != null) {
            Material configured = Material.matchMaterial(section.getString("material", fallbackMaterial.name()));
            material = configured == null ? fallbackMaterial : configured;
            modelData = section.getInt("custom-model-data", fallbackModelData);
        }
        ItemStack stack = new ItemStack(material);
        if (modelData > 0) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setCustomModelData(modelData);
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    private void decorate(ItemStack stack, String name, List<String> lore, boolean glint) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.setDisplayName(name);
        meta.setLore(lore);
        if (glint) {
            meta.setEnchantmentGlintOverride(true);
        }
        stack.setItemMeta(meta);
    }
}
