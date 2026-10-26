package fr.influencraft.flux.menu;

import java.util.List;

/**
 * Chemin de progression (section paths.<id> d'un menu ou d'une page) : des pièces
 * ItemsAdder (pack influroad) dessinées dans le titre, entre les cases, qui s'allument
 * au fur et à mesure de la progression du joueur.
 *
 * @param slots    cases du chemin, dans l'ordre (voisines sur une ligne ou une colonne)
 * @param branches embranchements {départ, arrivée} vers des paliers ; allumés quand la
 *                 case de départ est atteinte
 * @param progress nombre d'étapes atteintes (placeholder accepté)
 * @param cycle    0 = pas de cycle ; sinon la progression repart à 1 après {@code cycle}
 * @param color    état allumé : vert, or, bleu ou rose
 * @param start    demi-pièce de départ avant la 1re case (allumée dès la 1re étape)
 * @param end      demi-pièce de fin après la dernière case (allumée quand tout est atteint)
 * @param nodes    cases portant un losange (allumé quand la case est atteinte)
 * @param waypoints cases de passage (écrites « ~11 » dans slots) : le chemin les traverse pour
 *                 tourner, sans en faire une étape ; elles sont atteintes avec l'étape suivante
 * @param offset   étapes retirées de la progression avant l'affichage : page 2 d'un long
 *                 chemin de 7 cases = {@code offset: 7} (étapes 8 à 14)
 */
public record PathSpec(String id, List<Integer> slots, List<int[]> branches, String progress, int cycle,
                       String color, boolean start, boolean end, List<Integer> nodes, int offset,
                       java.util.Set<Integer> waypoints) {
}
