package fr.influencraft.influparrainage.service;

import org.jetbrains.annotations.Nullable;

/**
 * Résultat d'une tentative de parrainage (code tapé ou arrivée par adresse personnelle).
 *
 * @author Entr0pik
 */
public record ResultatUtilisation(Type type, @Nullable String pseudoParrain) {

    public enum Type {
        SUCCES,
        DEJA_PARRAINE,
        /** Le joueur est déjà arrivé par l'adresse d'un parrain, validation en cours */
        EN_ATTENTE,
        CODE_INVALIDE,
        PROPRE_CODE,
        CIRCULAIRE,
        /** Parrain et filleul jouent depuis la même connexion */
        MEME_IP,
        ERREUR_BDD
    }

    public static ResultatUtilisation succes(String pseudoParrain) {
        return new ResultatUtilisation(Type.SUCCES, pseudoParrain);
    }

    public static ResultatUtilisation echec(Type raison) {
        return new ResultatUtilisation(raison, null);
    }

    public static ResultatUtilisation echec(Type raison, String pseudoParrain) {
        return new ResultatUtilisation(raison, pseudoParrain);
    }
}
