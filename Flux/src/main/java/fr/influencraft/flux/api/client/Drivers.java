package fr.influencraft.flux.api.client;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passengers of a vehicle as the clients see them (packetevents rewrites the passengers packets; Flux binds it).
 * <ul>
 *   <li>{@link #drive}: that player stays first among the vehicle's passengers on every client. A client lets only
 *   the first passenger drive (a horse, a boat...): a plugin that sends passengers of its own first (BetterModel's
 *   displays when a model is on the vehicle) would otherwise leave the rider unable to move.</li>
 *   <li>{@link #carry}: entities that only exist on the clients (a model's displays, given by their entity ids) ride
 *   the vehicle behind the driver instead of their own vehicle ({@code source}): the client moves them with the
 *   vehicle, without the server's lag. They are taken out of every passengers packet of their source while carried,
 *   given back to it by {@link #release}.</li>
 * </ul>
 * Used by FluxPets' horse mounts: the pet's model rides the horse the player drives.
 */
public final class Drivers {

    /** Sends a passengers packet to one client as it is (not rewritten again). */
    public interface Sender {
        void send(Player viewer, int vehicle, int[] passengers);
    }

    private static final class Carry {
        final Entity source;
        volatile int[] ids;

        Carry(Entity source, int[] ids) {
            this.source = source;
            this.ids = ids;
        }
    }

    private static final Map<Integer, Integer> DRIVER_BY_VEHICLE = new ConcurrentHashMap<>();
    private static final Map<Integer, Carry> CARRY_BY_VEHICLE = new ConcurrentHashMap<>();
    private static final Map<Integer, Carry> CARRY_BY_SOURCE = new ConcurrentHashMap<>();
    private static volatile Sender sender;

    private Drivers() {
    }

    /** Flux: how a passengers packet reaches a client (null without packetevents: carrying is then off). */
    public static void bind(Sender s) {
        sender = s;
    }

    /** {@code driver} stays first among {@code vehicle}'s passengers on every client, until {@link #release}. */
    public static void drive(Entity vehicle, Entity driver) {
        DRIVER_BY_VEHICLE.put(vehicle.getEntityId(), driver.getEntityId());
    }

    /**
     * Client-side entities {@code ids} (riding {@code source} until now) ride {@code vehicle} on every client, behind
     * its driver. False when Flux cannot rewrite packets (no packetevents): nothing changes then.
     */
    public static boolean carry(Entity vehicle, Entity source, int[] ids) {
        if (sender == null) return false;
        Carry c = new Carry(source, ids.clone());
        CARRY_BY_VEHICLE.put(vehicle.getEntityId(), c);
        CARRY_BY_SOURCE.put(source.getEntityId(), c);
        send(vehicle, passengersOf(vehicle, c));
        send(source, passengersOf(source, null));
        return true;
    }

    /** The carried entities again (their ids may change: a model re-rendered), sent to every client. */
    public static void refresh(Entity vehicle, int[] ids) {
        Carry c = CARRY_BY_VEHICLE.get(vehicle.getEntityId());
        if (c == null) return;
        c.ids = ids.clone();
        send(vehicle, passengersOf(vehicle, c));
        send(c.source, passengersOf(c.source, null));
    }

    /** Ends {@link #drive} and {@link #carry}: the carried entities ride their source again. */
    public static void release(Entity vehicle) {
        DRIVER_BY_VEHICLE.remove(vehicle.getEntityId());
        Carry c = CARRY_BY_VEHICLE.remove(vehicle.getEntityId());
        if (c == null) return;
        CARRY_BY_SOURCE.remove(c.source.getEntityId());
        if (c.source.isValid()) {
            List<Integer> back = new ArrayList<>();
            for (Entity p : c.source.getPassengers()) back.add(p.getEntityId());
            for (int id : c.ids) back.add(id);
            send(c.source, back.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    /** Entity id of the driver of that vehicle, -1 when none is registered (network thread). */
    public static int driver(int vehicleId) {
        Integer id = DRIVER_BY_VEHICLE.get(vehicleId);
        return id == null ? -1 : id;
    }

    /**
     * A passengers packet as the clients must get it (network thread): the driver first and the carried entities
     * behind on a vehicle, the carried entities out of their source's. Null when it stays as it is.
     */
    public static int[] rewrite(int vehicleId, int[] passengers) {
        Carry fromSource = CARRY_BY_SOURCE.get(vehicleId);
        if (fromSource != null) {
            Set<Integer> carried = set(fromSource.ids);
            int[] kept = java.util.Arrays.stream(passengers).filter(id -> !carried.contains(id)).toArray();
            return kept.length == passengers.length ? null : kept;
        }
        int driver = driver(vehicleId);
        Carry c = CARRY_BY_VEHICLE.get(vehicleId);
        if (driver < 0 && c == null) return null;
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        for (int id : passengers) if (id == driver) out.add(id);
        for (int id : passengers) out.add(id);
        if (c != null) for (int id : c.ids) out.add(id);
        int[] result = out.stream().mapToInt(Integer::intValue).toArray();
        return java.util.Arrays.equals(result, passengers) ? null : result;
    }

    private static int[] passengersOf(Entity vehicle, Carry c) {
        int[] real = vehicle.getPassengers().stream().mapToInt(Entity::getEntityId).toArray();
        int[] rewritten = rewrite(vehicle.getEntityId(), real);
        return rewritten == null ? real : rewritten;
    }

    private static void send(Entity vehicle, int[] passengers) {
        Sender s = sender;
        if (s == null || !vehicle.isValid()) return;
        Set<Player> viewers = new LinkedHashSet<>(vehicle.getTrackedBy());
        for (Entity p : vehicle.getPassengers()) if (p instanceof Player player) viewers.add(player);
        for (Player viewer : viewers) s.send(viewer, vehicle.getEntityId(), passengers);
    }

    private static Set<Integer> set(int[] ids) {
        Set<Integer> s = new java.util.HashSet<>();
        for (int id : ids) s.add(id);
        return s;
    }
}
