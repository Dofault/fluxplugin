package fr.influencraft.flux.api.client;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A camera moved by the server and seen through by players: the camera of every scene of the suite (boss scenes of
 * FluxDungeons, its class showcase). Their mouse no longer moves what they see.
 *
 * <p>The camera is an invisible display entity given a new place every tick ({@link #move}); each client glides to
 * it over {@link #GLIDE} ticks (as Typewriter's cinematics do), which smooths the steps of the server's clock out, in
 * position and in rotation. A {@link #cut} is another entity, already at its place. Things drawn on the screen
 * (displays turned to the viewer by their billboard) ride {@link #entity()}: they stay where they are on the screen
 * whatever the camera does.</p>
 *
 * <p>What a viewer's client draws through a camera that is not himself (read in the 26.2 client): no hotbar, no life
 * nor food, still his experience bar, the name of the item he holds and his own arm. Keep him seated: a player whose
 * camera is elsewhere still walks. Java only: {@link #show} is false for a Bedrock player, who keeps his own eyes.</p>
 */
public final class CameraRig {

    /** Ticks a client takes to reach the place the camera was last given. */
    public static final int GLIDE = 10;

    private final Plugin plugin;
    private final Set<UUID> viewers = new LinkedHashSet<>();
    private ItemDisplay entity;
    private int moves;

    public CameraRig(Plugin plugin, Location at) {
        this.plugin = plugin;
        this.entity = spawn(at);
    }

    private static ItemDisplay spawn(Location at) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setTeleportDuration(GLIDE);
        });
    }

    /** {@code from}, turned to look at {@code target}. */
    public static Location aim(Location from, Vector target) {
        Vector to = target.clone().subtract(from.toVector());
        if (to.lengthSquared() > 1e-6) from.setDirection(to);
        return from;
    }

    /** The camera entity of the moment (another one after a {@link #cut}). */
    public Entity entity() {
        return entity;
    }

    /** The player sees through the camera. False when he cannot (Bedrock, no packet adapter): nothing changed. */
    public boolean show(Player player) {
        if (!Camera.available() || Clients.bedrock(player)) return false;
        viewers.add(player.getUniqueId());
        Camera.view(player, entity);
        return true;
    }

    public boolean shows(Player player) {
        return viewers.contains(player.getUniqueId());
    }

    /** His own eyes again. */
    public void release(Player player) {
        if (viewers.remove(player.getUniqueId()) && player.isOnline()) Camera.view(player, null);
    }

    /** The place the camera glides to; call it every tick, even when it does not move. */
    public void move(Location at) {
        if (!entity.isValid()) return;
        entity.teleport(at, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        // a client forgets its camera when the entity is sent to it again: said again now and then
        if (++moves % 10 == 3) again();
    }

    /** Another shot: a new camera already at its place (the former one would glide across the room). */
    public void cut(Location at) {
        ItemDisplay former = entity;
        entity = spawn(at);
        for (Entity rider : former.getPassengers()) entity.addPassenger(rider);
        again();
        Bukkit.getScheduler().runTaskLater(plugin, former::remove, 2L);
    }

    private void again() {
        for (UUID id : viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) Camera.view(p, entity);
        }
    }

    /** The end: every viewer gets his own eyes back, the camera goes (what rides it is the caller's to remove). */
    public void remove() {
        for (UUID id : viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) Camera.view(p, null);
        }
        viewers.clear();
        entity.remove();
    }
}
