package fr.influencraft.fluxspawner;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.claims.Claims;
import fr.influencraft.flux.api.mobs.MobStacks;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.staff.Staff;
import fr.influencraft.flux.api.text.ChatHelp;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.flux.api.value.Values;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * FluxSpawner: spawners with levels. A spawner is an item ({@code fluxspawner:<type>}) placed in a town; it makes its
 * mob into the stack standing on it, at a rate that grows with its level, while it has fuel, room for more mobs and a
 * player close by. Players level it up by reaching goals (kills of its own mobs, vanilla experience, money, items),
 * fill its tank, turn it into another mob, switch it on and off within the limit of their rank, and pick it up with
 * everything it earned. Rates, tanks and goals derive from FluxValue: see {@link Settings}.
 *
 * <pre>
 *   /spawners                                   the player's spawners
 *   /fluxspawner give|info|setlevel|fuel|list|remove|reload|help   (staff)
 * </pre>
 */
public final class FluxSpawner extends FluxPlugin {

    private final Settings settings = new Settings(this);
    private final SpawnerRegistry spawners = new SpawnerRegistry();
    private SpawnerStore store;
    private SpawnerItems items;
    private Production production;
    private Upgrades upgrades;
    private Menus menus;

    @Override
    protected void enable() {
        settings.load();
        store = new SpawnerStore(this);
        store.open();
        for (Spawner s : store.loadAll()) spawners.add(s);
        items = new SpawnerItems(this);
        items.register();
        production = new Production(this);
        upgrades = new Upgrades(this);
        menus = new Menus(this);
        menus.register();
        // the mobs of each spawner form their own stack, apart from the wild and from other spawners
        MobStacks.keyPart(this, e -> {
            UUID id = production.spawnerOf(e);
            return id == null ? null : "fluxspawner:" + id;
        });
        Values.onChange(settings::valuesChanged);
        getServer().getPluginManager().registerEvents(new SpawnerListener(this), this);
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
                menus.forget(e.getPlayer());
            }
        }, this);
        placeholders("fluxspawner", this::placeholder);
        commands();
        production.start();
        // blocks dressed again on the first tick (a type changed in types.yml, a spawner placed before a crash)
        Bukkit.getScheduler().runTask(this, () -> {
            for (Spawner s : spawners.placed()) {
                SpawnerType t = settings.type(s.type);
                if (t != null && s.chunkLoaded()) production.dress(s, t);
            }
        });
        getLogger().info(spawners.all().size() + " spawner(s), " + settings.types.size() + " type(s)"
                + (MobStacks.available() ? "" : ", no mob stacking (FluxStack missing): " + settings.noStackCapacity + " mobs at most per spawner"));
    }

    @Override
    protected void disable() {
        if (production != null) production.shutdown();
        if (store != null) {
            for (Spawner s : spawners.all()) if (s.dirty) store.save(s);
            store.close();
        }
    }

    @Override
    public void reload() {
        super.reload();
        settings.load();
        items.reloaded();
        production.start();
        menus.invalidate();
    }

    // ---------------------------------------------------------------- access for the other classes

    Settings settings() {
        return settings;
    }

    SpawnerRegistry spawners() {
        return spawners;
    }

    SpawnerStore store() {
        return store;
    }

    SpawnerItems items() {
        return items;
    }

    Production production() {
        return production;
    }

    Upgrades upgrades() {
        return upgrades;
    }

    Menus menus() {
        return menus;
    }

    String raw(String key, String... vars) {
        return messages().raw(key, vars);
    }

    /** Lines of a lang key; an empty key gives no line at all. */
    List<String> lines(String key, String... vars) {
        List<String> out = new ArrayList<>();
        if (!messages().has(key)) return out;
        for (String l : messages().rawList(key, vars)) out.add(l);
        if (out.size() == 1 && out.get(0).isEmpty()) out.clear();
        return out;
    }

    void send(CommandSender to, String key, String... vars) {
        messages().send(to, key, vars);
    }

    /** Variables every text about a spawner may use. */
    String[] vars(Spawner s, SpawnerType type) {
        if (type == null) return new String[0];
        return new String[]{
                "%type%", type.displayName(),
                "%level%", String.valueOf(s.level),
                "%max%", String.valueOf(settings.maxLevel(type)),
                "%rate%", Format.number(Math.round(settings.mobsPerHour(type, s.level))),
                "%waiting%", Format.number(s.waiting),
                "%capacity%", Format.number(settings.capacity(type, s.level)),
                "%fuel%", Production.time(s.fuel),
                "%tank%", Production.time(settings.tankSeconds(type, s.level)),
                "%status%", raw("status." + s.status.name().toLowerCase(Locale.ROOT)),
                "%owner%", s.ownerName == null ? "?" : s.ownerName,
                "%world%", s.world == null ? "-" : s.world,
                "%coords%", s.coordinates(),
                "%kills%", Format.number(s.kills)};
    }

    String nameOf(UUID player) {
        String n = Bukkit.getOfflinePlayer(player).getName();
        return n == null ? "?" : n;
    }

    boolean mayManage(Player p, Spawner s) {
        return s.owner.equals(p.getUniqueId()) || fr.influencraft.flux.api.staff.Staff.isAdmin(p);
    }

    void refresh(Spawner s) {
        SpawnerType t = settings.type(s.type);
        if (t != null && s.placed()) production.hologram(s, t);
        menus.refresh(s);
    }

    // ---------------------------------------------------------------- placing and picking up

    /** A spawner item is being placed there: every rule checked; false = the placement is refused (message sent). */
    boolean place(Player p, Block block, ItemStack item) {
        SpawnerType type = settings.type(items.typeOf(item));
        if (type == null) {
            send(p, "unknown-type");
            return false;
        }
        if (!p.hasPermission("fluxspawner.place") || (type.permission() != null && !p.hasPermission(type.permission()))) {
            send(p, "type-locked");
            Sounds.cue(p, "error");
            return false;
        }
        boolean staff = fr.influencraft.flux.api.staff.Staff.isAdmin(p);
        if (!settings.worldAllowed(block.getWorld().getName()) && !staff) {
            send(p, "world-disabled");
            Sounds.cue(p, "error");
            return false;
        }
        Location at = block.getLocation();
        if (settings.requireTown && !staff && Claims.available()
                && !(Claims.member(at, p.getUniqueId()) || Claims.owner(at, p.getUniqueId()))) {
            send(p, "not-in-town");
            Sounds.cue(p, "error");
            return false;
        }
        Spawner close = settings.minDistance > 0 ? spawners.near(block, settings.minDistance, null) : null;
        if (close != null) {
            send(p, "too-close", "%distance%", String.valueOf(settings.minDistance));
            Sounds.cue(p, "error");
            return false;
        }
        int placedLimit = settings.placedLimit(p);
        if (spawners.placedBy(p.getUniqueId()).size() >= placedLimit && !staff) {
            send(p, "placed-limit", "%limit%", String.valueOf(placedLimit));
            Sounds.cue(p, "error");
            return false;
        }
        UUID id = items.idOf(item);
        Spawner s = id == null ? null : spawners.get(id);
        if (s != null && s.placed()) {
            // the same spawner twice: a copied item
            send(p, "duplicate");
            Staff.alert("fluxspawner", raw("staff.duplicate-title"), raw("staff.duplicate", "%player%", p.getName(), "%id%", s.id.toString()));
            getLogger().warning(p.getName() + " placed a copy of spawner " + s.id + " (already at " + s.world + " " + s.coordinates() + ")");
            return false;
        }
        if (s == null) {
            s = new Spawner(id != null ? id : UUID.randomUUID(), System.currentTimeMillis());
            s.type = type.id();
            s.level = Math.min(items.levelOf(item), settings.maxLevel(type));
            spawners.add(s);
        }
        s.owner = p.getUniqueId();
        s.ownerName = p.getName();
        s.type = type.id();
        s.world = block.getWorld().getName();
        s.x = block.getX();
        s.y = block.getY();
        s.z = block.getZ();
        s.status = Spawner.Status.UNLOADED;
        spawners.moved(s, null);
        s.active = spawners.activeCount(p.getUniqueId()) < settings.activeLimit(p);
        store.save(s);
        Spawner placed = s;
        Bukkit.getScheduler().runTask(this, () -> {
            production.dress(placed, type);
            refresh(placed);
        });
        send(p, s.active ? "placed" : "placed-inactive", vars(s, type));
        Sounds.cue(p, "success");
        return true;
    }

    /** The spawner leaves its block: its waiting mobs are kept in it, the item goes to its owner. */
    void pickup(Spawner s, Player by) {
        SpawnerType type = settings.type(s.type);
        int waiting = production.clear(s);
        production.removeHologram(s);
        String former = s.blockKey();
        s.stock = type == null ? 0 : Math.min(waiting + s.stock, settings.capacity(type, s.level));
        s.world = null;
        s.active = false;
        s.status = Spawner.Status.ITEM;
        s.pending = 0;
        s.waiting = 0;
        spawners.moved(s, former);
        store.save(s);
        if (type == null) return;
        OfflinePlayer owner = Bukkit.getOfflinePlayer(s.owner);
        Rewards.deliver(owner, "fluxspawner", raw("rewards-label", vars(s, type)), List.of(items.spawner(type, s.level, s)));
        send(by, "picked-up", vars(s, type));
        Sounds.cue(by, "success");
        menus.invalidate();
    }

    /** The block is gone without a player breaking it (WorldEdit...): the spawner is lost, logged for the staff. */
    void lost(Spawner s) {
        getLogger().warning("Spawner " + s.id + " of " + s.ownerName + " (" + s.type + " level " + s.level + ") lost its block at "
                + s.world + " " + s.coordinates() + ": kept in the database, /fluxspawner restore " + s.id + " gives it back");
        production.removeHologram(s);
        String former = s.blockKey();
        s.world = null;
        s.active = false;
        s.status = Spawner.Status.ITEM;
        spawners.moved(s, former);
        store.save(s);
    }

    /** New spawner items (staff, capture of a natural spawner) through Flux's delivery. */
    void giveNew(OfflinePlayer to, SpawnerType type, int level, int amount) {
        ItemStack item = items.spawner(type, level, null);
        item.setAmount(Math.max(1, Math.min(64, amount)));
        Rewards.deliver(to, "fluxspawner", raw("rewards-label-new", "%type%", type.displayName()), List.of(item));
    }

    /** On or off; turning on needs room in the owner's limit (staff excepted). */
    void toggle(Player p, Spawner s) {
        if (s.active) {
            s.active = false;
            store.save(s);
            send(p, "turned-off");
            Sounds.cue(p, "toggle-off");
            return;
        }
        Player owner = Bukkit.getPlayer(s.owner);
        int limit = owner == null ? Integer.MAX_VALUE : settings.activeLimit(owner);
        if (spawners.activeCount(s.owner) >= limit && !fr.influencraft.flux.api.staff.Staff.isAdmin(p)) {
            send(p, "active-limit", "%limit%", String.valueOf(limit));
            Sounds.cue(p, "error");
            return;
        }
        s.active = true;
        store.save(s);
        send(p, "turned-on");
        Sounds.cue(p, "toggle-on");
    }

    /** A player killed one of its mobs. */
    void killed(Spawner s, SpawnerType type) {
        s.kills++;
        if (type != null) upgrades.killed(s, type);
        s.dirty = true;
    }

    /** Tells the owner, at most every 10 minutes per spawner, that it stopped for lack of fuel or room. */
    void statusChanged(Spawner s, Spawner.Status before) {
        if (s.status != Spawner.Status.NO_FUEL && s.status != Spawner.Status.FULL) return;
        Player owner = Bukkit.getPlayer(s.owner);
        long now = System.currentTimeMillis();
        if (owner == null || now - s.lastAlert < 600_000L) return;
        s.lastAlert = now;
        send(owner, s.status == Spawner.Status.NO_FUEL ? "alert-no-fuel" : "alert-full", vars(s, settings.type(s.type)));
        Sounds.cue(owner, "alert");
    }

    // ---------------------------------------------------------------- commands

    private void commands() {
        command("spawners")
                .description("Your spawners.")
                .permission("fluxspawner.use")
                .executor((sender, label, args) -> {
                    Player p = Players.of(sender);
                    if (p != null) menus.openList(p, null);
                    return true;
                })
                .register();
        command("fluxspawner")
                .description("Spawner administration.")
                .permission(fr.influencraft.flux.api.staff.Staff.ADMIN)
                .executor(this::admin)
                .completer(this::complete)
                .register();
    }

    private static final List<String> ADMIN = List.of("help", "give", "info", "setlevel", "fuel", "list", "restore", "remove", "reload");

    private List<ChatHelp.Entry> help() {
        List<ChatHelp.Entry> out = new ArrayList<>();
        for (String sub : ADMIN) {
            String args = raw("help.args." + sub);
            out.add(new ChatHelp.Entry(args.isBlank() ? sub : sub + " " + args, sub, fr.influencraft.flux.api.staff.Staff.ADMIN));
        }
        return out;
    }

    private boolean admin(CommandSender sender, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        switch (sub) {
            case "reload" -> {
                reload();
                send(sender, "reloaded");
            }
            case "give" -> {
                if (args.length < 3) {
                    ChatHelp.show(sender, messages(), label, help(), "help", true, 1, sender::hasPermission);
                    return true;
                }
                OfflinePlayer target = Players.find(args[1]);
                SpawnerType type = settings.type(args[2]);
                if (target == null || type == null) {
                    send(sender, target == null ? "unknown-player" : "unknown-type");
                    return true;
                }
                int level = args.length > 3 ? parse(args[3], 0) : 0;
                int amount = args.length > 4 ? parse(args[4], 1) : 1;
                level = Math.max(0, Math.min(level, settings.maxLevel(type)));
                giveNew(target, type, level, amount);
                send(sender, "given-admin", "%player%", Players.name(target, args[1]), "%type%", type.displayName(),
                        "%level%", String.valueOf(level), "%count%", String.valueOf(Math.max(1, amount)));
            }
            case "info", "setlevel", "fuel", "remove" -> {
                Spawner s = looked(sender);
                if (s == null) return true;
                SpawnerType type = settings.type(s.type);
                switch (sub) {
                    case "info" -> send(sender, "admin-info", vars(s, type));
                    case "setlevel" -> {
                        if (args.length < 2 || type == null) return usage(sender, label);
                        s.level = Math.max(0, Math.min(parse(args[1], s.level), settings.maxLevel(type)));
                        s.progress.clear();
                        store.save(s);
                        refresh(s);
                        send(sender, "admin-done", vars(s, type));
                    }
                    case "fuel" -> {
                        if (args.length < 2 || type == null) return usage(sender, label);
                        long seconds = Format.parseDuration(args[1]);
                        s.fuel = Math.max(0, Math.min(settings.tankSeconds(type, s.level), s.fuel + seconds));
                        store.save(s);
                        refresh(s);
                        send(sender, "admin-done", vars(s, type));
                    }
                    default -> {
                        // staff removal: the spawner and its data go, nothing is given back
                        production.clear(s);
                        production.removeHologram(s);
                        Block b = s.block();
                        spawners.remove(s);
                        store.delete(s.id);
                        if (b != null) b.setType(org.bukkit.Material.AIR);
                        send(sender, "admin-removed");
                    }
                }
            }
            case "list" -> {
                Player p = Players.of(sender);
                if (p == null) return true;
                OfflinePlayer target = args.length > 1 ? Players.find(args[1]) : p;
                if (target == null) {
                    send(sender, "unknown-player");
                    return true;
                }
                menus.openList(p, target.getUniqueId());
            }
            case "restore" -> {
                if (args.length < 2) return usage(sender, label);
                Spawner s;
                try {
                    s = spawners.get(UUID.fromString(args[1]));
                } catch (IllegalArgumentException e) {
                    s = null;
                }
                SpawnerType type = s == null ? null : settings.type(s.type);
                if (s == null || type == null || s.placed()) {
                    send(sender, "admin-restore-none");
                    return true;
                }
                OfflinePlayer owner = Bukkit.getOfflinePlayer(s.owner);
                Rewards.deliver(owner, "fluxspawner", raw("rewards-label", vars(s, type)), List.of(items.spawner(type, s.level, s)));
                send(sender, "admin-restored", "%owner%", nameOf(s.owner));
            }
            default -> ChatHelp.show(sender, messages(), label, help(), "help", true,
                    args.length > 1 ? parse(args[1], 1) : 1, sender::hasPermission);
        }
        return true;
    }

    private boolean usage(CommandSender sender, String label) {
        ChatHelp.show(sender, messages(), label, help(), "help", true, 1, sender::hasPermission);
        return true;
    }

    /** The spawner the staff member looks at (5 blocks). */
    private Spawner looked(CommandSender sender) {
        Player p = Players.of(sender);
        if (p == null) return null;
        Block b = p.getTargetBlockExact(5);
        Spawner s = spawners.at(b);
        if (s == null) send(sender, "look-at-spawner");
        return s;
    }

    private static int parse(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) return filter(ADMIN, args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("give") || sub.equals("list"))) return Players.complete(args[1]);
        if (args.length == 3 && sub.equals("give")) return filter(new ArrayList<>(settings.types.keySet()), args[2]);
        if (args.length == 2 && sub.equals("fuel")) return List.of("30m", "1h", "6h");
        return List.of();
    }

    private static List<String> filter(List<String> options, String start) {
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.startsWith(start.toLowerCase(Locale.ROOT))) out.add(o);
        return out;
    }

    // ---------------------------------------------------------------- placeholders

    /**
     * {@code %fluxspawner_active%}, {@code _active_limit}, {@code _placed}, {@code _placed_limit} (the player's
     * spawners), {@code _total_kills}.
     */
    private String placeholder(Player player, String key) {
        if (player == null) return "";
        UUID id = player.getUniqueId();
        return switch (key) {
            case "active" -> String.valueOf(spawners.activeCount(id));
            case "active_limit" -> limitText(settings.activeLimit(player));
            case "placed" -> String.valueOf(spawners.placedBy(id).size());
            case "placed_limit" -> limitText(settings.placedLimit(player));
            case "total_kills" -> {
                long n = 0;
                for (Spawner s : spawners.all()) if (s.owner.equals(id)) n += s.kills;
                yield Format.number(n);
            }
            default -> null;
        };
    }

    private String limitText(int n) {
        return n == Integer.MAX_VALUE ? raw("unlimited") : String.valueOf(n);
    }
}
