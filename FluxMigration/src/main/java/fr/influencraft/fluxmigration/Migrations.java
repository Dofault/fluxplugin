package fr.influencraft.fluxmigration;

import fr.influencraft.fluxmigration.migrations.PowerFlyToFluxFly;

import java.util.List;

/** Every migration of the suite, oldest first. Delete a migration (and its class) once it ran everywhere. */
final class Migrations {

    private Migrations() {
    }

    static List<Migration> all(FluxMigration plugin) {
        return List.of(
                new PowerFlyToFluxFly(plugin)
        );
    }
}
