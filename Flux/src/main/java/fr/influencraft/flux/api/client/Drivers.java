package fr.influencraft.flux.api.client;

import org.bukkit.entity.Entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The player who drives a vehicle, kept first among its passengers on every client. A client lets only the first
 * passenger drive (a horse, a boat...); a plugin that sends extra passengers of its own (BetterModel puts its model's
 * displays first when the model is on the vehicle) would otherwise leave the rider unable to move. Flux reorders the
 * passengers packet of a vehicle registered here (packetevents). Used by FluxPets' mounts (a horse carrying the pet's
 * model).
 */
public final class Drivers {

    private static final Map<Integer, Integer> DRIVER_BY_VEHICLE = new ConcurrentHashMap<>();

    private Drivers() {
    }

    /** {@code driver} stays first among {@code vehicle}'s passengers on every client, until {@link #release}. */
    public static void drive(Entity vehicle, Entity driver) {
        DRIVER_BY_VEHICLE.put(vehicle.getEntityId(), driver.getEntityId());
    }

    public static void release(Entity vehicle) {
        DRIVER_BY_VEHICLE.remove(vehicle.getEntityId());
    }

    /** Entity id of the driver of that vehicle, -1 when none is registered (network thread). */
    public static int driver(int vehicleId) {
        Integer id = DRIVER_BY_VEHICLE.get(vehicleId);
        return id == null ? -1 : id;
    }
}
