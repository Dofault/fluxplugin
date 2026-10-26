package fr.influencraft.fluxauctionhouse;

import fr.influencraft.fluxlands.FluxLandsApi;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Lien facultatif avec FluxLands : ville d'une échoppe, droit de place, localisation et fiche
 * de ville. Sans FluxLands, tout renvoie « hors ville » et la localisation donne les coordonnées.
 */
final class Ville {

    private Ville() {
    }

    static boolean actif() {
        return Bukkit.getPluginManager().isPluginEnabled("FluxLands");
    }

    static String nomA(Location l) {
        try {
            return actif() ? FluxLandsApi.villeA(l) : null;
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    static String idA(Location l) {
        try {
            return actif() ? FluxLandsApi.villeIdA(l) : null;
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    static double droitA(Location l) {
        try {
            return actif() ? FluxLandsApi.droitDePlaceA(l) : 0;
        } catch (LinkageError | RuntimeException e) {
            return 0;
        }
    }

    /** Dernier stock connu d'une échoppe selon FluxLands, -1 si inconnu (ou FluxLands absent / trop ancien). */
    static int stockConnu(long shopId) {
        try {
            return actif() ? FluxLandsApi.stockConnu(shopId) : -1;
        } catch (LinkageError | RuntimeException e) {
            return -1;
        }
    }

    static boolean localiser(Player p, long shopId) {
        try {
            return actif() && FluxLandsApi.localiserEchoppe(p, shopId);
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    static boolean fiche(Player p, String villeId) {
        if (villeId == null || !actif()) return false;
        try {
            FluxLandsApi.ficheVille(p, villeId);
            return true;
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }
}
