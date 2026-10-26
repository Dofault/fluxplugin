package fr.influencraft.influparrainage;

import fr.influencraft.influparrainage.bdd.DepotEvenement;
import fr.influencraft.influparrainage.bdd.DepotInvitation;
import fr.influencraft.influparrainage.bdd.DepotJoueur;
import fr.influencraft.influparrainage.bdd.DepotPalier;
import fr.influencraft.influparrainage.bdd.GestionnaireBDD;
import fr.influencraft.influparrainage.commande.CommandeParrainage;
import fr.influencraft.influparrainage.configuration.GestionnaireConfig;
import fr.influencraft.influparrainage.configuration.GestionnaireLangue;
import fr.influencraft.influparrainage.ecouteur.EcouteurConnexion;
import fr.influencraft.influparrainage.placeholder.ExpansionParrainage;
import fr.influencraft.influparrainage.service.EmpreinteIp;
import fr.influencraft.influparrainage.service.ServiceAdresse;
import fr.influencraft.influparrainage.service.ServiceClassement;
import fr.influencraft.influparrainage.service.ServicePalier;
import fr.influencraft.influparrainage.service.ServiceParrainage;
import fr.influencraft.influparrainage.service.ServiceProfil;
import fr.influencraft.influparrainage.service.ServiceRecuperation;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.logging.Level;

/**
 * Classe principale du plugin InfluParrainage, InfluenCraft.
 * Gère l'injection de dépendances et le cycle de vie complet du plugin.
 *
 * @author Entr0pik
 */
public class InfluParrainagePlugin extends JavaPlugin {

    private GestionnaireConfig gestionnaireConfig;
    private GestionnaireLangue gestionnaireLangue;
    private GestionnaireBDD gestionnaireBDD;
    private DepotJoueur depotJoueur;

    private ServiceClassement serviceClassement;
    private ServiceProfil serviceProfil;

    // Identifiant de la tâche périodique de rafraîchissement du classement
    private int idTacheRafraichissement = -1;

    @Override
    public void onEnable() {
        // --- Configuration ---
        gestionnaireConfig = new GestionnaireConfig(this);
        gestionnaireLangue = new GestionnaireLangue(this, gestionnaireConfig);

        // --- Base de données ---
        gestionnaireBDD = new GestionnaireBDD(this, gestionnaireConfig);
        try {
            gestionnaireBDD.initialiser();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Impossible d'initialiser la base de données. Désactivation du plugin.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // --- Dépôts ---
        depotJoueur = new DepotJoueur(this, gestionnaireBDD);
        var depotEvenement = new DepotEvenement(this, gestionnaireBDD);
        var depotPalier    = new DepotPalier(this, gestionnaireBDD);
        var depotInvitation = new DepotInvitation(this, gestionnaireBDD);

        // --- Services ---
        serviceClassement = new ServiceClassement(this, depotJoueur);
        var serviceAdresse   = new ServiceAdresse(depotJoueur, gestionnaireConfig);
        serviceProfil        = new ServiceProfil(this, depotJoueur, depotPalier, depotInvitation, serviceAdresse);
        var servicePalier    = new ServicePalier(this, depotPalier, gestionnaireConfig, gestionnaireLangue);
        var serviceParrainage = new ServiceParrainage(this, depotJoueur, depotEvenement, depotInvitation,
            gestionnaireConfig, gestionnaireLangue, servicePalier, serviceClassement, serviceAdresse, serviceProfil);
        var serviceRecuperation = new ServiceRecuperation(this, depotJoueur, depotPalier, gestionnaireConfig, serviceProfil);
        var empreinteIp      = new EmpreinteIp(this);

        // Chargement initial du classement depuis la base
        Bukkit.getScheduler().runTaskAsynchronously(this, serviceClassement::rafraichir);

        // Rafraîchissement périodique du classement
        long intervalTicks = gestionnaireConfig.getIntervaleRafraichissement() * 20L;
        idTacheRafraichissement = Bukkit.getScheduler()
            .runTaskTimerAsynchronously(this, serviceClassement::rafraichir, intervalTicks, intervalTicks)
            .getTaskId();

        // Validation automatique des arrivées par adresse (temps de jeu atteint) et rafraîchissement
        // des profils affichés dans le menu
        long intervalleVerif = gestionnaireConfig.getIntervalleVerification() * 20L;
        Bukkit.getScheduler().runTaskTimer(this, serviceParrainage::verifierAttentes, 100L, intervalleVerif);

        // --- Commandes ---
        var cmdParrainage = new CommandeParrainage(this, depotJoueur, serviceParrainage,
            servicePalier, serviceClassement, serviceProfil, serviceRecuperation, gestionnaireConfig, gestionnaireLangue);

        var bcParrainage = getCommand("parrainage");
        if (bcParrainage != null) {
            bcParrainage.setExecutor(cmdParrainage);
            bcParrainage.setTabCompleter(cmdParrainage);
        }

        // --- Écouteurs ---
        Bukkit.getPluginManager().registerEvents(
            new EcouteurConnexion(this, depotJoueur, serviceParrainage, serviceProfil, empreinteIp,
                gestionnaireConfig), this);

        // Rechargement à chaud (/reload, plugman) : joueurs déjà connectés
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            for (var joueur : Bukkit.getOnlinePlayers()) {
                try {
                    serviceParrainage.chargerAttente(joueur.getUniqueId());
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "Lecture de l'attente impossible pour " + joueur.getName(), e);
                }
                serviceProfil.recharger(joueur.getUniqueId());
            }
        });

        // --- PlaceholderAPI (soft-depend) ---
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new ExpansionParrainage(this, serviceProfil, serviceClassement, serviceParrainage).register();
            getLogger().info("Extension PlaceholderAPI enregistrée avec succès.");
        } else {
            getLogger().info("PlaceholderAPI absent — l'extension de placeholders ne sera pas enregistrée.");
        }

        getLogger().info("Plugin InfluParrainage activé avec succès (v" + getDescription().getVersion() + ").");
    }

    @Override
    public void onDisable() {
        // Annuler la tâche périodique puis toutes les tâches restantes
        if (idTacheRafraichissement != -1) {
            Bukkit.getScheduler().cancelTask(idTacheRafraichissement);
        }
        Bukkit.getScheduler().cancelTasks(this);

        if (gestionnaireBDD != null) {
            gestionnaireBDD.fermer();
        }

        getLogger().info("Plugin InfluParrainage désactivé proprement.");
    }

    /**
     * Recharge la configuration et le fichier de langue sans redémarrer le serveur.
     * Appelé par /parradmin recharger.
     */
    public void rechargerConfiguration() {
        gestionnaireConfig.charger();
        gestionnaireLangue.charger();
        // L'adresse affichée dépend du domaine configuré
        Bukkit.getScheduler().runTaskAsynchronously(this, () ->
            Bukkit.getOnlinePlayers().forEach(j -> serviceProfil.recharger(j.getUniqueId())));
        getLogger().info("Configuration et fichiers de langue rechargés.");
    }

    // --- Accesseurs ---
    public GestionnaireConfig  getGestionnaireConfig()  { return gestionnaireConfig; }
    public GestionnaireLangue  getGestionnaireLangue()  { return gestionnaireLangue; }
    public GestionnaireBDD     getGestionnaireBDD()     { return gestionnaireBDD; }
    public DepotJoueur         getDepotJoueur()         { return depotJoueur; }
    public ServiceClassement   getServiceClassement()   { return serviceClassement; }
}
