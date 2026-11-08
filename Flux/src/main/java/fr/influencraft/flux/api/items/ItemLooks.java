package fr.influencraft.flux.api.items;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Looks that plugins of the suite give to items (3D icon, worn appearance...), whoever made the item. An item is
 * recognised by its id ({@link CustomItems#id}: suite, ItemsAdder or eco item), never by a plugin's own markers, so
 * the item stays the same item when the plugin giving the look is removed.
 *
 * <p>Flux applies every look when a player joins, opens or closes an inventory, takes an item in hand, equips armour
 * or picks an item up. Looks must be idempotent: they report whether they changed the item.</p>
 */
public final class ItemLooks {

    /** A look: changes {@code item}, whose id is {@code id}; true when the item changed. */
    @FunctionalInterface
    public interface Look {
        boolean apply(String id, ItemStack item);
    }

    private record Owned(Plugin owner, Look look) {
    }

    private static final List<Owned> LOOKS = new CopyOnWriteArrayList<>();
    /** Looks given to every item, vanilla ones included ({@link #registerAny}). */
    private static final List<Owned> ANY = new CopyOnWriteArrayList<>();
    private static final Map<Plugin, Boolean> LISTENING = new ConcurrentHashMap<>();

    private ItemLooks() {
    }

    /** Declares a look of {@code owner}, dropped when it stops; call {@link #refreshOnline()} once registered. */
    public static void register(Plugin owner, Look look) {
        LOOKS.add(new Owned(owner, look));
        Plugin flux = Bukkit.getPluginManager().getPlugin("Flux");
        if (flux != null && flux.isEnabled() && LISTENING.putIfAbsent(flux, true) == null)
            Bukkit.getPluginManager().registerEvents(new Events(), flux);
    }

    /**
     * Declares a look given to every item, vanilla items included: {@code id} is null for an item without one. For
     * rules of the whole server (weapons' attack cadence), not for an item of a plugin.
     */
    public static void registerAny(Plugin owner, Look look) {
        ANY.add(new Owned(owner, look));
        Plugin flux = Bukkit.getPluginManager().getPlugin("Flux");
        if (flux != null && flux.isEnabled() && LISTENING.putIfAbsent(flux, true) == null)
            Bukkit.getPluginManager().registerEvents(new Events(), flux);
    }

    /** Called by Flux when a plugin stops. */
    public static void unregisterAll(Plugin owner) {
        LOOKS.removeIf(o -> o.owner() == owner);
        ANY.removeIf(o -> o.owner() == owner);
    }

    /** Applies every look to the item; true when it changed. */
    public static boolean apply(ItemStack item) {
        if (LOOKS.isEmpty() && ANY.isEmpty() || item == null || item.isEmpty()) return false;
        String id = LOOKS.isEmpty() ? null : CustomItems.id(item);
        boolean changed = false;
        for (Owned o : ANY) {
            try {
                changed |= o.look().apply(id, item);
            } catch (RuntimeException e) {
                o.owner().getLogger().warning("Item look on " + item.getType() + ": " + e);
            }
        }
        if (id == null) return changed;
        for (Owned o : LOOKS) {
            try {
                changed |= o.look().apply(id, item);
            } catch (RuntimeException e) {
                o.owner().getLogger().warning("Item look on " + id + ": " + e);
            }
        }
        return changed;
    }

    /** Applies every look to the items of an inventory. */
    public static void refresh(Inventory inventory) {
        if (LOOKS.isEmpty() && ANY.isEmpty()) return;
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (apply(item)) inventory.setItem(i, item);
        }
    }

    /** Applies every look to the inventories of the online players (after a look changed). */
    public static void refreshOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p.getInventory());
    }

    private static final class Events implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void join(PlayerJoinEvent event) {
            refresh(event.getPlayer().getInventory());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void open(InventoryOpenEvent event) {
            refresh(event.getInventory());
            refresh(event.getPlayer().getInventory());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void close(InventoryCloseEvent event) {
            refresh(event.getPlayer().getInventory());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void held(PlayerItemHeldEvent event) {
            Player p = event.getPlayer();
            ItemStack item = p.getInventory().getItem(event.getNewSlot());
            if (apply(item)) p.getInventory().setItem(event.getNewSlot(), item);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void armor(PlayerArmorChangeEvent event) {
            ItemStack item = event.getPlayer().getInventory().getItem(event.getSlot());
            if (apply(item)) event.getPlayer().getInventory().setItem(event.getSlot(), item);
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void pickup(EntityPickupItemEvent event) {
            ItemStack item = event.getItem().getItemStack();
            if (apply(item)) event.getItem().setItemStack(item);
        }
    }
}
