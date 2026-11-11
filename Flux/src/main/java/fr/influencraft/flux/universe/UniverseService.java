package fr.influencraft.flux.universe;

import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.staff.Staff;
import fr.influencraft.flux.api.text.Messages;
import fr.influencraft.flux.api.universe.UniverseEnterEvent;
import fr.influencraft.flux.api.universe.Universes;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Flux's universes ({@link Universes}): which world belongs to which universe ({@code universes.yml} and claims of
 * plugins), the profile swap ({@link Transition}) on join and world change, and the guards that keep every item in its
 * own universe (drops, containers, pickups, portals, crafting, commands, periodic check, quarantine).
 */
public final class UniverseService implements Universes.Backend, Listener {

    public static final String BYPASS = "flux.universe.bypass";
    public static final String ADMIN = "flux.universe.admin";

    /** One universe of universes.yml. */
    private record Settings(String id, Set<String> worlds, List<String> prefixes, GameMode gameMode, Set<String> commands,
                            boolean checkItems) {
    }

    private record Claim(Plugin plugin, String universe, Predicate<World> worlds) {
    }

    private final JavaPlugin flux;
    private final Supplier<Messages> messages;
    private final ProfileStore store;
    private final Map<String, Settings> universes = new HashMap<>();
    private final List<Claim> claims = new CopyOnWriteArrayList<>();
    /** Players whose profile is being swapped. */
    private final Set<UUID> locked = ConcurrentHashMap.newKeySet();
    /** Where a player stood before a teleport into another universe (sent back there when a swap fails). */
    private final Map<UUID, Location> before = new HashMap<>();
    /** Players whose inventory is checked at the next tick. */
    private final Set<UUID> toScan = new HashSet<>();

    private UniverseService(JavaPlugin flux, Supplier<Messages> messages, ProfileStore store) {
        this.flux = flux;
        this.messages = messages;
        this.store = store;
    }

    public static UniverseService start(JavaPlugin flux, Supplier<Messages> messages) {
        ProfileStore store;
        try {
            store = new ProfileStore(flux.getLogger());
        } catch (SQLException e) {
            flux.getLogger().severe("Universe tables unavailable, universes disabled (every world is vanilla): " + e);
            return null;
        }
        UniverseService service = new UniverseService(flux, messages, store);
        service.load();
        Universes.bind(service);
        Bukkit.getPluginManager().registerEvents(service, flux);
        long sweep = Math.max(1, flux.getConfig().getLong("universes.sweep-seconds", 5)) * 20L;
        Bukkit.getScheduler().runTaskTimer(flux, service::sweep, sweep, sweep);
        Commands.create(flux, "universes").description("Universe of a player: profile, quarantine.")
                .permission(ADMIN).suggest(false).executor(service::command).register();
        // players already online (reload): their profile is checked as at a join
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                store.preload(p.getUniqueId());
                service.joined(p);
            } catch (SQLException e) {
                flux.getLogger().severe("Profiles of " + p.getName() + " unreadable: " + e.getMessage());
            }
        }
        return service;
    }

    public void stop() {
        Universes.bind(null);
    }

    /** Reads universes.yml (shipped when missing). */
    public void load() {
        File file = new File(flux.getDataFolder(), "universes.yml");
        if (!file.exists()) flux.saveResource("universes.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        universes.clear();
        ConfigurationSection all = y.getConfigurationSection("universes");
        if (all == null) return;
        for (String id : all.getKeys(false)) {
            String key = id.toLowerCase(Locale.ROOT);
            if (Universes.VANILLA.equals(key)) {
                flux.getLogger().warning("universes.yml: \"vanilla\" is every world not listed elsewhere, its section is ignored");
                continue;
            }
            ConfigurationSection s = all.getConfigurationSection(id);
            if (s == null) continue;
            GameMode mode;
            try {
                mode = GameMode.valueOf(s.getString("game-mode", "ADVENTURE").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                flux.getLogger().warning("universes.yml: " + id + ".game-mode unknown, ADVENTURE used");
                mode = GameMode.ADVENTURE;
            }
            Set<String> commands = null;
            if (s.isList("commands")) {
                commands = new HashSet<>();
                for (String c : s.getStringList("commands")) commands.add(c.toLowerCase(Locale.ROOT));
            }
            universes.put(key, new Settings(key, new HashSet<>(s.getStringList("worlds")), s.getStringList("world-prefixes"), mode, commands,
                    s.getBoolean("check-items", false)));
        }
    }

    // ------------------------------------------------------------------ Backend

    @Override
    public String of(Player player) {
        return PlayerLive.markerOf(player);
    }

    @Override
    public String ofWorld(World world) {
        for (Claim c : claims) if (c.worlds().test(world)) return c.universe();
        String name = world.getName();
        for (Settings s : universes.values()) {
            if (s.worlds().contains(name)) return s.id();
            for (String p : s.prefixes()) if (name.startsWith(p)) return s.id();
        }
        return Universes.VANILLA;
    }

    @Override
    public boolean transitioning(Player player) {
        return locked.contains(player.getUniqueId());
    }

    @Override
    public void claim(Plugin plugin, String universe, Predicate<World> worlds) {
        claims.add(new Claim(plugin, universe.toLowerCase(Locale.ROOT), worlds));
    }

    public void unclaimAll(Plugin plugin) {
        claims.removeIf(c -> c.plugin().equals(plugin));
    }

    @Override
    public Location returnLocation(Player player, String universe) {
        if (universe.equals(of(player))) return null;
        return PlayerLive.location(store.cached(player.getUniqueId(), universe));
    }

    private boolean isolated(String universe) {
        return !Universes.VANILLA.equals(universe);
    }

    private GameMode freshMode(String universe) {
        Settings s = universes.get(universe);
        return s == null ? GameMode.SURVIVAL : s.gameMode();
    }

    private PlayerLive live(Player p) {
        return live(p, null, null);
    }

    private PlayerLive live(Player p, Location origin, String previous) {
        return new PlayerLive(p, this::freshMode, (universe, items) -> {
            for (ItemStack i : items) quarantine(p, universe, i, "overflow", false);
        }, origin, previous);
    }

    /**
     * Return point of the outgoing universe: where the player stood before the teleport that brought him here, or his
     * current place when he still stands in that universe; null otherwise (the stored one is kept).
     */
    private Location origin(Player p, String outgoing) {
        Location b = before.get(p.getUniqueId());
        if (b != null && b.getWorld() != null && ofWorld(b.getWorld()).equals(outgoing)) return b;
        return ofWorld(p.getWorld()).equals(outgoing) ? p.getLocation() : null;
    }

    // ------------------------------------------------------------------ join, swap

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        try {
            store.preload(e.getUniqueId());
        } catch (SQLException ex) {
            flux.getLogger().severe("Profiles of " + e.getName() + " unreadable, join refused: " + ex.getMessage());
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get().get("universe.unavailable", null));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        joined(e.getPlayer());
    }

    private void joined(Player p) {
        try {
            if (Transition.reconcile(live(p), store.repository(p.getUniqueId()))) {
                flux.getLogger().warning(p.getName() + ": player file older than his saved " + of(p) + " profile, saved profile restored");
            }
        } catch (IllegalStateException ex) {
            flux.getLogger().severe("Profile check of " + p.getName() + " failed, player kicked: " + ex.getMessage());
            p.kick(messages.get().get("universe.unavailable", p));
            return;
        }
        String target = ofWorld(p.getWorld());
        if (target.equals(of(p))) entered(p, target, target, true);
        else swap(p, target, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo().getWorld() == e.getFrom().getWorld()) return;
        if (!ofWorld(e.getTo().getWorld()).equals(ofWorld(e.getFrom().getWorld()))) before.put(e.getPlayer().getUniqueId(), e.getFrom());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        String target = ofWorld(p.getWorld());
        if (!target.equals(of(p))) swap(p, target, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        locked.remove(id);
        before.remove(id);
        toScan.remove(id);
        store.forget(id);
    }

    /** Swaps the player into the universe of the world he stands in. A swap in progress is never started twice. */
    private void swap(Player p, String target, boolean join) {
        UUID id = p.getUniqueId();
        if (!locked.add(id)) return;      // the end of the running swap checks the world again
        String from = of(p);
        Transition.swap(live(p, origin(p, from), store.cached(id, from)), store.repository(id), target,
                r -> Bukkit.getScheduler().runTaskAsynchronously(flux, r),
                r -> {
                    if (flux.isEnabled()) whenGrounded(p, r);
                },
                p::isOnline,
                outcome -> {
                    locked.remove(id);
                    switch (outcome) {
                        case DONE -> {
                            flux.getLogger().info(p.getName() + ": " + from + " -> " + target);
                            entered(p, target, from, join);
                            // he may have moved again while locked
                            String now = ofWorld(p.getWorld());
                            if (!now.equals(of(p))) swap(p, now, false);
                        }
                        case SAVE_FAILED, LOAD_FAILED -> failed(p, from, outcome);
                        case ABANDONED -> {
                        }
                    }
                });
    }

    /**
     * Runs the end of a swap once the player stands on the ground (at most 2 seconds later): changing his game mode
     * and flight while a portal still throws him through the air left his client stuck until he jumped. He stays
     * locked meanwhile (nothing in, nothing out).
     */
    private void whenGrounded(Player p, Runnable r) {
        int[] waited = {0};
        Bukkit.getScheduler().runTaskTimer(flux, task -> {
            if (!p.isOnline() || p.isOnGround() || p.isFlying() || p.isInWater() || ++waited[0] >= 40) {
                task.cancel();
                r.run();
            }
        }, 1L, 1L);
    }

    /** The swap could not be done: the player keeps his profile and goes back to a world of its universe. */
    private void failed(Player p, String universe, Transition.Outcome outcome) {
        flux.getLogger().severe("Swap of " + p.getName() + " failed (" + outcome + "), kept in " + universe);
        messages.get().send(p, "universe.failed");
        Location back = before.remove(p.getUniqueId());
        if (back == null || !ofWorld(back.getWorld()).equals(universe)) back = returnLocation(p, universe);
        if (back == null || !ofWorld(back.getWorld()).equals(universe)) {
            back = Universes.VANILLA.equals(universe) ? Bukkit.getWorlds().get(0).getSpawnLocation() : null;
        }
        if (back != null) p.teleport(back, PlayerTeleportEvent.TeleportCause.PLUGIN);
        else p.kick(messages.get().get("universe.unavailable", p));
    }

    private void entered(Player p, String universe, String previous, boolean join) {
        before.remove(p.getUniqueId());
        if (!p.isOnline()) return;
        scan(p, true, join ? "join" : "enter");
        deliver(p, universe);
        Bukkit.getPluginManager().callEvent(new UniverseEnterEvent(p, universe, previous, join));
    }

    // ------------------------------------------------------------------ quarantine

    /** Whether an item may be held in this universe (its mark, and every item inside a shulker box or a bundle). */
    static boolean belongs(ItemStack item, String universe) {
        if (item == null || item.isEmpty()) return true;
        if (!universe.equals(Universes.universeOf(item))) return false;
        if (!item.hasItemMeta()) return true;
        if (item.getItemMeta() instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof ShulkerBox box) {
            for (ItemStack inner : box.getInventory().getContents()) if (!belongs(inner, universe)) return false;
        }
        if (item.getItemMeta() instanceof BundleMeta bundle) {
            for (ItemStack inner : bundle.getItems()) if (!belongs(inner, universe)) return false;
        }
        return true;
    }

    private boolean exempt(Player p) {
        return p.hasPermission(BYPASS) && p.getGameMode() == GameMode.CREATIVE;
    }

    /**
     * Whether items held in this universe are checked. Vanilla always (an item of another universe must never stay
     * there); another universe only with {@code check-items: true} (its own profile keeps whatever lands in it, and it
     * never goes back to vanilla).
     */
    private boolean checked(String universe) {
        if (!isolated(universe)) return true;
        Settings s = universes.get(universe);
        return s != null && s.checkItems();
    }

    /** Takes every item of the wrong universe from the player (inventory, cursor, and ender chest when asked). */
    private void scan(Player p, boolean ender, String reason) {
        if (locked.contains(p.getUniqueId()) || exempt(p)) return;
        String universe = of(p);
        if (!checked(universe)) return;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (belongs(item, universe)) continue;
            if (quarantine(p, Universes.universeOf(item), item, reason, true)) p.getInventory().setItem(i, null);
        }
        ItemStack cursor = p.getItemOnCursor();
        if (!belongs(cursor, universe) && quarantine(p, Universes.universeOf(cursor), cursor, reason, true)) p.setItemOnCursor(null);
        if (!ender) return;
        ItemStack[] chest = p.getEnderChest().getContents();
        for (int i = 0; i < chest.length; i++) {
            if (belongs(chest[i], universe)) continue;
            if (quarantine(p, Universes.universeOf(chest[i]), chest[i], reason + "-ender", true)) p.getEnderChest().setItem(i, null);
        }
    }

    /**
     * Keeps an item for the player in the database, to be given back in {@code universe}. True when it is safe there
     * (only then may the caller remove it). {@code anomaly}: an item found in the wrong universe (logged, staff told).
     */
    private boolean quarantine(Player p, String universe, ItemStack item, String reason, boolean anomaly) {
        if (item == null || item.isEmpty()) return true;
        boolean ok = store.quarantine(p.getUniqueId(), universe, PlayerLive.encode(new ItemStack[]{item}), reason);
        if (ok && anomaly) {
            String what = item.getAmount() + "x " + item.getType().getKey().getKey();
            flux.getLogger().warning("Quarantine: " + what + " of universe " + universe + " found on " + p.getName()
                    + " in universe " + of(p) + " (" + reason + ")");
            Staff.alert("universe", "Univers", p.getName() + " : " + what + " (" + universe + ") retiré de l'univers " + of(p) + " (" + reason + ")");
            messages.get().send(p, "universe.quarantined");
        }
        return ok;
    }

    /** Gives back the quarantined items of this universe that fit in the inventory. */
    private void deliver(Player p, String universe) {
        List<ProfileStore.Quarantined> rows = store.quarantined(p.getUniqueId(), universe);
        int given = 0;
        for (ProfileStore.Quarantined row : rows) {
            if (p.getInventory().firstEmpty() < 0) break;
            ItemStack[] items = PlayerLive.decode(row.item());
            if (items.length == 0 || items[0] == null) {
                store.release(row.id());
                continue;
            }
            // released before given: a failed release keeps the row and gives nothing (never twice)
            if (!store.release(row.id())) break;
            for (ItemStack left : p.getInventory().addItem(items[0]).values()) quarantine(p, universe, left, "overflow", false);
            given++;
        }
        if (given > 0) messages.get().send(p, "universe.returned", "%count%", String.valueOf(given));
        if (rows.size() > given) messages.get().send(p, "universe.waiting", "%count%", String.valueOf(rows.size() - given));
    }

    /** Every few seconds: inventories of players outside of a swap. */
    private void sweep() {
        for (Player p : Bukkit.getOnlinePlayers()) scan(p, false, "sweep");
    }

    private void scanSoon(Player p) {
        if (!toScan.add(p.getUniqueId())) return;
        Bukkit.getScheduler().runTask(flux, () -> {
            toScan.remove(p.getUniqueId());
            if (p.isOnline()) scan(p, false, "click");
        });
    }

    // ------------------------------------------------------------------ guards: during a swap

    private boolean lockedOut(Player p, Cancellable e) {
        if (!locked.contains(p.getUniqueId())) return false;
        e.setCancelled(true);
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClickLocked(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && !lockedOut(p, e)) scanSoon(p);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDragLocked(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && !lockedOut(p, e)) scanSoon(p);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractLocked(PlayerInteractEvent e) {
        lockedOut(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHandsLocked(PlayerSwapHandItemsEvent e) {
        lockedOut(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onOpenLocked(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p) lockedOut(p, e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandLocked(PlayerCommandPreprocessEvent e) {
        if (lockedOut(e.getPlayer(), e)) messages.get().send(e.getPlayer(), "universe.busy");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageLocked(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && locked.contains(p.getUniqueId())) e.setCancelled(true);
        else if (e instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player p && locked.contains(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleportLocked(PlayerTeleportEvent e) {
        if (e.getTo().getWorld() != e.getFrom().getWorld()) lockedOut(e.getPlayer(), e);
    }

    // ------------------------------------------------------------------ guards: items stay in their universe

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (lockedOut(p, e)) return;
        // nothing is ever thrown on the ground outside vanilla (A, the drop key, is a skill there)
        if (isolated(of(p)) && !exempt(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        if (!isolated(of(e.getPlayer()))) return;
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        ItemStack stack = e.getItem().getItemStack();
        String universe = e.getEntity() instanceof Player p ? of(p) : ofWorld(e.getEntity().getWorld());
        if (e.getEntity() instanceof Player p && lockedOut(p, e)) return;
        if (!checked(universe) || belongs(stack, universe)) return;
        e.setCancelled(true);
        if (e.getEntity() instanceof Player p && quarantine(p, Universes.universeOf(stack), stack, "ground", true)) e.getItem().remove();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        String universe = ofWorld(e.getEntity().getWorld());
        if (!checked(universe) || belongs(e.getEntity().getItemStack(), universe)) return;
        e.setCancelled(true);
        flux.getLogger().warning("Item of universe " + Universes.universeOf(e.getEntity().getItemStack()) + " kept from appearing in "
                + e.getEntity().getWorld().getName());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent e) {
        Item item = e.getItem();
        String universe = ofWorld(item.getWorld());
        if (checked(universe) && !belongs(item.getItemStack(), universe)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent e) {
        Location at = e.getSource().getLocation();
        if (at == null) return;
        String universe = ofWorld(at.getWorld());
        if (checked(universe) && !belongs(e.getItem(), universe)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (e.getTo() == null || e.getTo().getWorld() == null) return;
        if (!ofWorld(e.getTo().getWorld()).equals(ofWorld(e.getFrom().getWorld()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (isolated(of(e.getPlayer())) && !e.getPlayer().hasPermission(BYPASS)) e.setCancelled(true);
    }

    /** Outside vanilla: no block container, ender chest, merchant or workstation, only plugin windows. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!isolated(of(p))) {
            // a vanilla container holding an item of another universe (leak from before the universes existed)
            scanContainer(p, e.getInventory());
            return;
        }
        if (p.hasPermission(BYPASS)) return;
        InventoryType type = e.getInventory().getType();
        if (type == InventoryType.PLAYER || type == InventoryType.CRAFTING || type == InventoryType.CREATIVE) return;
        InventoryHolder holder = e.getInventory().getHolder(false);
        if (type == InventoryType.ENDER_CHEST || type == InventoryType.MERCHANT || worldContainer(holder)) {
            e.setCancelled(true);
            messages.get().send(p, "universe.no-container");
        }
    }

    /** A container that exists in the world (block, double chest, minecart, animal), not a window made by a plugin. */
    private static boolean worldContainer(InventoryHolder holder) {
        return holder instanceof BlockInventoryHolder || holder instanceof DoubleChest
                || holder instanceof Entity && !(holder instanceof Player);
    }

    private void scanContainer(Player p, org.bukkit.inventory.Inventory inv) {
        if (inv.getType() == InventoryType.PLAYER || inv.getType() == InventoryType.CRAFTING) return;
        InventoryHolder holder = inv.getHolder(false);
        if (!worldContainer(holder)) return;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (belongs(contents[i], Universes.VANILLA)) continue;
            if (quarantine(p, Universes.universeOf(contents[i]), contents[i], "container", true)) inv.setItem(i, null);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (lockedOut(p, e)) return;
        if (!isolated(of(p)) || p.hasPermission(BYPASS)) return;
        Entity t = e.getRightClicked();
        if (t instanceof Hanging || t instanceof ArmorStand || t instanceof org.bukkit.entity.Allay
                || t instanceof org.bukkit.inventory.InventoryHolder) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (isolated(of(e.getPlayer())) && !e.getPlayer().hasPermission(BYPASS)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (e.getView().getPlayer() instanceof Player p && isolated(of(p)) && !p.hasPermission(BYPASS)) e.getInventory().setResult(null);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (e.getWhoClicked() instanceof Player p && isolated(of(p)) && !p.hasPermission(BYPASS)) e.setCancelled(true);
    }

    /** Outside vanilla, only the commands listed for the universe (universes.yml) may be typed. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        Settings s = universes.get(of(p));
        if (s == null || s.commands() == null || p.hasPermission(BYPASS)) return;
        String label = e.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (s.commands().contains(label)) return;
        e.setCancelled(true);
        messages.get().send(p, "universe.no-command");
    }

    // ------------------------------------------------------------------ /universes <player>

    private boolean command(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("/" + label + " <player>  |  /" + label + " reload");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            load();
            sender.sendMessage("universes.yml reloaded: " + String.join(", ", universes.keySet()));
            return true;
        }
        Player p = Bukkit.getPlayerExact(args[0]);
        if (p == null) {
            sender.sendMessage("Player not online.");
            return true;
        }
        PlayerLive live = live(p);
        sender.sendMessage(p.getName() + ": profile " + live.universe() + " v" + live.version() + ", world "
                + p.getWorld().getName() + " (" + ofWorld(p.getWorld()) + ")" + (transitioning(p) ? ", SWAPPING" : ""));
        List<String> waiting = new ArrayList<>();
        for (ProfileStore.Quarantined q : store.quarantined(p.getUniqueId(), null)) waiting.add(q.universe());
        sender.sendMessage("Quarantine: " + waiting.size() + " item(s) " + waiting);
        return true;
    }
}
