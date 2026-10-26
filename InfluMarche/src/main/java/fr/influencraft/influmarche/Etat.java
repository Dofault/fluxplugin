package fr.influencraft.influmarche;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Ce qu'un joueur regarde dans les menus du marché : liste, filtres, offre choisie, brouillon de vente. */
final class Etat {

    enum Vue { MARCHE, VENTES, ENCHERES, HISTORIQUE }

    /** Taille d'une page : grille 7 x 4 du marché, 7 x 3 des autres listes. */
    static int parPage(Vue v) {
        return v == Vue.MARCHE ? 28 : 21;
    }

    Vue vue = Vue.MARCHE;
    final int[] pages = new int[Vue.values().length];

    // Marché
    String recherche = "";
    int source;
    int categorie;
    int tri;

    // Historique : 0 tout, 1 mes ventes, 2 mes achats
    int filtreHisto;

    // Offre détaillée (menu marche_offre)
    String offre;
    int quantite = 1;
    long enchere;
    /** Menu à rouvrir par le bouton Retour de l'offre. */
    String retourOffre = "marche";

    // Mise en vente (menu marche_vendre)
    int venteSlot = -1;
    ItemStack venteObjet;
    int venteQuantite = 1;
    long ventePrix;
    boolean venteEnchere;

    // Liste calculée (rendu des placeholders), recalculée si l'état change ou au plus chaque seconde
    List<Vues.Entree> liste;
    long calcul;

    int page() {
        return pages[vue.ordinal()];
    }

    void page(int p) {
        pages[vue.ordinal()] = Math.max(0, p);
        changer();
    }

    void changer() {
        liste = null;
    }

    void viderVente() {
        venteSlot = -1;
        venteObjet = null;
        venteQuantite = 1;
        ventePrix = 0;
    }
}
