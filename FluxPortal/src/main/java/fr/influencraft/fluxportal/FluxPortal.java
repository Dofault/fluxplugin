package fr.influencraft.fluxportal;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.fluxportal.api.Portal;
import fr.influencraft.fluxportal.api.PortalEnd;
import fr.influencraft.fluxportal.api.PortalTravelEvent;
import fr.influencraft.fluxportal.api.Portals;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permanent portals (docs/technique/fluxportal.md): two upright rings anywhere, in any worlds, linked both ways. A
 * player walking, falling or flying through one ring comes out of the other, at the same place on the ring, turned
 * the same way. Saved in Flux's database, drawn by {@link PortalVisuals}, open to other plugins through
 * {@link Portals}. Commands: {@code /portail entree|sortie <name> [size]}, {@code /portail retirer <name>},
 * {@code /portail liste}.
 */
public final class FluxPortal extends FluxPlugin implements Listener {

    /** Ticks a traveller cannot go through again (he comes out right in front of the other ring). */
    private static final int COOLDOWN = 20;
    /** Height of a player's body middle above his feet. */
    private static final double BODY = 0.9;

    private PortalStore store;
    private PortalVisuals visuals;
    private final Map<String, Portal> portals = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastTravel = new HashMap<>();
    /** Draws the 3D window and carries travellers (RPE-portals when present), else null. */
    private PortalRenderer renderer;
    private BedrockView bedrock;

    @Override
    protected void enable() {
        saveDefaultConfig();
        try {
            store = new PortalStore(getLogger());
            for (Portal p : store.load()) portals.put(p.name(), p);
        } catch (SQLException e) {
            getLogger().severe("Portals unreadable, FluxPortal is off: " + e.getMessage());
            return;
        }
        visuals = new PortalVisuals(this, () -> portals.values());
        visuals.start();
        // Bedrock players see neither the 3D window nor the swirl: nether portal blocks, for them alone
        bedrock = new BedrockView(this, () -> portals.values());
        bedrock.start();
        getServer().getPluginManager().registerEvents(bedrock, this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(visuals, this);
        Portals.register(new Portals.Service() {
            public Portal set(String name, boolean entry, PortalEnd end, double size) {
                return place(name, entry, end, size);
            }

            public boolean remove(String name) {
                return FluxPortal.this.remove(name);
            }

            public Portal get(String name) {
                return portals.get(key(name));
            }

            public Collection<Portal> all() {
                return List.copyOf(portals.values());
            }
        });
        // once every plugin is up (RPE needs RP Engine's content): the 3D windows
        Bukkit.getScheduler().runTask(this, this::startRenderer);
        Commands.create(this, "portail").description("Permanent portals: entry, exit, remove, list.")
                .permission("fluxportal.admin").usage("/portail <entree|sortie|retirer|liste> [name] [size]")
                .executor(this::command).completer((sender, args) -> complete(args)).register();
        getLogger().info(portals.size() + " portal(s)");
    }

    private void startRenderer() {
        if (!getServer().getPluginManager().isPluginEnabled("RPEPortals")) {
            getLogger().info("RPE-portals missing: flat portals (no 3D window)");
            return;
        }
        fr.influencraft.fluxportal.rpe.RpeRenderer rpe = new fr.influencraft.fluxportal.rpe.RpeRenderer(this, this::crossing);
        getServer().getPluginManager().registerEvents(rpe, this);
        renderer = rpe;
        visuals.flatSurfaces(false);
        visuals.middle((p, entry) -> renderer.middle(p, entry));
        bedrock.middle((p, entry) -> renderer.middle(p, entry));
        for (Portal p : portals.values()) renderer.show(p);
        getLogger().info("3D windows drawn by RPE-portals");
    }

    /**
     * A traveller about to go through a renderer's window: FluxPortal's event first (other plugins may refuse it or
     * move the arrival), then its effects. Null = refused.
     */
    private Location crossing(Portal portal, Map.Entry<Player, Location> who) {
        Player p = who.getKey();
        PortalTravelEvent event = new PortalTravelEvent(p, portal, standable(who.getValue().clone()));
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return null;
        lastTravel.put(p.getUniqueId(), Bukkit.getCurrentTick());
        PortalEnd from = nearest(portal, p.getLocation());
        visuals.departure(p.getLocation(), from);
        visuals.arrivalSoon(event.to(), portal.other(from));
        return event.to();
    }

    /**
     * Feet a hair inside a block (an arrival computed at 64.99999, shown -0.0 on screen) leave the client stuck until
     * the player jumps: they are put on top of that block, and -0.0 becomes 0.0.
     */
    static Location standable(Location l) {
        double y = l.getY();
        org.bukkit.block.Block feet = l.getBlock();
        if (!feet.isPassable()) {
            double top = feet.getBoundingBox().getMaxY();
            if (top > y && top - y < 0.6) l.setY(top + 0.001);
        }
        if (l.getX() == 0) l.setX(0.0);
        if (l.getZ() == 0) l.setZ(0.0);
        return l;
    }

    private static PortalEnd nearest(Portal p, Location at) {
        if (!p.entry().world().equals(at.getWorld().getName())) return p.exit();
        if (!p.exit().world().equals(at.getWorld().getName())) return p.entry();
        return p.entry().center().distanceSquared(at.toVector()) <= p.exit().center().distanceSquared(at.toVector()) ? p.entry() : p.exit();
    }

    @Override
    protected void disable() {
        if (renderer != null) renderer.stop();
        Portals.register(null);
        if (visuals != null) visuals.stop();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ portals

    Portal place(String name, boolean entry, PortalEnd end, double size) {
        String k = key(name);
        Portal old = portals.get(k);
        double s = size > 0 ? size : old != null ? old.size() : getConfig().getDouble("size", 4);
        Portal p = new Portal(k, entry ? end : old == null ? null : old.entry(), entry ? (old == null ? null : old.exit()) : end, s);
        if (!store.save(p)) return null;
        portals.put(k, p);
        visuals.refresh(p);
        if (renderer != null) renderer.show(p);
        bedrock.reset();
        return p;
    }

    boolean remove(String name) {
        Portal p = portals.remove(key(name));
        if (p == null) return false;
        store.delete(p.name());
        visuals.remove(p);
        if (renderer != null) renderer.hide(p);
        bedrock.reset();
        return true;
    }

    // ------------------------------------------------------------------ going through

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;
        // the renderer carries travellers through its own window
        if (renderer != null) return;
        Player p = e.getPlayer();
        if (p.isInsideVehicle() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        int now = Bukkit.getCurrentTick();
        Integer last = lastTravel.get(p.getUniqueId());
        if (last != null && now - last < COOLDOWN) return;
        String world = to.getWorld().getName();
        Vector a = from.toVector().add(new Vector(0, BODY, 0)), b = to.toVector().add(new Vector(0, BODY, 0));
        for (Portal portal : portals.values()) {
            if (!portal.complete()) continue;
            for (PortalEnd end : new PortalEnd[]{portal.entry(), portal.exit()}) {
                if (!end.world().equals(world) || end.center().distanceSquared(b) > 64) continue;
                if (!PortalMath.crosses(end, a, b, portal.holeRadius())) continue;
                lastTravel.put(p.getUniqueId(), now);
                Vector velocity = b.clone().subtract(a);
                // never teleport inside a move event: next tick
                Bukkit.getScheduler().runTask(this, () -> travel(p, portal, end, b, to.getYaw(), to.getPitch(), velocity));
                return;
            }
        }
    }

    private void travel(Player p, Portal portal, PortalEnd entry, Vector point, float yaw, float pitch, Vector move) {
        if (!p.isOnline() || portals.get(portal.name()) != portal) return;
        PortalEnd exit = portal.other(entry);
        World w = Bukkit.getWorld(exit.world());
        if (w == null) return;
        PortalMath.Exit out = PortalMath.travel(entry, exit, point, yaw, move);
        Location to = standable(new Location(w, out.point().getX(), out.point().getY() - BODY, out.point().getZ(), out.yaw(), pitch));
        PortalTravelEvent event = new PortalTravelEvent(p, portal, to);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        // speed kept for a fall or a flight; a walk is driven by the player's keys anyway
        Vector velocity = PortalMath.travel(entry, exit, point, yaw, p.getVelocity()).velocity();
        visuals.departure(p.getLocation(), entry);
        if (!p.teleport(event.to(), PlayerTeleportEvent.TeleportCause.PLUGIN)) return;
        lastTravel.put(p.getUniqueId(), Bukkit.getCurrentTick());
        if (velocity.lengthSquared() > 0.04) p.setVelocity(velocity);
        visuals.arrival(event.to(), exit);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastTravel.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ /portail

    private boolean command(CommandSender sender, String label, String[] args) {
        if (args.length == 0) return false;
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "entree", "entrée", "entry", "sortie", "exit" -> {
                if (!(sender instanceof Player p)) {
                    messages().send(sender, "players-only");
                    return true;
                }
                if (args.length < 2) return false;
                double size = 0;
                if (args.length > 2) {
                    try {
                        size = Double.parseDouble(args[2]);
                    } catch (NumberFormatException e) {
                        size = -1;
                    }
                    double min = getConfig().getDouble("min-size", 2), max = getConfig().getDouble("max-size", 12);
                    if (size < min || size > max) {
                        messages().send(p, "bad-size", "%min%", fmt(min), "%max%", fmt(max));
                        return true;
                    }
                }
                boolean entry = sub.startsWith("entr");
                Portal portal = place(args[1], entry, endInFront(p, size > 0 ? size : sizeOf(args[1])), size);
                if (portal == null) {
                    messages().send(p, "error");
                    return true;
                }
                messages().send(p, entry ? "entry-placed" : "exit-placed", "%name%", portal.name());
                if (!portal.complete()) messages().send(p, entry ? "missing-exit" : "missing-entry", "%name%", portal.name());
            }
            case "retirer", "remove" -> {
                if (args.length < 2) return false;
                messages().send(sender, remove(args[1]) ? "removed" : "unknown", "%name%", key(args[1]));
            }
            case "liste", "list" -> {
                if (portals.isEmpty()) {
                    messages().send(sender, "list-empty");
                    return true;
                }
                messages().send(sender, "list-header", "%count%", String.valueOf(portals.size()));
                for (Portal portal : portals.values()) {
                    messages().send(sender, "list-line", "%name%", portal.name(), "%entry%", where(portal.entry()),
                            "%exit%", where(portal.exit()), "%size%", fmt(portal.size()));
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private double sizeOf(String name) {
        Portal p = portals.get(key(name));
        return p != null ? p.size() : getConfig().getDouble("size", 4);
    }

    /** The end placed in front of the player, its bottom on the ground he stands on, its front facing him. */
    private PortalEnd endInFront(Player p, double size) {
        Location l = p.getLocation();
        Vector ahead = PortalEnd.direction(l.getYaw()).multiply(getConfig().getDouble("distance", 2.5));
        double cx = Math.round((l.getX() + ahead.getX()) * 2) / 2.0, cz = Math.round((l.getZ() + ahead.getZ()) * 2) / 2.0;
        // faces the player: front yaw is his own turned around, snapped to 45 degrees for a clean build
        float yaw = Math.round((l.getYaw() + 180) / 45f) * 45f;
        yaw = ((yaw % 360) + 540) % 360 - 180;
        return new PortalEnd(l.getWorld().getName(), cx, Math.floor(l.getY()) + size / 2, cz, yaw);
    }

    private static String where(PortalEnd e) {
        return e == null ? "-" : e.world() + " " + Math.round(e.x()) + " " + Math.round(e.y()) + " " + Math.round(e.z());
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private List<String> complete(String[] args) {
        if (args.length == 1) return List.of("entree", "sortie", "retirer", "liste");
        if (args.length == 2 && !args[0].equalsIgnoreCase("liste")) return new ArrayList<>(portals.keySet());
        return List.of();
    }
}
