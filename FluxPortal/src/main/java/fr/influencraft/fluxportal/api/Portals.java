package fr.influencraft.fluxportal.api;

import java.util.Collection;
import java.util.List;

/**
 * Permanent portals of FluxPortal, for other plugins (a quest opening a way, a dungeon's exit...).
 *
 * <pre>
 *   Portals.set("donjon", true, new PortalEnd("flux_rpg", 0.5, 66, 8.5, 180f), 5);   // entry end
 *   Portals.set("donjon", false, new PortalEnd("world", 120.5, 70, -30.5, 90f), 5);  // exit end
 *   Portals.remove("donjon");
 * </pre>
 * Saved at once, drawn and crossable as soon as both ends exist, both ways. {@link PortalTravelEvent} tells when
 * someone goes through.
 */
public final class Portals {

    /** Implemented by FluxPortal. */
    public interface Service {
        Portal set(String name, boolean entry, PortalEnd end, double size);

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

    /** Places one end ({@code entry} true = the entry end) of the portal of that name, created when new. */
    public static Portal set(String name, boolean entry, PortalEnd end, double size) {
        Service s = service;
        return s == null ? null : s.set(name, entry, end, size);
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
