package fr.influencraft.fluxcommerce.auctionhouse;

import fr.influencraft.fluxlands.FluxLandsApi;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Optional link with FluxLands: town of a chest shop, its market fee, going to a shop, the town's page. Without
 * FluxLands every shop is "outside a town" and going there gives its coordinates.
 */
final class Towns {

    private Towns() {
    }

    static boolean present() {
        return Bukkit.getPluginManager().isPluginEnabled("FluxLands");
    }

    static String nameAt(Location l) {
        try {
            return present() ? FluxLandsApi.villeA(l) : null;
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    static String idAt(Location l) {
        try {
            return present() ? FluxLandsApi.villeIdA(l) : null;
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    static double feeAt(Location l) {
        try {
            return present() ? FluxLandsApi.droitDePlaceA(l) : 0;
        } catch (LinkageError | RuntimeException e) {
            return 0;
        }
    }

    /** Sends the player to a shop (nearest warp or town spawn); false when FluxLands could not. */
    static boolean goToShop(Player p, long shopId) {
        try {
            return present() && FluxLandsApi.localiserEchoppe(p, shopId);
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    /** Towns the player lives in, empty without FluxLands. */
    static List<String> of(Player p) {
        try {
            return present() ? FluxLandsApi.villesDe(p) : List.of();
        } catch (LinkageError | RuntimeException e) {
            return List.of();
        }
    }

    static boolean openPage(Player p, String townId) {
        if (townId == null || !present()) return false;
        try {
            FluxLandsApi.ficheVille(p, townId);
            return true;
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }
}
