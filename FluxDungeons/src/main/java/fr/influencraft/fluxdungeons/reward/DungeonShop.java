package fr.influencraft.fluxdungeons.reward;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.ListProvider;
import fr.influencraft.flux.api.list.ListQuery;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.pets.Companions;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.trade.TradeWindow;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxdungeons.dungeon.DungeonDefinition;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The dungeon shop (2026-11-16, laid out as the market since the owner's feedback of the same day): what a dungeon
 * gives is tokens, and tokens buy everything here: the boss's mount (sold only to those who beat that boss), the job's
 * boosters, the job's enchanted books, the dungeon's leather set. Contents and prices in {@code shop.yml}, written by
 * {@code assets/balance/tower.py}, never by hand.
 * <p>
 * One Flux list ({@code boutique_articles}): its filter is the job (« Tous » first), its second filter ({@code kind})
 * the kind of item: mounts, boosters, sets, or a tool / armour piece (an enchanted book shows under every item its
 * enchantment goes on); Flux pages it and searches it. The jobs are a row of buttons in the inventory, scrolled by an
 * arrow at each end ({@code boutique_metiers}, {@code boutique_defile}); a job clicked again goes back to « Tous ».
 * A click on an article opens Flux's buying window ({@link TradeWindow}). Articles are shown with their own
 * description ({@code CustomItems.preview}: the booster's text of FluxPets, without its « right click » lines).
 * Semi-rp things (boosters, books) are vanilla items: bought from the rpg lobby they wait in {@code /recompenses}.
 */
public final class DungeonShop {

    public static final String MENU = "donjon_boutique";
    public static final String LIST = "boutique_articles";
    /** Job buttons shown at once in the inventory row (« Tous » takes one more, the arrows two). */
    private static final int JOBS_SHOWN = 6;

    record Article(String kind, String id, int level, double price) {
    }

    record Page(String dungeon, String job, List<Article> articles) {
    }

    /** A kind of item of the second filter: its lang key and, for books, the item its enchantment must fit. */
    private record Kind(String key, Material fits) {
    }

    private static final List<Kind> KINDS = kinds();

    private static List<Kind> kinds() {
        List<Kind> out = new ArrayList<>(List.of(new Kind("all", null), new Kind("mounts", null), new Kind("boosters", null),
                new Kind("sets", null)));
        String[][] tools = {{"pickaxe", "DIAMOND_PICKAXE"}, {"shovel", "DIAMOND_SHOVEL"}, {"axe", "DIAMOND_AXE"},
                {"hoe", "DIAMOND_HOE"}, {"sword", "DIAMOND_SWORD"}, {"spear", "DIAMOND_SPEAR"}, {"mace", "MACE"},
                {"bow", "BOW"}, {"crossbow", "CROSSBOW"}, {"trident", "TRIDENT"}, {"fishing_rod", "FISHING_ROD"},
                {"helmet", "DIAMOND_HELMET"}, {"chestplate", "DIAMOND_CHESTPLATE"}, {"leggings", "DIAMOND_LEGGINGS"},
                {"boots", "DIAMOND_BOOTS"}};
        for (String[] t : tools) {
            Material m = Material.matchMaterial(t[1]);
            if (m != null) out.add(new Kind(t[0], m));
        }
        return List.copyOf(out);
    }

    private final FluxPlugin plugin;
    private final Supplier<Collection<DungeonDefinition>> dungeons;
    private final DungeonTokens tokens;
    private final DungeonVictories victories;
    private final java.util.function.Function<String, String> jobName;
    private final Map<String, Page> pages = new LinkedHashMap<>();
    /** First job shown in each player's row of jobs. */
    private final Map<UUID, Integer> scroll = new ConcurrentHashMap<>();

    public DungeonShop(FluxPlugin plugin, Supplier<Collection<DungeonDefinition>> dungeons, DungeonTokens tokens,
                       DungeonVictories victories, java.util.function.Function<String, String> jobName) {
        this.plugin = plugin;
        this.dungeons = dungeons;
        this.tokens = tokens;
        this.victories = victories;
        this.jobName = jobName;
        Lists.register(plugin, LIST, new ListProvider() {
            public List<ListEntry> entries(ListQuery q) {
                return articles(q.viewer(), q.filter(), q.kind());
            }

            public List<String> filters() {
                List<String> out = new ArrayList<>(List.of(raw("shop.all")));
                for (Page p : ordered()) out.add(jobName.apply(p.job()));
                return out;
            }

            public List<String> kinds() {
                return KINDS.stream().map(k -> raw("shop.kinds." + k.key())).toList();
            }
        });
        Lists.register(plugin, "boutique_boutons", q -> buttons(q.viewer()));
        Lists.register(plugin, "boutique_metiers", q -> jobs(q.viewer()));
        Lists.register(plugin, "boutique_defile", q -> arrows(q.viewer()));
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
        Lists.invalidate(LIST);
    }

    /** Opens the shop on every job, or on a dungeon's job (its button shown in the row). */
    public void open(Player p, String dungeon) {
        Lists.reset(p, LIST);
        int index = dungeon == null ? -1 : indexOf(dungeon);
        if (index >= 0) {
            Lists.act(p, LIST, "filter " + (index + 1), 28);
            scroll.put(p.getUniqueId(), Math.max(0, Math.min(index, ordered().size() - JOBS_SHOWN)));
        } else scroll.put(p.getUniqueId(), 0);
        invalidate(p);
        FluxApi.open(p, MENU);
    }

    /** {@code job <n>} (0 = all), {@code scroll <-1|1>}, {@code buy <dungeon>:<n>}. */
    public void act(Player p, String[] args) {
        if (args.length < 2) return;
        switch (args[0]) {
            case "job" -> {
                int n;
                try {
                    n = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    return;
                }
                // the job shown, clicked again: back to every job
                int current = Lists.state(p, LIST).filter();
                Lists.act(p, LIST, "filter " + (n == current ? 0 : n), 28);
                invalidate(p);
            }
            case "scroll" -> {
                int max = Math.max(0, ordered().size() - JOBS_SHOWN);
                int step = args[1].startsWith("-") ? -1 : 1;
                scroll.put(p.getUniqueId(), Math.max(0, Math.min(max, scroll.getOrDefault(p.getUniqueId(), 0) + step)));
                invalidate(p);
            }
            case "buy" -> {
                // <dungeon>:<n>: the article n of that dungeon's shop
                int colon = args[1].indexOf(':');
                Page page = colon < 0 ? null : pages.get(args[1].substring(0, colon));
                int n;
                try {
                    n = Integer.parseInt(args[1].substring(colon + 1));
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

    private void invalidate(Player p) {
        for (String l : List.of(LIST, "boutique_boutons", "boutique_metiers", "boutique_defile")) Lists.invalidate(p, l);
    }

    /** The shops in the order of the dungeons. */
    private List<Page> ordered() {
        List<DungeonDefinition> sorted = new ArrayList<>();
        for (DungeonDefinition d : dungeons.get()) if (pages.containsKey(d.id())) sorted.add(d);
        sorted.sort(java.util.Comparator.comparingInt(DungeonDefinition::floor));
        List<Page> out = new ArrayList<>();
        for (DungeonDefinition d : sorted) out.add(pages.get(d.id()));
        return out;
    }

    private int indexOf(String dungeon) {
        List<Page> all = ordered();
        for (int i = 0; i < all.size(); i++) if (all.get(i).dungeon().equals(dungeon)) return i;
        return -1;
    }

    private DungeonDefinition definition(String dungeon) {
        for (DungeonDefinition d : dungeons.get()) if (d.id().equals(dungeon)) return d;
        return null;
    }

    // ---------------------------------------------------------------- buttons

    /** Back, info, tokens, the kind filter, the search (their slots in the menu file). */
    private List<ListEntry> buttons(Player viewer) {
        List<ListEntry> out = new ArrayList<>();
        out.add(button("back", Items.of("itemsadder:fluxui:btn_back_orange", raw("shop.back"),
                Lore.create().left(raw("shop.back-action")).build(), viewer), "[menu] donjons", ""));
        out.add(button("info", Items.of("itemsadder:fluxui:btn_info_blue", raw("shop.info"),
                Lore.create().description(raw("shop.info-desc")).description(raw("shop.info-mount")).build(), viewer), "", ""));
        Currency money = tokens.currency();
        out.add(button("tokens", Items.of("itemsadder:fluxdonjon:jeton_donjon", raw("menu.tokens"),
                Lore.create().description(raw("menu.tokens-desc"))
                        .kv(raw("menu.tokens-owned"), money == null ? "0" : money.format(money.balance(viewer))).build(), viewer), "", ""));
        Lists.Choice kind = Lists.choice(viewer, LIST, "kind");
        out.add(button("kind", Items.of("itemsadder:fluxui:btn_filter_blue", raw("shop.kind"),
                Lore.create().description(raw("shop.kind-desc")).filter(kind.labels(), kind.selected()).build(), viewer),
                "[list] " + LIST + " kind", "[list] " + LIST + " kind-prev"));
        String search = Lists.state(viewer, LIST).search();
        Lore lore = Lore.create().description(raw("shop.search-desc"));
        if (!search.isEmpty()) lore.kv(raw("shop.search-current"), search);
        lore.left(raw("shop.search"));
        if (!search.isEmpty()) lore.right(raw("shop.search-clear"));
        out.add(button("search", Items.of("itemsadder:fluxui:btn_search_blue", raw("shop.search-name"), lore.build(), viewer),
                "[list] " + LIST + " search", "[list] " + LIST + " clear"));
        return out;
    }

    private static ListEntry button(String id, ItemStack item, String left, String right) {
        return ListEntry.of(id).item(item == null ? new ItemStack(Material.BARRIER) : item).set("left", left).set("right", right);
    }

    /** « Tous » then the jobs from the scroll position, the one chosen glowing. */
    private List<ListEntry> jobs(Player viewer) {
        List<Page> all = ordered();
        int chosen = Lists.state(viewer, LIST).filter();
        List<ListEntry> out = new ArrayList<>();
        Lore every = Lore.create().description(raw("shop.all-desc"));
        if (chosen == 0) every.ok(raw("shop.job-shown"));
        else every.left(raw("shop.job-action"));
        ItemStack allItem = Items.of("itemsadder:fluxdonjon:boutique", raw("shop.all"), every.build(), viewer);
        if (allItem != null && chosen == 0) allItem.editMeta(m -> m.setEnchantmentGlintOverride(true));
        out.add(ListEntry.of("all").item(allItem == null ? new ItemStack(Material.CHEST) : allItem).set("left", "[fluxdungeon] shop job 0"));
        int from = Math.max(0, Math.min(scroll.getOrDefault(viewer.getUniqueId(), 0), Math.max(0, all.size() - JOBS_SHOWN)));
        for (int i = from; i < Math.min(all.size(), from + JOBS_SHOWN); i++) {
            Page page = all.get(i);
            DungeonDefinition d = definition(page.dungeon());
            boolean on = chosen == i + 1;
            Lore lore = Lore.create().kv(raw("shop.articles"), page.articles().size());
            if (d != null) lore.kv(raw("menu.level"), d.recommendedLevel());
            if (on) lore.ok(raw("shop.job-shown")).left(raw("shop.job-all"));
            else lore.left(raw("shop.job-action"));
            String icon = fr.influencraft.flux.api.jobs.JobIcons.icon(page.job());
            String name = raw("shop.page", "%job%", jobName.apply(page.job()));
            ItemStack item = icon == null ? null : Items.of(icon, name, lore.build(), viewer);
            if (item == null) item = Items.dress(new ItemStack(d == null ? Material.CHEST : d.icon()), name, lore.build(), viewer);
            if (on) item.editMeta(m -> m.setEnchantmentGlintOverride(true));
            out.add(ListEntry.of(page.dungeon()).item(item).set("left", "[fluxdungeon] shop job " + (i + 1)));
        }
        return out;
    }

    /** The arrows at each end of the row of jobs: shown only when there is something that way. */
    private List<ListEntry> arrows(Player viewer) {
        int from = scroll.getOrDefault(viewer.getUniqueId(), 0);
        int max = Math.max(0, ordered().size() - JOBS_SHOWN);
        ItemStack left = from > 0 ? Items.of("itemsadder:fluxui:btn_left_purple", "", List.of(), viewer) : null;
        ItemStack right = from < max ? Items.of("itemsadder:fluxui:btn_right_purple", "", List.of(), viewer) : null;
        List<ListEntry> out = new ArrayList<>();
        out.add(left == null ? ListEntry.gap() : ListEntry.of("prev").item(hide(left)).set("left", "[fluxdungeon] shop scroll -1"));
        out.add(right == null ? ListEntry.gap() : ListEntry.of("next").item(hide(right)).set("left", "[fluxdungeon] shop scroll 1"));
        return out;
    }

    private static ItemStack hide(ItemStack item) {
        item.editMeta(m -> m.setHideTooltip(true));
        return item;
    }

    // ---------------------------------------------------------------- articles

    /** Articles of the chosen job (0 = every job) and kind (0 = every kind), in the order of the dungeons. */
    private List<ListEntry> articles(Player viewer, int job, int kind) {
        List<ListEntry> out = new ArrayList<>();
        Currency money = tokens.currency();
        List<Page> all = ordered();
        Kind k = KINDS.get(Math.floorMod(kind, KINDS.size()));
        for (int pi = 0; pi < all.size(); pi++) {
            if (job > 0 && job != pi + 1) continue;
            Page page = all.get(pi);
            for (int i = 0; i < page.articles().size(); i++) {
                Article a = page.articles().get(i);
                if (!fits(a, k)) continue;
                ItemStack item = item(a, viewer, true);
                if (item == null) continue;
                Lore lore = Lore.create().kv(raw("shop.price"), money == null ? String.valueOf((long) a.price()) : money.format(a.price()));
                lore.kv(raw("shop.from"), jobName.apply(page.job()));
                describe(page, a, viewer, lore);
                lore.left(raw("shop.buy-action"));
                List<String> lines = new ArrayList<>(item.hasItemMeta() && item.getItemMeta().hasLore()
                        ? item.getItemMeta().lore().stream().map(Texts::ampersand).toList() : List.of());
                lines.addAll(lore.build());
                String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? Texts.ampersand(item.getItemMeta().displayName()) : null;
                out.add(ListEntry.of(page.dungeon() + ":" + i).item(Items.dress(item.clone(), name, lines, viewer))
                        .set("left", "[fluxdungeon] shop buy " + page.dungeon() + ":" + i)
                        .set("name", plain(name == null ? a.id() : name))
                        .set("job", jobName.apply(page.job())));
            }
        }
        return out;
    }

    /** Whether an article is of a kind: books by the items their enchantment goes on. */
    private static boolean fits(Article a, Kind k) {
        return switch (k.key()) {
            case "all" -> true;
            case "mounts" -> a.kind().equals("mount");
            case "boosters" -> a.kind().equals("booster");
            case "sets" -> a.kind().equals("item");
            default -> {
                if (!a.kind().equals("book") || k.fits() == null) yield false;
                Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(a.id()));
                yield e != null && e.canEnchantItem(new ItemStack(k.fits()));
            }
        };
    }

    private static String plain(String legacy) {
        return Texts.plain(Texts.colors(legacy)).toLowerCase(Locale.ROOT);
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

    /**
     * The article: {@code shown}, as a shop shows it (the item's own description without its « how to use » lines,
     * {@code CustomItems.preview}); else the item given. Null when it cannot be made (unknown pet, item or enchantment).
     */
    private ItemStack item(Article a, Player viewer, boolean shown) {
        return switch (a.kind()) {
            case "mount" -> {
                ItemStack portrait = Companions.portrait(a.id());
                String name = Companions.name(a.id());
                if (portrait == null) yield null;
                yield Items.dress(portrait.clone(), raw("shop.mount", "%pet%", name == null ? a.id() : name), List.of(), viewer);
            }
            case "booster" -> shown ? CustomItems.preview("fluxpets:booster_metier_" + a.id(), viewer)
                    : CustomItems.item("fluxpets:booster_metier_" + a.id(), viewer);
            case "book" -> book(a.id(), a.level(), shown);
            default -> shown ? CustomItems.preview(a.id(), viewer) : CustomItems.item(a.id(), viewer);
        };
    }

    /**
     * An enchanted book of one custom enchantment (EcoEnchants registers them in the game's registry); named after
     * its enchantment when shown, so the shop reads and searches as a list of enchantments.
     */
    private static ItemStack book(String enchant, int level, boolean shown) {
        Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(enchant));
        if (e == null) return null;
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        book.editMeta(EnchantmentStorageMeta.class, m -> m.addStoredEnchant(e, Math.max(1, level), true));
        if (shown) {
            String name = PlainTextComponentSerializer.plainText().serialize(e.displayName(Math.max(1, level)));
            book.editMeta(m -> m.customName(net.kyori.adventure.text.Component.text(name)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        }
        return book;
    }

    private TradeWindow.Offer offer(Page page, Article a) {
        return new TradeWindow.Offer() {
            public ItemStack item() {
                ItemStack i = DungeonShop.this.item(a, null, true);
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
        ItemStack item = item(a, p, false);
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

    private String raw(String key, String... vars) {
        return plugin.messages().raw(key, vars);
    }
}
