package fr.influencraft.influrecompenses;

import java.util.List;

/**
 * Une récompense de la config : un jour du cycle de connexion, ou un palier de temps de jeu.
 *
 * @param seuil       numéro du jour (connexion) ou temps de jeu requis en secondes
 * @param nom         titre affiché dans le menu
 * @param description lignes affichées dans le menu (couleurs &amp;, placeholders acceptés)
 * @param commandes   commandes console jouées à la récupération ({joueur} = pseudo)
 */
public record Palier(long seuil, String nom, List<String> description, List<String> commandes) {
}
