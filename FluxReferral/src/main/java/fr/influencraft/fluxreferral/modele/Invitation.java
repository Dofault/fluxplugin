package fr.influencraft.fluxreferral.modele;

import java.util.UUID;

/**
 * Arrivée d'un joueur par l'adresse personnelle d'un parrain, en attente de validation
 * (le filleul doit atteindre le temps de jeu minimum).
 *
 * @param tempsJoue dernier temps de jeu connu du filleul, en secondes (pour l'affichage côté parrain)
 */
public record Invitation(
    UUID filleulUuid,
    UUID parrainUuid,
    String hote,
    long tempsJoue
) {}
