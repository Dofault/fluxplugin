package fr.influencraft.fluxportal;

import fr.influencraft.fluxportal.api.Portal;
import fr.influencraft.fluxportal.api.PortalEnd;
import fr.influencraft.fluxportal.api.PortalTravelEvent;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import fr.influencraft.flux.api.FluxPlugin;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Walking through a portal: the eyes going into a hole from its front carry the player out of the other side's front,
 * at the same place of the hole, turned the same way, keeping his speed (a fall or a run goes on). The same for Java
 * and Bedrock players. From behind, a side is nothing.
 */
final class Crossings implements Listener {

    /** How far inside the hole's edges the eyes must pass. */
    private static final double MARGIN = 0.15;

    private final FluxPlugin plugin;
    private final Supplier<Collection<Portal>> portals;
    private final Map<UUID, Integer> last = new HashMap<>();
    /** When a player was last told to wait (one message a second at most). */
    private final Map<UUID, Integer> warned = new HashMap<>();
    /** Effects of a crossing (where he left, where he came out). */
    private final java.util.function.BiConsumer<Location, Location> travelled;

    Crossings(FluxPlugin plugin, Supplier<Collection<Portal>> portals, java.util.function.BiConsumer<Location, Location> travelled) {
        this.plugin = plugin;
        this.portals = portals;
        this.travelled = travelled;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (from.getWorld() != to.getWorld() || from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;
        Player p = e.getPlayer();
        if (p.isInsideVehicle()) return;
        int now = Bukkit.getCurrentTick();
        String world = to.getWorld().getName();
        double eye = p.getEyeHeight();
        for (Portal portal : portals.get()) {
            if (!portal.complete() || !portal.open()) continue;
            for (int side = 1; side <= 2; side++) {
                PortalEnd end = portal.side(side);
                if (!end.world().equals(world) || end.middle().distanceSquared(to.toVector()) > 100) continue;
                Frame here = new Frame(end, portal.width(), portal.height());
                if (!here.entered(from.getX(), from.getY() + eye, from.getZ(), to.getX(), to.getY() + eye, to.getZ(), MARGIN)) continue;
                // no going back and forth: a player waits (travel-cooldown, ticks) before taking a portal again
                Integer was = last.get(p.getUniqueId());
                int wait = plugin.getConfig().getInt("travel-cooldown", 60);
                if (was != null && now - was < wait) {
                    Integer told = warned.get(p.getUniqueId());
                    if (told == null || now - told >= 20) {
                        warned.put(p.getUniqueId(), now);
                        plugin.messages().send(p, "cooldown", "%seconds%", String.valueOf((int) Math.ceil((wait - (now - was)) / 20.0)));
                    }
                    return;
                }
                last.put(p.getUniqueId(), now);
                // never teleport inside a move event: next tick
                Bukkit.getScheduler().runTask(plugin, () -> carry(p, portal, end));
                return;
            }
        }
    }

    private void carry(Player p, Portal portal, PortalEnd from) {
        if (!p.isOnline()) return;
        PortalEnd toEnd = portal.other(from);
        World w = Bukkit.getWorld(toEnd.world());
        if (w == null) return;
        Frame here = new Frame(from, portal.width(), portal.height()), there = new Frame(toEnd, portal.width(), portal.height());
        Location at = p.getLocation(), left = at.clone();
        Vector out = here.carry(there, at.getX(), at.getY(), at.getZ());
        Location to = standable(new Location(w, out.getX(), out.getY(), out.getZ(), at.getYaw() + here.turnTo(there), at.getPitch()));
        PortalTravelEvent event = new PortalTravelEvent(p, portal, from, to);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        Vector velocity = here.carryDirection(there, p.getVelocity());
        last.put(p.getUniqueId(), Bukkit.getCurrentTick());
        boolean done;
        if (event.to().getWorld() == p.getWorld()) {
            // the client keeps its own speed, turned like the player
            done = p.teleport(event.to(), PlayerTeleportEvent.TeleportCause.PLUGIN, TeleportFlag.Relative.VELOCITY_X, TeleportFlag.Relative.VELOCITY_Y,
                    TeleportFlag.Relative.VELOCITY_Z, TeleportFlag.Relative.VELOCITY_ROTATION);
        } else {
            done = p.teleport(event.to(), PlayerTeleportEvent.TeleportCause.PLUGIN);
            if (done && velocity.lengthSquared() > 0.0025) p.setVelocity(velocity);
        }
        if (done) travelled.accept(left, event.to());
    }

    /**
     * Feet a hair inside a block (an arrival computed at 64.99999) leave the client stuck until the player jumps: they
     * are put on top of that block, and -0.0 becomes 0.0.
     */
    static Location standable(Location l) {
        Block feet = l.getBlock();
        if (!feet.isPassable()) {
            double top = feet.getBoundingBox().getMaxY();
            if (top > l.getY() && top - l.getY() < 0.6) l.setY(top + 0.001);
        }
        if (l.getX() == 0) l.setX(0.0);
        if (l.getZ() == 0) l.setZ(0.0);
        return l;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        last.remove(e.getPlayer().getUniqueId());
        warned.remove(e.getPlayer().getUniqueId());
    }
}
