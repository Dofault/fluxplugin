package fr.influencraft.influparrainage.service;

import fr.influencraft.influparrainage.InfluParrainagePlugin;
import fr.influencraft.influparrainage.bdd.DepotJoueur;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;

/**
 * Service gérant le cache en mémoire du classement des parrains.
 * Le cache est rafraîchi périodiquement et après chaque validation de parrainage.
 *
 * @author Entr0pik
 */
public class ServiceClassement {

    private final InfluParrainagePlugin plugin;
    private final DepotJoueur depotJoueur;

    // Volatile garantit la visibilité entre threads lors du remplacement de la liste
    private volatile List<Map.Entry<String, Integer>> cacheClassement = List.of();

    public ServiceClassement(InfluParrainagePlugin plugin, DepotJoueur depotJoueur) {
        this.plugin = plugin;
        this.depotJoueur = depotJoueur;
    }

    /**
     * Rafraîchit le cache depuis la base de données.
     * Doit être appelé depuis un thread asynchrone.
     */
    public void rafraichir() {
        int taille = plugin.getGestionnaireConfig().getTailleClassement();
        try {
            List<Map.Entry<String, Integer>> nouveauCache = depotJoueur.obtenirClassementAvecPseudos(taille);
            this.cacheClassement = Collections.unmodifiableList(nouveauCache);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Erreur lors du rafraîchissement du classement", e);
        }
    }

    public List<Map.Entry<String, Integer>> obtenirClassement() {
        return cacheClassement;
    }

    /** Pseudo à la position N du classement (1-indexé). Retourne "N/A" si absent. */
    public String obtenirPseudoPosition(int position) {
        if (position < 1 || position > cacheClassement.size()) return "N/A";
        return cacheClassement.get(position - 1).getKey();
    }

    /** Nombre de filleuls à la position N (1-indexé). Retourne 0 si absent. */
    public int obtenirNbFilleulsPosition(int position) {
        if (position < 1 || position > cacheClassement.size()) return 0;
        return cacheClassement.get(position - 1).getValue();
    }
}
