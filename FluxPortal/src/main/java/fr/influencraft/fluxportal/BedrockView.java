package fr.influencraft.fluxportal;

import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.fluxportal.api.Portal;
import fr.influencraft.fluxportal.api.PortalEnd;
import org.bukkit.Axis;
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
 * What a Bedrock player sees of a portal: the window is a Java shader, so the hole is filled, for him alone, with
 * nether portal blocks sent as packets (nothing changes in the world). They stand in the blocks just behind the
 * window's plane, from the floor up, exactly where a Java player sees the far side: he walks in without jumping, and
 * the server carries him through the plane like everyone else. Only open (air, plants...) blocks are filled.
 */
final class BedrockView implements Listener {

    private static final double RANGE = 64;

    private final Supplier<Collection<Portal>> portals;
    private final JavaPlugin plugin;
    /** Player -> blocks shown to him (to put the real ones back). */
    private final Map<UUID, Set<Location>> shown = new HashMap<>();
    private int passes;

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
                if (!portal.complete() || !portal.open()) continue;
                for (int side = 1; side <= 2; side++) {
                    PortalEnd end = portal.side(side);
                    if (!end.world().equals(p.getWorld().getName()) || end.middle().distanceSquared(p.getLocation().toVector()) > RANGE * RANGE) continue;
                    BlockData block = portalBlock(end);
                    for (Location l : hole(p.getWorld(), new Frame(end, portal.width(), portal.height()))) {
                        want.add(l);
                        send.put(l, block);
                    }
                }
            }
            Set<Location> had = shown.getOrDefault(p.getUniqueId(), Set.of());
            for (Location l : had) if (!want.contains(l) && l.getWorld() == p.getWorld()) p.sendBlockChange(l, l.getBlock().getBlockData());
            Map<Location, BlockData> fresh = new HashMap<>();
            send.forEach((l, d) -> {
                if (all || !had.contains(l)) fresh.put(l, d);
            });
            if (!fresh.isEmpty()) p.sendMultiBlockChange(fresh);
            if (want.isEmpty()) shown.remove(p.getUniqueId());
            else shown.put(p.getUniqueId(), want);
        }
    }

    /** The open blocks of the hole, in the column just behind its plane. */
    static List<Location> hole(World w, Frame f) {
        List<Location> out = new ArrayList<>();
        int hw = (int) Math.round(f.halfWidth() - 0.5), height = (int) Math.round(f.halfHeight() * 2), by = f.bottomY();
        for (int i = -hw; i <= hw; i++) {
            int x = (int) Math.floor(f.worldX(i, -0.5)), z = (int) Math.floor(f.worldZ(i, -0.5));
            for (int j = 0; j < height; j++) {
                Block b = w.getBlockAt(x, by + j, z);
                if (b.isPassable() && !b.isLiquid()) out.add(b.getLocation());
            }
        }
        return out;
    }

    private static BlockData portalBlock(PortalEnd end) {
        Orientable d = (Orientable) Material.NETHER_PORTAL.createBlockData();
        // facing north or south: the hole runs along x
        d.setAxis(end.facing() % 2 == 0 ? Axis.X : Axis.Z);
        return d;
    }

    /** A portal moved or removed: what was shown is put back (the next pass shows the right blocks). */
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
