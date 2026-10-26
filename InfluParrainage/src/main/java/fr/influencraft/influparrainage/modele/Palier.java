package fr.influencraft.influparrainage.modele;

import java.util.List;

/**
 * DTO représentant un palier de récompenses tel que défini dans config.yml.
 * {@code nom} et {@code description} ne servent qu'à l'affichage (menu, placeholders).
 *
 * @author Entr0pik
 */
public record Palier(
    int filleuls,
    List<String> commandes,
    boolean annonceGlobale,
    String nom,
    List<String> description
) {}
