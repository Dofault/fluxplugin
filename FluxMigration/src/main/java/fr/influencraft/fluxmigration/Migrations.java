package fr.influencraft.fluxmigration;

import fr.influencraft.fluxmigration.migrations.AutoSellFilesToDatabase;
import fr.influencraft.fluxmigration.migrations.DungeonLostGearToRewards;
import fr.influencraft.fluxmigration.migrations.OfferingFilesToDatabase;
import fr.influencraft.fluxmigration.migrations.PetsEcoToDatabase;
import fr.influencraft.fluxmigration.migrations.AuctionHouseToFluxAuctionHouse;
import fr.influencraft.fluxmigration.migrations.CasinoChipsToFlux;
import fr.influencraft.fluxmigration.migrations.EcoCratesToFluxCrate;
import fr.influencraft.fluxmigration.migrations.MarriagePlusToFluxFamily;
import fr.influencraft.fluxmigration.migrations.PlayerPointsToInfluence;
import fr.influencraft.fluxmigration.migrations.PlayerWarpsToFluxPW;
import fr.influencraft.fluxmigration.migrations.PowerFlyToFluxFly;
import fr.influencraft.fluxmigration.migrations.QuickShopToFluxChestShop;

import java.util.List;

/** Every migration of the suite, oldest first. Delete a migration (and its class) once it ran everywhere. */
final class Migrations {

    private Migrations() {
    }

    static List<Migration> all(FluxMigration plugin) {
        return List.of(
                new AutoSellFilesToDatabase(plugin),
                new OfferingFilesToDatabase(plugin),
                new PetsEcoToDatabase(plugin),
                new PowerFlyToFluxFly(plugin),
                new EcoCratesToFluxCrate(plugin),
                new PlayerPointsToInfluence(plugin),
                new CasinoChipsToFlux(plugin),
                new MarriagePlusToFluxFamily(plugin),
                new PlayerWarpsToFluxPW(plugin),
                new QuickShopToFluxChestShop(plugin),
                new AuctionHouseToFluxAuctionHouse(plugin),
                new DungeonLostGearToRewards(plugin)
        );
    }
}
