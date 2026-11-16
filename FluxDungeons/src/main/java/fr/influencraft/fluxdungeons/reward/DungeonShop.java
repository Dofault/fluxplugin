package fr.influencraft.fluxdungeons.reward;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.pets.Companions;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.trade.TradeWindow;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxdungeons.dungeon.DungeonDefinition;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The dungeon shop (2026-11-16): what a dungeon gives is tokens, and tokens buy everything here. One window, a page per
 * floor of the tower (the dungeon's job): the boss's mount, sold only to those who beat that boss; the job's boosters;
 * the enchanted books of the job; the dungeon's rpg set. Contents and prices in {@code shop.yml}, written by
 * {@code assets/balance/tower.py} (prices in runs of the floor), never by hand.
 * <p>
 * Menu {@code donjon_boutique} (written by {@code assets/menus/gen_menus.py}): lists {@code boutique_boutons},
 * {@code boutique_etages} (one button per floor), {@code boutique_articles} (the page shown); a click on an article
 * opens Flux's buying window ({@link TradeWindow}) for it. Semi-rp things (boosters, books) are vanilla items: bought
 * from the rpg lobby they wait in {@code /recompenses} (Flux delivers by universe).
 */
public final class DungeonShop {

    public static final String MENU = "donjon_boutique";

    record Article(String kind, String id, int level, double price) {
    }

    record Page(String dungeon, String job, List<Article> articles) {
    }

    private final FluxPlugin plugin;
    private final Supplier<Collection<DungeonDefinition>> dungeons;
    private final DungeonTokens tokens;
    private final DungeonVictories victories;
    private final java.util.function.Function<String, String> jobName;
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<UUID, String> shown = new ConcurrentHashMap<>();

    public DungeonShop(FluxPlugin plugin, Supplier<Collection<DungeonDefinition>> dungeons, DungeonTokens tokens,
                       DungeonVictories victories, java.util.function.Function<String, String> jobName) {
        this.plugin = plugin;
        this.dungeons = dungeons;
        this.tokens = tokens;
        this.victories = victories;
        this.jobName = jobName;
        Lists.register(plugin, "boutique_boutons", q -> buttons(q.viewer()));
        Lists.register(plugin, "boutique_etages", q -> floors(q.viewer()));
        Lists.register(plugin, "boutique_articles", q -> articles(q.viewer()));
    }

    /** Reads shop.yml (written by tower.py; nothing for sale without it). */
    public void reload() {
        pages.clear();
        File file = new File(plugin.getDataFolder(), "shop.yml");
        if (!file.isFile()) {
            plugin.getLogger().warning("No shop.yml: the dungeon shop is empty (run assets/balance/tower.py --write).");
            return;
        }
        ConfigurationSection floors = YamlConfiguration.loadConfiguration(file).getConfigurationSection("floors");
        if (floors == null) return;
        for (String dungeon : floors.getKeys(false)) {
            ConfigurationSection f = floors.getConfigurationSection(dungeon);
            if (f == null) continue;
            List<Article> list = new ArrayList<>();
            for (Map<?, ?> m : f.getMapList("items")) {
                String kind = String.valueOf(m.get("kind"));
                String id = String.valueOf(switch (kind) {
                    case "mount" -> m.get("pet");
                    case "booster" -> m.get("job");
                    case "book" -> m.get("enchant");
                    default -> m.get("item");
                });
                list.add(new Article(kind, id, m.get("level") instanceof Number n ? n.intValue() : 1,
                        m.get("price") instanceof Number n ? n.doubleValue() : 0));
            }
            pages.put(dungeon, new Page(dungeon, f.getString("job", ""), list));
        }
    }

    /** Opens the shop on a dungeon's page (null: the one shown last, else the first floor). */
    public void open(Player p, String dungeon) {
        if (dungeon != null && pages.containsKey(dungeon)) shown.put(p.getUniqueId(), dungeon);
        else shown.putIfAbsent(p.getUniqueId(), first());
        invalidate(p);
        FluxApi.open(p, MENU);
    }

    /** {@code page <dungeon>}, {@code buy <n>}. */
    public void act(Player p, String[] args) {
        if (args.length < 2) return;
        switch (args[0]) {
            case "page" -> {
                if (!pages.containsKey(args[1])) return;
                shown.put(p.getUniqueId(), args[1]);
                invalidate(p);
            }
            case "buy" -> {
                Page page = page(p);
                int n;
                try {
                    n = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    return;
                }
                if (page == null || n < 0 || n >= page.articles().size()) return;
                Article a = page.articles().get(n);
                if (!TradeWindow.buy(p, raw("shop.title", "%job%", jobName.apply(page.job())), offer(page, a))) {
                    plugin.messages().send(p, "shop.closed");
                }
            }
            default -> {
            }
        }
    }

    /** {@code shop_floor} (floor shown), {@code shop_title}; null for another one. */
    public String placeholder(Player p, String params) {
        Page page = page(p);
        return switch (params) {
            case "shop_floor" -> page == null ? "0" : String.valueOf(floorOf(page.dungeon()));
            case "shop_title" -> page == null ? "" : Texts.plain(Texts.colors(raw("shop.page", "%job%", jobName.apply(page.job()))));
            default -> null;
        };
    }

    private void invalidate(Player p) {
        for (String l : List.of("boutique_boutons", "boutique_etages", "boutique_articles")) Lists.invalidate(p, l);
    }

    private String first() {
        DungeonDefinition best = null;
        for (DungeonDefinition d : dungeons.get()) {
            if (d.floor() > 0 && pages.containsKey(d.id()) && (best == null || d.floor() < best.floor())) best = d;
        }
        return best != null ? best.id() : pages.isEmpty() ? "" : pages.keySet().iterator().next();
    }

    private Page page(Player p) {
        return pages.get(shown.getOrDefault(p.getUniqueId(), first()));
    }

    private DungeonDefinition definition(String dungeon) {
        for (DungeonDefinition d : dungeons.get()) if (d.id().equals(dungeon)) return d;
        return null;
    }

    private int floorOf(String dungeon) {
        DungeonDefinition d = definition(dungeon);
        return d == null ? 0 : d.floor();
    }

    // ---------------------------------------------------------------- lists

    private List<ListEntry> buttons(Player viewer) {
        List<ListEntry> out = new ArrayList<>();
        out.add(ListEntry.of("back").item(orBarrier(Items.of("itemsadder:fluxui:btn_back_orange", raw("shop.back"),
                Lore.create().left(raw("shop.back-action")).build(), viewer))).set("left", "[menu] donjons"));
        out.add(ListEntry.of("info").item(orBarrier(Items.of("itemsadder:fluxui:btn_info_blue", raw("shop.info"),
                Lore.create().description(raw("shop.info-desc")).description(raw("shop.info-mount")).build(), viewer))).set("left", ""));
        Currency money = tokens.currency();
        out.add(ListEntry.of("tokens").item(orBarrier(Items.of("itemsadder:fluxdonjon:jeton_donjon", raw("menu.tokens"),
                Lore.create().description(raw("menu.tokens-desc"))
                        .kv(raw("menu.tokens-owned"), money == null ? "0" : money.format(money.balance(viewer))).build(), viewer))).set("left", ""));
        return out;
    }

    /** One button per floor, from the bottom of the tower: its boss, its job, its level; the page shown glows. */
    private List<ListEntry> floors(Player viewer) {
        List<DungeonDefinition> sorted = new ArrayList<>();
        for (DungeonDefinition d : dungeons.get()) if (d.floor() > 0 && pages.containsKey(d.id())) sorted.add(d);
        sorted.sort(java.util.Comparator.comparingInt(DungeonDefinition::floor));
        Page current = page(viewer);
        List<ListEntry> out = new ArrayList<>();
        for (DungeonDefinition d : sorted) {
            Page page = pages.get(d.id());
            Lore lore = Lore.create().kv(raw("shop.floor"), d.floor()).kv(raw("menu.level"), d.recommendedLevel())
                    .kv(raw("shop.articles"), page.articles().size());
            boolean on = current != null && current.dungeon().equals(d.id());
            if (on) lore.ok(raw("shop.floor-shown"));
            else lore.left(raw("shop.floor-action"));
            ItemStack item = Items.of("itemsadder:influportraits:portrait_" + d.pet(), raw("shop.page", "%job%", jobName.apply(page.job())), lore.build(), viewer);
            if (item == null) item = Items.dress(new ItemStack(d.icon()), raw("shop.page", "%job%", jobName.apply(page.job())), lore.build(), viewer);
            if (on) item.editMeta(m -> m.setEnchantmentGlintOverride(true));
            out.add(ListEntry.of(d.id()).item(item).set("left", "[fluxdungeon] shop page " + d.id()));
        }
        return out;
    }

    private List<ListEntry> articles(Player viewer) {
        Page page = page(viewer);
        List<ListEntry> out = new ArrayList<>();
        if (page == null) return out;
        Currency money = tokens.currency();
        for (int i = 0; i < page.articles().size(); i++) {
            Article a = page.articles().get(i);
            ItemStack item = item(a, viewer);
            if (item == null) continue;
            Lore lore = Lore.create().kv(raw("shop.price"), money == null ? String.valueOf((long) a.price()) : money.format(a.price()));
            describe(page, a, viewer, lore);
            lore.left(raw("shop.buy-action"));
            List<String> lines = new ArrayList<>(item.hasItemMeta() && item.getItemMeta().hasLore()
                    ? item.getItemMeta().lore().stream().map(Texts::ampersand).toList() : List.of());
            lines.addAll(lore.build());
            String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? Texts.ampersand(item.getItemMeta().displayName()) : null;
            out.add(ListEntry.of(String.valueOf(i)).item(Items.dress(item.clone(), name, lines, viewer))
                    .set("left", "[fluxdungeon] shop buy " + i));
        }
        return out;
    }

    /** Why an article can or cannot be bought, under its price. */
    private void describe(Page page, Article a, Player viewer, Lore lore) {
        switch (a.kind()) {
            case "mount" -> {
                lore.description(raw("shop.mount-desc"));
                if (Companions.owns(viewer, a.id())) lore.ok(raw("shop.mount-owned"));
                else if (victories != null && victories.beaten(viewer, page.dungeon())) lore.ok(raw("shop.mount-unlocked"));
                else lore.ko(raw("shop.mount-locked"));
            }
            case "booster", "book" -> lore.info(raw("shop.semi-rp"));
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- articles

    /** The article as shown and as given (one of it), null when it cannot be made (unknown pet, item or enchantment). */
    private ItemStack item(Article a, Player viewer) {
        return switch (a.kind()) {
            case "mount" -> {
                ItemStack portrait = Companions.portrait(a.id());
                String name = Companions.name(a.id());
                if (portrait == null) yield null;
                yield Items.dress(portrait.clone(), raw("shop.mount", "%pet%", name == null ? a.id() : name), List.of(), viewer);
            }
            case "booster" -> CustomItems.item("fluxpets:booster_metier_" + a.id(), viewer);
            case "book" -> book(a.id(), a.level());
            default -> CustomItems.item(a.id(), viewer);
        };
    }

    /** An enchanted book of one custom enchantment (EcoEnchants registers them in the game's registry). */
    private static ItemStack book(String enchant, int level) {
        Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(enchant));
        if (e == null) return null;
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        book.editMeta(EnchantmentStorageMeta.class, m -> m.addStoredEnchant(e, Math.max(1, level), true));
        return book;
    }

    private TradeWindow.Offer offer(Page page, Article a) {
        return new TradeWindow.Offer() {
            public ItemStack item() {
                ItemStack i = DungeonShop.this.item(a, null);
                return i == null ? new ItemStack(Material.BARRIER) : i;
            }

            public double price() {
                return a.price();
            }

            public String currency() {
                return tokens.currencyId();
            }

            public int available() {
                return a.kind().equals("mount") ? 1 : Integer.MAX_VALUE;
            }

            public void describe(Player viewer, Lore lore) {
                DungeonShop.this.describe(page, a, viewer, lore);
            }

            public boolean buy(Player player, int bundles) {
                return DungeonShop.this.buy(player, page, a, bundles);
            }
        };
    }

    private boolean buy(Player p, Page page, Article a, int bundles) {
        if (bundles <= 0) return false;
        if (a.kind().equals("mount")) {
            if (bundles != 1) return false;
            if (Companions.owns(p, a.id())) {
                plugin.messages().send(p, "shop.mount-owned-error");
                return false;
            }
            if (victories == null || !victories.beaten(p, page.dungeon())) {
                plugin.messages().send(p, "shop.mount-locked-error");
                Sounds.cue(p, "error");
                return false;
            }
        }
        ItemStack item = item(a, p);
        if (item == null) {
            plugin.messages().send(p, "shop.unavailable");
            return false;
        }
        Currency money = tokens.currency();
        double cost = a.price() * bundles;
        String what = item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? Texts.plain(item.getItemMeta().displayName()) : a.id();
        if (money == null || !money.take(p, cost, Origin.of(plugin, "dungeon_shop").detail(what))) {
            plugin.messages().send(p, "shop.no-tokens");
            Sounds.cue(p, "error");
            return false;
        }
        if (a.kind().equals("mount")) {
            if (!Companions.adopt(p, a.id())) {
                // nothing given: the tokens go back
                money.give(p, cost, Origin.of(plugin, "dungeon_shop_refund").detail(what));
                plugin.messages().send(p, "shop.unavailable");
                return false;
            }
        } else {
            List<ItemStack> given = new ArrayList<>();
            for (int i = 0; i < bundles; i++) given.add(item.clone());
            Rewards.deliver(p, "dungeons", raw("shop.label"), given);
            if (a.kind().equals("booster") || a.kind().equals("book")) plugin.messages().send(p, "shop.bought-semi-rp");
        }
        Sounds.cue(p, "success");
        invalidate(p);
        return true;
    }

    private static ItemStack orBarrier(ItemStack i) {
        return i == null ? new ItemStack(Material.BARRIER) : i;
    }

    private String raw(String key, String... vars) {
        return plugin.messages().raw(key, vars);
    }
}
