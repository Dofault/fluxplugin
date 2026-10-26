package io.dofault.supermarket.shop;

import io.dofault.supermarket.managers.LangManager;
import io.dofault.supermarket.managers.PriceManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Éditeur d'un coffre magasin, ouvert par un admin accroupi + clic droit sur le coffre.
 * <ul>
 * <li>clic sur un objet de son propre inventaire : copie exacte ajoutée au coffre (booster InfluPet,
 * objet ItemsAdder...). Un objet vanilla qui a un prix d'achat dans {@code prices.yml} le suit en
 * direct ; sinon le prix est demandé dans le tchat ;</li>
 * <li>« Changer le prix » : prix propre au coffre, saisi dans le tchat ({@code défaut} revient à
 * {@code prices.yml} pour un objet vanilla) ;</li>
 * <li>« Retirer » : double clic de confirmation, comme l'achat.</li>
 * </ul>
 */
public class ShopEditor implements Listener {

    private static final int ITEM_COLUMN = 2;
    private static final int PRICE_COLUMN = 4;
    private static final int REMOVE_COLUMN = 6;
    private static final int[] FILLER_COLUMNS = { 0, 1, 7, 8 };
    private static final long PROMPT_TIMEOUT_MILLIS = 60_000L;

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final PriceManager priceManager;
    private final ShopRegistry registry;
    private final MenuItems menuItems;

    /** Saisie de prix en attente dans le tchat, par joueur (le tchat est asynchrone). */
    private final Map<UUID, PricePrompt> prompts = new ConcurrentHashMap<>();

    private record PricePrompt(Shop shop, ShopEntry entry, boolean isNew, long expiresAt) {
    }

    public ShopEditor(JavaPlugin plugin, LangManager lang, PriceManager priceManager, ShopRegistry registry,
            Economy econ) {
        this.plugin = plugin;
        this.lang = lang;
        this.priceManager = priceManager;
        this.registry = registry;
        this.menuItems = new MenuItems(plugin, lang, econ);
    }

    public void open(Player player, Shop shop) {
        Session session = new Session(player, shop);
        int rows = Math.min(Shop.MAX_ITEMS, shop.getItems().size() + 1);
        session.inventory = Bukkit.createInventory(session, rows * 9, lang.get("shop-editor-title"));
        session.render();
        player.openInventory(session.inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 0.9f);
    }

    // ------------------------------------------------------------------
    // Session (un éditeur ouvert)
    // ------------------------------------------------------------------

    private final class Session implements InventoryHolder {

        private final Player player;
        private final Shop shop;
        private Inventory inventory;
        private int armedRow = -1;
        private BukkitTask disarmTask;

        private Session(Player player, Shop shop) {
            this.player = player;
            this.shop = shop;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void render() {
            inventory.clear();
            ItemStack filler = menuItems.filler();
            List<ShopEntry> entries = shop.getItems();
            int rows = inventory.getSize() / 9;
            for (int row = 0; row < rows; row++) {
                int base = row * 9;
                for (int column : FILLER_COLUMNS) {
                    inventory.setItem(base + column, filler);
                }
                if (row >= entries.size()) {
                    inventory.setItem(base + PRICE_COLUMN, MenuItems.decorate(
                            menuItems.icon("editor-add", Material.PAPER, 10021, 1),
                            lang.get("shop-editor-add-name"), lang.getList("shop-editor-add-lore")));
                    continue;
                }
                ShopEntry entry = entries.get(row);
                inventory.setItem(base + ITEM_COLUMN, MenuItems.withExtraLore(entry, priceLore(entry)));
                inventory.setItem(base + PRICE_COLUMN, MenuItems.decorate(
                        menuItems.icon("editor-price", Material.PAPER, 10040, 1),
                        lang.get("shop-editor-price-name"), lang.getList("shop-editor-price-lore")));
                boolean armed = row == armedRow;
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
                inventory.setItem(base + REMOVE_COLUMN, remove);
            }
        }

        private List<String> priceLore(ShopEntry entry) {
            double unit = entry.unitPrice(priceManager);
            String price = unit > 0 ? menuItems.price(unit) : lang.get("shop-menu-unavailable");
            return lang.getList(entry.getPrice() != null ? "shop-editor-item-lore-custom" : "shop-editor-item-lore-default",
                    Map.of("price", price));
        }

        private void onTopClick(int slot) {
            int row = slot / 9;
            int column = slot % 9;
            List<ShopEntry> entries = shop.getItems();
            if (row >= entries.size()) {
                return;
            }
            ShopEntry entry = entries.get(row);
            if (column == PRICE_COLUMN) {
                disarm();
                prompt(player, shop, entry, false);
            } else if (column == REMOVE_COLUMN) {
                if (armedRow != row) {
                    arm(row);
                    return;
                }
                disarm();
                entries.remove(entry);
                registry.save();
                MenuItems.send(player, lang.get("shop-editor-removed"), entry.getItem());
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.6f, 1.0f);
                reopen();
            }
        }

        private void onOwnItemClick(ItemStack clicked) {
            if (clicked == null || clicked.getType().isAir()) {
                return;
            }
            List<ShopEntry> entries = shop.getItems();
            if (entries.stream().anyMatch(entry -> entry.matches(clicked))) {
                fail(player, "shop-editor-already");
                return;
            }
            if (entries.size() >= Shop.MAX_ITEMS) {
                player.sendMessage(lang.get("shop-editor-full", Map.of("max", String.valueOf(Shop.MAX_ITEMS))));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }
            ShopEntry entry = new ShopEntry(clicked, null);
            if (entry.isPlain() && priceManager.isBuyable(clicked.getType().name())) {
                entries.add(entry);
                registry.save();
                MenuItems.send(player, lang.get("shop-editor-added-default"), entry.getItem());
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
                reopen();
                return;
            }
            prompt(player, shop, entry, true);
        }

        /**
         * Rouvre l'éditeur (le nombre de rangées suit le nombre d'objets), un tick plus tard :
         * Bukkit déconseille d'ouvrir un inventaire pendant un événement de clic.
         */
        private void reopen() {
            disarm();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && registry.isRegistered(shop)) {
                    open(player, shop);
                }
            });
        }

        private void arm(int row) {
            disarm();
            armedRow = row;
            long timeout = Math.max(1, plugin.getConfig().getLong("shop-menu.confirm-timeout-seconds", 5)) * 20L;
            disarmTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                disarmTask = null;
                armedRow = -1;
                if (player.getOpenInventory().getTopInventory().getHolder() == this) {
                    render();
                }
            }, timeout);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 0.7f);
            render();
        }

        private void disarm() {
            if (disarmTask != null) {
                disarmTask.cancel();
                disarmTask = null;
            }
            armedRow = -1;
        }
    }

    // ------------------------------------------------------------------
    // Saisie du prix dans le tchat
    // ------------------------------------------------------------------

    private void prompt(Player player, Shop shop, ShopEntry entry, boolean isNew) {
        prompts.put(player.getUniqueId(),
                new PricePrompt(shop, entry, isNew, System.currentTimeMillis() + PROMPT_TIMEOUT_MILLIS));
        // Fermeture décalée d'un tick : appelée depuis un événement de clic.
        Bukkit.getScheduler().runTask(plugin, () -> player.closeInventory());
        MenuItems.send(player, lang.get(entry.isPlain() && !isNew
                ? "shop-editor-price-prompt-default"
                : "shop-editor-price-prompt"), entry.getItem());
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.2f);
    }

    // LOWEST : la saisie doit être interceptée avant que les plugins de tchat ne la diffusent.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PricePrompt prompt = prompts.remove(player.getUniqueId());
        if (prompt == null) {
            return;
        }
        if (System.currentTimeMillis() > prompt.expiresAt()) {
            return; // saisie abandonnée : le message part normalement dans le tchat
        }
        event.setCancelled(true);
        String input = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> handleInput(player, prompt, input));
    }

    private void handleInput(Player player, PricePrompt prompt, String input) {
        if (!player.isOnline()) {
            return;
        }
        Shop shop = prompt.shop();
        ShopEntry entry = prompt.entry();
        String normalized = input.toLowerCase(Locale.ROOT);

        if (normalized.equals("annuler")) {
            player.sendMessage(lang.get("shop-editor-price-cancelled"));
            reopenIfRegistered(player, shop);
            return;
        }
        if (!registry.isRegistered(shop)) {
            player.sendMessage(lang.get("shop-admin-not-a-chest"));
            return;
        }

        Double price;
        if (normalized.equals("défaut") || normalized.equals("defaut")) {
            if (!entry.isPlain() || !priceManager.isBuyable(entry.getItem().getType().name())) {
                retry(player, prompt, "shop-editor-price-no-default");
                return;
            }
            price = null;
        } else {
            try {
                double value = Double.parseDouble(normalized.replace(',', '.'));
                if (!(value > 0) || Double.isInfinite(value)) {
                    throw new NumberFormatException();
                }
                price = PriceManager.roundMoney(value);
            } catch (NumberFormatException e) {
                retry(player, prompt, "shop-editor-price-invalid");
                return;
            }
        }

        if (prompt.isNew()) {
            List<ShopEntry> entries = shop.getItems();
            if (entries.size() >= Shop.MAX_ITEMS || entries.stream().anyMatch(other -> other.matches(entry.getItem()))) {
                player.sendMessage(lang.get("shop-editor-full", Map.of("max", String.valueOf(Shop.MAX_ITEMS))));
                reopenIfRegistered(player, shop);
                return;
            }
            entry.setPrice(price);
            entries.add(entry);
        } else {
            entry.setPrice(price);
        }
        registry.save();
        MenuItems.send(player, lang.get("shop-editor-price-set", Map.of(
                "price", menuItems.price(entry.unitPrice(priceManager)))), entry.getItem());
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        reopenIfRegistered(player, shop);
    }

    private void retry(Player player, PricePrompt prompt, String messageKey) {
        player.sendMessage(lang.get(messageKey));
        prompts.put(player.getUniqueId(), new PricePrompt(prompt.shop(), prompt.entry(), prompt.isNew(),
                System.currentTimeMillis() + PROMPT_TIMEOUT_MILLIS));
    }

    private void reopenIfRegistered(Player player, Shop shop) {
        if (registry.isRegistered(shop)) {
            open(player, shop);
        }
    }

    private void fail(Player player, String key) {
        player.sendMessage(lang.get(key));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
    }

    // ------------------------------------------------------------------
    // Événements
    // ------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Session session)) {
            return;
        }
        // Rien ne bouge, ni dans l'éditeur ni dans l'inventaire de l'admin : un clic sur un de ses
        // objets en ajoute seulement une copie au coffre.
        event.setCancelled(true);
        if (event.getClick() != ClickType.LEFT || event.getClickedInventory() == null) {
            return;
        }
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            session.onTopClick(event.getSlot());
        } else {
            session.onOwnItemClick(event.getCurrentItem());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Session) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Session session) {
            session.disarm();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        prompts.remove(event.getPlayer().getUniqueId());
    }
}
