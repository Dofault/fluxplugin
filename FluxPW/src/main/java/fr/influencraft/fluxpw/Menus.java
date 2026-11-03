package fr.influencraft.fluxpw;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.ListQuery;
import fr.influencraft.flux.api.list.ListProvider;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The warp menus, built in Java and placed by the menu files:
 * <ul>
 *   <li>{@code pw} (/pw): categories, sponsored places, own warps, favourites, search, create;</li>
 *   <li>{@code pw_list}: the warps of a category, of the player or his favourites, sorted, searchable;</li>
 *   <li>{@code pw_manage}: the settings of one warp;</li>
 *   <li>{@code pw_blocked}: the players blocked from one warp.</li>
 * </ul>
 * Buttons run {@code [pw] <verb> [argument]}.
 */
final class Menus {

    enum Kind { CATEGORY, MINE, FAVORITES }

    private record View(Kind kind, String category) {
    }

    static final String WARPS = "pw_warps";
    private static final List<String> LISTS = List.of("pw_info", "pw_categories", "pw_featured", WARPS, "pw_list_info",
            "pw_sort", "pw_manage_info", "pw_manage", "pw_blocked_info", "pw_blocked");

    private final FluxPW plugin;
    private final Map<UUID, View> views = new HashMap<>();
    /** Warp whose settings each player looks at, and whether he came from a list (back button). */
    private final Map<UUID, UUID> managed = new HashMap<>();
    private final Map<UUID, Boolean> managedFromList = new HashMap<>();

    Menus(FluxPW plugin) {
        this.plugin = plugin;
    }

    private String raw(String key, String... vars) {
        return plugin.messages().raw(key, vars);
    }

    void register() {
        Lists.registerItem(plugin, "pw_info", this::homeInfo);
        Lists.register(plugin, "pw_categories", q -> categoryEntries(q.viewer()));
        Lists.register(plugin, "pw_featured", q -> featuredEntries(q.viewer()));
        Lists.register(plugin, WARPS, new ListProvider() {
            public List<ListEntry> entries(ListQuery q) {
                return warpEntries(q.viewer(), q.sort());
            }

            public List<String> sorts() {
                return plugin.sorts.stream().map(s -> raw("sorts." + s)).toList();
            }
        });
        Lists.registerItem(plugin, "pw_list_info", this::listInfo);
        Lists.registerItem(plugin, "pw_sort", this::sortButton);
        Lists.registerItem(plugin, "pw_manage_info", p -> {
            Warp w = managed(p);
            return w == null ? null : overview(p, w);
        });
        Lists.register(plugin, "pw_manage", q -> manageEntries(q.viewer()));
        Lists.registerItem(plugin, "pw_blocked_info", this::blockedInfo);
        Lists.register(plugin, "pw_blocked", q -> blockedEntries(q.viewer()));
        FluxApi.registerAction(plugin, "pw", (player, view, argument) -> act(player, argument.trim()));
    }

    void invalidate() {
        for (String id : LISTS) Lists.invalidate(id);
    }

    void forget(Player p) {
        views.remove(p.getUniqueId());
        managed.remove(p.getUniqueId());
        managedFromList.remove(p.getUniqueId());
    }

    // ---------------------------------------------------------------- opening

    void openHome(Player p) {
        FluxApi.open(p, "pw");
    }

    void openList(Player p, Kind kind, String category) {
        String node = kind == Kind.MINE ? "fluxpw.mywarps" : "fluxpw.use";
        if (!FluxPW.has(p, node)) {
            plugin.send(p, "no-permission");
            return;
        }
        views.put(p.getUniqueId(), new View(kind, category));
        Lists.reset(p, WARPS);
        FluxApi.open(p, "pw_list");
    }

    void openManage(Player p, Warp w, boolean fromList) {
        managed.put(p.getUniqueId(), w.id);
        managedFromList.put(p.getUniqueId(), fromList);
        Lists.reset(p, "pw_blocked");
        Lists.invalidate(p, "pw_manage");
        Lists.invalidate(p, "pw_manage_info");
        FluxApi.open(p, "pw_manage");
    }

    /** After giving a warp away: its settings stay open only for staff. */
    void afterTransfer(Player p, Warp w) {
        if (plugin.canManage(p, w)) reopenManage(p);
        else openList(p, Kind.MINE, null);
    }

    private Warp managed(Player p) {
        UUID id = managed.get(p.getUniqueId());
        return id == null ? null : plugin.warps().get(id);
    }

    private void reopenManage(Player p) {
        Warp w = managed(p);
        if (w != null && plugin.canManage(p, w)) openManage(p, w, managedFromList.getOrDefault(p.getUniqueId(), false));
        else openList(p, Kind.MINE, null);
    }

    /** The list the player was looking at (to come back after a form), else the home menu. */
    private Runnable backToList(Player p) {
        MenuView v = FluxApi.view(p);
        String menu = v == null ? "pw" : v.menuId();
        return () -> {
            if (p.isOnline()) FluxApi.open(p, menu);
        };
    }

    private void refresh(Player p) {
        invalidate();
        MenuView v = FluxApi.view(p);
        if (v != null && v.isOpen()) v.refresh();
    }

    // ---------------------------------------------------------------- actions

    private void act(Player p, String argument) {
        String[] parts = argument.split("\\s+", 2);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        switch (verb) {
            case "home" -> openHome(p);
            case "category" -> {
                Category c = plugin.categories.get(arg);
                if (c == null) return;
                if (!c.allowed(p)) {
                    plugin.send(p, "category-locked");
                    Sounds.cue(p, "error");
                    return;
                }
                openList(p, Kind.CATEGORY, c.key());
            }
            case "mine" -> openList(p, Kind.MINE, null);
            case "favorites" -> openList(p, Kind.FAVORITES, null);
            case "search" -> search(p);
            case "create" -> createForm(p);
            case "back" -> {
                if (managedFromList.getOrDefault(p.getUniqueId(), false) && views.containsKey(p.getUniqueId())) FluxApi.open(p, "pw_list");
                else openHome(p);
            }
            case "manage-back" -> reopenManage(p);
            case "block" -> {
                Warp w = managed(p);
                if (w == null) return;
                p.closeInventory();
                plugin.block(p, w, () -> FluxApi.open(p, "pw_blocked"));
            }
            case "unblock" -> {
                Warp w = managed(p);
                if (w == null) return;
                try {
                    plugin.unblock(p, w, UUID.fromString(arg));
                } catch (IllegalArgumentException e) {
                    return;
                }
                refresh(p);
            }
            case "set" -> setting(p, arg);
            default -> {
                Warp w = plugin.warp(arg);
                if (w == null) return;
                switch (verb) {
                    case "visit" -> {
                        Runnable back = backToList(p);
                        p.closeInventory();
                        plugin.visit(p, w, back);
                    }
                    case "rate" -> {
                        Runnable back = backToList(p);
                        p.closeInventory();
                        plugin.rate(p, w, back);
                    }
                    case "favorite" -> {
                        plugin.favorite(p, w);
                        refresh(p);
                    }
                    case "manage" -> {
                        if (!plugin.canManage(p, w) || !(FluxPW.has(p, "fluxpw.settings") || FluxPW.has(p, "fluxpw.manage"))) {
                            plugin.send(p, "not-owner");
                            Sounds.cue(p, "error");
                            return;
                        }
                        openManage(p, w, true);
                    }
                    default -> {
                    }
                }
            }
        }
    }

    private void search(Player p) {
        p.closeInventory();
        Dialogs.form(p, raw("forms.search.title"))
                .body(raw("forms.search.body"))
                .requiredInput("value", raw("forms.search.field"), "", 32)
                .onSubmit((pl, answer) -> {
                    views.put(pl.getUniqueId(), new View(Kind.CATEGORY, plugin.allCategory().key()));
                    Lists.reset(pl, WARPS);
                    Lists.act(pl, WARPS, "search " + answer.text("value"), 36);
                    FluxApi.open(pl, "pw_list");
                })
                .onCancel(this::openHome)
                .open();
    }

    private void createForm(Player p) {
        if (!FluxPW.has(p, "fluxpw.create")) {
            plugin.send(p, "no-permission");
            Sounds.cue(p, "error");
            return;
        }
        p.closeInventory();
        double price = plugin.price("create");
        Dialogs.form(p, raw("forms.create.title"))
                .body(raw("forms.create.body", "%price%", price > 0 ? plugin.money().format(price) : raw("free"),
                        "%limit%", String.valueOf(plugin.nameMax)))
                .requiredInput("value", raw("forms.create.field"), "", plugin.nameMax)
                .onSubmit((pl, answer) -> plugin.create(pl, answer.text("value").replace(' ', '_')))
                .onCancel(this::openHome)
                .open();
    }

    private void setting(Player p, String what) {
        Warp w = managed(p);
        if (w == null) return;
        Runnable back = () -> {
            if (p.isOnline()) reopenManage(p);
        };
        if (what.equals("blocked")) {
            if (plugin.may(p, w, "fluxpw.settings.blockplayer")) {
                Lists.reset(p, "pw_blocked");
                FluxApi.open(p, "pw_blocked");
            }
            return;
        }
        p.closeInventory();
        switch (what) {
            case "admission" -> plugin.setAdmission(p, w, back);
            case "category" -> plugin.setCategory(p, w, back);
            case "display-name" -> plugin.setDisplayName(p, w, back);
            case "icon" -> plugin.setIcon(p, w, back);
            case "description" -> plugin.setDescription(p, w, back);
            case "status" -> plugin.setStatus(p, w, back);
            case "rename" -> plugin.rename(p, w, back);
            case "relocate" -> plugin.relocate(p, w, back);
            case "transfer" -> plugin.transfer(p, w, back);
            case "feature" -> plugin.feature(p, w, back);
            case "remove" -> plugin.remove(p, w, !w.owner(p.getUniqueId()), back, () -> openList(p, Kind.MINE, null));
            case "visit" -> plugin.visit(p, w, back);
            default -> back.run();
        }
    }

    // ---------------------------------------------------------------- home menu

    private ItemStack homeInfo(Player p) {
        Lore lore = Lore.create().description(raw("menu.home-description"));
        int open = 0;
        for (Warp w : plugin.warps().values()) if (w.open()) open++;
        lore.kv(raw("menu.warps"), open);
        int limit = plugin.limit(p);
        lore.kv(raw("menu.yours"), plugin.owned(p.getUniqueId()) + " / " + (limit == Integer.MAX_VALUE ? raw("unlimited") : limit));
        return Items.of("itemsadder:influville:warp", raw("menu.home-name"), lore.build(), p);
    }

    private int count(Category c) {
        int n = 0;
        for (Warp w : plugin.warps().values()) if (w.open() && (c.all() || plugin.category(w) == c)) n++;
        return n;
    }

    private List<ListEntry> categoryEntries(Player p) {
        List<ListEntry> out = new ArrayList<>();
        for (Category c : plugin.categories.values()) {
            Lore lore = Lore.create();
            if (!c.description().isBlank()) lore.description(c.description());
            lore.kv(raw("menu.warps"), count(c));
            boolean allowed = c.allowed(p);
            if (allowed) lore.left(raw("menu.open"));
            else lore.ko(raw("menu.category-locked"));
            out.add(ListEntry.of(c.key()).item(flags(Items.of(c.icon(), c.name(), lore.build(), p)))
                    .set("clic", allowed ? "[pw] category " + c.key() : "[sound] @error"));
        }
        return out;
    }

    private List<ListEntry> featuredEntries(Player p) {
        List<ListEntry> out = new ArrayList<>();
        List<Warp> featured = plugin.featured();
        for (int i = 0; i < plugin.featuredSlots; i++) {
            if (i < featured.size()) {
                Warp w = featured.get(i);
                ListEntry e = warpEntry(p, w, false);
                out.add(e);
            } else {
                Lore lore = Lore.create().description(raw("menu.free-place-description"));
                double price = plugin.price("feature");
                if (price > 0) lore.kv(raw("menu.price"), plugin.money().format(price));
                out.add(ListEntry.of("free-" + i).item(Items.of("LANTERN", raw("menu.free-place", "%n%", String.valueOf(i + 1)), lore.build(), p))
                        .set("left", "[sound] @click").set("right", "[sound] @click").set("shift_left", "[sound] @click").set("shift_right", "[sound] @click"));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- warp lists

    private View view(Player p) {
        return views.getOrDefault(p.getUniqueId(), new View(Kind.CATEGORY, plugin.allCategory().key()));
    }

    private List<ListEntry> warpEntries(Player p, int sortIndex) {
        View v = view(p);
        List<Warp> list = new ArrayList<>();
        switch (v.kind()) {
            case MINE -> {
                for (Warp w : plugin.warps().values()) if (w.owner(p.getUniqueId())) list.add(w);
            }
            case FAVORITES -> {
                for (UUID id : plugin.favorites(p.getUniqueId())) {
                    Warp w = plugin.warps().get(id);
                    if (w != null) list.add(w);
                }
            }
            case CATEGORY -> {
                Category c = plugin.categories.get(v.category());
                for (Warp w : plugin.warps().values())
                    if (w.open() && (c == null || c.all() || plugin.category(w) == c)) list.add(w);
            }
        }
        String sort = plugin.sorts.get(Math.floorMod(sortIndex, plugin.sorts.size()));
        Comparator<Warp> order = switch (sort) {
            case "visits" -> Comparator.comparingInt((Warp w) -> w.visits).reversed();
            case "rating" -> Comparator.comparingDouble(Warp::rating).reversed();
            case "latest" -> Comparator.comparingLong((Warp w) -> w.created).reversed();
            default -> Comparator.comparing((Warp w) -> FluxPW.strip(w.shownName()), String.CASE_INSENSITIVE_ORDER);
        };
        if (v.kind() != Kind.FAVORITES) list.sort(order);
        List<ListEntry> out = new ArrayList<>();
        for (Warp w : list) out.add(warpEntry(p, w, v.kind() == Kind.MINE));
        return out;
    }

    private static String stars(Warp w) {
        int full = (int) Math.floor(w.rating() + 1e-9);
        return "★".repeat(full) + "☆".repeat(5 - full);
    }

    /** A warp in a list. Left: visit; right: rate (or settings in the own warps); shift + right: favourite. */
    private ListEntry warpEntry(Player p, Warp w, boolean ownList) {
        boolean placed = w.location() != null;
        boolean manager = plugin.canManage(p, w);
        Lore lore = Lore.create();
        lore.description(w.description == null || w.description.isBlank() ? raw("menu.no-description") : w.description);
        if (!ownList) lore.kv(raw("menu.owner"), w.ownerName == null ? "?" : w.ownerName);
        lore.kv(raw("menu.category"), plugin.category(w).name());
        lore.kv(raw("menu.world"), w.world == null ? "?" : w.world);
        lore.kv(raw("menu.created"), Format.date(w.created));
        lore.kv(raw("menu.visits"), raw("menu.visits-value", "%visits%", Format.number(w.visits), "%today%", String.valueOf(w.today)));
        if (plugin.rating) lore.kv(raw("menu.rating"), w.reviewers.isEmpty() ? raw("menu.no-rating")
                : raw("menu.rating-value", "%stars%", stars(w), "%rating%", Format.decimal(Math.round(w.rating() * 10) / 10.0),
                "%count%", String.valueOf(w.reviewers.size())));
        lore.kv(raw("menu.status"), raw("status." + w.status.name().toLowerCase(Locale.ROOT)));
        lore.kv(raw("menu.admission"), w.admission > 0 ? plugin.money().format(w.admission) : raw("free"));
        if (w.featured()) lore.info(raw("menu.featured-until", "%date%", Format.date(w.featuredUntil)));
        if (!placed) lore.ko(raw("menu.world-missing"));
        if (w.blocked.contains(p.getUniqueId()) && !manager) lore.ko(raw("menu.blocked-you"));
        boolean favorite = plugin.favorites(p.getUniqueId()).contains(w.id);
        String left = placed ? "[pw] visit " + w.id : "[sound] @error";
        String right;
        if (ownList || (manager && !plugin.rating)) {
            right = "[pw] manage " + w.id;
            if (placed) lore.left(raw("menu.visit"));
            lore.right(raw("menu.manage"));
        } else {
            right = plugin.rating ? "[pw] rate " + w.id : "[sound] @error";
            if (placed) lore.left(raw("menu.visit"));
            if (plugin.rating) lore.right(raw("menu.rate"));
        }
        String shiftLeft = manager && !ownList ? "[pw] manage " + w.id : left;
        if (manager && !ownList) lore.shiftLeft(raw("menu.manage"));
        lore.shiftRight(raw(favorite ? "menu.favorite-remove" : "menu.favorite-add"));
        ItemStack item = placed ? plugin.iconItem(w.icon) : new ItemStack(Material.BARRIER);
        String name = w.shownName();
        return ListEntry.of(w.id.toString()).item(flags(Items.dress(item, name, lore.build(), p)))
                .set("name", w.name).set("display", FluxPW.strip(name)).set("owner", w.ownerName == null ? "" : w.ownerName)
                .set("left", left).set("right", right).set("shift_left", shiftLeft).set("shift_right", "[pw] favorite " + w.id);
    }

    /** No attribute or enchantment lines under a warp icon (a sword as icon). */
    private static ItemStack flags(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack listInfo(Player p) {
        View v = view(p);
        String title = switch (v.kind()) {
            case MINE -> raw("menu.mine-name");
            case FAVORITES -> raw("menu.favorites-name");
            case CATEGORY -> {
                Category c = plugin.categories.get(v.category());
                yield c == null ? plugin.allCategory().name() : c.name();
            }
        };
        Lore lore = Lore.create().description(raw("menu.list-description-" + v.kind().name().toLowerCase(Locale.ROOT)));
        lore.kv(raw("menu.warps"), Lists.entries(p, WARPS).size());
        String search = Lists.state(p, WARPS).search();
        if (!search.isEmpty()) lore.kv(raw("menu.search"), search);
        String icon = switch (v.kind()) {
            case MINE -> "ENDER_PEARL";
            case FAVORITES -> "NETHER_STAR";
            case CATEGORY -> {
                Category c = plugin.categories.get(v.category());
                yield c == null ? "ENDER_EYE" : c.icon();
            }
        };
        return flags(Items.of(icon, title, lore.build(), p));
    }

    private ItemStack sortButton(Player p) {
        List<String> labels = plugin.sorts.stream().map(s -> raw("sorts." + s)).toList();
        int selected = Math.floorMod(Lists.state(p, WARPS).sort(), labels.size());
        Lore lore = Lore.create().filter(labels, selected);
        return Items.of("itemsadder:influitems:filter", raw("menu.sort"), lore.build(), p);
    }

    // ---------------------------------------------------------------- settings menu

    private ItemStack overview(Player p, Warp w) {
        Lore lore = Lore.create();
        if (w.description != null && !w.description.isBlank()) lore.description(w.description);
        lore.kv(raw("menu.name"), w.name);
        lore.kv(raw("menu.owner"), w.ownerName == null ? "?" : w.ownerName);
        lore.kv(raw("menu.category"), plugin.category(w).name());
        lore.kv(raw("menu.created"), Format.date(w.created));
        lore.kv(raw("menu.visits"), raw("menu.visits-value", "%visits%", Format.number(w.visits), "%today%", String.valueOf(w.today)));
        lore.kv(raw("menu.unique-visits"), w.visitors.size());
        if (plugin.rating) lore.kv(raw("menu.rating"), w.reviewers.isEmpty() ? raw("menu.no-rating")
                : raw("menu.rating-value", "%stars%", stars(w), "%rating%", Format.decimal(Math.round(w.rating() * 10) / 10.0),
                "%count%", String.valueOf(w.reviewers.size())));
        lore.kv(raw("menu.status"), raw("status." + w.status.name().toLowerCase(Locale.ROOT)));
        lore.kv(raw("menu.admission"), w.admission > 0 ? plugin.money().format(w.admission) : raw("free"));
        if (w.featured()) lore.info(raw("menu.featured-until", "%date%", Format.date(w.featuredUntil)));
        if (w.location() == null) lore.ko(raw("menu.world-missing"));
        ItemStack item = w.location() == null ? new ItemStack(Material.BARRIER) : plugin.iconItem(w.icon);
        return flags(Items.dress(item, w.shownName(), lore.build(), p));
    }

    private ListEntry setting(Player p, Warp w, String key, String material, String node, Lore lore) {
        double price = plugin.price(priceKey(key));
        if (price > 0) lore.kv(raw("menu.price"), plugin.money().format(price));
        boolean allowed = node == null || FluxPW.has(p, node);
        if (allowed) lore.left(raw("manage." + key + ".action"));
        else lore.ko(raw("menu.locked"));
        return ListEntry.of(key).item(flags(Items.of(material, raw("manage." + key + ".name"), lore.build(), p)))
                .set("clic", allowed ? "[pw] set " + key : "[sound] @error");
    }

    private static String priceKey(String setting) {
        return switch (setting) {
            case "visit", "blocked", "remove" -> "-";
            default -> setting;
        };
    }

    private Lore described(String key) {
        return Lore.create().description(raw("manage." + key + ".description"));
    }

    private List<ListEntry> manageEntries(Player p) {
        Warp w = managed(p);
        if (w == null) return List.of();
        List<ListEntry> out = new ArrayList<>();
        out.add(setting(p, w, "admission", "GOLD_INGOT", "fluxpw.settings.setadmission",
                described("admission").kv(raw("menu.current"), w.admission > 0 ? plugin.money().format(w.admission) : raw("free"))));
        out.add(setting(p, w, "category", "BOOKSHELF", "fluxpw.settings.settype",
                described("category").kv(raw("menu.current"), plugin.category(w).name())));
        out.add(setting(p, w, "display-name", "NAME_TAG", "fluxpw.settings.changedisplayname",
                described("display-name").kv(raw("menu.current"), w.shownName())));
        out.add(setting(p, w, "icon", "ITEM_FRAME", "fluxpw.settings.setitem", described("icon")));
        out.add(setting(p, w, "description", "WRITABLE_BOOK", "fluxpw.settings.setdescription", described("description")));
        out.add(setting(p, w, "status", "OAK_DOOR", "fluxpw.settings.setstatus",
                described("status").kv(raw("menu.current"), raw("status." + w.status.name().toLowerCase(Locale.ROOT)))));
        Lore feature = described("feature");
        if (w.featured()) feature.info(raw("menu.featured-until", "%date%", Format.date(w.featuredUntil)));
        else feature.kv(raw("menu.places"), plugin.featured().size() + " / " + plugin.featuredSlots);
        out.add(setting(p, w, "feature", "LANTERN", "fluxpw.feature", feature));
        out.add(setting(p, w, "visit", "ENDER_PEARL", null, described("visit")));
        out.add(setting(p, w, "rename", "OAK_SIGN", "fluxpw.rename", described("rename").kv(raw("menu.current"), w.name)));
        out.add(setting(p, w, "relocate", "COMPASS", "fluxpw.relocate", described("relocate")));
        out.add(setting(p, w, "transfer", "PLAYER_HEAD", "fluxpw.transfer", described("transfer")));
        out.add(setting(p, w, "blocked", "SHIELD", "fluxpw.settings.blockplayer",
                described("blocked").kv(raw("menu.blocked-count"), w.blocked.size())));
        out.add(ListEntry.gap());
        out.add(setting(p, w, "remove", "BARRIER", "fluxpw.remove", described("remove")));
        return out;
    }

    // ---------------------------------------------------------------- blocked players

    private ItemStack blockedInfo(Player p) {
        Warp w = managed(p);
        if (w == null) return null;
        Lore lore = Lore.create().description(raw("menu.blocked-description", "%warp%", w.shownName()));
        lore.kv(raw("menu.blocked-count"), w.blocked.size());
        return Items.of("SHIELD", raw("manage.blocked.name"), lore.build(), p);
    }

    private List<ListEntry> blockedEntries(Player p) {
        Warp w = managed(p);
        if (w == null) return List.of();
        List<ListEntry> out = new ArrayList<>();
        List<UUID> ids = new ArrayList<>(w.blocked);
        ids.sort(Comparator.comparing(id -> name(id), String.CASE_INSENSITIVE_ORDER));
        for (UUID id : ids) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta skull) {
                skull.setPlayerProfile(Bukkit.createProfile(id, Bukkit.getOfflinePlayer(id).getName()));
                head.setItemMeta(skull);
            }
            Lore lore = Lore.create().left(raw("menu.unblock"));
            out.add(ListEntry.of(id.toString()).item(Items.dress(head, name(id), lore.build(), p))
                    .set("name", name(id)).set("clic", "[pw] unblock " + id));
        }
        return out;
    }

    private static String name(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "?" : n;
    }
}
