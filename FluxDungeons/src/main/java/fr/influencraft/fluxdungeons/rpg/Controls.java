package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.universe.Universes;
import fr.influencraft.fluxdungeons.rpg.core.Equipment;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keys of the rpg, with the class weapon in hand: left click = basic attack, right click = the weapon's skill,
 * A (drop) = second skill, F (swap hands) = third skill. Only a weapon of the player's own class at his level works:
 * holding another class's weapon casts nothing, whatever the inventory tricks.
 *
 * <p>A and F are taken whatever happens to the vanilla action (Flux already cancels drops outside vanilla, so this
 * listens to cancelled events too). A drop made from the inventory window (Q on a slot, click outside) is not a cast.</p>
 */
final class Controls implements Listener {

    private final RpgPlayers players;
    private final Skills skills;
    private final RpgItems items;
    /** Tick of the last drop made from an inventory window, per player: the drop event that follows is not a cast. */
    private final Map<UUID, Long> windowDrop = new HashMap<>();

    Controls(RpgPlayers players, Skills skills, RpgItems items) {
        this.players = players;
        this.skills = skills;
        this.items = items;
    }

    /** The player's rpg state when he holds his own class weapon (usable at his level), else null. */
    private RpgPlayer armed(Player p, ItemStack hand) {
        if (Universes.transitioning(p)) return null;
        RpgPlayer r = players.get(p);
        if (r == null || r.stats() == null || p.isDead()) return null;
        Equipment eq = items.equipment();
        Equipment.Item weapon = eq == null ? null : eq.item(RpgItems.idOf(hand));
        if (weapon == null || !weapon.slot().equals("weapon") || !weapon.usableBy(r.heroClass().id()) || weapon.level() > r.level()) return null;
        return r;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        Player p = e.getPlayer();
        if (players.get(p) == null) return;
        // vanilla melee never hurts in the rpg universe: the basic attack does
        e.setCancelled(true);
        RpgPlayer r = armed(p, p.getInventory().getItemInMainHand());
        if (r != null) skills.basic(p, r, e.getAttacked() instanceof LivingEntity le ? le : null);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() == Action.PHYSICAL) return;
        Player p = e.getPlayer();
        if (players.get(p) == null) return;
        RpgPlayer r = armed(p, e.getItem());
        if (r == null) return;
        // the weapon's own vanilla use (drawing a bow, tilling with a hoe) never happens
        e.setCancelled(true);
        if (e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK) skills.basic(p, r, null);
        else skills.cast(p, r, "right");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWindowDrop(InventoryClickEvent e) {
        InventoryAction a = e.getAction();
        if (e.getWhoClicked() instanceof Player p && (a == InventoryAction.DROP_ALL_CURSOR || a == InventoryAction.DROP_ONE_CURSOR
                || a == InventoryAction.DROP_ALL_SLOT || a == InventoryAction.DROP_ONE_SLOT)) {
            windowDrop.put(p.getUniqueId(), (long) CombatEngine.now());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (players.get(p) == null) return;
        e.setCancelled(true);
        Long w = windowDrop.remove(p.getUniqueId());
        if (w != null && CombatEngine.now() - w <= 1) return;
        RpgPlayer r = armed(p, e.getItemDrop().getItemStack());
        if (r != null) skills.cast(p, r, "a");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (players.get(p) == null) return;
        // no off hand in the rpg universe: F is a skill
        e.setCancelled(true);
        RpgPlayer r = armed(p, p.getInventory().getItemInMainHand());
        if (r != null) skills.cast(p, r, "f");
    }

    void forget(Player p) {
        windowDrop.remove(p.getUniqueId());
    }
}
