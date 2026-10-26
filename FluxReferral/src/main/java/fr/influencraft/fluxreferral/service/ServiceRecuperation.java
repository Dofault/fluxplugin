package fr.influencraft.fluxreferral.service;

import fr.influencraft.fluxreferral.FluxReferralPlugin;
import fr.influencraft.fluxreferral.bdd.DepotJoueur;
import fr.influencraft.fluxreferral.bdd.DepotPalier;
import fr.influencraft.fluxreferral.configuration.GestionnaireConfig;
import fr.influencraft.fluxreferral.modele.JoueurParrainage;
import fr.influencraft.fluxreferral.modele.Palier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Récupération manuelle des récompenses de parrainage : rien n'est jamais distribué tout seul.
 * Trois sources de récompenses en attente pour un joueur :
 * <ul>
 *   <li>en tant que <b>parrain</b>, une récompense par filleul (répétée, jamais limitée) ;</li>
 *   <li>en tant que <b>parrain</b>, un bonus par palier franchi (rare) ;</li>
 *   <li>en tant que <b>filleul</b>, une récompense d'accueil (une seule fois).</li>
 * </ul>
 * Chaque case du chemin du menu récupère sa propre récompense ({@link #recupererUnFilleul},
 * {@link #recupererPalier}) ; {@link #recuperer} (commande nue, sans argument) donne tout
 * d'un coup. Dans tous les cas, une unité n'est donnée que s'il reste au moins une case libre
 * dans l'inventaire, et acquittée en base (asynchrone) juste après l'exécution de ses
 * commandes — jamais avant, jamais en lot à la fin : un pépin en cours de route ne fait perdre
 * que ce qui restait, jamais redonner ce qui a déjà été accordé.
 */
public class ServiceRecuperation {

    private final FluxReferralPlugin plugin;
    private final DepotJoueur depotJoueur;
    private final DepotPalier depotPalier;
    private final GestionnaireConfig config;
    private final ServiceProfil serviceProfil;

    /** Anti double-appel : une seule récupération en vol par joueur, quelle que soit sa forme. */
    private final Set<UUID> enCours = ConcurrentHashMap.newKeySet();

    public ServiceRecuperation(FluxReferralPlugin plugin, DepotJoueur depotJoueur, DepotPalier depotPalier,
                               GestionnaireConfig config, ServiceProfil serviceProfil) {
        this.plugin = plugin;
        this.depotJoueur = depotJoueur;
        this.depotPalier = depotPalier;
        this.config = config;
        this.serviceProfil = serviceProfil;
    }

    /** Résumé d'une récupération, pour le message au joueur. */
    public record Resultat(int filleulsRecompenses, boolean filleulRecompenseRecue,
                           List<Integer> paliersRecompenses, boolean inventairePlein, boolean dejaEnCours) {
        public int total() {
            return filleulsRecompenses + (filleulRecompenseRecue ? 1 : 0) + paliersRecompenses.size();
        }

        static Resultat vide(boolean plein) {
            return new Resultat(0, false, List.of(), plein, false);
        }

        static Resultat enCoursDeja() {
            return new Resultat(0, false, List.of(), false, true);
        }
    }

    // ====================================================================
    // Récupération unité par unité (une case du chemin du menu)
    // ====================================================================

    /** Donne la prochaine récompense de filleul en attente (une seule, la suivante dans l'ordre). */
    public void recupererUnFilleul(Player joueur, Consumer<Resultat> callback) {
        UUID uuid = joueur.getUniqueId();
        if (!demarrer(uuid, callback)) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var opt = depotJoueur.trouverParUUID(uuid);
                boolean enAttente = opt.isPresent()
                    && depotJoueur.obtenirFilleulsRecompensesDonnees(uuid) < opt.get().nbFilleuls();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Resultat resultat;
                    if (!enAttente) {
                        resultat = Resultat.vide(false);
                    } else if (!aUneCaseLibre(joueur)) {
                        resultat = Resultat.vide(true);
                    } else {
                        executer(config.getCommandesParrain(), joueur.getName());
                        acquitterAsync(uuid, () -> depotJoueur.incrementerFilleulsRecompensesDonnees(uuid, 1), "récompense filleul");
                        resultat = new Resultat(1, false, List.of(), false, false);
                    }
                    terminer(uuid, resultat, callback);
                });
            } catch (SQLException e) {
                erreurLecture(uuid, joueur, e, callback);
            }
        });
    }

    /** Donne le bonus d'un palier précis, s'il est atteint et pas déjà récupéré. */
    public void recupererPalier(Player joueur, int seuil, Consumer<Resultat> callback) {
        UUID uuid = joueur.getUniqueId();
        if (!demarrer(uuid, callback)) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean enAttente = depotPalier.paliersEnAttente(uuid).contains(seuil);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Resultat resultat;
                    Palier palier = config.getPaliers().stream().filter(p -> p.filleuls() == seuil).findFirst().orElse(null);
                    if (!enAttente || palier == null) {
                        resultat = Resultat.vide(false);
                    } else if (!aUneCaseLibre(joueur)) {
                        resultat = Resultat.vide(true);
                    } else {
                        executer(palier.commandes(), joueur.getName());
                        acquitterAsync(uuid, () -> depotPalier.marquerRecupere(uuid, seuil), "palier " + seuil);
                        resultat = new Resultat(0, false, List.of(seuil), false, false);
                    }
                    terminer(uuid, resultat, callback);
                });
            } catch (SQLException e) {
                erreurLecture(uuid, joueur, e, callback);
            }
        });
    }

    // ====================================================================
    // Récupération groupée (commande nue /parrainage recuperer)
    // ====================================================================

    /**
     * Récupère tout ce qui est en attente pour {@code joueur} (parrain et filleul confondus).
     * Peut être appelée depuis le thread principal ; le résultat revient sur le thread principal.
     */
    public void recuperer(Player joueur, Consumer<Resultat> callback) {
        UUID uuid = joueur.getUniqueId();
        if (!demarrer(uuid, callback)) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var opt = depotJoueur.trouverParUUID(uuid);
                if (opt.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> terminer(uuid, Resultat.vide(false), callback));
                    return;
                }
                JoueurParrainage j = opt.get();
                int filleulsEnAttente = Math.max(0, j.nbFilleuls() - depotJoueur.obtenirFilleulsRecompensesDonnees(uuid));
                boolean filleulRecompenseEnAttente = j.parrainUuid() != null && !depotJoueur.obtenirFilleulRecompenseRecuperee(uuid);
                List<Integer> paliersEnAttente = new ArrayList<>(depotPalier.paliersEnAttente(uuid));
                paliersEnAttente.sort(Comparator.naturalOrder());

                Bukkit.getScheduler().runTask(plugin, () ->
                    donnerSurThreadPrincipal(joueur, filleulsEnAttente, filleulRecompenseEnAttente, paliersEnAttente, callback));
            } catch (SQLException e) {
                erreurLecture(uuid, joueur, e, callback);
            }
        });
    }

    /** Thread principal : vérifie l'inventaire et distribue, une unité à la fois. */
    private void donnerSurThreadPrincipal(Player joueur, int filleulsEnAttente, boolean filleulRecompenseEnAttente,
                                          List<Integer> paliersEnAttente, Consumer<Resultat> callback) {
        UUID uuid = joueur.getUniqueId();
        int filleulsRecompenses = 0;
        boolean filleulRecompenseRecue = false;
        List<Integer> paliersRecompenses = new ArrayList<>();
        boolean plein = false;

        // Ordre : accueil du filleul d'abord (unique), puis les paliers (bonus, plus rares),
        // puis les récompenses par filleul (répétitives) — pour que les cases libres servent
        // en priorité aux récompenses les plus significatives.
        if (filleulRecompenseEnAttente) {
            if (aUneCaseLibre(joueur)) {
                executer(config.getCommandesFilleul(), joueur.getName());
                filleulRecompenseRecue = true;
                acquitterAsync(uuid, () -> depotJoueur.marquerFilleulRecompenseRecuperee(uuid), "accueil filleul");
            } else {
                plein = true;
            }
        }

        if (!plein) {
            List<Palier> paliers = config.getPaliers();
            for (int seuil : paliersEnAttente) {
                if (!aUneCaseLibre(joueur)) {
                    plein = true;
                    break;
                }
                Palier palier = paliers.stream().filter(p -> p.filleuls() == seuil).findFirst().orElse(null);
                if (palier == null) continue; // palier retiré de la config entre-temps
                executer(palier.commandes(), joueur.getName());
                paliersRecompenses.add(seuil);
                final int seuilFinal = seuil;
                acquitterAsync(uuid, () -> depotPalier.marquerRecupere(uuid, seuilFinal), "palier " + seuil);
            }
        }

        if (!plein) {
            List<String> commandesParrain = config.getCommandesParrain();
            while (filleulsRecompenses < filleulsEnAttente) {
                if (!aUneCaseLibre(joueur)) {
                    plein = true;
                    break;
                }
                executer(commandesParrain, joueur.getName());
                filleulsRecompenses++;
                acquitterAsync(uuid, () -> depotJoueur.incrementerFilleulsRecompensesDonnees(uuid, 1), "récompense filleul");
            }
        }

        terminer(uuid, new Resultat(filleulsRecompenses, filleulRecompenseRecue, paliersRecompenses, plein, false), callback);
    }

    // ====================================================================

    /** @return false si une récupération est déjà en vol (callback déjà appelé dans ce cas) */
    private boolean demarrer(UUID uuid, Consumer<Resultat> callback) {
        if (!enCours.add(uuid)) {
            callback.accept(Resultat.enCoursDeja());
            return false;
        }
        return true;
    }

    private void terminer(UUID uuid, Resultat resultat, Consumer<Resultat> callback) {
        if (resultat.total() > 0) serviceProfil.recharger(uuid);
        enCours.remove(uuid);
        callback.accept(resultat);
    }

    private void erreurLecture(UUID uuid, Player joueur, SQLException e, Consumer<Resultat> callback) {
        plugin.getLogger().log(Level.SEVERE, "Erreur BDD lors de la lecture des récompenses en attente de " + joueur.getName(), e);
        Bukkit.getScheduler().runTask(plugin, () -> terminer(uuid, Resultat.vide(false), callback));
    }

    private static boolean aUneCaseLibre(Player joueur) {
        return joueur.getInventory().firstEmpty() != -1;
    }

    private static void executer(List<String> commandes, String pseudo) {
        for (String commande : commandes) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commande.replace("{joueur}", pseudo));
        }
    }

    /** Écrit l'acquittement en base de façon asynchrone, sans bloquer la distribution en cours. */
    private interface EcritureBDD {
        void ecrire() throws SQLException;
    }

    private void acquitterAsync(UUID uuid, EcritureBDD ecriture, String libelle) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ecriture.ecrire();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE,
                    "Erreur BDD lors de l'acquittement (" + libelle + ") pour " + uuid, e);
            }
        });
    }
}
