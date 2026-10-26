package fr.influencraft.fluxrewards;

import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;

/**
 * État d'un joueur : jours de connexion et récompenses déjà récupérées.
 * Modifié uniquement sur le thread principal ; une copie est écrite sur disque en asynchrone.
 */
public final class DonneesJoueur {

    /** Nombre de jours de connexion comptés (série en cours si la série est stricte). */
    int jours;
    /** Dernier jour de connexion compté, null avant la première connexion. */
    LocalDate dernierJour;
    /** Jours du cycle en cours déjà récupérés. */
    final Set<Integer> joursRecuperes = new TreeSet<>();
    /** Paliers de temps de jeu récupérés (seuil en secondes). */
    final Set<Long> tempsRecuperes = new TreeSet<>();
    /** Dernier temps de jeu connu (secondes), pour un joueur hors ligne. */
    long tempsJoue;

    /** Jour du cycle (1 à cycle), 0 avant la première connexion. */
    int jourDuCycle(int cycle) {
        return jours <= 0 ? 0 : (jours - 1) % cycle + 1;
    }
}
