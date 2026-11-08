package fr.influencraft.fluxdungeons.dungeon;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxdungeons.mob.MobTemplate;
import fr.influencraft.fluxdungeons.mob.Mobs;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Edit mode of an imported dungeon: its original map is opened (a working copy, written back on save), and edited
 * with tools in hand. Each spawn point is shown (name above, clickable body), the start too.
 * <ul>
 *   <li>Place: right click a block puts the chosen monster there; right click the air (or sneak + right click) opens
 *   the list of the dungeon's monsters to choose one.</li>
 *   <li>Move: right click a point picks it up, right click a block puts it down.</li>
 *   <li>Remove, objective: right click a point (or near one).</li>
 *   <li>Start: right click a block (or the air: where the player stands).</li>
 *   <li>Save, quit without saving: two right clicks.</li>
 * </ul>
 * Builds go back into the map folder on save; points go into {@code edits/<id>.yml} at each change, which the
 * importer never touches and which overrides the dungeon file's {@code map} points. A dungeon being edited cannot
 * be started. Commands ({@code /fluxdungeons edit ...}) do the same as the tools.
 */
public final class Editor implements Listener {

    /** One open edit: the map's world, its points as being edited, the markers shown. */
    private static final class Session {
        final String dungeon;
        final World world;
        final List<DungeonDefinition.Spawn> spawns;
        final Set<String> objectives;
        final List<String> catalog;
        double[] start;
        final Map<String, Entity[]> markers = new HashMap<>();
        boolean saving;

        Session(String dungeon, World world, DungeonDefinition.MapSpec map) {
            this.dungeon = dungeon;
            this.world = world;
            this.spawns = new ArrayList<>(map.spawns());
            this.objectives = new LinkedHashSet<>(map.objectives());
            this.start = map.start().clone();
            Set<String> c = new LinkedHashSet<>();
            for (DungeonDefinition.Spawn sp : map.spawns()) c.add(sp.mob());
            c.addAll(map.objectives());
            this.catalog = new ArrayList<>(c);
        }
    }

    /** The monster list menu. */
    private static final class MobMenu implements InventoryHolder {
        final Session session;
        final int page;
        final List<String> shown = new ArrayList<>();
        Inventory inventory;

        MobMenu(Session session, int page) {
            this.session = session;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private enum Tool {
        PLACE(Material.ZOMBIE_SPAWN_EGG), MOVE(Material.LEAD), REMOVE(Material.BARRIER), OBJECTIVE(Material.NETHER_STAR),
        START(Material.RECOVERY_COMPASS), SAVE(Material.LIME_DYE), QUIT(Material.RED_DYE);

        final Material material;

        Tool(Material material) {
            this.material = material;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final String MARKER_TAG = "fluxdungeons_marker";
    private static final double REACH = 6, MARKER_RANGE = 48;
    private static final int PAGE = 45;
    private static final long CONFIRM_MS = 3000;

    private final FluxPlugin plugin;
    private final WorldInstances instances;
    private final Function<String, DungeonDefinition> definitions;
    private final Mobs mobs;
    private final NamespacedKey toolKey, pointKey;
    private Runnable reload = () -> {
    };
    private final Map<String, Session> sessions = new HashMap<>();
    private final Set<String> opening = new java.util.HashSet<>();
    private final Map<UUID, Location> back = new HashMap<>();
    private final Map<UUID, GameMode> modes = new HashMap<>();
    private final Map<UUID, String> chosen = new HashMap<>();
    private final Map<UUID, String> carried = new HashMap<>();
    private final Map<UUID, Long> confirm = new HashMap<>();

    Editor(FluxPlugin plugin, WorldInstances instances, Function<String, DungeonDefinition> definitions, Mobs mobs) {
        this.plugin = plugin;
        this.instances = instances;
        this.definitions = definitions;
        this.mobs = mobs;
        this.toolKey = new NamespacedKey(plugin, "edit_tool");
        this.pointKey = new NamespacedKey(plugin, "edit_point");
    }

    /** What reloads the dungeons once a map or its points changed. */
    public void onSaved(Runnable reload) {
        this.reload = reload;
    }

    /** True while the dungeon is open in edit mode (it cannot be started). */
    public boolean locked(String dungeon) {
        return sessions.containsKey(dungeon) || opening.contains(dungeon);
    }

    private Session sessionOf(Player p) {
        return sessionOf(p.getWorld());
    }

    private Session sessionOf(World w) {
        for (Session s : sessions.values()) if (s.world.equals(w)) return s;
        return null;
    }

    private static String worldName(String dungeon) {
        return "fde_" + dungeon.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    // ---------------------------------------------------------------- open, save, quit

    /** Opens the dungeon in edit mode (or joins the open edit) and brings the player to its start. */
    public void open(Player p, DungeonDefinition def) {
        if (def.map() == null) {
            plugin.messages().send(p, "admin.imported-no-template", "%dungeon%", def.name());
            return;
        }
        Session open = sessions.get(def.id());
        if (open != null) {
            enter(p, open);
            return;
        }
        if (opening.contains(def.id())) return;
        File folder = new File(plugin.getDataFolder(), def.map().folder());
        if (!new File(folder, "region").isDirectory()) {
            plugin.messages().send(p, "start.failed");
            return;
        }
        opening.add(def.id());
        plugin.messages().send(p, "edit.opening", "%dungeon%", def.name());
        instances.create(folder, worldName(def.id()), world -> {
            opening.remove(def.id());
            if (world == null) {
                plugin.messages().send(p, "start.failed");
                return;
            }
            Session s = new Session(def.id(), world, def.map());
            sessions.put(def.id(), s);
            // a new map is empty: something to stand on at the start
            Block under = world.getBlockAt((int) Math.floor(s.start[0]), (int) Math.floor(s.start[1]) - 1, (int) Math.floor(s.start[2]));
            if (under.getType().isAir()) under.setType(Material.GLASS);
            if (p.isOnline()) enter(p, s);
            draw(s);
        });
    }

    private void enter(Player p, Session s) {
        if (!s.world.equals(p.getWorld())) {
            back.putIfAbsent(p.getUniqueId(), p.getLocation());
            modes.putIfAbsent(p.getUniqueId(), p.getGameMode());
        }
        p.teleport(new Location(s.world, s.start[0], s.start[1], s.start[2], (float) s.start[3], (float) s.start[4]),
                PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.setGameMode(GameMode.CREATIVE);
        giveTools(p);
        plugin.messages().send(p, "edit.opened", "%dungeon%", definitions.apply(s.dungeon).name(),
                "%spawns%", String.valueOf(s.spawns.size()));
    }

    /** Writes the points, then the builds back into the map, and closes the edit. */
    public void save(Player p) {
        Session s = sessionOf(p);
        if (s == null || s.saving) {
            plugin.messages().send(p, "edit.not-editing");
            return;
        }
        s.saving = true;
        writePoints(s);
        clearMarkers(s);
        sendBack(s);
        DungeonDefinition def = definitions.apply(s.dungeon);
        File folder = new File(plugin.getDataFolder(), def.map().folder());
        plugin.messages().send(p, "edit.saving", "%dungeon%", def.name());
        instances.saveBack(s.world, folder, () -> {
            sessions.remove(s.dungeon);
            reload.run();
            if (p.isOnline()) {
                plugin.messages().send(p, "edit.saved", "%dungeon%", def.name());
                Sounds.cue(p, "success");
            }
        });
    }

    /** Closes the edit without writing the builds (points already moved stay saved). */
    public void cancel(Player p) {
        Session s = sessionOf(p);
        if (s == null || s.saving) {
            plugin.messages().send(p, "edit.not-editing");
            return;
        }
        close(s);
        reload.run();
        plugin.messages().send(p, "edit.cancelled");
    }

    private void close(Session s) {
        sessions.remove(s.dungeon);
        clearMarkers(s);
        sendBack(s);
        instances.destroy(s.world);
    }

    /** Server stopping: open edits are closed without writing their builds. */
    public void closeAll() {
        for (Session s : List.copyOf(sessions.values())) close(s);
    }

    private void sendBack(Session s) {
        for (Player q : List.copyOf(s.world.getPlayers())) {
            leaveEdit(q);
            Location to = back.remove(q.getUniqueId());
            if (to == null || to.getWorld() == null || to.getWorld().equals(s.world)) to = Bukkit.getWorlds().get(0).getSpawnLocation();
            q.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    /** Tools taken back, game mode given back. */
    private void leaveEdit(Player p) {
        takeTools(p);
        GameMode mode = modes.remove(p.getUniqueId());
        if (mode != null) p.setGameMode(mode);
        chosen.remove(p.getUniqueId());
        carried.remove(p.getUniqueId());
        confirm.remove(p.getUniqueId());
    }

    // ---------------------------------------------------------------- tools

    private void giveTools(Player p) {
        takeTools(p);
        for (Tool t : Tool.values()) {
            ItemStack item = tool(p, t);
            var left = p.getInventory().addItem(item);
            if (!left.isEmpty()) {
                plugin.messages().send(p, "edit.inventory-full");
                return;
            }
        }
    }

    private void takeTools(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) if (toolOf(contents[i]) != null) p.getInventory().setItem(i, null);
    }

    private ItemStack tool(Player p, Tool t) {
        String chosenMob = chosen.get(p.getUniqueId());
        String name = plugin.messages().raw("edit.tool." + t.key() + ".name");
        if (t == Tool.PLACE && chosenMob != null) name += plugin.messages().raw("edit.tool.place.chosen", "%mob%", mobName(chosenMob));
        List<String> lore = Lore.create().description(plugin.messages().raw("edit.tool." + t.key() + ".description"))
                .right(plugin.messages().raw("edit.tool." + t.key() + ".action")).build();
        ItemStack item = Items.of(t.material.name(), name, lore, p);
        item.editMeta(m -> m.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, t.key()));
        return item;
    }

    private Tool toolOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String k = item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        if (k == null) return null;
        for (Tool t : Tool.values()) if (t.key().equals(k)) return t;
        return null;
    }

    private void refreshPlaceTool(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) if (toolOf(contents[i]) == Tool.PLACE) p.getInventory().setItem(i, tool(p, Tool.PLACE));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Tool t = toolOf(e.getItem());
        if (t == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Session s = sessionOf(p);
        if (s == null || s.saving) return;
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!right) return;
        Block clicked = e.getAction() == Action.RIGHT_CLICK_BLOCK ? e.getClickedBlock() : null;
        Location at = clicked == null ? p.getLocation() : on(clicked, e.getBlockFace(), p);
        switch (t) {
            case PLACE -> {
                String mob = chosen.get(p.getUniqueId());
                if (clicked == null || p.isSneaking() || mob == null) openMenu(p, s, 0);
                else addAt(p, s, mob, at);
            }
            case MOVE -> {
                String key = carried.get(p.getUniqueId());
                if (key == null) {
                    int i = nearest(s, clicked == null ? p.getLocation() : clicked.getLocation().add(0.5, 1, 0.5));
                    if (i < 0) plugin.messages().send(p, "edit.none-near");
                    else pickUp(p, s, i);
                } else putDown(p, s, key, at);
            }
            case REMOVE, OBJECTIVE -> {
                int i = nearest(s, clicked == null ? p.getLocation() : clicked.getLocation().add(0.5, 1, 0.5));
                if (i < 0) plugin.messages().send(p, "edit.none-near");
                else if (t == Tool.REMOVE) removeAt(p, s, i);
                else toggleObjective(p, s, i);
            }
            case START -> startAt(p, s, at);
            case SAVE, QUIT -> {
                Long first = confirm.get(p.getUniqueId());
                long now = System.currentTimeMillis();
                if (first == null || now - first > CONFIRM_MS) {
                    confirm.put(p.getUniqueId(), now);
                    plugin.messages().send(p, t == Tool.SAVE ? "edit.confirm-save" : "edit.confirm-quit");
                    Sounds.cue(p, "confirm");
                    return;
                }
                confirm.remove(p.getUniqueId());
                if (t == Tool.SAVE) save(p);
                else cancel(p);
            }
        }
    }

    /** Right click on a point's body with a tool. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPoint(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Interaction hit)) return;
        String key = hit.getPersistentDataContainer().get(pointKey, PersistentDataType.STRING);
        if (key == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Session s = sessionOf(p);
        Tool t = toolOf(p.getInventory().getItemInMainHand());
        if (s == null || s.saving || t == null) return;
        int i = indexOf(s, key);
        if (i < 0) return;
        switch (t) {
            case REMOVE -> removeAt(p, s, i);
            case OBJECTIVE -> toggleObjective(p, s, i);
            case MOVE -> {
                if (carried.containsKey(p.getUniqueId())) putDown(p, s, carried.get(p.getUniqueId()), hit.getLocation());
                else pickUp(p, s, i);
            }
            case PLACE -> {
                // the clicked point's monster becomes the chosen one
                chosen.put(p.getUniqueId(), s.spawns.get(i).mob());
                refreshPlaceTool(p);
                plugin.messages().send(p, "edit.chosen", "%mob%", mobName(s.spawns.get(i).mob()));
            }
            default -> {
            }
        }
    }

    /** Where a click on a block puts a point: on top of it (or against the clicked face), facing the player. */
    private static Location on(Block b, BlockFace face, Player p) {
        Block target = face == null || face == BlockFace.UP ? b.getRelative(BlockFace.UP) : b.getRelative(face);
        Location l = target.getLocation().add(0.5, 0, 0.5);
        l.setYaw(p.getLocation().getYaw() + 180);
        return l;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (toolOf(e.getItemDrop().getItemStack()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        if (sessionOf(e.getFrom()) != null && sessionOf(e.getPlayer()) == null) leaveEdit(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (sessionOf(e.getPlayer()) != null) leaveEdit(e.getPlayer());
    }

    // ---------------------------------------------------------------- monster menu

    private void openMenu(Player p, Session s, int page) {
        // the dungeon's own monsters first, then every other known monster (a new dungeon has none of its own)
        List<String> all = new ArrayList<>(s.catalog);
        mobs.templateIds().stream().filter(id -> !all.contains(id)).sorted().forEach(all::add);
        int pages = Math.max(1, (all.size() + PAGE - 1) / PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        MobMenu menu = new MobMenu(s, pg);
        Inventory inv = Bukkit.createInventory(menu, 54, Texts.parse(plugin.messages().raw("edit.menu.title",
                "%dungeon%", Texts.ampersand(Texts.parse(definitions.apply(s.dungeon).name())))));
        menu.inventory = inv;
        String current = chosen.get(p.getUniqueId());
        for (int i = pg * PAGE, slot = 0; i < all.size() && slot < PAGE; i++, slot++) {
            String id = all.get(i);
            MobTemplate t = mobs.template(id);
            Material egg = t == null ? null : Material.matchMaterial(t.type().name() + "_SPAWN_EGG");
            long placed = s.spawns.stream().filter(sp -> sp.mob().equals(id)).count();
            Lore lore = Lore.create().kv(plugin.messages().raw("edit.menu.placed"), placed);
            if (t != null) lore.kv(plugin.messages().raw("edit.menu.rank"), plugin.messages().raw("edit.menu.rank-" + t.extra().rank().toLowerCase(Locale.ROOT)));
            if (s.objectives.contains(id)) lore.info(plugin.messages().raw("edit.menu.objective"));
            if (id.equals(current)) lore.ok(plugin.messages().raw("edit.menu.current"));
            lore.left(plugin.messages().raw("edit.menu.choose"));
            ItemStack item = Items.of((egg == null ? Material.NAME_TAG : egg).name(), mobName(id), lore.build(), p);
            inv.setItem(slot, item);
            menu.shown.add(id);
        }
        if (pg > 0) inv.setItem(45, arrow(p, "influitems:btn_prev"));
        if (pg < pages - 1) inv.setItem(53, arrow(p, "influitems:btn_next"));
        p.openInventory(inv);
    }

    private static ItemStack arrow(Player p, String itemsAdder) {
        ItemStack item = Items.of(Items.itemsAdderExists(itemsAdder) ? "itemsadder:" + itemsAdder : "ARROW", p);
        item.editMeta(m -> m.setHideTooltip(true));
        return item;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof MobMenu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getSlot();
        if (slot == 45 && menu.page > 0) {
            Sounds.cue(p, "page");
            openMenu(p, menu.session, menu.page - 1);
        } else if (slot == 53) {
            Sounds.cue(p, "page");
            openMenu(p, menu.session, menu.page + 1);
        } else if (slot < menu.shown.size()) {
            String id = menu.shown.get(slot);
            chosen.put(p.getUniqueId(), id);
            refreshPlaceTool(p);
            p.closeInventory();
            Sounds.cue(p, "click");
            plugin.messages().send(p, "edit.chosen", "%mob%", mobName(id));
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof MobMenu) e.setCancelled(true);
    }

    private String mobName(String id) {
        MobTemplate t = mobs.template(id);
        return t == null ? id : t.name();
    }

    // ---------------------------------------------------------------- points (commands)

    /** Adds a spawn point of this monster where the player stands. */
    public void add(Player p, String mob) {
        Session s = sessionOf(p);
        if (s == null) plugin.messages().send(p, "edit.not-editing");
        else addAt(p, s, mob, p.getLocation());
    }

    /** Removes the spawn point nearest to the player (within a few blocks). */
    public void remove(Player p) {
        Session s = sessionOf(p);
        if (s == null) {
            plugin.messages().send(p, "edit.not-editing");
            return;
        }
        int i = nearest(s, p.getLocation());
        if (i < 0) plugin.messages().send(p, "edit.none-near");
        else removeAt(p, s, i);
    }

    /** Moves the spawn point nearest to the player to where he stands. */
    public void move(Player p) {
        Session s = sessionOf(p);
        if (s == null) {
            plugin.messages().send(p, "edit.not-editing");
            return;
        }
        int i = nearest(s, p.getLocation());
        if (i < 0) plugin.messages().send(p, "edit.none-near");
        else putDown(p, s, key(s.spawns.get(i)), p.getLocation());
    }

    /** Puts the start of the dungeon where the player stands. */
    public void start(Player p) {
        Session s = sessionOf(p);
        if (s == null) plugin.messages().send(p, "edit.not-editing");
        else startAt(p, s, p.getLocation());
    }

    /** Makes the monster of the nearest point an objective of the dungeon, or no longer one. */
    public void objective(Player p) {
        Session s = sessionOf(p);
        if (s == null) {
            plugin.messages().send(p, "edit.not-editing");
            return;
        }
        int i = nearest(s, p.getLocation());
        if (i < 0) plugin.messages().send(p, "edit.none-near");
        else toggleObjective(p, s, i);
    }

    /** Monsters already placed in the dungeon the player edits (completion). */
    public List<String> placedMobs(Player p) {
        Session s = sessionOf(p);
        if (s == null) return List.of();
        return s.spawns.stream().map(DungeonDefinition.Spawn::mob).distinct().sorted().toList();
    }

    // ---------------------------------------------------------------- points (shared)

    private void addAt(Player p, Session s, String mob, Location l) {
        s.spawns.add(new DungeonDefinition.Spawn(mob, round(l.getX()), round(l.getY()), round(l.getZ()), Math.round(l.getYaw())));
        if (!s.catalog.contains(mob)) s.catalog.add(mob);
        changed(s);
        Sounds.cue(p, "success");
        plugin.messages().send(p, "edit.added", "%mob%", mobName(mob), "%spawns%", String.valueOf(s.spawns.size()));
    }

    private void removeAt(Player p, Session s, int i) {
        DungeonDefinition.Spawn gone = s.spawns.remove(i);
        if (s.spawns.stream().noneMatch(sp -> sp.mob().equals(gone.mob()))) s.objectives.remove(gone.mob());
        carried.values().remove(key(gone));
        changed(s);
        Sounds.cue(p, "remove");
        plugin.messages().send(p, "edit.removed", "%mob%", mobName(gone.mob()), "%spawns%", String.valueOf(s.spawns.size()));
    }

    private void pickUp(Player p, Session s, int i) {
        String key = key(s.spawns.get(i));
        carried.put(p.getUniqueId(), key);
        Entity[] m = s.markers.get(key);
        if (m != null && m[0] instanceof TextDisplay d) d.setGlowing(true);
        Sounds.cue(p, "click");
        plugin.messages().send(p, "edit.picked", "%mob%", mobName(s.spawns.get(i).mob()));
    }

    private void putDown(Player p, Session s, String key, Location l) {
        carried.remove(p.getUniqueId());
        int i = indexOf(s, key);
        if (i < 0) return;
        DungeonDefinition.Spawn old = s.spawns.get(i);
        s.spawns.set(i, new DungeonDefinition.Spawn(old.mob(), round(l.getX()), round(l.getY()), round(l.getZ()), Math.round(l.getYaw())));
        changed(s);
        Sounds.cue(p, "success");
        plugin.messages().send(p, "edit.moved", "%mob%", mobName(old.mob()));
    }

    private void startAt(Player p, Session s, Location l) {
        s.start = new double[]{round(l.getX()), round(l.getY()), round(l.getZ()), Math.round(l.getYaw()), Math.round(l.getPitch())};
        changed(s);
        Sounds.cue(p, "success");
        plugin.messages().send(p, "edit.start");
    }

    private void toggleObjective(Player p, Session s, int i) {
        String mob = s.spawns.get(i).mob();
        boolean now = s.objectives.add(mob) || !s.objectives.remove(mob);
        changed(s);
        Sounds.cue(p, now ? "toggle-on" : "toggle-off");
        plugin.messages().send(p, now ? "edit.objective-on" : "edit.objective-off", "%mob%", mobName(mob),
                "%objectives%", String.valueOf(s.objectives.size()));
    }

    private int nearest(Session s, Location at) {
        int best = -1;
        double bestD = REACH * REACH;
        for (int i = 0; i < s.spawns.size(); i++) {
            DungeonDefinition.Spawn sp = s.spawns.get(i);
            double dx = sp.x() - at.getX(), dy = sp.y() - at.getY(), dz = sp.z() - at.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static String key(DungeonDefinition.Spawn sp) {
        return sp.mob() + "@" + sp.x() + "," + sp.y() + "," + sp.z();
    }

    private static int indexOf(Session s, String key) {
        for (int i = 0; i < s.spawns.size(); i++) if (key(s.spawns.get(i)).equals(key)) return i;
        return -1;
    }

    private void changed(Session s) {
        writePoints(s);
        clearMarkers(s);
        draw(s);
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** {@code edits/<id>.yml}: start, spawns and objectives of the map, kept apart from the imported file. */
    private void writePoints(Session s) {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Points of the dungeon moved in game (/fluxdungeons edit): they replace those of",
                "dungeons/" + s.dungeon + ".yml. Delete this file to come back to the imported points."));
        y.set("map.start", List.of(s.start[0], s.start[1], s.start[2], s.start[3], s.start[4]));
        y.set("map.objectives", new ArrayList<>(s.objectives));
        List<Map<String, Object>> spawns = new ArrayList<>();
        for (DungeonDefinition.Spawn sp : s.spawns) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mob", sp.mob());
            m.put("at", List.of(sp.x(), sp.y(), sp.z(), (double) sp.yaw()));
            spawns.add(m);
        }
        y.set("map.spawns", spawns);
        File f = new File(plugin.getDataFolder(), "edits/" + s.dungeon + ".yml");
        f.getParentFile().mkdirs();
        try {
            y.save(f);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save " + f + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- markers

    /** Every two seconds: markers within {@link #MARKER_RANGE} blocks of a player in an edit world, none farther. */
    void tick() {
        for (Session s : sessions.values()) if (!s.saving) draw(s);
    }

    /**
     * Each point shown near players: its monster's name above (objectives marked) and a clickable body; the start
     * too. Only near players, since spawning a marker loads its chunk and a map may have hundreds of points; markers
     * are never saved in the map.
     */
    private void draw(Session s) {
        List<Location> players = s.world.getPlayers().stream().map(Player::getLocation).toList();
        s.markers.entrySet().removeIf(en -> {
            Entity[] m = en.getValue();
            if (m[0].isValid() && near(m[0].getLocation(), players)) return false;
            for (Entity x : m) x.remove();
            return true;
        });
        Set<String> carriedKeys = new java.util.HashSet<>(carried.values());
        for (DungeonDefinition.Spawn sp : s.spawns) {
            String key = key(sp);
            Location feet = new Location(s.world, sp.x(), sp.y(), sp.z());
            if (s.markers.containsKey(key) || !near(feet, players)) continue;
            String text = plugin.messages().raw(s.objectives.contains(sp.mob()) ? "edit.marker-objective" : "edit.marker", "%mob%", mobName(sp.mob()));
            TextDisplay label = label(feet.clone().add(0, 2.3, 0), text);
            if (carriedKeys.contains(key)) label.setGlowing(true);
            s.markers.put(key, new Entity[]{label, body(feet, key)});
        }
        Location start = new Location(s.world, s.start[0], s.start[1], s.start[2]);
        if (!s.markers.containsKey("start") && near(start, players)) {
            s.markers.put("start", new Entity[]{label(start.clone().add(0, 2.3, 0), plugin.messages().raw("edit.marker-start"))});
        }
    }

    private static boolean near(Location at, List<Location> players) {
        for (Location l : players) if (l.getWorld() == at.getWorld() && l.distanceSquared(at) <= MARKER_RANGE * MARKER_RANGE) return true;
        return false;
    }

    private static TextDisplay label(Location at, String text) {
        return at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.text(Texts.parse(text));
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setSeeThrough(true);
            d.addScoreboardTag(MARKER_TAG);
        });
    }

    private Interaction body(Location feet, String key) {
        return feet.getWorld().spawn(feet, Interaction.class, i -> {
            i.setInteractionWidth(0.9f);
            i.setInteractionHeight(1.9f);
            i.setResponsive(true);
            i.setPersistent(false);
            i.addScoreboardTag(MARKER_TAG);
            i.getPersistentDataContainer().set(pointKey, PersistentDataType.STRING, key);
        });
    }

    private static void clearMarkers(Session s) {
        for (Entity[] m : s.markers.values()) for (Entity x : m) x.remove();
        s.markers.clear();
        // markers left in chunks unloaded meanwhile: never write them into the map
        for (Entity e : s.world.getEntities()) if (e.getScoreboardTags().contains(MARKER_TAG)) e.remove();
    }
}
