package fr.influencraft.fluxdungeons.dungeon;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.pets.Companions;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxdungeons.Loadouts;
import fr.influencraft.fluxdungeons.hero.HeroClass;
import fr.influencraft.fluxdungeons.hero.Heroes;
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
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The launch screen of a dungeon: every player of the party picks what he takes in, and the run starts once they
 * are all ready. Each player is shown on two rows: his armour (boots to helmet) and up to five pets, then his three
 * abilities and the six items he takes. Worn armour is taken by default; an item is added by clicking it in his own
 * inventory (it is not moved: only its place is remembered, and checked again when he enters).
 * <p>
 * Rows of the window: party and dungeon (top), the viewer's own panel, the panel of a team mate chosen by clicking
 * his head, the ready button.
 */
public final class Lobby implements Listener {

    private static final int SIZE = 54;
    private static final int BACK = 0, INFO = 8, CLASS = 45, LEVEL = 47, READY = 49;
    private static final int OWN = 9, MATE = 27;
    private static final int PETS = 5;
    /** A lobby nobody launches is dropped after this long. */
    private static final long TIMEOUT_MS = 5 * 60_000;

    /** A party getting ready for a dungeon. */
    private static final class Party {
        final DungeonDefinition def;
        final List<UUID> members;
        final Map<UUID, Loadouts.Selection> selections = new HashMap<>();
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

    private static final class PetPicker implements InventoryHolder {
        final Party party;
        final UUID viewer;
        final int slot;
        final List<String> shown = new ArrayList<>();
        Inventory inventory;

        PetPicker(Party party, UUID viewer, int slot) {
            this.party = party;
            this.viewer = viewer;
            this.slot = slot;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Starts the run once everybody is ready (checks again, the players may have changed meanwhile). */
    public interface Launcher {
        void launch(DungeonDefinition def, List<Player> players, Map<UUID, Loadouts.Selection> selections, int level);
    }

    /** The level offered first: the one named Normal, else the first. */
    private static int defaultLevel(DungeonDefinition def) {
        for (int i = 0; i < def.levels().size(); i++) if (def.levels().get(i).name().equalsIgnoreCase("normal")) return i;
        return 0;
    }

    private final FluxPlugin plugin;
    private final Heroes heroes;
    private final java.util.function.ToIntFunction<String> lockedGear;
    private Launcher launcher;
    private final Map<UUID, Party> byPlayer = new HashMap<>();
    /** Last choice of each player, offered again at his next launch (if those items are still there). */
    private final Map<UUID, Loadouts.Selection> last = new HashMap<>();
    private int maxPets = PETS;

    Lobby(FluxPlugin plugin, Heroes heroes, java.util.function.ToIntFunction<String> lockedGear) {
        this.plugin = plugin;
        this.heroes = heroes;
        this.lockedGear = lockedGear;
        Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 200L, 200L);
    }

    void launcher(Launcher launcher) {
        this.launcher = launcher;
    }

    void maxPets(int n) {
        maxPets = Math.max(0, Math.min(PETS, n));
    }

    public boolean waiting(Player p) {
        return byPlayer.containsKey(p.getUniqueId());
    }

    /** Opens the launch screen for these players (already checked). */
    public void open(DungeonDefinition def, List<Player> players) {
        List<UUID> ids = new ArrayList<>();
        for (Player p : players) ids.add(p.getUniqueId());
        Party party = new Party(def, ids);
        for (Player p : players) {
            byPlayer.put(p.getUniqueId(), party);
            party.selections.put(p.getUniqueId(), initial(p));
        }
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
        int locked = lockedGear.applyAsInt(party.def.id());
        if (locked > 0) infoLore.info(plugin.messages().raw("lobby.locked-gear", "%count%", String.valueOf(locked)));
        inv.setItem(INFO, Items.of("NETHER_STAR", party.def.name(), infoLore.build(), viewer));
        UUID mate = mate(party, screen.viewer);
        int head = 1;
        for (UUID u : party.members) {
            if (head > 7) break;
            inv.setItem(head++, head(party, u, mate, viewer));
        }
        panel(inv, OWN, party, screen.viewer, viewer, true);
        if (mate != null) panel(inv, MATE, party, mate, viewer, false);
        HeroClass c = heroes.classOf(screen.viewer);
        if (c != null) {
            inv.setItem(CLASS, Items.dress(new ItemStack(c.icon()), c.colored(),
                    Lore.create().description(c.description()).left(plugin.messages().raw("lobby.class-change")).build(), viewer));
        }
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
        if (!l.gear().isEmpty()) lore.kv(plugin.messages().raw("lobby.difficulty-gear"), l.gear());
        lore.kv(plugin.messages().raw("lobby.difficulty-spells"), plugin.messages().raw(l.spells() ? "lobby.spells-on" : "lobby.spells-off"));
        if (l.loan()) lore.ok(plugin.messages().raw("lobby.difficulty-loan"));
        if (l.requireGear()) lore.info(plugin.messages().raw("lobby.difficulty-required"));
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

    /**
     * Why this player may not take this level, null when he may: levels with {@code require-gear} want his four armour
     * pieces taken and a weapon among his chosen items.
     */
    private String missingGear(Party party, Player p) {
        DungeonDefinition.Level l = party.def.levels().get(party.level);
        if (!l.requireGear()) return null;
        Loadouts.Selection sel = party.selections.get(p.getUniqueId());
        for (int a = 0; a < Loadouts.ARMOR.length; a++) {
            ItemStack worn = p.getInventory().getItem(Loadouts.ARMOR[a]);
            if (worn == null || worn.isEmpty() || !sel.armor()[a]) return "lobby.need-armor";
        }
        for (int k = 0; k < Loadouts.ITEMS; k++) if (Loadouts.isWeapon(chosen(p, sel, k))) return null;
        return "lobby.need-weapon";
    }

    /** Two rows: armour then pets, abilities then items. */
    private void panel(Inventory inv, int at, Party party, UUID who, Player viewer, boolean own) {
        Player owner = Bukkit.getPlayer(who);
        Loadouts.Selection sel = party.selections.get(who);
        if (owner == null || sel == null) return;
        PlayerInventory pi = owner.getInventory();
        for (int a = 0; a < Loadouts.ARMOR.length; a++) {
            ItemStack worn = pi.getItem(Loadouts.ARMOR[a]);
            if (worn == null || worn.isEmpty()) {
                inv.setItem(at + a, Items.of("LIGHT_GRAY_STAINED_GLASS_PANE", plugin.messages().raw("lobby.armor-empty"), List.of(), viewer));
            } else if (sel.armor()[a]) {
                inv.setItem(at + a, own ? withLine(worn, Lore.create().left(plugin.messages().raw("lobby.armor-leave")).build()) : worn.clone());
            } else {
                inv.setItem(at + a, Items.of("GRAY_STAINED_GLASS_PANE", plugin.messages().raw("lobby.armor-left"),
                        own ? Lore.create().left(plugin.messages().raw("lobby.armor-take")).build() : List.of(), viewer));
            }
        }
        Map<String, Companions.Companion> owned = new LinkedHashMap<>();
        for (Companions.Companion c : Companions.owned(owner)) owned.put(c.id(), c);
        for (int k = 0; k < PETS; k++) {
            int slot = at + 4 + k;
            if (k >= maxPets) {
                inv.setItem(slot, Items.of("BLACK_STAINED_GLASS_PANE", " ", List.of(), viewer));
                continue;
            }
            Companions.Companion c = k < sel.pets().size() ? owned.get(sel.pets().get(k)) : null;
            if (c != null) {
                ItemStack icon = c.icon().clone();
                Lore lore0 = Lore.create().kv(plugin.messages().raw("lobby.pet-level"), c.level());
                if (own) lore0.left(plugin.messages().raw("lobby.pet-remove"));
                List<String> lore = lore0.build();
                inv.setItem(slot, Items.dress(icon, c.name(), lore, viewer));
            } else {
                inv.setItem(slot, Items.of("LIGHT_GRAY_STAINED_GLASS_PANE", plugin.messages().raw("lobby.pet-empty"),
                        own && !owned.isEmpty() ? Lore.create().left(plugin.messages().raw("lobby.pet-choose")).build()
                                : own ? Lore.create().description(plugin.messages().raw("lobby.pet-none")).build() : List.of(), viewer));
            }
        }
        HeroClass c = heroes.classOf(who);
        List<ItemStack> abilities = c == null ? List.of() : heroes.abilityIcons(viewer, c);
        for (int k = 0; k < Loadouts.FIRST_ITEM_SLOT; k++) {
            inv.setItem(at + 9 + k, k < abilities.size() ? abilities.get(k)
                    : Items.of("BLACK_STAINED_GLASS_PANE", " ", List.of(), viewer));
        }
        for (int k = 0; k < Loadouts.ITEMS; k++) {
            int slot = at + 9 + Loadouts.FIRST_ITEM_SLOT + k;
            ItemStack chosen = chosen(owner, sel, k);
            if (chosen != null) {
                inv.setItem(slot, own ? withLine(chosen, Lore.create().left(plugin.messages().raw("lobby.item-remove")).build()) : chosen.clone());
            } else {
                inv.setItem(slot, Items.of("LIGHT_GRAY_STAINED_GLASS_PANE", plugin.messages().raw("lobby.item-empty"),
                        own ? Lore.create().description(plugin.messages().raw("lobby.item-how")).build() : List.of(), viewer));
            }
        }
    }

    private ItemStack head(Party party, UUID u, UUID mate, Player viewer) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        skull.editMeta(m -> {
            if (m instanceof SkullMeta sm) sm.setOwningPlayer(Bukkit.getOfflinePlayer(u));
        });
        Player p = Bukkit.getPlayer(u);
        String name = p == null ? "?" : p.getName();
        HeroClass c = heroes.classOf(u);
        Lore lore = Lore.create();
        if (c != null) lore.kv(plugin.messages().raw("lobby.class"), c.colored() + " " + heroes.level(u, c.id()));
        lore.line(plugin.messages().raw(party.ready.contains(u) ? "lobby.head-ready" : "lobby.head-waiting"));
        if (!u.equals(viewer.getUniqueId()) && !u.equals(mate)) lore.left(plugin.messages().raw("lobby.head-view"));
        return Items.dress(skull, name, lore.build(), viewer);
    }

    private static ItemStack withLine(ItemStack item, List<String> extra) {
        ItemStack copy = item.clone();
        copy.editMeta(m -> {
            List<net.kyori.adventure.text.Component> lore = m.lore() == null ? new ArrayList<>() : new ArrayList<>(m.lore());
            lore.add(net.kyori.adventure.text.Component.empty());
            for (String line : extra) lore.add(Texts.parse(line).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            m.lore(lore);
        });
        return copy;
    }

    /** The item a player chose in a place, null when empty or no longer there. */
    private static ItemStack chosen(Player owner, Loadouts.Selection sel, int k) {
        int index = sel.items()[k];
        if (index < 0 || index >= 36) return null;
        ItemStack item = owner.getInventory().getItem(index);
        if (item == null || item.isEmpty()) return null;
        ItemStack seen = sel.snapshots()[k];
        if (seen != null && !(seen.isSimilar(item) && seen.getAmount() == item.getAmount())) return null;
        return item;
    }

    private UUID mate(Party party, UUID viewer) {
        UUID chosen = party.viewing.get(viewer);
        if (chosen != null && party.members.contains(chosen)) return chosen;
        for (UUID u : party.members) if (!u.equals(viewer)) return u;
        return null;
    }

    // ---------------------------------------------------------------- choices

    private Loadouts.Selection initial(Player p) {
        Loadouts.Selection sel = Loadouts.Selection.empty();
        Loadouts.Selection before = last.get(p.getUniqueId());
        if (before != null) {
            for (int k = 0; k < Loadouts.ITEMS; k++) {
                if (chosen(p, before, k) != null) {
                    sel.items()[k] = before.items()[k];
                    sel.snapshots()[k] = before.snapshots()[k];
                }
            }
            System.arraycopy(before.armor(), 0, sel.armor(), 0, sel.armor().length);
        }
        List<String> owned = Companions.owned(p).stream().map(Companions.Companion::id).toList();
        List<String> pets = before != null && !before.pets().isEmpty() ? before.pets() : Companions.active(p);
        for (String id : pets) if (owned.contains(id) && sel.pets().size() < maxPets) sel.pets().add(id);
        return sel;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (h instanceof Screen || h instanceof PetPicker) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (h instanceof PetPicker picker) {
            e.setCancelled(true);
            pickPet(picker, e);
            return;
        }
        if (!(h instanceof Screen screen)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !e.getClick().isLeftClick() && !e.getClick().isRightClick()) return;
        Party party = screen.party;
        if (byPlayer.get(p.getUniqueId()) != party) {
            p.closeInventory();
            return;
        }
        Loadouts.Selection sel = party.selections.get(p.getUniqueId());
        int raw = e.getRawSlot();
        if (raw >= SIZE) {
            // his own inventory: add the clicked item
            if (!(e.getClickedInventory() instanceof PlayerInventory) || e.getSlot() >= 36) return;
            ItemStack item = e.getCurrentItem();
            if (item == null || item.isEmpty()) return;
            addItem(p, party, sel, e.getSlot(), item);
            return;
        }
        if (raw == BACK) {
            cancel(p);
            return;
        }
        if (raw == CLASS) {
            Bukkit.getScheduler().runTask(plugin, () -> p.performCommand("dungeon class"));
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
            // another level: everybody checks his gear again
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
        int rel = raw - OWN;
        if (rel < 0 || rel >= 18) return;
        if (rel < 4) {
            ItemStack worn = p.getInventory().getItem(Loadouts.ARMOR[rel]);
            if (worn == null || worn.isEmpty()) return;
            sel.armor()[rel] = !sel.armor()[rel];
            changed(p, party);
            Sounds.cue(p, sel.armor()[rel] ? "toggle-on" : "toggle-off");
        } else if (rel < 9) {
            int k = rel - 4;
            if (k >= maxPets) return;
            if (k < sel.pets().size()) {
                sel.pets().remove(k);
                changed(p, party);
                Sounds.cue(p, "remove");
            } else if (!Companions.owned(p).isEmpty()) {
                openPicker(p, party, k);
            }
        } else if (rel >= 9 + Loadouts.FIRST_ITEM_SLOT) {
            int k = rel - 9 - Loadouts.FIRST_ITEM_SLOT;
            if (sel.items()[k] < 0) return;
            sel.items()[k] = -1;
            sel.snapshots()[k] = null;
            changed(p, party);
            Sounds.cue(p, "remove");
        }
    }

    private void addItem(Player p, Party party, Loadouts.Selection sel, int index, ItemStack item) {
        int free = -1;
        for (int k = 0; k < Loadouts.ITEMS; k++) {
            if (sel.items()[k] == index && chosen(p, sel, k) != null) {
                // clicked again: taken back out
                sel.items()[k] = -1;
                sel.snapshots()[k] = null;
                changed(p, party);
                Sounds.cue(p, "remove");
                return;
            }
            if (free < 0 && chosen(p, sel, k) == null) free = k;
        }
        if (free < 0) {
            plugin.messages().send(p, "lobby.items-full", "%max%", String.valueOf(Loadouts.ITEMS));
            Sounds.cue(p, "error");
            return;
        }
        sel.items()[free] = index;
        sel.snapshots()[free] = item.clone();
        changed(p, party);
        Sounds.cue(p, "click");
    }

    /** A choice changed: the player is no longer ready, every screen of the party is redrawn. */
    private void changed(Player p, Party party) {
        party.ready.remove(p.getUniqueId());
        last.put(p.getUniqueId(), copy(party.selections.get(p.getUniqueId())));
        redraw(party);
    }

    private static Loadouts.Selection copy(Loadouts.Selection s) {
        return new Loadouts.Selection(s.items().clone(), s.snapshots().clone(), s.armor().clone(), new ArrayList<>(s.pets()));
    }

    private void toggleReady(Player p, Party party) {
        if (!party.ready.contains(p.getUniqueId())) {
            String missing = missingGear(party, p);
            if (missing != null) {
                plugin.messages().send(p, missing, "%level%", party.def.levels().get(party.level).name());
                Sounds.cue(p, "error");
                return;
            }
        }
        if (!party.ready.add(p.getUniqueId())) {
            party.ready.remove(p.getUniqueId());
            Sounds.cue(p, "toggle-off");
            redraw(party);
            return;
        }
        Sounds.cue(p, "confirm");
        last.put(p.getUniqueId(), copy(party.selections.get(p.getUniqueId())));
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
            String missing = missingGear(party, m);
            if (missing != null) {
                // his gear changed since he got ready
                party.ready.remove(u);
                plugin.messages().send(m, missing, "%level%", party.def.levels().get(party.level).name());
                redraw(party);
                return;
            }
            players.add(m);
        }
        for (UUID u : party.members) byPlayer.remove(u);
        for (Player m : players) {
            if (m.getOpenInventory().getTopInventory().getHolder() instanceof Screen s && s.party == party) m.closeInventory();
        }
        launcher.launch(party.def, players, party.selections, party.level);
    }

    private void openPicker(Player p, Party party, int slot) {
        PetPicker picker = new PetPicker(party, p.getUniqueId(), slot);
        picker.inventory = Bukkit.createInventory(picker, SIZE, Texts.parse(plugin.messages().raw("lobby.pet-title")));
        Loadouts.Selection sel = party.selections.get(p.getUniqueId());
        int i = 0;
        for (Companions.Companion c : Companions.owned(p)) {
            if (i >= SIZE - 1) break;
            boolean taken = sel.pets().contains(c.id());
            Lore lore0 = Lore.create().kv(plugin.messages().raw("lobby.pet-level"), c.level());
            if (taken) lore0.line(plugin.messages().raw("lobby.pet-taken"));
            else lore0.left(plugin.messages().raw("lobby.pet-take"));
            List<String> lore = lore0.build();
            picker.inventory.setItem(i++, Items.dress(c.icon().clone(), c.name(), lore, p));
            picker.shown.add(c.id());
        }
        picker.inventory.setItem(SIZE - 1, Items.of("itemsadder:influitems:btn_back", plugin.messages().raw("lobby.back"), List.of(), p));
        p.openInventory(picker.inventory);
        Sounds.cue(p, "page");
    }

    private void pickPet(PetPicker picker, InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Party party = picker.party;
        if (byPlayer.get(p.getUniqueId()) != party) {
            p.closeInventory();
            return;
        }
        int raw = e.getRawSlot();
        if (raw == SIZE - 1) {
            show(p);
            return;
        }
        if (raw < 0 || raw >= picker.shown.size()) return;
        Loadouts.Selection sel = party.selections.get(p.getUniqueId());
        String id = picker.shown.get(raw);
        if (sel.pets().contains(id) || sel.pets().size() >= maxPets) return;
        sel.pets().add(id);
        changed(p, party);
        Sounds.cue(p, "click");
        show(p);
    }

    // ---------------------------------------------------------------- end of a lobby

    private void close(Party party, String key, String who) {
        for (UUID u : party.members) {
            if (byPlayer.get(u) == party) byPlayer.remove(u);
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder();
            if (h instanceof Screen s && s.party == party || h instanceof PetPicker pk && pk.party == party) p.closeInventory();
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
