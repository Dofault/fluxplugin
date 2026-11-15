package fr.influencraft.fluxtools.modules;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.fishing.Fishing;
import fr.influencraft.flux.api.inventory.Inventories;
import fr.influencraft.flux.api.universe.Universes;
import fr.influencraft.flux.api.util.Sounds;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Nothing a player uses ever breaks: an item stops wearing at its last point of durability and is then worn out,
 * like a vanilla elytra. A worn-out item does nothing until it is repaired:
 *
 * <ul>
 *   <li>in the hand, it breaks no block, hits nothing and its right click (bow, rod, hoe, shield...) is refused;
 *   every try plays the break sound and reminds the player in the action bar;</li>
 *   <li>armour is taken off and handed back to the player (inventory, else {@code /recompenses}), and cannot be put
 *   back on: no slot holds a piece that protects from nothing. An elytra stays, vanilla already grounds it.</li>
 * </ul>
 *
 * <p>The state is read from the item itself (durability left), nothing is stored: any repair (anvil, mending,
 * {@code /repair}) makes it work again. Rods with their own durability counter follow the same rule through the
 * fishing service of Flux. Creative players are left alone, and so are the items tagged unrepairable
 * ({@link AnvilRestrictionListener}): those are meant to be used up and break as in vanilla.</p>
 */
public final class WornOutItems implements Listener {

    private static final EquipmentSlot[] ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    /** Several events answer one click: one reminder for all of them. */
    private static final long REMINDER_GAP_MS = 150;

    private final FluxPlugin plugin;
    private final String sound;
    private final Map<UUID, Long> reminded = new HashMap<>();

    WornOutItems(FluxPlugin plugin, String sound) {
        this.plugin = plugin;
        this.sound = sound;
    }

    /** Uses left before the item would break, -1 for an item that does not wear. */
    static int left(ItemStack item) {
        if (item == null || item.isEmpty()) return -1;
        if (item.getType() == Material.FISHING_ROD) {
            int own = Fishing.durabilityLeft(item);
            if (own >= 0) return own;
        }
        if (item.hasData(DataComponentTypes.UNBREAKABLE)) return -1;
        Integer max = item.getData(DataComponentTypes.MAX_DAMAGE);
        if (max == null || max <= 1) return -1;
        Integer damage = item.getData(DataComponentTypes.DAMAGE);
        return max - (damage == null ? 0 : damage);
    }

    /** Read last: only an item at its last point pays for the lore check. */
    static boolean wornOut(ItemStack item) {
        int left = left(item);
        return left >= 0 && left <= 1 && !AnvilRestrictionListener.isMarkedUnrepairable(item);
    }

    // ─── Wear: the last point is never taken ───────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWear(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        int left = left(item);
        if (left < 0) return;
        int room = left - 1;
        if (event.getDamage() < room) return;
        // an item nobody can repair is meant to be used up: it breaks as in vanilla, without a word
        if (AnvilRestrictionListener.isMarkedUnrepairable(item)) return;
        if (room <= 0) {
            event.setCancelled(true);
            return;
        }
        if (event.getDamage() > room) {
            // a rod with its own counter is worn by its plugin, which may not read the amount back from the event
            if (item.getType() == Material.FISHING_ROD && Fishing.hasCustomDurability(item)) {
                event.setCancelled(true);
                return;
            }
            event.setDamage(room);
        }
        // this use was its last one
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            Sounds.play(player, sound);
            plugin.messages().send(player, "worn-out.broken");
            takeOffArmour(player);
        });
    }

    // ─── In the hand: no effect ────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (refuse(event.getPlayer(), event.getItemInHand(), true)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (refuse(player, player.getInventory().getItemInMainHand(), true)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (event.getCause() != DamageCause.ENTITY_ATTACK && event.getCause() != DamageCause.ENTITY_SWEEP_ATTACK) return;
        if (refuse(player, player.getInventory().getItemInMainHand(), true)) event.setCancelled(true);
    }

    /** Right click: the item is not used; what the click does without it (a chest, a door) still happens. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        Player player = event.getPlayer();
        if (!refuse(player, item, false)) return;
        event.setUseItemInHand(Event.Result.DENY);
        // no reminder for a click that never was a use of the item
        Material type = item.getType();
        Block clicked = event.getClickedBlock();
        boolean noUse = Tag.ITEMS_PICKAXES.isTagged(type) || Tag.ITEMS_SWORDS.isTagged(type) || type == Material.MACE;
        boolean opensBlock = clicked != null && clicked.getType().isInteractable() && !player.isSneaking();
        if (!noUse && !opensBlock) remind(player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (refuse(event.getPlayer(), event.getItem(), true)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player) || !refuse(player, event.getBow(), true)) return;
        event.setCancelled(true);
        player.updateInventory();
    }

    private boolean refuse(Player player, ItemStack item, boolean remind) {
        if (player.getGameMode() == GameMode.CREATIVE || !wornOut(item)) return false;
        if (remind) remind(player);
        return true;
    }

    private void remind(Player player) {
        long now = System.currentTimeMillis();
        Long last = reminded.put(player.getUniqueId(), now);
        if (last != null && now - last < REMINDER_GAP_MS) return;
        Sounds.play(player, sound);
        plugin.messages().actionBar(player, "worn-out.reminder");
    }

    // ─── Armour: taken off ─────────────────────────────────────────────────────

    @EventHandler
    public void onArmourChange(PlayerArmorChangeEvent event) {
        if (!wornArmour(event.getNewItem())) return;
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> takeOffArmour(player));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> takeOffArmour(player), 20L);
    }

    /** Armour worn out by the hit that killed him is still on after a death that keeps the inventory. */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> takeOffArmour(player));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        reminded.remove(event.getPlayer().getUniqueId());
    }

    private static boolean wornArmour(ItemStack item) {
        return wornOut(item) && !item.hasData(DataComponentTypes.GLIDER);
    }

    private void takeOffArmour(Player player) {
        if (!player.isOnline() || player.isDead() || player.getGameMode() == GameMode.CREATIVE
                || Universes.transitioning(player)) return;
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> taken = new ArrayList<>();
        for (EquipmentSlot slot : ARMOUR) {
            ItemStack piece = inventory.getItem(slot);
            if (!wornArmour(piece)) continue;
            inventory.setItem(slot, null);
            taken.add(piece);
        }
        if (taken.isEmpty()) return;
        Inventories.giveOrDrop(player, taken);
        Sounds.play(player, sound);
        plugin.messages().send(player, "worn-out.armour");
    }
}
