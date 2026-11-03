package fr.influencraft.fluxpw.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Player warps of FluxPW, for the other plugins of the suite (FluxLands shows each warp with its town, FluxMigration
 * imports the former warp plugin). Main thread only.
 */
public final class PlayerWarps {

    /** A warp as other plugins see it (a copy: changing it changes nothing). */
    public record Info(String id, String name, String displayName, UUID owner, String ownerName, Location location,
                       int visits, double rating, int admission, String icon, String category, boolean open,
                       boolean password, String description, long created) {
    }

    /**
     * A warp to import. {@code world} and the coordinates are kept even when the world is not loaded; {@code icon} is a
     * custom item id ({@code namespace:id}) or a material name, null for the default icon; {@code status} is
     * {@code OPENED}, {@code CLOSED} or {@code PASSWORD_PROTECTED}.
     */
    public record Import(UUID id, String name, String displayName, UUID owner, String ownerName, String world,
                         double x, double y, double z, float yaw, float pitch, String icon, String description,
                         String category, String status, String password, int admission, int visits, int ratingSum,
                         long created, long featuredUntil, List<UUID> reviewers, List<UUID> visitors,
                         List<UUID> blocked) {
    }

    /** Implemented by FluxPW. */
    public interface Service {
        List<Info> all();

        Info byId(String id);

        Info byName(String name);

        void visit(Player player, String id);

        void create(Player player, String name);

        boolean importWarp(Import warp);

        boolean importFavorite(UUID player, UUID warp);

        /** Counter that grows whenever a warp is created, changed or deleted. */
        long version();
    }

    private static volatile Service service;

    private PlayerWarps() {
    }

    public static void bind(Service s) {
        service = s;
    }

    public static boolean available() {
        return service != null;
    }

    /** Every warp, in whatever order. */
    public static List<Info> all() {
        return service == null ? List.of() : service.all();
    }

    public static Info byId(String id) {
        return service == null || id == null ? null : service.byId(id);
    }

    /** Warp of that name (case ignored), or null. */
    public static Info byName(String name) {
        return service == null || name == null ? null : service.byName(name);
    }

    /** Sends the player to a warp with every check of /pw (password, admission fee, delay). */
    public static void visit(Player player, String id) {
        if (service != null) service.visit(player, id);
    }

    /** Creates a warp where the player stands, with every check and the price of /pw create. */
    public static void create(Player player, String name) {
        if (service != null) service.create(player, name);
    }

    /** Adds a warp; false when a warp with this id or name already exists. */
    public static boolean importWarp(Import warp) {
        return service != null && service.importWarp(warp);
    }

    /** Adds a favourite warp of a player; false when unknown warp or already there. */
    public static boolean importFavorite(UUID player, UUID warp) {
        return service != null && service.importFavorite(player, warp);
    }

    /** Grows on every change: a plugin keeping its own index of the warps rebuilds it when this moved. */
    public static long version() {
        return service == null ? 0 : service.version();
    }
}
