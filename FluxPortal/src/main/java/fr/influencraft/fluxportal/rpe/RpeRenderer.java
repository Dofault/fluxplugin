package fr.influencraft.fluxportal.rpe;

import ai.resourcepack.addons.portals.api.PortalOptions;
import ai.resourcepack.addons.portals.api.PortalPair;
import ai.resourcepack.addons.portals.api.PortalPlace;
import ai.resourcepack.addons.portals.api.PortalsApi;
import fr.influencraft.fluxportal.api.Portal;
import fr.influencraft.fluxportal.api.PortalEnd;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * The 3D window of RPE-portals (ResourcePack AI, GPL-3.0) as FluxPortal's renderer: each complete portal is opened as
 * an RPE pair that never closes by itself, without RPE's sounds (FluxPortal plays its own). RPE carries travellers
 * through its window; each crossing is handed to FluxPortal first ({@code crossing}), which may refuse it or move the
 * arrival. The only class of the suite that knows RPE.
 *
 * <p>RPE keeps no pair over a restart: FluxPortal opens them again at start (its portals are the saved ones).</p>
 */
public final class RpeRenderer implements fr.influencraft.fluxportal.PortalRenderer, Listener {

    /** FluxPortal's decision on a crossing: the portal, the traveller and the arrival; null = refused. */
    private final BiFunction<Portal, Map.Entry<Player, Location>, Location> crossing;
    private final JavaPlugin plugin;
    private final Map<String, UUID> pairs = new HashMap<>();
    private final Map<UUID, Portal> byPair = new HashMap<>();

    public RpeRenderer(JavaPlugin plugin, BiFunction<Portal, Map.Entry<Player, Location>, Location> crossing) {
        this.plugin = plugin;
        this.crossing = crossing;
    }

    @Override
    public void show(Portal p) {
        hide(p);
        if (!p.complete()) return;
        PortalPlace a = place(p.entry(), p.size()), b = place(p.exit(), p.size());
        if (a == null || b == null) return;
        double size = Math.max(PortalOptions.MIN_SIZE, Math.min(PortalOptions.MAX_SIZE, p.size()));
        PortalOptions options = PortalOptions.builder().owner(PortalOptions.NO_OWNER).size(size).sounds(false)
                .travel(true).window(true).lifetimeTicks(0).startShut(false).build();
        Optional<PortalPair> pair;
        try {
            pair = PortalsApi.get().open(a, b, options);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("RPE could not open the window of " + p.name() + ": " + e);
            return;
        }
        if (pair.isEmpty()) {
            plugin.getLogger().warning("RPE refused the window of " + p.name() + " (no window map left? see RPEPortals/maps.yml)");
            return;
        }
        pairs.put(p.name(), pair.get().id());
        byPair.put(pair.get().id(), p);
    }

    @Override
    public void hide(Portal p) {
        UUID id = pairs.remove(p.name());
        if (id == null) return;
        byPair.remove(id);
        try {
            PortalsApi.get().pair(id).ifPresent(PortalPair::remove);
        } catch (RuntimeException ignored) {
            // RPE already stopped
        }
    }

    @Override
    public void stop() {
        for (String name : pairs.keySet().toArray(String[]::new)) {
            Portal p = byPair.get(pairs.get(name));
            if (p != null) hide(p);
        }
    }

    /** The ground under the ring's middle and the way its front faces: RPE's place of a ring. */
    private static PortalPlace place(PortalEnd end, double size) {
        World w = Bukkit.getWorld(end.world());
        if (w == null) return null;
        return new PortalPlace(new Location(w, end.x(), end.y() - size / 2, end.z()), end.yaw());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTravel(ai.resourcepack.addons.portals.api.event.PortalTravelEvent e) {
        Portal portal = byPair.get(e.pair().id());
        if (portal == null) return;
        Location to = crossing.apply(portal, Map.entry(e.getPlayer(), e.to()));
        if (to == null) e.setCancelled(true);
        else e.to(to);
    }
}
