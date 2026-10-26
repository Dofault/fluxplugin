package fr.influencraft.influmarche;

import fr.influencraft.influlands.InfluLandsApi;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Lien facultatif avec InfluLands : ville d'une échoppe, droit de place, localisation et fiche
 * de ville. Sans InfluLands, tout renvoie « hors ville » et la localisation donne les coordonnées.
 */
final class Ville {

    private Ville() {
    }

    static boolean actif() {
        return Bukkit.getPluginManager().isPluginEnabled("InfluLands");
    }

    static String nomA(Location l) {
        try {
            return actif() ? InfluLandsApi.villeA(l) : null;
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    static String idA(Location l) {
        try {
            return actif() ? InfluLandsApi.villeIdA(l) : null;
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    static double droitA(Location l) {
        try {
            return actif() ? InfluLandsApi.droitDePlaceA(l) : 0;
        } catch (LinkageError | RuntimeException e) {
            return 0;
        }
    }

    /** Dernier stock connu d'une échoppe selon InfluLands, -1 si inconnu (ou InfluLands absent / trop ancien). */
    static int stockConnu(long shopId) {
        try {
            return actif() ? InfluLandsApi.stockConnu(shopId) : -1;
        } catch (LinkageError | RuntimeException e) {
            return -1;
        }
    }

    static boolean localiser(Player p, long shopId) {
        try {
            return actif() && InfluLandsApi.localiserEchoppe(p, shopId);
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    static boolean fiche(Player p, String villeId) {
        if (villeId == null || !actif()) return false;
        try {
            InfluLandsApi.ficheVille(p, villeId);
            return true;
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }
}
