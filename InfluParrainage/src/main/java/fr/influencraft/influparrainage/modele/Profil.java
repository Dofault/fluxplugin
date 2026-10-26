package fr.influencraft.influparrainage.modele;

import java.util.List;
import java.util.Set;

/**
 * Vue en mémoire des données de parrainage d'un joueur connecté, lue par les placeholders
 * (aucune requête SQL sur le thread principal pendant l'affichage d'un menu).
 *
 * <p>Rien n'est distribué automatiquement : {@code filleulsRecompensesDonnees} et
 * {@code paliersRecuperes} suivent ce qui a déjà été récupéré ({@code /parrainage recuperer}),
 * distinct de ce qui est atteint ({@code nbFilleuls}, {@code paliersObtenus}).</p>
 *
 * @param adresse                    adresse personnelle à donner aux amis (ex : dofault.play.influencraft.fr)
 * @param parrain                    pseudo du parrain validé, ou null
 * @param parrainAttente             pseudo du parrain dont l'invitation attend le temps de jeu minimum, ou null
 * @param filleulsRecompensesDonnees nombre de récompenses de filleul (répétitives) déjà récupérées, toujours &lt;= nbFilleuls
 * @param paliersObtenus             seuils de palier atteints (récupérés ou non)
 * @param paliersRecuperes           seuils de palier déjà récupérés, sous-ensemble de paliersObtenus
 * @param filleulRecompenseEnAttente ce joueur, en tant que filleul, a une récompense d'accueil non récupérée
 * @param attente                    filleuls arrivés par l'adresse, pas encore validés
 * @param recents                    derniers filleuls validés, du plus récent au plus ancien
 */
public record Profil(
    String code,
    String adresse,
    int nbFilleuls,
    int filleulsRecompensesDonnees,
    int rang,
    String parrain,
    String parrainAttente,
    long tempsJoueAttente,
    Set<Integer> paliersObtenus,
    Set<Integer> paliersRecuperes,
    boolean filleulRecompenseEnAttente,
    List<Filleul> attente,
    List<String> recents
) {

    /** Filleul en attente : pseudo et dernier temps de jeu connu (secondes). */
    public record Filleul(String pseudo, long tempsJoue) {}
}
