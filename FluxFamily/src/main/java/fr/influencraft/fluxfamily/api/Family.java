package fr.influencraft.fluxfamily.api;

import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Couples and families of the players, for the other plugins of the suite (tab list, referral, migrations). Works for
 * offline players; answers come from memory, never from the database.
 *
 * <p>A family tree is made of parent links. FluxFamily owns adoptions; other plugins give their own links with
 * {@link #lineage} (FluxReferral: the sponsor of a player is his parent). A player has one parent at most: an
 * adoption first, else the first lineage that knows him.</p>
 */
public final class Family {

    public enum Status { SINGLE, COUPLE, MARRIED }

    /** Parent links kept by another plugin. Main thread, from memory. */
    public interface Lineage {
        /** The parent of a player, null when none. */
        UUID parent(UUID child);

        /** The children of a player (empty when none). */
        List<UUID> children(UUID parent);
    }

    /** Implemented by FluxFamily. */
    public interface Service {
        Status status(UUID player);

        UUID partner(UUID player);

        boolean divorced(UUID player);

        /** Records a couple (migration); false when one of them already has a partner. */
        boolean importCouple(UUID a, UUID b, boolean married, long since, Location home);

        /** Records an adoption (migration); false when the child already has a parent. */
        boolean importAdoption(UUID parent, UUID child, long since);
    }

    private record Owned(Plugin owner, Lineage lineage) {
    }

    private static final Map<String, Owned> LINEAGES = new ConcurrentHashMap<>();
    private static volatile Service service;

    private Family() {
    }

    public static void bind(Service s) {
        service = s;
    }

    public static boolean available() {
        return service != null;
    }

    public static Status status(UUID player) {
        return service == null ? Status.SINGLE : service.status(player);
    }

    public static boolean married(UUID player) {
        return status(player) == Status.MARRIED;
    }

    public static UUID partner(UUID player) {
        return service == null ? null : service.partner(player);
    }

    public static boolean divorced(UUID player) {
        return service != null && service.divorced(player);
    }

    public static boolean importCouple(UUID a, UUID b, boolean married, long since, Location home) {
        return service != null && service.importCouple(a, b, married, since, home);
    }

    public static boolean importAdoption(UUID parent, UUID child, long since) {
        return service != null && service.importAdoption(parent, child, since);
    }

    /** Gives the parent links of a plugin ({@code id}: what the link is, e.g. {@code parrainage}). */
    public static void lineage(Plugin owner, String id, Lineage lineage) {
        LINEAGES.put(id, new Owned(owner, lineage));
    }

    public static void unregisterAll(Plugin owner) {
        LINEAGES.values().removeIf(o -> o.owner() == owner);
    }

    /** The lineages of other plugins, by id. */
    public static Map<String, Lineage> lineages() {
        Map<String, Lineage> out = new java.util.LinkedHashMap<>();
        LINEAGES.forEach((id, o) -> out.put(id, o.lineage()));
        return out;
    }
}
