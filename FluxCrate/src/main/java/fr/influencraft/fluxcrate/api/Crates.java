package fr.influencraft.fluxcrate.api;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;

/**
 * Public API of FluxCrate, for the migration of the former crates plugin (FluxMigration): write crate files and
 * placed crates, then reload. Main thread.
 */
public final class Crates {

    /** Implemented by FluxCrate. */
    public interface Service {
        boolean hasCrates();

        /** Writes {@code crates/<id>.yml}. */
        void save(String id, YamlConfiguration config) throws IOException;

        void place(Location location, String crate);

        void reload();
    }

    private static volatile Service service;

    private Crates() {
    }

    public static void bind(Service s) {
        service = s;
    }

    public static boolean available() {
        return service != null;
    }

    /** Whether FluxCrate already has crates (an import never overwrites them). */
    public static boolean hasCrates() {
        return service != null && service.hasCrates();
    }

    public static void save(String id, YamlConfiguration config) throws IOException {
        if (service != null) service.save(id, config);
    }

    public static void place(Location location, String crate) {
        if (service != null) service.place(location, crate);
    }

    public static void reload() {
        if (service != null) service.reload();
    }
}
