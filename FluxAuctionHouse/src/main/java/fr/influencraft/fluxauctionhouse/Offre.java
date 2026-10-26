package fr.influencraft.fluxauctionhouse;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Une offre du marché, quelle que soit sa source : vente directe ou enchère de l'hôtel des ventes
 * (AuctionHouse), échoppe d'une ville ou rachat d'une échoppe (FluxChestShop). Instantané pris au
 * dernier passage du catalogue.
 *
 * @param id        identifiant de note AuctionHouse, ou « e » + identifiant FluxChestShop
 * @param prix      prix de toute l'offre (enchère : offre actuelle ou mise de départ)
 * @param quantite  objets proposés (échoppe : objets par transaction)
 * @param finDans   secondes restantes (hôtel des ventes), -1 sinon
 * @param stock     échoppe : stock (vente) ou place libre (rachat), -1 inconnu (chunk déchargé), max = illimité
 * @param prochaine enchère : offre minimale suivante
 */
record Offre(Type type, String id, ItemStack item, String cle, String nom, String index, Objets.Categorie categorie,
             double prix, int quantite, UUID vendeurId, String vendeur, long finDans, int stock,
             String ville, String villeId, double droitDePlace, Location lieu, int offres, double prochaine, long creation) {

    enum Type {
        VENTE("Vente directe", "&#FFD94D"),
        ENCHERE("Enchère", "&#E060A0"),
        ECHOPPE("Échoppe", "&#97FC79"),
        RACHAT("Rachat", "&#2BC4E8");

        final String nom, couleur;

        Type(String nom, String couleur) {
            this.nom = nom;
            this.couleur = couleur;
        }

        boolean hdv() {
            return this == VENTE || this == ENCHERE;
        }
    }

    double prixUnite() {
        return quantite > 0 ? prix / quantite : prix;
    }

    long shopId() {
        return type.hdv() ? -1 : Long.parseLong(id.substring(1));
    }
}
