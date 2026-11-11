package fr.influencraft.fluxportal;

import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.fluxportal.api.Portal;
import fr.influencraft.fluxportal.api.PortalEnd;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What a Bedrock player sees of a portal: neither the 3D window (a shader) nor the swirl (a display entity) reach him,
 * so the hole is filled, for him alone, with nether portal blocks sent as packets (nothing changes in the world).
 * A nether portal stands on the x or z axis only: a ring turned 45 degrees takes the nearest one. Travel is the
 * server's, the same for everyone.
 */
final class BedrockView implements Listener {

    private static final double RANGE = 48;

    private final JavaPlugin plugin;
    private final Supplier<Collection<Portal>> portals;
    /** Player -> blocks shown to him (to put the real ones back). */
    private final Map<UUID, Set<Location>> shown = new HashMap<>();
    private int passes;
    private java.util.function.BiFunction<Portal, Boolean, Location> middle = (p, entry) -> null;

    void middle(java.util.function.BiFunction<Portal, Boolean, Location> middle) {
        this.middle = middle;
    }

    BedrockView(JavaPlugin plugin, Supplier<Collection<Portal>> portals) {
        this.plugin = plugin;
        this.portals = portals;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::update, 40L, 20L);
    }

    /** Every second: each Bedrock player sees the portals near him, the others go back to the real blocks. */
    private void update() {
        // every 5 s everything is sent again (a block update from the server may have shown the real air)
        boolean all = ++passes % 5 == 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Clients.bedrock(p)) continue;
            Set<Location> want = new HashSet<>();
            Map<Location, BlockData> send = new HashMap<>();
            for (Portal portal : portals.get()) {
                if (!portal.complete()) continue;
                for (PortalEnd end : new PortalEnd[]{portal.entry(), portal.exit()}) {
                    if (!end.world().equals(p.getWorld().getName()) || end.center().distanceSquared(p.getLocation().toVector()) > RANGE * RANGE) continue;
                    Location real = middle.apply(portal, end == portal.entry());
                    PortalEnd at = real == null ? end : new PortalEnd(end.world(), real.getX(), real.getY(), real.getZ(), end.yaw());
                    for (Location l : disc(p.getWorld(), at, portal.holeRadius())) {
                        want.add(l);
                        send.put(l, portalBlock(end));
                    }
                }
            }
            Set<Location> had = shown.getOrDefault(p.getUniqueId(), Set.of());
            List<Location> restore = new ArrayList<>();
            for (Location l : had) if (!want.contains(l)) restore.add(l);
            for (Location l : restore) if (l.getWorld() == p.getWorld()) p.sendBlockChange(l, l.getBlock().getBlockData());
            Map<Location, BlockData> fresh = new HashMap<>();
            send.forEach((l, d) -> {
                if (all || !had.contains(l)) fresh.put(l, d);
            });
            if (!fresh.isEmpty()) p.sendMultiBlockChange(fresh);
            if (want.isEmpty()) shown.remove(p.getUniqueId());
            else shown.put(p.getUniqueId(), want);
        }
    }

    /** Air blocks of the ring's hole, on the axis nearest to its facing. */
    private static List<Location> disc(World w, PortalEnd end, double radius) {
        List<Location> out = new ArrayList<>();
        Vector n = end.normal();
        boolean alongX = Math.abs(n.getZ()) >= Math.abs(n.getX());   // front faces z: the portal spans x
        int cx = (int) Math.floor(end.x()), cy = (int) Math.floor(end.y()), cz = (int) Math.floor(end.z());
        int r = (int) Math.ceil(radius);
        for (int a = -r; a <= r; a++) {
            for (int dy = -r; dy <= r; dy++) {
                double ox = a + 0.5 - (alongX ? end.x() - cx : end.z() - cz), oy = dy + 0.5 - (end.y() - cy);
                if (ox * ox + oy * oy > radius * radius) continue;
                Block b = alongX ? w.getBlockAt(cx + a, cy + dy, cz) : w.getBlockAt(cx, cy + dy, cz + a);
                if (b.getType().isAir()) out.add(b.getLocation());
            }
        }
        return out;
    }

    private static BlockData portalBlock(PortalEnd end) {
        Vector n = end.normal();
        Orientable d = (Orientable) Material.NETHER_PORTAL.createBlockData();
        d.setAxis(Math.abs(n.getZ()) >= Math.abs(n.getX()) ? org.bukkit.Axis.X : org.bukkit.Axis.Z);
        return d;
    }

    /** A portal moved or removed: what was shown is forgotten (the next pass shows the right blocks). */
    void reset() {
        for (Map.Entry<UUID, Set<Location>> e : shown.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            for (Location l : e.getValue()) if (l.getWorld() == p.getWorld()) p.sendBlockChange(l, l.getBlock().getBlockData());
        }
        shown.clear();
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        shown.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        shown.remove(e.getPlayer().getUniqueId());
    }
}
