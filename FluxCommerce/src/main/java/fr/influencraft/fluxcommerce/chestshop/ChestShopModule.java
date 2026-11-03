package fr.influencraft.fluxcommerce.chestshop;

import fr.influencraft.flux.api.claims.Claims;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.shops.ChestShops;
import fr.influencraft.flux.api.text.ChatHelp;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxcommerce.FluxCommerce;
import fr.influencraft.fluxcommerce.Module;
import fr.influencraft.fluxcommerce.api.Shops;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Chest shops of FluxCommerce: shops of the players, written for the suite (they replace QuickShop-Hikari and its
 * former fork FluxChestShop). A player turns a container into a shop with sneak + right click and the item in hand, in a window
 * (sell or buy, items per bundle, price of a bundle, options of other plugins); customers right click it and trade in
 * Flux's trade window; the owner fills it and his friends may too. No sign: the item and its price float above the
 * container. Data in Flux's database ({@link ShopStore}), shops shown to the suite through {@link ChestShops}
 * (FluxLands: town, market fee, town shops; the auction house: market). API for imports: {@link Shops}.
 *
 * <pre>
 *   /echoppe [help] | create | settings | list | admin &lt;remove|info|reload&gt;
 * </pre>
 */
public final class ChestShopModule extends Module {

    static final String USE = "fluxcommerce.chestshop.use", CREATE = "fluxcommerce.chestshop.create",
            ADMIN = "fluxcommerce.chestshop.admin";
    static final String LIMIT = "fluxcommerce.chestshop.limit.";

    private final ShopStore store = new ShopStore(getLogger());
    private final ShopRegistry registry = new ShopRegistry(this, store);
    private final Displays displays = new Displays(this);
    private final Signs signs = new Signs(this);
    private final Trades trades = new Trades(this);
    private final ShopForms forms = new ShopForms(this);

    // settings
    private final Set<Material> containers = EnumSet.noneOf(Material.class);
    private Set<String> worlds = Set.of(), disabledWorlds = Set.of();
    private long minPrice, maxPrice;
    private int defaultLimit;

    public ChestShopModule(FluxCommerce host) {
        super(host, "chestshop", "chestshop");
    }

    @Override
    protected void enable() {
        settings();
        store.open();
        registry.load();
        for (Shop s : registry.shops()) signs.index(s);
        ChestShops.register(host, registry);
        Shops.bind(new ShopImports(this));
        Bukkit.getPluginManager().registerEvents(new ShopListener(this), host);
        command("chestshop")
                .description("Chest shops: create and manage your shops.")
                .aliases("echoppe")
                .executor(this::onCommand)
                .completer(this::complete)
                .register();
        // stock counts written in the background every 30 s
        Bukkit.getScheduler().runTaskTimer(host, () -> registry.flushStocks(false), 600L, 600L);
        Bukkit.getScheduler().runTask(host, displays::showAll);
        displays.start();
        getLogger().info(registry.shops().size() + " shop(s) loaded.");
    }

    @Override
    protected void disable() {
        displays.hideAll();
        registry.flushStocks(true);
        Shops.bind(null);
    }

    @Override
    public void reload() {
        super.reload();
        settings();
        displays.hideAll();
        displays.showAll();
        displays.start();
    }

    /** How a new shop is shown when its creator does not choose: {@code display.default} (sign or floating). */
    String defaultLook() {
        return Shop.FLOATING.equalsIgnoreCase(getConfig().getString("display.default", Shop.SIGN)) ? Shop.FLOATING : Shop.SIGN;
    }

    String defaultWood() {
        String wood = getConfig().getString("display.default-wood", "OAK").toUpperCase(Locale.ROOT);
        return signs.woods().containsKey(wood) ? wood : "OAK";
    }

    private void settings() {
        var c = getConfig();
        containers.clear();
        for (String m : c.getStringList("containers")) {
            Material mat = Material.matchMaterial(m);
            if (mat == null) getLogger().warning("Unknown material in containers: " + m);
            else containers.add(mat);
        }
        if (containers.isEmpty()) {
            getLogger().warning("containers is empty: chests and barrels used");
            containers.addAll(List.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL));
        }
        worlds = lower(c.getStringList("worlds"));
        disabledWorlds = lower(c.getStringList("disabled-worlds"));
        minPrice = Math.max(1, c.getLong("price.min", 1));
        maxPrice = Math.max(minPrice, c.getLong("price.max", 1_000_000_000L));
        defaultLimit = c.getInt("limits.default", 0);
    }

    private static Set<String> lower(List<String> list) {
        Set<String> out = new HashSet<>();
        for (String s : list) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    // ---------------------------------------------------------------- rules

    boolean isContainer(Block block) {
        return block != null && containers.contains(block.getType());
    }

    boolean allowedWorld(World w) {
        String name = w.getName().toLowerCase(Locale.ROOT);
        if (!worlds.isEmpty()) return worlds.contains(name);
        return !disabledWorlds.contains(name);
    }

    long minPrice() {
        return minPrice;
    }

    long maxPrice() {
        return maxPrice;
    }

    /** Owner, or staff. */
    boolean mayManage(Player p, Shop shop) {
        return shop.isOwner(p.getUniqueId()) || p.hasPermission(ADMIN);
    }

    /** Why this player may not make a shop of this container with this item (a lang key), or null. */
    String mayCreate(Player p, Block block, ItemStack item) {
        if (!p.hasPermission(CREATE)) return "no-permission";
        if (host.bans().of(p.getUniqueId()) != null) return "banned";
        if (block == null || !isContainer(block) || !block.getChunk().isLoaded()) return "not-a-container";
        if (!allowedWorld(block.getWorld())) return "world-disabled";
        if (registry.at(block) != null) return "already-shop";
        if (item == null || item.getType().isAir()) return "hold-item";
        if (blacklisted(item)) return "item-refused";
        if (!p.hasPermission(ADMIN) && !Claims.mayOpenContainers(block.getLocation(), p.getUniqueId())) return "not-your-container";
        int limit = limit(p);
        if (limit > 0 && registry.ownedBy(p.getUniqueId()).size() >= limit) return "limit-reached";
        return null;
    }

    /** Forbidden in all commerce ({@code blacklist} of the main config). */
    private boolean blacklisted(ItemStack item) {
        return host.rules().forbidden(item);
    }

    /** Most shops this player may own: the highest fluxcommerce.chestshop.limit.&lt;n&gt;, else limits.default (0 = no limit). */
    int limit(Player p) {
        if (p.hasPermission(ADMIN) || p.hasPermission(LIMIT + "unlimited")) return 0;
        int best = -1;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            String node = info.getPermission();
            if (!info.getValue() || !node.startsWith(LIMIT)) continue;
            try {
                best = Math.max(best, Integer.parseInt(node.substring(LIMIT.length())));
            } catch (NumberFormatException ignored) {
                // fluxcommerce.chestshop.limit.unlimited, checked above
            }
        }
        return best >= 0 ? best : defaultLimit;
    }

    /** Removes a shop; the container stays with its content. */
    void removeShop(Shop shop, CommandSender by) {
        displays.remove(shop);
        signs.remove(shop);
        registry.remove(shop);
        getLogger().info((by == null ? "?" : by.getName()) + " removed shop " + shop.id + " (" + shop.ownerName + ", "
                + shop.item.getType().getKey() + ") at " + shop.key());
        if (by instanceof Player p) {
            send(p, "removed", shop.item);
            Sounds.cue(p, "remove");
        }
    }

    // ---------------------------------------------------------------- texts

    /** Price of one item of the shop, e.g. "10 000 (glyph)/u" ({@code unit-price} of the lang file). */
    String unitPrice(Shop s) {
        return messages().raw("unit-price", "%amount%", Format.decimal(s.unitPrice()));
    }

    String price(double amount) {
        Currency money = money();
        return money == null ? Format.decimal(amount) : money.format(amount);
    }

    /** Name of an item for the account history: its custom name, else translated by the reader's client. */
    String itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) return Texts.plain(meta.displayName());
        return "<lang:" + item.getType().translationKey() + ">";
    }

    /** The item's name as each client writes it (custom name kept). */
    static Component itemComponent(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) return meta.displayName();
        if (meta != null && meta.hasItemName()) return meta.itemName();
        return Component.translatable(item.getType().translationKey());
    }

    /** A message of the lang file whose {@code %item%} is the item's name (in the player's language). */
    void send(Player to, String key, ItemStack item, String... vars) {
        String prefix = messages().raw("prefix");
        Component name = itemComponent(item).hoverEvent(item.asHoverEvent());
        for (String line : messages().rawList(key, vars)) {
            if (line.isEmpty()) continue;
            Component c = Texts.parseClickable(line.startsWith("!") ? line.substring(1) : prefix + line, to);
            to.sendMessage(c.replaceText(TextReplacementConfig.builder().matchLiteral("%item%").replacement(name).build()));
        }
    }

    // ---------------------------------------------------------------- command

    private static final List<String> SUBCOMMANDS = List.of("help", "create", "settings", "list", "admin");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("help", "info", "remove", "reload");
    private static final List<ChatHelp.Entry> HELP = List.of(new ChatHelp.Entry("create", "create", CREATE),
            new ChatHelp.Entry("settings", "settings", CREATE), new ChatHelp.Entry("list", "list", CREATE),
            new ChatHelp.Entry("help [page]", "help", null), new ChatHelp.Entry("admin help", "admin", ADMIN));
    private static final List<ChatHelp.Entry> ADMIN_HELP = List.of(new ChatHelp.Entry("admin info", "admin-info", ADMIN),
            new ChatHelp.Entry("admin remove", "admin-remove", ADMIN), new ChatHelp.Entry("admin reload", "admin-reload", ADMIN),
            new ChatHelp.Entry("admin help [page]", "admin-help", ADMIN));

    /** Paged help (Flux's ChatHelp, the look of Lands' and FluxPW's help); {@code pageArg}: index of the page number. */
    private void help(CommandSender sender, String label, String[] args, boolean admin, int pageArg) {
        int page = 1;
        if (args.length > pageArg) {
            try {
                page = Integer.parseInt(args[pageArg]);
            } catch (NumberFormatException ignored) {
                // first page
            }
        }
        ChatHelp.show(sender, messages(), label, admin ? ADMIN_HELP : HELP, admin ? "admin help" : "help", admin, page,
                sender::hasPermission);
    }

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("admin")) return admin(sender, label, args);
        if (sub.equals("help")) {
            help(sender, label, args, false, 1);
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only (console: /" + label + " admin reload).");
            return true;
        }
        switch (sub) {
            case "create" -> {
                Block b = target(p);
                if (b == null || !isContainer(b)) {
                    messages().send(p, "look-at-container");
                    return true;
                }
                forms.create(p, b, p.getInventory().getItemInMainHand());
            }
            case "settings" -> {
                Shop s = registry.at(target(p));
                if (s == null) messages().send(p, "look-at-shop");
                else forms.settings(p, s);
            }
            case "list" -> list(p);
            default -> help(p, label, new String[0], false, 1);
        }
        return true;
    }

    private boolean admin(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            messages().send(sender, "no-permission");
            return true;
        }
        String sub = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            host.reload();
            messages().send(sender, "reloaded");
            return true;
        }
        if (sub.equals("help") || sub.isEmpty() || !(sender instanceof Player)) {
            help(sender, label, args, true, 2);
            return true;
        }
        Player p = (Player) sender;
        Shop s = registry.at(target(p));
        switch (sub) {
            case "remove" -> {
                if (s == null) messages().send(p, "look-at-shop");
                else removeShop(s, p);
            }
            case "info" -> {
                if (s == null) {
                    messages().send(p, "look-at-shop");
                } else {
                    registry.count(s);
                    send(p, "info", s.item, "%id%", String.valueOf(s.id), "%owner%", s.ownerName == null ? "?" : s.ownerName,
                            "%mode%", messages().raw(s.buying ? "mode-buying" : "mode-selling"), "%bundle%", String.valueOf(s.bundle),
                            "%price%", price(s.price), "%stock%", s.unlimited ? messages().raw("form.unlimited") : String.valueOf(s.stock),
                            "%friends%", String.valueOf(s.friends.size()), "%options%", s.options.isEmpty() ? "-" : String.join(", ", s.options));
                }
            }
            default -> help(p, label, new String[0], true, 2);
        }
        return true;
    }

    private void list(Player p) {
        List<Shop> mine = registry.ownedBy(p.getUniqueId());
        if (mine.isEmpty()) {
            messages().send(p, "list-none");
            return;
        }
        messages().send(p, "list-header", "%count%", String.valueOf(mine.size()));
        for (Shop s : mine) {
            send(p, "list-line", s.item, "%mode%", messages().raw(s.buying ? "mode-buying" : "mode-selling"),
                    "%bundle%", String.valueOf(s.bundle), "%price%", price(s.price),
                    "%stock%", s.unlimited ? messages().raw("form.unlimited") : s.stock < 0 ? "?" : String.valueOf(s.stock),
                    "%place%", s.x + " " + s.y + " " + s.z, "%world%", s.world);
        }
    }

    private List<String> complete(CommandSender sender, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(SUBCOMMANDS);
            if (!sender.hasPermission(ADMIN)) options.remove("admin");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin") && sender.hasPermission(ADMIN)) {
            options.addAll(ADMIN_SUBCOMMANDS);
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.startsWith(typed));
        return options;
    }

    private static Block target(Player p) {
        return p.getTargetBlockExact(6);
    }

    // ---------------------------------------------------------------- accessors

    ShopRegistry registry() {
        return registry;
    }

    ShopStore store() {
        return store;
    }

    Displays displays() {
        return displays;
    }

    Signs signs() {
        return signs;
    }

    Trades trades() {
        return trades;
    }

    ShopForms forms() {
        return forms;
    }
}
