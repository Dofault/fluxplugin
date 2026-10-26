package fr.influencraft.influparrainage.modele;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO représentant les données de parrainage d'un joueur.
 *
 * @author Entr0pik
 */
public record JoueurParrainage(
    UUID uuid,
    String pseudo,
    String code,
    UUID parrainUuid,
    int nbFilleuls,
    LocalDateTime premiereConnexion,
    LocalDateTime derniereConnexion
) {}
