package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.universe.Universes;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What every rpg character always carries, whatever happened to his inventory:
 * <ul>
 * <li>the <b>apprentice weapon</b> of his class ({@link RpgItems#starterId}): exactly one, in any slot he likes. Changing
 * class turns it into the new class's weapon where it lies; a second one, or another class's, is taken away; a missing
 * one is given back;</li>
 * <li>the <b>menu compass</b> ({@link RpgItems#MENU}) in the last hotbar slot, which never moves: a right click with
 * it, or a click on it in the inventory, opens the rpg menu.</li>
 * </ul>
 * {@link #ensure} repairs the kit from what the player really holds (inventory, armour, off hand, cursor and the 2x2
 * crafting grid): it is called when he enters the universe, changes class, after every inventory click and every few
 * seconds, so no path (a disconnection with the weapon on the cursor, a creative staff member, a cleared inventory,
 * a profile saved before the kit existed) has to be foreseen one by one. Nothing is ever destroyed to make room: an
 * item pushed out of a slot goes to a free slot, else to the player's rewards.
 */
final class RpgKit implements Listener {

    /** The compass's slot: the last of the hotbar (key 9). */
    static final int MENU_SLOT = 8;
    /** Ticks between two openings of the menu by the compass (a held right click repeats every 4 ticks). */
    private static final int OPEN_GAP = 10;

    /** Somewhere an item of the player can be. */
    private record Place(Supplier<ItemStack> get, Consumer<ItemStack> set) {
    }

    private final FluxPlugin plugin;
    private final RpgItems items;
    private final RpgPlayers players;
    private final Map<UUID, Long> opened = new HashMap<>();

    RpgKit(FluxPlugin plugin, RpgItems items, RpgPlayers players) {
        this.plugin = plugin;
        this.items = items;
        this.players = players;
    }

    /**
     * Brings the player's kit in line with his class. {@code announce}: tell him what he was given (entering, changing
     * class), silent otherwise (repairs). True when something changed.
     */
    boolean ensure(Player p, RpgPlayer r, boolean announce) {
        if (r == null || items.equipment() == null || !p.isOnline() || p.isDead() || Universes.transitioning(p)
                || !RpgWorld.UNIVERSE.equals(Universes.of(p))) return false;
        PlayerInventory inv = p.getInventory();
        List<Place> places = places(p);
        String wanted = RpgItems.starterId(r.heroClass().id());
        boolean changed = false;

        // what he holds: one weapon is kept (his class's first), every other one and every stray compass goes
        Place kept = null;
        boolean right = false;
        for (int i = 0; i < places.size(); i++) {
            Place place = places.get(i);
            String id = RpgItems.idOf(place.get().get());
            if (id == null) continue;
            if (id.equals(RpgItems.MENU)) {
                if (i != MENU_SLOT) {
                    place.set().accept(null);
                    changed = true;
                }
                continue;
            }
            if (!items.starter(id)) continue;
            Place extra = null;
            if (id.equals(wanted) && !right) {
                extra = kept;
                kept = place;
                right = true;
            } else if (kept == null) kept = place;
            else extra = place;
            if (extra != null) {
                extra.set().accept(null);
                changed = true;
            }
        }

        // the compass in its slot; what lay there moves away (the weapon is placed again below)
        ItemStack menu = RpgItems.MENU.equals(RpgItems.idOf(inv.getItem(MENU_SLOT))) ? null : items.make(RpgItems.MENU);
        if (menu != null) {
            ItemStack displaced = inv.getItem(MENU_SLOT);
            boolean weaponThere = kept == places.get(MENU_SLOT);
            inv.setItem(MENU_SLOT, menu);
            changed = true;
            if (weaponThere) {
                kept = null;
                if (right) {
                    place(p, displaced);
                    kept = places.get(MENU_SLOT);   // settled: neither replaced nor given below
                }
            } else if (displaced != null && !displaced.isEmpty()) rehome(p, displaced);
            if (announce) plugin.messages().send(p, "kit.menu.given");
            if (weaponThere && right) return true;
        }

        ItemStack weapon = right ? null : items.make(wanted);
        if (weapon == null) return changed;   // he has it, or his class has none
        if (kept != null) {
            kept.set().accept(weapon);
            if (announce) plugin.messages().send(p, "kit.replaced", "%weapon%", items.name(wanted));
        } else {
            place(p, weapon);
            if (announce) plugin.messages().send(p, "kit.weapon", "%weapon%", items.name(wanted));
        }
        return true;
    }

    /** Every place an item of his can be: the inventory (armour and off hand included), the cursor, the crafting grid. */
    private static List<Place> places(Player p) {
        PlayerInventory inv = p.getInventory();
        List<Place> out = new ArrayList<>();
        int size = inv.getContents().length;
        for (int i = 0; i < size; i++) {
            int slot = i;
            out.add(new Place(() -> inv.getItem(slot), item -> inv.setItem(slot, item)));
        }
        out.add(new Place(p::getItemOnCursor, p::setItemOnCursor));
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING) {
            // slot 0 is the result
            for (int i = 1; i < top.getSize(); i++) {
                int slot = i;
                out.add(new Place(() -> top.getItem(slot), item -> top.setItem(slot, item)));
            }
        }
        return out;
    }

    /** Puts the weapon in the first hotbar slot when it is free, else in a free slot, else in place of the first one. */
    private void place(Player p, ItemStack weapon) {
        PlayerInventory inv = p.getInventory();
        ItemStack first = inv.getItem(0);
        int slot = first == null || first.isEmpty() ? 0 : inv.firstEmpty();
        if (slot >= 0) {
            inv.setItem(slot, weapon);
            return;
        }
        inv.setItem(0, weapon);
        rehome(p, first);
    }

    /**
     * An item pushed out of its slot: a free slot, else the player's rewards. It stays an item of this universe (an
     * unmarked one, brought in by the staff, would otherwise be handed over in the vanilla one).
     */
    private void rehome(Player p, ItemStack item) {
        Universes.tag(item, RpgWorld.UNIVERSE);
        if (!Rewards.deliver(p, "dungeons", plugin.messages().raw("kit.moved-label"), List.of(item)).isEmpty()) {
            plugin.messages().send(p, "kit.moved");
        }
    }

    /** The compass was used: the rpg menu (the character's sheet). */
    void open(Player p) {
        if (Universes.transitioning(p) || players.get(p) == null) return;
        long now = CombatEngine.now();
        Long last = opened.put(p.getUniqueId(), now);
        if (last != null && now - last < OPEN_GAP) return;
        players.showSheet(p);
    }

    // ------------------------------------------------------------------ the compass stays in its slot

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || players.get(p) == null) return;
        boolean slot = e.getClickedInventory() instanceof PlayerInventory && e.getSlot() == MENU_SLOT;
        boolean compass = RpgItems.MENU.equals(RpgItems.idOf(e.getCurrentItem())) || RpgItems.MENU.equals(RpgItems.idOf(e.getCursor()));
        // the number key 9 over any slot swaps with the compass's slot
        if (!slot && !compass && e.getHotbarButton() != MENU_SLOT) return;
        e.setCancelled(true);
        if (slot && compass) Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            p.closeInventory();
            open(p);
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || players.get(p) == null) return;
        for (int raw : e.getRawSlots()) {
            if (e.getView().getInventory(raw) instanceof PlayerInventory && e.getView().convertSlot(raw) == MENU_SLOT) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** The window closed: the cursor and the crafting grid went back to the inventory, perhaps as a second weapon. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) players.kitSoon(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        opened.remove(e.getPlayer().getUniqueId());
    }
}
