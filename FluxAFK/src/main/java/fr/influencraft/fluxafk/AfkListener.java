package fr.influencraft.fluxafk;

import fr.influencraft.fluxafk.FluxAFK.Leave;
import fr.influencraft.fluxafk.FluxAFK.Session;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * What players do, for FluxAFK: outside the zone every real action restarts the idle time; in the zone it brings the
 * player back (and the action itself is cancelled), while what merely happens to him (damage, hunger, mobs, items on
 * the ground, being pushed) is blocked.
 */
final class AfkListener implements Listener {

    /** Further than this (blocks) from where he stood, a teleport that FluxAFK did not make took him somewhere else. */
    private static final double ELSEWHERE = 4.0;

    private final FluxAFK afk;

    AfkListener(FluxAFK afk) {
        this.afk = afk;
    }

    private static boolean moving(Input in) {
        return in.isForward() || in.isBackward() || in.isLeft() || in.isRight() || in.isJump() || in.isSneak() || in.isSprint();
    }

    /** Outside the zone: a real action. In the zone: cancelled, and the player comes back. */
    private void acted(Player p, Cancellable event) {
        if (afk.session(p) == null) {
            afk.active(p);
            return;
        }
        if (event != null) event.setCancelled(true);
        afk.leaveSoon(p);
    }

    // ---------------------------------------------------------------- moving

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInput(PlayerInputEvent e) {
        Player p = e.getPlayer();
        if (afk.session(p) == null) afk.active(p);
        else if (moving(e.getInput())) afk.leaveSoon(p); // releasing a key held since before does not count
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Session s = afk.session(p);
        if (s == null) {
            if (e.hasChangedOrientation()) afk.active(p);
            return;
        }
        if (afk.teleporting() || s.leaving) return;
        if (e.hasChangedOrientation() && afk.settled(s)) {
            afk.leaveSoon(p);
            return;
        }
        if (!e.hasChangedPosition() || s.anchor == null) return;
        if (moving(p.getCurrentInput())) {
            afk.leaveSoon(p);
            return;
        }
        // moved without touching a key: pushed by another player or by the world, he stays AFK on his place
        Location to = e.getTo();
        double tolerance = afk.driftTolerance();
        if (to.getWorld() != s.anchor.getWorld() || to.distanceSquared(s.anchor) > tolerance * tolerance) {
            Location back = s.anchor.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (afk.teleporting() || afk.session(p) == null) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getWorld() != to.getWorld() || from.distanceSquared(to) > ELSEWHERE * ELSEWHERE) afk.leave(p, Leave.STAY, true);
    }

    // ---------------------------------------------------------------- acting

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL) {
            if (afk.session(e.getPlayer()) != null) e.setCancelled(true); // a pressure plate under his feet
            return;
        }
        acted(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        acted(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent e) {
        acted(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlace(BlockPlaceEvent e) {
        acted(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent e) {
        acted(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        acted(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onHeld(PlayerItemHeldEvent e) {
        acted(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSwing(PlayerAnimationEvent e) {
        acted(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) acted(p, e);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        afk.active(p);
        if (afk.chatLeaves() && afk.session(p) != null) afk.leaveSoon(p); // leaveSoon schedules on the main thread
    }

    /** Before the command runs: /sethome or /pw create typed in the zone must apply where the player came from. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        afk.active(p);
        if (afk.session(p) == null) return;
        String message = e.getMessage();
        int space = message.indexOf(' ');
        String label = message.substring(1, space < 0 ? message.length() : space);
        if (!afk.keepsAfk(label)) afk.leave(p, Leave.BACK, true);
    }

    // ---------------------------------------------------------------- protected while AFK

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (afk.session(p) != null) {
            e.setCancelled(true);
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && afk.fallProtected(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Player attacker = e.getDamager() instanceof Player p ? p
                : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null;
        if (attacker == null) return;
        if (afk.session(attacker) != null) {
            e.setCancelled(true);
            afk.leaveSoon(attacker);
            return;
        }
        afk.combat(attacker);
        if (e.getEntity() instanceof Player victim && victim != attacker) afk.combat(victim);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && afk.session(p) != null && e.getFoodLevel() < p.getFoodLevel()) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && afk.session(p) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && afk.session(p) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        afk.leave(e.getPlayer(), Leave.STAY, false);
    }

    // ---------------------------------------------------------------- connection

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        afk.joined(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        afk.quit(e.getPlayer());
    }
}
