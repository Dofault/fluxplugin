package fr.influencraft.fluxdungeons.dungeon;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The launch screen of a dungeon: the party sees who comes, with which rpg class and armour, the leader picks the
 * difficulty, and the run starts once everybody is ready. Nothing is chosen: a dungeon is played with the rpg profile
 * as it is (class weapon, armour, skills), never with things of the semi-rp world.
 * <p>
 * Rows of the window: party and dungeon (top), the viewer's own panel, the panel of a team mate chosen by clicking
 * his head, the class, difficulty and ready buttons.
 */
public final class Lobby implements Listener {

    private static final int SIZE = 54;
    private static final int BACK = 0, INFO = 8, CLASS = 45, LEVEL = 47, READY = 49;
    private static final int OWN = 9, MATE = 27;
    /** A lobby nobody launches is dropped after this long. */
    private static final long TIMEOUT_MS = 5 * 60_000;

    /** A party getting ready for a dungeon. */
    private static final class Party {
        final DungeonDefinition def;
        final List<UUID> members;
        final Set<UUID> ready = new HashSet<>();
        final Map<UUID, UUID> viewing = new HashMap<>();
        final long created = System.currentTimeMillis();
        /** Difficulty level chosen by the leader (first member), index in the dungeon's levels. */
        int level;

        Party(DungeonDefinition def, List<UUID> members) {
            this.def = def;
            this.members = members;
            this.level = defaultLevel(def);
        }
    }

    private static final class Screen implements InventoryHolder {
        final Party party;
        final UUID viewer;
        Inventory inventory;

        Screen(Party party, UUID viewer) {
            this.party = party;
            this.viewer = viewer;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Starts the run once everybody is ready (checks again, the players may have changed meanwhile). */
    public interface Launcher {
        void launch(DungeonDefinition def, List<Player> players, int level);
    }

    /** The level offered first: the one named Normal, else the first. */
    private static int defaultLevel(DungeonDefinition def) {
        for (int i = 0; i < def.levels().size(); i++) if (def.levels().get(i).name().equalsIgnoreCase("normal")) return i;
        return 0;
    }

    private final FluxPlugin plugin;
    private Launcher launcher;
    private final Map<UUID, Party> byPlayer = new HashMap<>();

    /** Class and level of a player in the rpg ("Guerrier 5"), null when he has none yet. */
    private java.util.function.Function<UUID, String> rpgClass = u -> null;

    void classLine(java.util.function.Function<UUID, String> classLine) {
        this.rpgClass = classLine == null ? u -> null : classLine;
    }

    Lobby(FluxPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 200L, 200L);
    }

    void launcher(Launcher launcher) {
        this.launcher = launcher;
    }

    public boolean waiting(Player p) {
        return byPlayer.containsKey(p.getUniqueId());
    }

    /** Dungeon of the launch screen this player waits on (he may have closed it), null when none. */
    public DungeonDefinition dungeonOf(Player p) {
        Party party = byPlayer.get(p.getUniqueId());
        return party == null ? null : party.def;
    }

    /** Opens the launch screen for these players (already checked), at the difficulty level chosen (-1: default). */
    public void open(DungeonDefinition def, List<Player> players, int level) {
        List<UUID> ids = new ArrayList<>();
        for (Player p : players) ids.add(p.getUniqueId());
        Party party = new Party(def, ids);
        if (level >= 0 && level < def.levels().size()) party.level = level;
        for (Player p : players) byPlayer.put(p.getUniqueId(), party);
        for (Player p : players) show(p);
    }

    /** Opens the screen again for a player of a lobby (he closed it); false when he is in none. */
    public boolean reopen(Player p) {
        if (!byPlayer.containsKey(p.getUniqueId())) return false;
        show(p);
        return true;
    }

    /** The player gives up: the whole party's lobby is closed. */
    public void cancel(Player p) {
        Party party = byPlayer.get(p.getUniqueId());
        if (party != null) close(party, "lobby.cancelled", p.getName());
    }

    // ---------------------------------------------------------------- drawing

    private void show(Player p) {
        Party party = byPlayer.get(p.getUniqueId());
        if (party == null) return;
        Screen screen = new Screen(party, p.getUniqueId());
        screen.inventory = Bukkit.createInventory(screen, SIZE, Texts.parse(plugin.messages().raw("lobby.title", "%dungeon%", party.def.name())));
        draw(screen, p);
        p.openInventory(screen.inventory);
    }

    private void redraw(Party party) {
        for (UUID u : party.members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof Screen s && s.party == party) {
                draw(s, p);
            }
        }
    }

    private void draw(Screen screen, Player viewer) {
        Inventory inv = screen.inventory;
        inv.clear();
        Party party = screen.party;
        inv.setItem(BACK, Items.of("itemsadder:influitems:btn_back", plugin.messages().raw("lobby.leave"),
                Lore.create().description(plugin.messages().raw("lobby.leave-desc")).left(plugin.messages().raw("lobby.leave-action")).build(), viewer));
        List<String> info = new ArrayList<>(party.def.description());
        Lore infoLore = Lore.create().description(info)
                .kv(plugin.messages().raw("lobby.players"), party.def.minPlayers() + "-" + party.def.maxPlayers())
                .kv(plugin.messages().raw("lobby.level"), party.def.recommendedLevel());
        inv.setItem(INFO, Items.of("NETHER_STAR", party.def.name(), infoLore.build(), viewer));
        UUID mate = mate(party, screen.viewer);
        int head = 1;
        for (UUID u : party.members) {
            if (head > 7) break;
            inv.setItem(head++, head(party, u, mate, viewer));
        }
        panel(inv, OWN, screen.viewer, viewer);
        if (mate != null) panel(inv, MATE, mate, viewer);
        String line = rpgClass.apply(screen.viewer);
        inv.setItem(CLASS, Items.of("BOOK", line == null ? plugin.messages().raw("lobby.no-class") : line,
                Lore.create().description(plugin.messages().raw("lobby.class-desc")).left(plugin.messages().raw("lobby.class-change")).build(), viewer));
        if (party.def.levels().size() > 1) inv.setItem(LEVEL, levelButton(party, viewer));
        boolean ready = party.ready.contains(screen.viewer);
        inv.setItem(READY, Items.of(ready ? "LIME_DYE" : "GRAY_DYE",
                plugin.messages().raw(ready ? "lobby.ready" : "lobby.not-ready"),
                Lore.create().description(plugin.messages().raw("lobby.ready-desc",
                                "%ready%", String.valueOf(party.ready.size()), "%total%", String.valueOf(party.members.size())))
                        .left(plugin.messages().raw(ready ? "lobby.unready-action" : "lobby.ready-action")).build(), viewer));
    }

    private ItemStack levelButton(Party party, Player viewer) {
        List<DungeonDefinition.Level> levels = party.def.levels();
        DungeonDefinition.Level l = levels.get(party.level);
        Lore lore = Lore.create().description(plugin.messages().raw("lobby.difficulty-desc"));
        lore.kv(plugin.messages().raw("lobby.difficulty-spells"), !l.spells() ? plugin.messages().raw("lobby.spells-off")
                : l.abilities() > 0 ? plugin.messages().raw("lobby.spells-count", "%count%", String.valueOf(l.abilities()))
                : plugin.messages().raw("lobby.spells-on"));
        lore.filter(levels.stream().map(DungeonDefinition.Level::name).toList(), party.level);
        if (leader(party, viewer.getUniqueId())) {
            lore.left(plugin.messages().raw("lobby.difficulty-next")).right(plugin.messages().raw("lobby.difficulty-prev"));
        } else {
            lore.line(plugin.messages().raw("lobby.difficulty-leader", "%player%", name(party.members.get(0))));
        }
        String[] icons = {"WOODEN_SWORD", "IRON_SWORD", "DIAMOND_SWORD", "NETHERITE_SWORD"};
        int icon = levels.size() <= 1 ? 1 : Math.round((float) party.level * (icons.length - 1) / (levels.size() - 1));
        return Items.of(icons[icon], plugin.messages().raw("lobby.difficulty", "%level%", l.name()), lore.build(), viewer);
    }

    private static boolean leader(Party party, UUID u) {
        return !party.members.isEmpty() && party.members.get(0).equals(u);
    }

    private static String name(UUID u) {
        Player p = Bukkit.getPlayer(u);
        return p == null ? "?" : p.getName();
    }

    /** His rpg armour (boots to helmet), shown as it is, and what he fights with: nothing to choose. */
    private void panel(Inventory inv, int at, UUID who, Player viewer) {
        Player owner = Bukkit.getPlayer(who);
        if (owner == null) return;
        ItemStack[] armor = owner.getInventory().getArmorContents();
        for (int a = 0; a < 4; a++) {
            ItemStack worn = a < armor.length ? armor[a] : null;
            inv.setItem(at + a, worn == null || worn.isEmpty()
                    ? Items.of("LIGHT_GRAY_STAINED_GLASS_PANE", plugin.messages().raw("lobby.armor-empty"), List.of(), viewer) : worn.clone());
        }
        inv.setItem(at + 9, Items.of("BOOK", plugin.messages().raw("lobby.rpg-gear"),
                Lore.create().description(plugin.messages().raw("lobby.rpg-gear-desc")).build(), viewer));
    }

    private ItemStack head(Party party, UUID u, UUID mate, Player viewer) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        skull.editMeta(m -> {
            if (m instanceof SkullMeta sm) sm.setOwningPlayer(Bukkit.getOfflinePlayer(u));
        });
        Player p = Bukkit.getPlayer(u);
        String name = p == null ? "?" : p.getName();
        Lore lore = Lore.create();
        String line = rpgClass.apply(u);
        if (line != null) lore.kv(plugin.messages().raw("lobby.class"), line);
        lore.line(plugin.messages().raw(party.ready.contains(u) ? "lobby.head-ready" : "lobby.head-waiting"));
        if (!u.equals(viewer.getUniqueId()) && !u.equals(mate)) lore.left(plugin.messages().raw("lobby.head-view"));
        return Items.dress(skull, name, lore.build(), viewer);
    }

    private UUID mate(Party party, UUID viewer) {
        UUID chosen = party.viewing.get(viewer);
        if (chosen != null && party.members.contains(chosen)) return chosen;
        for (UUID u : party.members) if (!u.equals(viewer)) return u;
        return null;
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (h instanceof Screen) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (!(h instanceof Screen screen)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !e.getClick().isLeftClick() && !e.getClick().isRightClick()) return;
        Party party = screen.party;
        if (byPlayer.get(p.getUniqueId()) != party) {
            p.closeInventory();
            return;
        }
        int raw = e.getRawSlot();
        if (raw >= SIZE) return;
        if (raw == BACK) {
            cancel(p);
            return;
        }
        if (raw == CLASS) {
            Bukkit.getScheduler().runTask(plugin, () -> p.performCommand("classe"));
            return;
        }
        if (raw == READY) {
            toggleReady(p, party);
            return;
        }
        if (raw == LEVEL && party.def.levels().size() > 1) {
            if (!leader(party, p.getUniqueId())) {
                Sounds.cue(p, "error");
                return;
            }
            int n = party.def.levels().size();
            party.level = (party.level + (e.getClick().isRightClick() ? n - 1 : 1)) % n;
            // another level: everybody agrees again
            party.ready.clear();
            Sounds.cue(p, "click");
            redraw(party);
            return;
        }
        if (raw >= 1 && raw <= 7) {
            int index = raw - 1;
            if (index < party.members.size() && !party.members.get(index).equals(p.getUniqueId())) {
                party.viewing.put(p.getUniqueId(), party.members.get(index));
                Sounds.cue(p, "page");
                draw(screen, p);
            }
            return;
        }
    }

    private void toggleReady(Player p, Party party) {
        if (!party.ready.add(p.getUniqueId())) {
            party.ready.remove(p.getUniqueId());
            Sounds.cue(p, "toggle-off");
            redraw(party);
            return;
        }
        Sounds.cue(p, "confirm");
        if (!party.ready.containsAll(party.members)) {
            redraw(party);
            return;
        }
        List<Player> players = new ArrayList<>();
        for (UUID u : party.members) {
            Player m = Bukkit.getPlayer(u);
            if (m == null) {
                close(party, "lobby.cancelled", "?");
                return;
            }
            players.add(m);
        }
        for (UUID u : party.members) byPlayer.remove(u);
        for (Player m : players) {
            if (m.getOpenInventory().getTopInventory().getHolder() instanceof Screen s && s.party == party) m.closeInventory();
        }
        launcher.launch(party.def, players, party.level);
    }

    // ---------------------------------------------------------------- end of a lobby

    private void close(Party party, String key, String who) {
        for (UUID u : party.members) {
            if (byPlayer.get(u) == party) byPlayer.remove(u);
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder();
            if (h instanceof Screen s && s.party == party) p.closeInventory();
            plugin.messages().send(p, key, "%player%", who, "%dungeon%", party.def.name());
        }
    }

    private void expire() {
        long now = System.currentTimeMillis();
        for (Party party : new HashSet<>(byPlayer.values())) {
            if (now - party.created > TIMEOUT_MS) close(party, "lobby.expired", "");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Party party = byPlayer.get(e.getPlayer().getUniqueId());
        if (party != null) close(party, "lobby.cancelled", e.getPlayer().getName());
    }

    /** Everybody out (server stop). */
    void closeAll() {
        for (Party party : new HashSet<>(byPlayer.values())) close(party, "lobby.expired", "");
    }
}
