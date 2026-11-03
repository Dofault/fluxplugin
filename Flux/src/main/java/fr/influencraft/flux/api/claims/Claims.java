package fr.influencraft.flux.api.claims;

import org.bukkit.Location;

import java.util.UUID;

/**
 * Protected areas of the server, whatever plugin manages them (Lands today, {@code providers.claims}). Plugins of the
 * suite never call that plugin directly, except the one whose purpose is to extend it (FluxLands).
 */
public final class Claims {

    /** What an adapter provides. */
    public interface Provider {
        String name();

        /** Id of the smallest protected area at this location (a sub-area when there is one), or null. */
        String areaId(Location location);

        /** Id of the claim (town) at this location, or null. */
        String claimId(Location location);

        /** Display name of the claim at this location, or null. */
        String claimName(Location location);

        /** The player owns the claim at this location or is trusted in it; false when there is no claim. */
        default boolean member(Location location, UUID player) {
            return false;
        }

        /** The player owns the claim at this location; false when there is no claim. */
        default boolean owner(Location location, UUID player) {
            return false;
        }

        /** The player may open containers (chests, barrels) at this location; true when there is no claim. */
        default boolean mayOpenContainers(Location location, UUID player) {
            return true;
        }
    }

    private static volatile Provider provider;

    private Claims() {
    }

    /** Called by Flux. */
    public static void use(Provider p) {
        provider = p;
    }

    public static boolean available() {
        return provider != null;
    }

    public static String providerName() {
        Provider p = provider;
        return p == null ? "none" : p.name();
    }

    public static String areaId(Location location) {
        Provider p = provider;
        return p == null || location == null ? null : p.areaId(location);
    }

    public static String claimId(Location location) {
        Provider p = provider;
        return p == null || location == null ? null : p.claimId(location);
    }

    public static String claimName(Location location) {
        Provider p = provider;
        return p == null || location == null ? null : p.claimName(location);
    }

    /** The player owns or is trusted in the claim at this location (false when there is no claim or no provider). */
    public static boolean member(Location location, UUID player) {
        Provider p = provider;
        return p != null && location != null && player != null && p.member(location, player);
    }

    /** The player owns the claim at this location (false when there is no claim or no provider). */
    public static boolean owner(Location location, UUID player) {
        Provider p = provider;
        return p != null && location != null && player != null && p.owner(location, player);
    }

    /** The player may open containers at this location: true without a claim or without a provider. */
    public static boolean mayOpenContainers(Location location, UUID player) {
        Provider p = provider;
        return p == null || location == null || player == null || p.mayOpenContainers(location, player);
    }

    /** Both locations are in the same protected area. */
    public static boolean sameArea(Location a, Location b) {
        String id = areaId(a);
        return id != null && id.equals(areaId(b));
    }
}
