package fr.influencraft.fluxpw;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.claims.Claims;
import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.flux.api.text.ChatHelp;
import fr.influencraft.fluxpw.api.PlayerWarps;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * FluxPW: player warps. A player creates a warp where he stands ({@code /pw create <name>}), in his own town or one
 * he lives in, then sets it up from its menu: category, icon, display name, description, admission fee, open /
 * closed / password, blocked players, sponsored place. Everyone visits warps from {@code /pw} (categories, sponsored
 * places, search, favourites) or with {@code /pw <name>}, rates them and keeps favourites. Teleports wait a few
 * seconds standing still; the admission fee is held during the wait and paid to the owner on arrival.
 *
 * <pre>
 *   /pw [warp [player]] | create &lt;name&gt; | remove &lt;name&gt; | manage &lt;name&gt; | mywarps | open &lt;menu&gt; | help | about | reload
 * </pre>
 * Permissions are the {@code fluxpw.*} nodes of the former warp plugin. Data in Flux's database
 * ({@link WarpStore}), menus {@code pw}, {@code pw_list}, {@code pw_manage}, {@code pw_blocked} ({@link Menus}),
 * API {@link PlayerWarps}.
 */
public final class FluxPW extends FluxPlugin implements Listener {

    /** Words after /pw that are not warps (a warp cannot take one of these names). */
    static final List<String> SUBCOMMANDS = List.of("create", "remove", "manage", "mywarps", "favorites", "open", "help", "admin");
    /** Words after /pw admin. */
    static final List<String> ADMIN = List.of("help", "info", "list", "manage", "remove", "send", "about", "reload");
    /** Permission of each word after /pw admin (fluxpw.admin gives them all). */
    private static final Map<String, String> ADMIN_NODES = Map.of("info", "fluxpw.manage.others", "list", "fluxpw.manage.others",
            "manage", "fluxpw.manage.others", "remove", "fluxpw.manage.others", "send", "fluxpw.teleport.others",
            "about", "fluxpw.about", "reload", "fluxpw.reload");

    private final WarpStore store = new WarpStore(getLogger());
    private final Teleports teleports = new Teleports(this);
    private final Menus menus = new Menus(this);
    private final Map<UUID, Warp> warps = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> favorites = new HashMap<>();
    /** Players warned that a warp is unsafe: visiting the same warp again within 15 s goes anyway. */
    private final Map<UUID, Unsafe> unsafe = new HashMap<>();
    private long version;

    private record Unsafe(UUID warp, long until) {
    }

    // settings
    final Map<String, Category> categories = new LinkedHashMap<>();
    final Map<String, Double> prices = new HashMap<>();
    List<String> sorts = List.of();
    private int delay;
    private boolean safetyCheck;
    private int defaultLimit;
    int nameMax;
    private boolean displayColors;
    int descriptionMin, descriptionMax;
    int maxAdmission;
    int featuredSlots;
    private long featuredMinutes;
    String defaultIcon;
    private Warp.Status defaultStatus;
    private final Set<Material> bannedIcons = EnumSet.noneOf(Material.class);
    private Set<String> disabledWorlds = Set.of();
    private boolean allowTrusted;
    private boolean announceCreation, announceVisit;
    boolean rating;

    @Override
    protected void enable() {
        settings();
        store.open();
        warps.putAll(store.load());
        favorites.putAll(store.favorites());
        PlayerWarps.bind(new Api(this));
        placeholders("fluxpw", this::placeholder);
        menus.register();
        command("pwarp")
                .description("Player warps: visit, create and manage warps.")
                .aliases("pw", "playerwarps")
                .executor(this::onCommand)
                .completer(this::complete)
                .register();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(teleports, this);
        Bukkit.getPluginManager().registerEvents(new fr.influencraft.fluxpw.compat.LegacyPermissions(this), this);
        getLogger().info(warps.size() + " warp(s) loaded.");
    }

    @Override
    protected void disable() {
        teleports.cancelAll();
        PlayerWarps.bind(null);
    }

    @Override
    public void reload() {
        super.reload();
        settings();
        changed();
    }

    private void settings() {
        var c = getConfig();
        delay = c.getInt("teleport.delay", 3);
        teleports.settings(c.getConfigurationSection("teleport"));
        safetyCheck = c.getBoolean("teleport.safety-check", true);
        defaultLimit = c.getInt("limits.default", 0);
        nameMax = c.getInt("names.max-length", 24);
        displayColors = c.getBoolean("names.display-name-colours", true);
        descriptionMin = c.getInt("description.min-length", 5);
        descriptionMax = c.getInt("description.max-length", 64);
        maxAdmission = c.getInt("admission.max", 500);
        featuredSlots = c.getInt("featured.slots", 5);
        featuredMinutes = c.getLong("featured.duration-minutes", 4320);
        defaultIcon = c.getString("defaults.icon", "OAK_SIGN");
        try {
            defaultStatus = Warp.Status.valueOf(c.getString("defaults.status", "OPENED").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            defaultStatus = Warp.Status.OPENED;
        }
        bannedIcons.clear();
        bannedIcons.addAll(List.of(Material.AIR, Material.NETHER_PORTAL, Material.END_PORTAL));
        for (String m : c.getStringList("banned-icons")) {
            Material mat = Material.matchMaterial(m);
            if (mat == null) getLogger().warning("Unknown material in banned-icons: " + m);
            else bannedIcons.add(mat);
        }
        disabledWorlds = new HashSet<>(c.getStringList("disabled-worlds").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
        allowTrusted = c.getBoolean("claims.allow-residents", true);
        announceCreation = c.getBoolean("announce.creation", true);
        announceVisit = c.getBoolean("announce.visit", false);
        rating = c.getBoolean("rating", true);
        List<String> s = new ArrayList<>();
        for (String sort : c.getStringList("sorts")) {
            String key = sort.toLowerCase(Locale.ROOT);
            if (List.of("alphabetical", "visits", "rating", "latest").contains(key)) s.add(key);
            else getLogger().warning("Unknown sort in sorts: " + sort);
        }
        sorts = s.isEmpty() ? List.of("alphabetical") : List.copyOf(s);
        prices.clear();
        ConfigurationSection p = c.getConfigurationSection("prices");
        if (p != null) for (String key : p.getKeys(false)) prices.put(key, p.getDouble(key));
        categories.clear();
        ConfigurationSection cats = c.getConfigurationSection("categories");
        if (cats != null) for (String key : cats.getKeys(false)) {
            ConfigurationSection cat = cats.getConfigurationSection(key);
            if (cat == null) continue;
            categories.put(key, new Category(key, cat.getString("name", key), cat.getString("icon", "PAPER"),
                    cat.getString("description", ""), cat.getString("permission"), cat.getBoolean("all", false)));
        }
        if (categories.values().stream().noneMatch(Category::all)) {
            categories.put("all", new Category("all", "All", "ENDER_EYE", "", null, true));
        }
    }

    // ---------------------------------------------------------------- lookups

    Map<UUID, Warp> warps() {
        return warps;
    }

    Warp warp(String idOrName) {
        if (idOrName == null) return null;
        try {
            Warp w = warps.get(UUID.fromString(idOrName));
            if (w != null) return w;
        } catch (IllegalArgumentException ignored) {
        }
        for (Warp w : warps.values()) if (w.name.equalsIgnoreCase(idOrName)) return w;
        return null;
    }

    Category allCategory() {
        for (Category c : categories.values()) if (c.all()) return c;
        return categories.values().iterator().next();
    }

    /** Category of a warp (the « all » one when its category no longer exists). */
    Category category(Warp w) {
        Category c = w.category == null ? null : categories.get(w.category);
        return c == null ? allCategory() : c;
    }

    Set<UUID> favorites(UUID player) {
        return favorites.getOrDefault(player, Set.of());
    }

    long version() {
        return version;
    }

    String cmd() {
        return Commands.label(getName(), "pwarp");
    }

    static boolean has(CommandSender s, String node) {
        return s.isOp() || s.hasPermission("fluxpw.admin") || s.hasPermission(node);
    }

    boolean canManage(Player p, Warp w) {
        return w.owner(p.getUniqueId()) || has(p, "fluxpw.manage.others");
    }

    /** Most warps the player may own: {@code fluxpw.limit.<n>} (highest wins), else {@code limits.default}. */
    int limit(Player p) {
        if (has(p, "fluxpw.limit.unlimited")) return Integer.MAX_VALUE;
        int best = defaultLimit;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            String node = info.getPermission();
            if (!info.getValue() || !node.startsWith("fluxpw.limit.")) continue;
            try {
                best = Math.max(best, Integer.parseInt(node.substring("fluxpw.limit.".length())));
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    int owned(UUID player) {
        int n = 0;
        for (Warp w : warps.values()) if (w.owner(player)) n++;
        return n;
    }

    List<Warp> featured() {
        List<Warp> out = new ArrayList<>();
        for (Warp w : warps.values()) if (w.featured() && w.open()) out.add(w);
        out.sort((a, b) -> Long.compare(a.featuredUntil, b.featuredUntil));
        return out;
    }

    double price(String key) {
        return money().available() ? prices.getOrDefault(key, 0.0) : 0;
    }

    /** The item of a warp icon: a custom item id or a material, the default icon when unknown. */
    ItemStack iconItem(String icon) {
        String spec = icon == null || icon.isBlank() ? defaultIcon : icon;
        ItemStack item = null;
        if (spec.contains(":") && !spec.startsWith("minecraft:")) item = CustomItems.item(spec);
        if (item == null) {
            Material m = Material.matchMaterial(spec);
            item = new ItemStack(m == null || !m.isItem() || m.isAir() ? Material.PAPER : m);
        }
        item = item.clone();
        item.setAmount(1);
        return item;
    }

    /** A warp changed (or was created): saved, and every menu showing warps drawn again. */
    void save(Warp w) {
        store.save(w);
        changed();
    }

    void changed() {
        version++;
        menus.invalidate();
    }

    void send(CommandSender to, String key, String... vars) {
        messages().send(to, key, vars);
    }

    private void broadcast(Player except, String key, String... vars) {
        for (Player online : Bukkit.getOnlinePlayers()) if (!online.equals(except)) send(online, key, vars);
    }

    // ---------------------------------------------------------------- paying

    /**
     * Runs an action that costs {@code prices.<priceKey>}: when it has a price, the player confirms it first, pays,
     * and gets it back when the action fails. {@code after} runs once everything is over (menu reopened), even on
     * cancel; may be null.
     */
    void paid(Player p, String priceKey, String action, String detail, BooleanSupplier run, Runnable after) {
        double fee = price(priceKey);
        if (fee <= 0) {
            run.getAsBoolean();
            if (after != null) after.run();
            return;
        }
        if (!money().has(p, fee)) {
            send(p, "no-money", "%price%", money().format(fee));
            Sounds.cue(p, "error");
            if (after != null) after.run();
            return;
        }
        Dialogs.confirm(p, messages().raw("confirm-title"),
                messages().raw("confirm-price", "%action%", action, "%price%", money().format(fee)),
                yes -> {
                    Origin origin = Origin.of(this, priceKey).detail(detail).notifyPlayer();
                    if (!money().take(p, fee, origin)) {
                        send(p, "no-money", "%price%", money().format(fee));
                    } else if (!run.getAsBoolean()) {
                        money().give(p, fee, Origin.of(this, "refund").detail(detail).notifyPlayer());
                    }
                    if (after != null) after.run();
                },
                no -> {
                    if (after != null) after.run();
                });
    }

    // ---------------------------------------------------------------- creating

    /** Why the player cannot have a warp called {@code name} here (message key + variables), null when he can. */
    private String[] createRefusal(Player p, String name) {
        if (owned(p.getUniqueId()) >= limit(p)) return new String[]{"limit-reached", "%limit%", String.valueOf(limit(p))};
        String refused = placeRefusal(p, "create-disabled-world");
        if (refused != null) return new String[]{refused, "%world%", p.getWorld().getName()};
        String nameRefused = nameRefusal(name, null);
        if (nameRefused != null) return new String[]{nameRefused, "%name%", name, "%limit%", String.valueOf(nameMax)};
        return null;
    }

    /** Why a warp cannot stand where the player is (message key), null when it can. */
    private String placeRefusal(Player p, String worldKey) {
        if (disabledWorlds.contains(p.getWorld().getName().toLowerCase(Locale.ROOT)) && !has(p, "fluxpw.admin")) return worldKey;
        Location at = p.getLocation();
        if (Claims.claimId(at) != null && !Claims.owner(at, p.getUniqueId())
                && !(allowTrusted && Claims.member(at, p.getUniqueId()))) return "foreign-claim";
        return null;
    }

    /** Why {@code name} cannot name a warp (message key), null when it can. {@code self} = the warp being renamed. */
    String nameRefusal(String name, Warp self) {
        if (name == null || name.isBlank()) return "invalid-input";
        if (name.contains(" ")) return "name-space";
        if (name.length() > nameMax) return "name-too-long";
        if (SUBCOMMANDS.contains(name.toLowerCase(Locale.ROOT))) return "name-reserved";
        for (Warp w : warps.values()) if (w != self && w.name.equalsIgnoreCase(name)) return "name-taken";
        return null;
    }

    void create(Player p, String name) {
        if (!has(p, "fluxpw.create")) {
            send(p, "no-permission");
            return;
        }
        name = name == null ? "" : name.trim();
        String[] refused = createRefusal(p, name);
        if (refused != null) {
            refuse(p, refused);
            return;
        }
        String finalName = name;
        paid(p, "create", messages().raw("actions.create", "%name%", name), name, () -> {
            String[] again = createRefusal(p, finalName);
            if (again != null) {
                refuse(p, again);
                return false;
            }
            Warp w = new Warp(UUID.randomUUID());
            w.name = finalName;
            w.owner = p.getUniqueId();
            w.ownerName = p.getName();
            w.location(p.getLocation());
            w.category = allCategory().key();
            w.status = defaultStatus;
            w.created = System.currentTimeMillis();
            warps.put(w.id, w);
            save(w);
            send(p, "created", "%name%", finalName, "%cmd%", cmd());
            Sounds.cue(p, "success");
            if (announceCreation) broadcast(p, "created-broadcast", "%player%", p.getName(), "%name%", finalName, "%cmd%", cmd());
            return true;
        }, null);
    }

    private void refuse(CommandSender to, String[] keyAndVars) {
        String[] vars = new String[keyAndVars.length - 1];
        System.arraycopy(keyAndVars, 1, vars, 0, vars.length);
        send(to, keyAndVars[0], vars);
        if (to instanceof Player p) Sounds.cue(p, "error");
    }

    // ---------------------------------------------------------------- visiting

    /** Why the player cannot visit the warp (message key), null when he can. */
    private String visitRefusal(Player p, Warp w) {
        Category c = category(w);
        if (!c.allowed(p)) return "category-locked";
        boolean manager = canManage(p, w);
        if (!w.open() && !manager) return "warp-closed";
        if (w.blocked.contains(p.getUniqueId()) && !manager) return "warp-blocked";
        if (w.location() == null) return "warp-world-missing";
        return null;
    }

    private boolean paysAdmission(Player p, Warp w) {
        return w.admission > 0 && money().available() && !canManage(p, w);
    }

    /** Visit with every step: rights, password, admission fee confirmation, then the teleport. */
    void visit(Player p, Warp w, Runnable back) {
        if (!has(p, "fluxpw.use")) {
            send(p, "no-permission");
            return;
        }
        String refused = visitRefusal(p, w);
        if (refused != null) {
            send(p, refused, "%warp%", w.shownName());
            Sounds.cue(p, "error");
            return;
        }
        if (paysAdmission(p, w) && !money().has(p, w.admission)) {
            send(p, "no-money-visit", "%warp%", w.shownName(), "%price%", money().format(w.admission));
            Sounds.cue(p, "error");
            return;
        }
        if (w.status == Warp.Status.PASSWORD_PROTECTED && !canManage(p, w)) {
            Dialogs.form(p, messages().raw("password-title"))
                    .body(messages().raw("password-body", "%warp%", w.shownName()))
                    .requiredInput("password", messages().raw("password-field"), "", 15)
                    .onSubmit((pl, answer) -> {
                        // the owner may have removed the password meanwhile: then no check
                        if (w.status == Warp.Status.PASSWORD_PROTECTED && !answer.text("password").equals(w.password)) {
                            send(pl, "password-wrong");
                            Sounds.cue(pl, "error");
                            return;
                        }
                        confirmAdmission(pl, w, back);
                    })
                    .onCancel(pl -> {
                        if (back != null) back.run();
                    })
                    .open();
            return;
        }
        confirmAdmission(p, w, back);
    }

    private void confirmAdmission(Player p, Warp w, Runnable back) {
        if (!paysAdmission(p, w)) {
            teleport(p, w, 0);
            return;
        }
        int fee = w.admission;
        Dialogs.confirm(p, messages().raw("admission-title"),
                messages().raw("admission-confirm", "%warp%", w.shownName(), "%price%", money().format(fee), "%player%", w.ownerName),
                yes -> teleport(yes, w, fee),
                no -> {
                    if (back != null) back.run();
                });
    }

    /** Sends the player; {@code confirmedFee} is the most he agreed to pay (the owner may change it meanwhile). */
    private void teleport(Player p, Warp w, int confirmedFee) {
        if (!warps.containsKey(w.id)) {
            send(p, "unknown-warp");
            return;
        }
        String refused = visitRefusal(p, w);
        if (refused != null) {
            send(p, refused, "%warp%", w.shownName());
            return;
        }
        Location to = w.location();
        if (safetyCheck && !safe(to)) {
            Unsafe u = unsafe.get(p.getUniqueId());
            if (u == null || !u.warp().equals(w.id) || u.until() < System.currentTimeMillis()) {
                unsafe.put(p.getUniqueId(), new Unsafe(w.id, System.currentTimeMillis() + 15000));
                send(p, "unsafe", "%warp%", w.shownName());
                Sounds.cue(p, "alert");
                return;
            }
        }
        unsafe.remove(p.getUniqueId());
        boolean manager = canManage(p, w);
        int fee = manager || !money().available() ? 0 : Math.min(confirmedFee, w.admission);
        if (fee > 0 && !money().take(p, fee, Origin.of(this, "visit").detail(w.name).notifyPlayer())) {
            send(p, "no-money-visit", "%warp%", w.shownName(), "%price%", money().format(fee));
            return;
        }
        int wait = has(p, "fluxpw.delay.bypass") ? 0 : delay;
        teleports.start(p, to, wait, arrived -> {
            if (!arrived) {
                if (fee > 0) money().give(p, fee, Origin.of(this, "visit-refund").detail(w.name).notifyPlayer());
                if (p.isOnline()) send(p, "teleport-cancelled");
                return;
            }
            if (fee > 0) money().give(Bukkit.getOfflinePlayer(w.owner), fee,
                    Origin.of(this, "visit-income").detail(w.name + " (" + p.getName() + ")").notifyPlayer());
            send(p, "teleported", "%warp%", w.shownName(), "%player%", w.ownerName);
            if (!manager && warps.containsKey(w.id)) {
                w.visits++;
                w.today++;
                if (w.visitors.add(p.getUniqueId())) store.link(w.id, p.getUniqueId(), WarpStore.VISIT, true);
                save(w);
                if (announceVisit) {
                    Player owner = Bukkit.getPlayer(w.owner);
                    if (owner != null) send(owner, "visit-notification", "%player%", p.getName(), "%warp%", w.shownName());
                }
            }
        });
    }

    /** Room for the head and something solid to stand on. */
    private static boolean safe(Location at) {
        if (at == null || at.getWorld() == null) return false;
        Block head = at.clone().add(0, 1, 0).getBlock();
        Material ground = at.clone().add(0, -1, 0).getBlock().getType();
        return head.isEmpty() && ground.isSolid() && ground != Material.CACTUS && ground != Material.MAGMA_BLOCK;
    }

    // ---------------------------------------------------------------- ratings and favourites

    void rate(Player p, Warp w, Runnable back) {
        if (!rating) return;
        if (!has(p, "fluxpw.review")) {
            send(p, "no-permission");
            return;
        }
        if (w.owner(p.getUniqueId())) {
            send(p, "rate-own");
            Sounds.cue(p, "error");
            return;
        }
        if (w.reviewers.contains(p.getUniqueId())) {
            send(p, "rate-already");
            Sounds.cue(p, "error");
            return;
        }
        var form = Dialogs.form(p, messages().raw("rate-title")).body(messages().raw("rate-body", "%warp%", w.shownName()));
        // one button per line, best rating first: five buttons side by side do not fit the window
        String on = messages().raw("rate-star-on"), off = messages().raw("rate-star-off");
        for (int i = 5; i >= 1; i--) {
            form.action(String.valueOf(i), messages().raw("rate-choice", "%stars%", on.repeat(i) + off.repeat(5 - i),
                    "%label%", messages().raw("rate-labels." + i)));
        }
        form.columns(1)
                .onSubmit((pl, answer) -> {
                    int stars;
                    try {
                        stars = Integer.parseInt(answer.button());
                    } catch (NumberFormatException e) {
                        return;
                    }
                    if (stars < 1 || stars > 5 || !warps.containsKey(w.id) || !w.reviewers.add(pl.getUniqueId())) return;
                    w.ratingSum += stars;
                    store.link(w.id, pl.getUniqueId(), WarpStore.REVIEW, true);
                    save(w);
                    send(pl, "rated", "%warp%", w.shownName(), "%stars%", String.valueOf(stars));
                    Sounds.cue(pl, "success");
                    if (back != null) back.run();
                })
                .onCancel(pl -> {
                    if (back != null) back.run();
                })
                .open();
    }

    void favorite(Player p, Warp w) {
        if (!has(p, "fluxpw.favorite")) {
            send(p, "no-permission");
            return;
        }
        Set<UUID> mine = favorites.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashSet<>());
        boolean added = mine.add(w.id);
        if (!added) mine.remove(w.id);
        store.link(w.id, p.getUniqueId(), WarpStore.FAVORITE, added);
        send(p, added ? "favorite-added" : "favorite-removed", "%warp%", w.shownName());
        Sounds.cue(p, added ? "toggle-on" : "toggle-off");
        menus.invalidate();
    }

    // ---------------------------------------------------------------- managing

    /** Checks the right to change a warp setting ({@code node}); tells the player when he cannot. */
    boolean may(Player p, Warp w, String node) {
        if (!warps.containsKey(w.id)) {
            send(p, "unknown-warp");
            return false;
        }
        if (!canManage(p, w)) {
            send(p, "not-owner");
            Sounds.cue(p, "error");
            return false;
        }
        if (node != null && !has(p, node)) {
            send(p, "no-permission");
            Sounds.cue(p, "error");
            return false;
        }
        return true;
    }

    /**
     * A text form for one setting. {@code apply} gets the typed text and takes care of what follows (paying, then
     * {@code back}); a cancel runs {@code back} directly.
     */
    private void ask(Player p, Warp w, String key, String initial, int max, Runnable back, java.util.function.Consumer<String> apply) {
        Dialogs.form(p, messages().raw("forms." + key + ".title"))
                .body(messages().raw("forms." + key + ".body", "%warp%", w.shownName(), "%max%", String.valueOf(descriptionMax),
                        "%min%", String.valueOf(descriptionMin), "%limit%", money().format(maxAdmission)))
                .requiredInput("value", messages().raw("forms." + key + ".field"), initial == null ? "" : initial, max)
                .onSubmit((pl, answer) -> apply.accept(answer.text("value")))
                .onCancel(pl -> {
                    if (back != null) back.run();
                })
                .open();
    }

    /** A refused value: tell why, then back to the menu. */
    private void refused(Player p, Runnable back, String key, String... vars) {
        send(p, key, vars);
        Sounds.cue(p, "error");
        if (back != null) back.run();
    }

    void setAdmission(Player p, Warp w, Runnable back) {
        if (!may(p, w, "fluxpw.settings.setadmission")) return;
        ask(p, w, "admission", String.valueOf(w.admission), 9, back, text -> {
            int value;
            try {
                value = Integer.parseInt(text.replace(" ", ""));
            } catch (NumberFormatException e) {
                refused(p, back, "not-a-number", "%input%", text);
                return;
            }
            if (value < 0 || value > maxAdmission) {
                refused(p, back, "admission-out-of-range", "%max%", money().format(maxAdmission));
                return;
            }
            paid(p, "admission", messages().raw("actions.admission"), w.name, () -> {
                w.admission = value;
                save(w);
                send(p, "admission-set", "%warp%", w.shownName(),
                        "%price%", value == 0 ? messages().raw("free") : money().format(value));
                return true;
            }, back);
        });
    }

    void setCategory(Player p, Warp w, Runnable back) {
        if (!may(p, w, "fluxpw.settings.settype")) return;
        List<Category> choices = new ArrayList<>(categories.values());
        List<String> names = choices.stream().map(Category::name).toList();
        Dialogs.form(p, messages().raw("forms.category.title"))
                .body(messages().raw("forms.category.body", "%warp%", w.shownName()))
                .choice("category", messages().raw("forms.category.field"), names)
                .onSubmit((pl, answer) -> {
                    int i = names.indexOf(answer.text("category"));
                    if (i < 0) {
                        if (back != null) back.run();
                        return;
                    }
                    Category c = choices.get(i);
                    paid(pl, "category", messages().raw("actions.category"), w.name, () -> {
                        w.category = c.key();
                        save(w);
                        send(pl, "category-set", "%warp%", w.shownName(), "%category%", c.name());
                        return true;
                    }, back);
                })
                .onCancel(pl -> {
                    if (back != null) back.run();
                })
                .open();
    }

    void setDisplayName(Player p, Warp w, Runnable back) {
        if (!may(p, w, "fluxpw.settings.changedisplayname")) return;
        ask(p, w, "display-name", w.displayName, 64, back, text -> {
            String value = displayColors ? text : strip(text);
            if (strip(value).length() > nameMax) {
                refused(p, back, "name-too-long", "%limit%", String.valueOf(nameMax));
                return;
            }
            paid(p, "display-name", messages().raw("actions.display-name"), w.name, () -> {
                w.displayName = value;
                save(w);
                send(p, "display-name-set", "%warp%", w.name, "%name%", value);
                return true;
            }, back);
        });
    }

    /** Text without its colour codes (legacy {@code &x} and {@code &#RRGGBB}). */
    static String strip(String text) {
        return text == null ? "" : text.replaceAll("(?i)&#[0-9a-f]{6}|&[0-9a-fk-or]|<[^>]+>", "");
    }

    void setDescription(Player p, Warp w, Runnable back) {
        if (!may(p, w, "fluxpw.settings.setdescription")) return;
        ask(p, w, "description", w.description, descriptionMax + 32, back, text -> {
            int length = strip(text).length();
            if (length < descriptionMin || length > descriptionMax) {
                refused(p, back, "description-length", "%min%", String.valueOf(descriptionMin), "%max%", String.valueOf(descriptionMax));
                return;
            }
            paid(p, "description", messages().raw("actions.description"), w.name, () -> {
                w.description = text;
                save(w);
                send(p, "description-set", "%warp%", w.shownName());
                return true;
            }, back);
        });
    }

    void rename(Player p, Warp w, Runnable back) {
        if (!may(p, w, "fluxpw.rename")) return;
        ask(p, w, "rename", w.name, nameMax, back, text -> {
            String why = nameRefusal(text, w);
            if (why != null) {
                refused(p, back, why, "%name%", text, "%limit%", String.valueOf(nameMax));
                return;
            }
            paid(p, "rename", messages().raw("actions.rename"), w.name, () -> {
                if (nameRefusal(text, w) != null) return false;
                String former = w.name;
                w.name = text;
                save(w);
                send(p, "renamed", "%old%", former, "%name%", text);
                return true;
            }, back);
        });
    }

    void relocate(Player p, Warp w, Runnable back) {
        if (!may(p, w, "fluxpw.relocate")) return;
        String refused = placeRefusal(p, "relocate-disabled-world");
        if (refused != null) {
            send(p, refused, "%world%", p.getWorld().getName());
            Sounds.cue(p, "error");
            if (back != null) back.run();
            return;
        }
        paid(p, "relocate", messages().raw("actions.relocate"), w.name, () -> {
            if (placeRefusal(p, "relocate-disabled-world") != null) return false;
            w.location(p.getLocation());
            save(w);
            send(p, "relocated", "%warp%", w.shownName());
            return true;
        }, back);
    }

    void transfer(Player p, Warp w, Runnable back) {
        if (!may(p, w, "fluxpw.transfer")) return;
        ask(p, w, "transfer", "", 16, back, text -> {
            Player target = Bukkit.getPlayerExact(text);
            if (target == null) refused(p, back, "player-offline", "%player%", text);
            else if (w.owner(target.getUniqueId())) refused(p, back, "transfer-already-owner");
            else if (owned(target.getUniqueId()) >= limit(target)) refused(p, back, "transfer-limit", "%player%", target.getName());
            else paid(p, "transfer", messages().raw("actions.transfer"), w.name, () -> {
                    if (!target.isOnline()) return false;
                    w.owner = target.getUniqueId();
                    w.ownerName = target.getName();
                    save(w);
                    send(p, "transferred", "%warp%", w.shownName(), "%player%", target.getName());
                    send(target, "transferred-to-you", "%warp%", w.shownName(), "%player%", p.getName());
                    return true;
                }, () -> menus.afterTransfer(p, w));
        });
    }

    void setStatus(Player p, Warp w, Runnable back) {
        if (!may(p, w, "fluxpw.settings.setstatus")) return;
        Dialogs.form(p, messages().raw("forms.status.title"))
                .body(messages().raw("forms.status.body", "%warp%", w.shownName(), "%status%", messages().raw("status." + w.status.name().toLowerCase(Locale.ROOT))))
                .action("OPENED", messages().raw("status.opened"))
                .action("CLOSED", messages().raw("status.closed"))
                .action("PASSWORD_PROTECTED", messages().raw("status.password_protected"))
                .columns(3)
                .onSubmit((pl, answer) -> {
                    Warp.Status status;
                    try {
                        status = Warp.Status.valueOf(answer.button());
                    } catch (IllegalArgumentException e) {
                        if (back != null) back.run();
                        return;
                    }
                    if (status == Warp.Status.PASSWORD_PROTECTED) {
                        askPassword(pl, w, back);
                        return;
                    }
                    paid(pl, "status", messages().raw("actions.status"), w.name, () -> {
                        w.status = status;
                        save(w);
                        send(pl, "status-set", "%warp%", w.shownName(), "%status%", messages().raw("status." + status.name().toLowerCase(Locale.ROOT)));
                        return true;
                    }, back);
                })
                .onCancel(pl -> {
                    if (back != null) back.run();
                })
                .open();
    }

    private void askPassword(Player p, Warp w, Runnable back) {
        ask(p, w, "password", "", 15, back, text -> {
            if (text.length() < 3 || text.length() > 15) {
                refused(p, back, "password-length");
                return;
            }
            paid(p, "status", messages().raw("actions.status"), w.name, () -> {
                w.password = text;
                w.status = Warp.Status.PASSWORD_PROTECTED;
                save(w);
                send(p, "password-set", "%warp%", w.shownName());
                return true;
            }, back);
        });
    }

    void setIcon(Player p, Warp w, Runnable back) {
        if (!may(p, w, "fluxpw.settings.setitem")) return;
        Dialogs.form(p, messages().raw("forms.icon.title"))
                .body(messages().raw("forms.icon.body", "%warp%", w.shownName()))
                .input("value", messages().raw("forms.icon.field"), "", 64)
                .action("hand", messages().raw("forms.icon.hand"))
                .action("typed", messages().raw("forms.icon.typed"))
                .columns(2)
                .onSubmit((pl, answer) -> {
                    String icon = answer.button().equals("hand") ? iconOf(pl.getInventory().getItemInMainHand()) : typedIcon(answer.text("value"));
                    String refused = iconRefusal(pl, icon);
                    if (refused != null) {
                        send(pl, refused, "%item%", icon == null ? answer.text("value") : icon);
                        Sounds.cue(pl, "error");
                        if (back != null) back.run();
                        return;
                    }
                    paid(pl, "icon", messages().raw("actions.icon"), w.name, () -> {
                        w.icon = icon;
                        save(w);
                        send(pl, "icon-set", "%warp%", w.shownName(), "%item%", icon.toLowerCase(Locale.ROOT));
                        return true;
                    }, back);
                })
                .onCancel(pl -> {
                    if (back != null) back.run();
                })
                .open();
    }

    /** Icon of an item held: its custom item id, else its material; null for an empty hand. */
    static String iconOf(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        String custom = CustomItems.id(item);
        return custom != null ? custom : item.getType().name();
    }

    /** Icon typed by the player: a known custom item id, else a material; null when neither. */
    private static String typedIcon(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        if (t.contains(":") && !t.toLowerCase(Locale.ROOT).startsWith("minecraft:") && CustomItems.exists(t)) return CustomItems.resolve(t);
        Material m = Material.matchMaterial(t);
        return m == null || !m.isItem() ? null : m.name();
    }

    /** Why the player cannot use this icon (message key), null when he can. */
    private String iconRefusal(Player p, String icon) {
        if (icon == null) return "icon-unknown";
        boolean custom = icon.contains(":");
        if (!custom && bannedIcons.contains(Material.matchMaterial(icon))) return "icon-banned";
        if (has(p, "fluxpw.icon.*")) return null;
        // fluxpw.icon.diamond, fluxpw.icon.<namespace>.<id> for a custom item
        String node = "fluxpw.icon." + icon.toLowerCase(Locale.ROOT).replace(':', '.');
        return p.hasPermission(node) ? null : "icon-no-permission";
    }

    void feature(Player p, Warp w, Runnable back) {
        if (!may(p, w, "fluxpw.feature")) return;
        if (w.featured()) {
            send(p, "feature-already");
            Sounds.cue(p, "error");
            return;
        }
        if (featured().size() >= featuredSlots) {
            send(p, "feature-full");
            Sounds.cue(p, "error");
            return;
        }
        paid(p, "feature", messages().raw("actions.feature"), w.name, () -> {
            if (w.featured() || featured().size() >= featuredSlots) {
                send(p, "feature-full");
                return false;
            }
            w.featuredUntil = System.currentTimeMillis() + featuredMinutes * 60_000L;
            save(w);
            send(p, "featured", "%warp%", w.shownName());
            return true;
        }, back);
    }

    void block(Player p, Warp w, Runnable back) {
        if (!may(p, w, "fluxpw.settings.blockplayer")) return;
        ask(p, w, "block", "", 16, back, text -> {
            OfflinePlayer target = Players.find(text);
            if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
                refused(p, back, "player-unknown", "%player%", text);
            } else if (target.getUniqueId().equals(p.getUniqueId())) {
                refused(p, back, "block-self");
            } else if (!w.blocked.add(target.getUniqueId())) {
                refused(p, back, "block-already", "%player%", Players.name(target, text));
            } else {
                store.link(w.id, target.getUniqueId(), WarpStore.BLOCK, true);
                save(w);
                send(p, "blocked", "%player%", Players.name(target, text), "%warp%", w.shownName());
                Sounds.cue(p, "success");
                if (back != null) back.run();
            }
        });
    }

    void unblock(Player p, Warp w, UUID player) {
        if (!may(p, w, "fluxpw.settings.blockplayer") || !w.blocked.remove(player)) return;
        store.link(w.id, player, WarpStore.BLOCK, false);
        save(w);
        send(p, "unblocked", "%player%", Players.name(Bukkit.getOfflinePlayer(player), "?"), "%warp%", w.shownName());
        Sounds.cue(p, "remove");
    }

    /** Deletes a warp after confirmation; {@code staff}: from /pw admin (the right to delete one's own warps is not asked). */
    void remove(CommandSender sender, Warp w, boolean staff, Runnable back, Runnable afterRemoved) {
        if (!(sender instanceof Player p)) {
            delete(w);
            send(sender, "removed", "%warp%", w.name);
            return;
        }
        if (!may(p, w, staff ? null : "fluxpw.remove")) return;
        Dialogs.confirm(p, messages().raw("remove-title"), messages().raw("remove-confirm", "%warp%", w.shownName()),
                yes -> {
                    if (!warps.containsKey(w.id)) return;
                    delete(w);
                    double refund = price("delete-refund");
                    if (refund > 0) money().give(Bukkit.getOfflinePlayer(w.owner), refund, Origin.of(this, "delete-refund").detail(w.name).notifyPlayer());
                    send(yes, "removed", "%warp%", w.shownName());
                    Sounds.cue(yes, "remove");
                    if (afterRemoved != null) afterRemoved.run();
                },
                no -> {
                    if (back != null) back.run();
                });
    }

    private void delete(Warp w) {
        warps.remove(w.id);
        for (Set<UUID> f : favorites.values()) f.remove(w.id);
        store.delete(w);
        changed();
    }

    // ---------------------------------------------------------------- commands

    /** Commands of /pw help, in order: what follows the label, lang key, permission; the staff also sees /pw admin. */
    private List<ChatHelp.Entry> playerHelp(CommandSender sender) {
        List<ChatHelp.Entry> out = new ArrayList<>(List.of(entry("", "menu", "fluxpw.use"), entry("", "visit", "fluxpw.use"),
                entry("create", "create", "fluxpw.create"), entry("manage", "manage", "fluxpw.manage"),
                entry("remove", "remove", "fluxpw.remove"), entry("mywarps", "mywarps", "fluxpw.mywarps"),
                entry("favorites", "favorites", "fluxpw.favorite"), entry("help", "help", "fluxpw.help")));
        if (staff(sender)) out.add(entry("admin", "admin", null));
        return out;
    }

    private List<ChatHelp.Entry> adminHelp() {
        List<ChatHelp.Entry> out = new ArrayList<>();
        for (String sub : ADMIN) out.add(entry("admin " + sub, "admin-" + sub, ADMIN_NODES.get(sub)));
        return out;
    }

    /** {@code sub} followed by its arguments as the lang file writes them ({@code help.args.<key>}). */
    private ChatHelp.Entry entry(String sub, String key, String permission) {
        String args = messages().raw("help.args." + key);
        return new ChatHelp.Entry((sub + " " + args).trim(), key, permission);
    }

    private static int page(String[] args, int index) {
        try {
            return args.length > index ? Integer.parseInt(args[index]) : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** May use at least one /pw admin command. */
    private static boolean staff(CommandSender s) {
        if (has(s, "fluxpw.admin")) return true;
        for (String node : ADMIN_NODES.values()) if (s.hasPermission(node)) return true;
        return false;
    }

    /** Own warp of the player, or null after telling why (unknown, not his: staff go through /pw admin). */
    private Warp ownWarp(Player p, String name) {
        Warp w = warp(name);
        if (w == null) send(p, "unknown-warp");
        else if (!w.owner(p.getUniqueId())) send(p, "not-owner-admin", "%cmd%", cmd());
        else return w;
        return null;
    }

    /**
     * {@code /pw [warp] | create <name> | manage <warp> | remove <warp> | mywarps | favorites | help [page] | admin ...}.
     * Players' commands only act on their own warps; everything on someone else's warp goes through /pw admin.
     */
    private boolean onCommand(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            Player p = Players.of(sender);
            if (p == null) return true;
            if (!has(p, "fluxpw.use")) send(p, "no-permission");
            else menus.openHome(p);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("admin")) {
            onAdmin(sender, args);
            return true;
        }
        if (sub.equals("help")) {
            if (!has(sender, "fluxpw.help")) send(sender, "no-permission");
            else ChatHelp.show(sender, messages(), cmd(), playerHelp(sender), "help", false, page(args, 1), node -> has(sender, node));
            return true;
        }
        if (!SUBCOMMANDS.contains(sub)) {
            visitCommand(sender, args);
            return true;
        }
        Player p = Players.of(sender);
        if (p == null) return true;
        String node = switch (sub) {
            case "favorites" -> "fluxpw.favorite";
            case "open" -> "fluxpw.use";
            default -> "fluxpw." + sub;
        };
        if (!has(p, node)) {
            send(p, "no-permission");
            return true;
        }
        String arg = args.length > 1 ? args[1] : null;
        switch (sub) {
            case "create" -> {
                if (arg == null || args.length > 2) send(p, "usage", "%cmd%", cmd());
                else create(p, arg);
            }
            case "manage" -> {
                Warp w = arg == null ? null : ownWarp(p, arg);
                if (arg == null) send(p, "usage", "%cmd%", cmd());
                else if (w != null) menus.openManage(p, w, false);
            }
            case "remove" -> {
                Warp w = arg == null ? null : ownWarp(p, arg);
                if (arg == null) send(p, "usage", "%cmd%", cmd());
                else if (w != null) remove(p, w, false, null, null);
            }
            case "mywarps" -> menus.openList(p, Menus.Kind.MINE, null);
            case "favorites" -> menus.openList(p, Menus.Kind.FAVORITES, null);
            case "open" -> {
                // former menu names (buttons of DeluxeMenus): not in the help
                switch (arg == null ? "" : arg.toLowerCase(Locale.ROOT)) {
                    case "my_warps" -> menus.openList(p, Menus.Kind.MINE, null);
                    case "saved_warps" -> menus.openList(p, Menus.Kind.FAVORITES, null);
                    case "warps" -> menus.openList(p, Menus.Kind.CATEGORY, allCategory().key());
                    default -> menus.openHome(p);
                }
            }
            default -> {
            }
        }
        return true;
    }

    /** {@code /pw <warp>}. */
    private void visitCommand(CommandSender sender, String[] args) {
        Warp w = warp(args[0]);
        if (w == null) {
            send(sender, "unknown-warp");
            return;
        }
        Player p = Players.of(sender);
        if (p != null) visit(p, w, null);
    }

    /** {@code /pw admin [help [page] | info <warp> | list [player] | manage <warp> | remove <warp> | send <warp> <player> | about | reload]}. */
    private void onAdmin(CommandSender sender, String[] args) {
        if (!staff(sender)) {
            send(sender, "no-permission");
            return;
        }
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "help";
        if (sub.equals("help") || !ADMIN.contains(sub)) {
            ChatHelp.show(sender, messages(), cmd(), adminHelp(), "admin help", true, page(args, 2), node -> has(sender, node));
            return;
        }
        if (!has(sender, ADMIN_NODES.get(sub))) {
            send(sender, "no-permission");
            return;
        }
        String arg = args.length > 2 ? args[2] : null;
        switch (sub) {
            case "reload" -> {
                reload();
                send(sender, "reloaded");
            }
            case "about" -> send(sender, "about", "%version%", getPluginMeta().getVersion(),
                    "%authors%", String.join(", ", getPluginMeta().getAuthors()), "%warps%", String.valueOf(warps.size()));
            case "list" -> {
                List<Warp> list = new ArrayList<>();
                OfflinePlayer owner = arg == null ? null : Players.find(arg);
                if (arg != null && owner == null) {
                    send(sender, "player-unknown", "%player%", arg);
                    return;
                }
                for (Warp w : warps.values()) if (owner == null || w.owner(owner.getUniqueId())) list.add(w);
                list.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                send(sender, "admin-list", "%count%", String.valueOf(list.size()), "%player%", owner == null ? "" : Players.name(owner, arg));
                for (Warp w : list) send(sender, "admin-list-line", "%cmd%", cmd(), "%warp%", w.name,
                        "%owner%", w.ownerName == null ? "?" : w.ownerName, "%world%", w.world == null ? "?" : w.world,
                        "%status%", messages().raw("status." + w.status.name().toLowerCase(Locale.ROOT)));
            }
            default -> {
                Warp w = warp(arg);
                if (arg == null) {
                    ChatHelp.show(sender, messages(), cmd(), adminHelp(), "admin help", true, 1, node -> has(sender, node));
                    return;
                }
                if (w == null) {
                    send(sender, "unknown-warp");
                    return;
                }
                switch (sub) {
                    case "info" -> send(sender, "admin-info", "%warp%", w.name, "%display%", w.shownName(), "%id%", w.id.toString(),
                            "%owner%", w.ownerName == null ? "?" : w.ownerName, "%world%", w.world == null ? "?" : w.world,
                            "%x%", String.valueOf((int) Math.floor(w.x)), "%y%", String.valueOf((int) Math.floor(w.y)),
                            "%z%", String.valueOf((int) Math.floor(w.z)), "%category%", category(w).name(),
                            "%status%", messages().raw("status." + w.status.name().toLowerCase(Locale.ROOT)),
                            "%admission%", w.admission > 0 ? money().format(w.admission) : messages().raw("free"),
                            "%visits%", String.valueOf(w.visits), "%visitors%", String.valueOf(w.visitors.size()),
                            "%rating%", String.format(Locale.ROOT, "%.1f", w.rating()), "%reviews%", String.valueOf(w.reviewers.size()),
                            "%blocked%", String.valueOf(w.blocked.size()),
                            "%created%", fr.influencraft.flux.api.util.Format.date(w.created),
                            "%featured%", w.featured() ? fr.influencraft.flux.api.util.Format.date(w.featuredUntil) : "-", "%cmd%", cmd());
                    case "remove" -> remove(sender, w, true, null, null);
                    case "manage" -> {
                        Player p = Players.of(sender);
                        if (p != null) menus.openManage(p, w, false);
                    }
                    case "send" -> {
                        Player target = args.length > 3 ? Bukkit.getPlayerExact(args[3]) : null;
                        if (args.length <= 3) send(sender, "admin-send-usage", "%cmd%", cmd());
                        else if (target == null) send(sender, "player-offline", "%player%", args[3]);
                        else {
                            visit(target, w, null);
                            send(sender, "admin-sent", "%player%", target.getName(), "%warp%", w.shownName());
                        }
                    }
                    default -> {
                    }
                }
            }
        }
    }

    private List<String> complete(CommandSender sender, String[] args) {
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        boolean admin = args.length > 1 && args[0].equalsIgnoreCase("admin") && staff(sender);
        if (args.length == 1) {
            for (String sub : SUBCOMMANDS) {
                if (sub.equals("open")) continue;
                if (sub.equals("admin") ? staff(sender) : has(sender, sub.equals("favorites") ? "fluxpw.favorite" : "fluxpw." + sub)) out.add(sub);
            }
            for (Warp w : warps.values()) if (w.open()) out.add(w.name);
        } else if (admin && args.length == 2) {
            for (String sub : ADMIN) if (sub.equals("help") || has(sender, ADMIN_NODES.get(sub))) out.add(sub);
        } else if (admin && args.length == 3) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("list")) return Players.complete(args[2]);
            if (List.of("info", "manage", "remove", "send").contains(sub)) for (Warp w : warps.values()) out.add(w.name);
        } else if (admin && args.length == 4 && args[1].equalsIgnoreCase("send")) {
            return Players.complete(args[3]);
        } else if (!admin && args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ((sub.equals("manage") || sub.equals("remove")) && sender instanceof Player p) {
                for (Warp w : warps.values()) if (w.owner(p.getUniqueId())) out.add(w.name);
            }
        }
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(typed));
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    // ---------------------------------------------------------------- events

    /** Keeps the owner names of a player's warps up to date (searching by owner, offline owners). */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        for (Warp w : warps.values()) {
            if (w.owner(p.getUniqueId()) && !p.getName().equals(w.ownerName)) {
                w.ownerName = p.getName();
                save(w);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        unsafe.remove(e.getPlayer().getUniqueId());
        menus.forget(e.getPlayer());
    }

    // ---------------------------------------------------------------- placeholders

    /**
     * {@code %fluxpw_total_warps%}, {@code _owned} and {@code _limit} (of the player), {@code _visits[_<warp>]} and
     * {@code _unique_visits[_<warp>]} (one warp, or all of them). For plugins outside the suite.
     */
    private String placeholder(Player p, String key) {
        if (key.equals("total_warps")) return String.valueOf(warps.size());
        if (key.equals("owned")) return p == null ? "0" : String.valueOf(owned(p.getUniqueId()));
        if (key.equals("limit")) {
            if (p == null) return "0";
            int limit = limit(p);
            return limit == Integer.MAX_VALUE ? messages().raw("unlimited") : String.valueOf(limit);
        }
        boolean unique = key.startsWith("unique_visits");
        if (unique || key.startsWith("visits")) {
            String prefix = unique ? "unique_visits" : "visits";
            String name = key.length() > prefix.length() + 1 ? key.substring(prefix.length() + 1) : "";
            if (name.isEmpty()) {
                int total = 0;
                for (Warp w : warps.values()) total += unique ? w.visitors.size() : w.visits;
                return String.valueOf(total);
            }
            Warp w = warp(name);
            return w == null ? "0" : String.valueOf(unique ? w.visitors.size() : w.visits);
        }
        return null;
    }

    // ---------------------------------------------------------------- API

    private record Api(FluxPW plugin) implements PlayerWarps.Service {

        public List<PlayerWarps.Info> all() {
            List<PlayerWarps.Info> out = new ArrayList<>();
            for (Warp w : plugin.warps.values()) out.add(info(w));
            return out;
        }

        public PlayerWarps.Info byId(String id) {
            try {
                Warp w = plugin.warps.get(UUID.fromString(id));
                return w == null ? null : info(w);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        public PlayerWarps.Info byName(String name) {
            for (Warp w : plugin.warps.values()) if (w.name.equalsIgnoreCase(name)) return info(w);
            return null;
        }

        public void visit(Player player, String id) {
            Warp w = plugin.warp(id);
            if (w == null) plugin.send(player, "unknown-warp");
            else plugin.visit(player, w, null);
        }

        public void create(Player player, String name) {
            plugin.create(player, name);
        }

        public long version() {
            return plugin.version;
        }

        private PlayerWarps.Info info(Warp w) {
            String icon = w.icon == null || w.icon.isBlank() ? plugin.defaultIcon : w.icon;
            String spec = icon.contains(":") && !icon.startsWith("minecraft:") ? "itemsadder:" + icon
                    : "minecraft:" + icon.toLowerCase(Locale.ROOT).replace("minecraft:", "");
            return new PlayerWarps.Info(w.id.toString(), w.name, w.shownName(), w.owner, w.ownerName, w.location(), w.visits,
                    w.rating(), w.admission, spec, plugin.category(w).name(), w.open(),
                    w.status == Warp.Status.PASSWORD_PROTECTED, w.description, w.created);
        }

        public boolean importWarp(PlayerWarps.Import in) {
            if (in.id() == null || in.owner() == null || in.name() == null || plugin.warps.containsKey(in.id())) return false;
            for (Warp w : plugin.warps.values()) if (w.name.equalsIgnoreCase(in.name())) return false;
            Warp w = new Warp(in.id());
            w.name = in.name();
            w.displayName = in.displayName() == null || in.displayName().equals(in.name()) ? null : in.displayName();
            w.owner = in.owner();
            w.ownerName = in.ownerName();
            w.world = in.world();
            w.x = in.x();
            w.y = in.y();
            w.z = in.z();
            w.yaw = in.yaw();
            w.pitch = in.pitch();
            w.icon = in.icon();
            w.description = in.description();
            w.category = in.category() == null || !plugin.categories.containsKey(in.category()) ? plugin.allCategory().key() : in.category();
            try {
                w.status = Warp.Status.valueOf(in.status() == null ? "OPENED" : in.status().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                w.status = Warp.Status.OPENED;
            }
            w.password = in.password();
            w.admission = Math.max(0, in.admission());
            w.visits = Math.max(0, in.visits());
            w.ratingSum = Math.max(0, in.ratingSum());
            w.created = in.created();
            w.featuredUntil = in.featuredUntil();
            if (in.reviewers() != null) w.reviewers.addAll(in.reviewers());
            if (in.visitors() != null) w.visitors.addAll(in.visitors());
            if (in.blocked() != null) w.blocked.addAll(in.blocked());
            plugin.warps.put(w.id, w);
            plugin.store.save(w);
            plugin.store.links(w.id, List.copyOf(w.reviewers), WarpStore.REVIEW);
            plugin.store.links(w.id, List.copyOf(w.visitors), WarpStore.VISIT);
            plugin.store.links(w.id, List.copyOf(w.blocked), WarpStore.BLOCK);
            plugin.changed();
            return true;
        }

        public boolean importFavorite(UUID player, UUID warp) {
            if (!plugin.warps.containsKey(warp)) return false;
            if (!plugin.favorites.computeIfAbsent(player, k -> new LinkedHashSet<>()).add(warp)) return false;
            plugin.store.link(warp, player, WarpStore.FAVORITE, true);
            return true;
        }
    }

}
