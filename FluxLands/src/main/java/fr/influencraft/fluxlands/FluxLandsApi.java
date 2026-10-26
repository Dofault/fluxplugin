package fr.influencraft.fluxlands;

import me.angeschossen.lands.api.land.Land;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Accès public pour les autres plugins maison (FluxAuctionHouse : marché unifié HDV + échoppes).
 * Toutes les méthodes s'appellent sur le fil principal, FluxLands activé.
 */
public final class FluxLandsApi {

    private FluxLandsApi() {
    }

    private static FluxLands plugin() {
        return JavaPlugin.getPlugin(FluxLands.class);
    }

    /** Nom de la ville qui contient ce lieu, ou null hors ville. */
    public static String villeA(Location loc) {
        Land land = loc == null ? null : plugin().villes().a(loc);
        return land == null ? null : land.getName();
    }

    /** Identifiant de la ville qui contient ce lieu, ou null hors ville. */
    public static String villeIdA(Location loc) {
        Land land = loc == null ? null : plugin().villes().a(loc);
        return land == null ? null : Villes.id(land);
    }

    /** Droit de place (en %) perçu par la ville de ce lieu, 0 hors ville ou si elle ne perçoit pas. */
    public static double droitDePlaceA(Location loc) {
        FluxLands iv = plugin();
        Land land = loc == null ? null : iv.villes().a(loc);
        if (land == null || !iv.villes().percoit(land)) return 0;
        return iv.registre().taux(Villes.id(land));
    }

    /**
     * Dernier stock connu d'une échoppe (rachat : place libre), relevé par FluxLands quand sa zone
     * était chargée. -1 si jamais relevé, Integer.MAX_VALUE si illimitée.
     */
    public static int stockConnu(long shopId) {
        FluxLands iv = plugin();
        return iv.quickShop() == null ? -1 : iv.quickShop().stockConnu(shopId);
    }

    /** Téléporte vers une échoppe (warp le plus proche, sinon spawn de la ville). False si elle est inconnue de l'index. */
    public static boolean localiserEchoppe(Player p, long shopId) {
        Commande c = plugin().commande();
        return c != null && c.localiserEchoppe(p, shopId);
    }

    /** Ouvre la fiche publique d'une ville par son identifiant. */
    public static void ficheVille(Player p, String villeId) {
        Commande c = plugin().commande();
        if (c != null) c.ficheVille(p, villeId);
    }
}
