package fr.influencraft.fluxportal.api;

import java.util.Collection;
import java.util.List;

/**
 * Permanent portals of FluxPortal, for other plugins (a quest opening a way, a dungeon's exit...).
 *
 * <pre>
 *   Portals.set("donjon", 1, PortalEnd.standing("resaac", 10, 64, 20, 2, 4));
 *   Portals.set("donjon", 2, PortalEnd.standing("flux_rpg", 0, 65, 8, 0, 4));
 *   Portals.open("donjon", false);   // shut for a while: no window, no travel
 *   Portals.remove("donjon");
 * </pre>
 * Saved at once, drawn and crossable as soon as both sides exist. {@link PortalTravelEvent} tells when someone goes
 * through.
 */
public final class Portals {

    /** Implemented by FluxPortal. */
    public interface Service {
        Portal set(String name, int side, PortalEnd end);

        boolean open(String name, boolean open);

        boolean remove(String name);

        Portal get(String name);

        Collection<Portal> all();
    }

    private static volatile Service service;

    private Portals() {
    }

    public static void register(Service s) {
        service = s;
    }

    public static boolean available() {
        return service != null;
    }

    /** Places side 1 or 2 of the portal of that name (created when new, with the configured size). */
    public static Portal set(String name, int side, PortalEnd end) {
        Service s = service;
        return s == null ? null : s.set(name, side, end);
    }

    public static boolean open(String name, boolean open) {
        Service s = service;
        return s != null && s.open(name, open);
    }

    public static boolean remove(String name) {
        Service s = service;
        return s != null && s.remove(name);
    }

    public static Portal get(String name) {
        Service s = service;
        return s == null ? null : s.get(name);
    }

    public static Collection<Portal> all() {
        Service s = service;
        return s == null ? List.of() : s.all();
    }
}
