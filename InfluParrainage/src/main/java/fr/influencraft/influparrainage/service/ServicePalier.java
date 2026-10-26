package fr.influencraft.influparrainage.service;

import fr.influencraft.influparrainage.InfluParrainagePlugin;
import fr.influencraft.influparrainage.bdd.DepotPalier;
import fr.influencraft.influparrainage.configuration.GestionnaireConfig;
import fr.influencraft.influparrainage.configuration.GestionnaireLangue;
import fr.influencraft.influparrainage.modele.Palier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Service gérant l'évaluation et le franchissement des paliers de récompenses.
 *
 * <p>Franchir un palier ({@link #verifierEtDeclencher}) ne distribue plus rien tout seul :
 * seule l'annonce sociale (atteinte, broadcast éventuel) a lieu tout de suite. La récompense
 * elle-même reste en attente jusqu'à ce que le joueur la récupère ({@link ServiceRecuperation},
 * {@code /parrainage recuperer}), qui vérifie au passage qu'il a de la place dans son inventaire.
 *
 * @author Entr0pik
 */
public class ServicePalier {

    private final InfluParrainagePlugin plugin;
    private final DepotPalier depotPalier;
    private final GestionnaireConfig config;
    private final GestionnaireLangue langue;

    public ServicePalier(InfluParrainagePlugin plugin, DepotPalier depotPalier,
                         GestionnaireConfig config, GestionnaireLangue langue) {
        this.plugin = plugin;
        this.depotPalier = depotPalier;
        this.config = config;
        this.langue = langue;
    }

    /**
     * Vérifie et enregistre tous les paliers atteints pour un parrain (récompense en attente,
     * à récupérer manuellement). Appelé depuis un thread asynchrone ; la notification et
     * l'éventuelle annonce globale repassent sur le thread principal.
     */
    public void verifierEtDeclencher(UUID parrainUuid, String pseudoParrain, int nbFilleuls) {
        List<Palier> paliers = config.getPaliers();
        for (Palier palier : paliers) {
            if (nbFilleuls >= palier.filleuls()) {
                try {
                    if (!depotPalier.palierDejaObtenu(parrainUuid, palier.filleuls())) {
                        depotPalier.enregistrerPalier(parrainUuid, palier.filleuls());
                        Bukkit.getScheduler().runTask(plugin, () -> notifierFranchissement(parrainUuid, pseudoParrain, palier));
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE,
                        "Erreur lors de la vérification du palier " + palier.filleuls()
                        + " pour " + pseudoParrain, e);
                }
            }
        }
    }

    /**
     * Force le déclenchement IMMÉDIAT d'un palier spécifique, récompense comprise (commande
     * admin de test, `/parrainage admin forcerpalier`) : contourne volontairement la file
     * d'attente, n'affecte pas l'état "récupéré" du palier en base.
     * Doit être appelé depuis le thread principal.
     *
     * @param n index 1-basé dans la liste des paliers de config
     */
    public void forcerPalier(UUID parrainUuid, String pseudoParrain, int n) {
        List<Palier> paliers = config.getPaliers();
        if (n < 1 || n > paliers.size()) return;
        Palier palier = paliers.get(n - 1);
        for (String commande : palier.commandes()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commande.replace("{joueur}", pseudoParrain));
        }
        Player parrain = Bukkit.getPlayer(parrainUuid);
        if (parrain != null && parrain.isOnline()) {
            parrain.sendMessage(langue.getMessage("admin-forcerpalier-recompense-donnee",
                Map.of("compteur", String.valueOf(palier.filleuls()))));
        }
    }

    /** Palier franchi : annonce sociale tout de suite, récompense laissée en attente. */
    private void notifierFranchissement(UUID parrainUuid, String pseudoParrain, Palier palier) {
        Player parrain = Bukkit.getPlayer(parrainUuid);
        if (parrain != null && parrain.isOnline()) {
            parrain.sendMessage(langue.getMessage("palier-atteint-joueur",
                Map.of("compteur", String.valueOf(palier.filleuls()))));
        }

        if (palier.annonceGlobale()) {
            var composant = MiniMessage.miniMessage().deserialize(
                langue.getMessageBrut("palier-atteint-annonce", Map.of(
                    "joueur", pseudoParrain,
                    "compteur", String.valueOf(palier.filleuls())
                ))
            );
            for (Player joueurEnLigne : Bukkit.getOnlinePlayers()) {
                joueurEnLigne.sendMessage(composant);
            }
        }
    }
}
