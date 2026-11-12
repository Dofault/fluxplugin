package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.universe.Universes;
import fr.influencraft.fluxdungeons.combat.AttackCadence;
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
 * <p>A class with {@code basic.click: right} (the archer) swaps the two clicks: the basic attack goes on the right
 * click, <b>held down</b> (a shot each time the attack speed allows), the weapon's skill on the left click. Holding is
 * seen two ways: the client sends the use again every 4 ticks while the button is down when the item cannot be used
 * (a bow without arrows), and {@link Player#isHandRaised()} while it draws (creative, arrows in the inventory). The
 * bow's own vanilla shot never happens ({@link #onBowShot}).</p>
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
    /** Tick of the last right click of players attacking on the right click, while they hold it down. */
    private final Map<UUID, Long> holding = new HashMap<>();
    /** Ticks without a new right click after which the button is taken as released (the client repeats every 4). */
    private static final int HOLD_GAP = 5;

    Controls(org.bukkit.plugin.Plugin plugin, RpgPlayers players, Skills skills, RpgItems items) {
        this.players = players;
        this.skills = skills;
        this.items = items;
        org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, this::holdTick, 1L, 1L);
    }

    /** Players still holding the right click: the basic attack again as soon as it is ready. */
    private void holdTick() {
        if (holding.isEmpty()) return;
        long now = CombatEngine.now();
        holding.entrySet().removeIf(en -> {
            Player p = org.bukkit.Bukkit.getPlayer(en.getKey());
            if (p == null) return true;
            ItemStack hand = p.getInventory().getItemInMainHand();
            boolean held = now - en.getValue() <= HOLD_GAP || (p.isHandRaised() && p.getActiveItemHand() == EquipmentSlot.HAND);
            RpgPlayer r = held ? armed(p, hand) : null;
            if (r == null || !r.heroClass().basicOnRight()) return true;
            skills.basic(p, r, null, false);
            return false;
        });
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
        ItemStack hand = p.getInventory().getItemInMainHand();
        // swords and axes swing through the server's one swing (combat.AttackCadence, then #melee)
        if (AttackCadence.paced(hand)) return;
        RpgPlayer r = armed(p, hand);
        if (r == null) return;
        // the weapon's skill when the basic attack is on the right click
        if (r.heroClass().basicOnRight()) skills.cast(p, r, "right");
        // the clicked creature (a model's hit box stands for the creature under it)
        else skills.basic(p, r, skills.body(e.getAttacked()), false);
    }

    /**
     * A real swing of a sword or an axe (combat.AttackCadence): the class's basic attack when the player holds his own
     * class weapon. False otherwise (outside the universe, or not his weapon: a plain swing, no hit).
     */
    boolean melee(Player p, org.bukkit.entity.Entity clicked) {
        if (players.get(p) == null) return false;
        RpgPlayer r = armed(p, p.getInventory().getItemInMainHand());
        if (r == null) return false;
        skills.basic(p, r, clicked == null ? null : skills.body(clicked), true);
        return true;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() == Action.PHYSICAL) return;
        Player p = e.getPlayer();
        if (players.get(p) == null) return;
        RpgPlayer r = armed(p, e.getItem());
        if (r == null) return;
        boolean left = e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK;
        if (r.heroClass().basicOnRight()) {
            if (left) {
                e.setCancelled(true);
                skills.cast(p, r, "right");
                return;
            }
            // the bow may draw (posture of the arms, keeps the hold seen while drawing); never on a block's use
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK) e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            holding.put(p.getUniqueId(), (long) CombatEngine.now());
            skills.basic(p, r, null, false);
            return;
        }
        // the weapon's own vanilla use (drawing a bow, tilling with a hoe) never happens
        e.setCancelled(true);
        if (left) {
            // a sword or an axe swings through the arm swing (combat.AttackCadence): this event is not sent for every
            // swing (a pickable entity in reach, a block outside adventure mode)
            if (!AttackCadence.paced(e.getItem())) skills.basic(p, r, null, false);
        }
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

    /** The vanilla shot of a drawn class bow: the rpg's arrows are the basic attack's, never this one. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBowShot(org.bukkit.event.entity.EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || players.get(p) == null) return;
        if (armed(p, e.getBow()) == null) return;
        e.setCancelled(true);
        e.setConsumeItem(false);
    }

    void forget(Player p) {
        windowDrop.remove(p.getUniqueId());
        holding.remove(p.getUniqueId());
    }
}
