package fr.influencraft.fluxreferral.ecouteur;

import fr.influencraft.fluxreferral.FluxReferralPlugin;
import fr.influencraft.fluxreferral.bdd.DepotJoueur;
import fr.influencraft.fluxreferral.configuration.GestionnaireConfig;
import fr.influencraft.fluxreferral.service.EmpreinteIp;
import fr.influencraft.fluxreferral.service.ServiceParrainage;
import fr.influencraft.fluxreferral.service.ServiceProfil;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.logging.Level;

/**
 * Écouteur des événements de connexion : crée l'entrée joueur en base, gère la génération du code
 * et détecte l'arrivée par l'adresse personnelle d'un parrain.
 *
 * @author Entr0pik
 */
public class EcouteurConnexion implements Listener {

    private final FluxReferralPlugin plugin;
    private final DepotJoueur depotJoueur;
    private final ServiceParrainage serviceParrainage;
    private final ServiceProfil serviceProfil;
    private final EmpreinteIp empreinteIp;
    private final GestionnaireConfig config;

    public EcouteurConnexion(FluxReferralPlugin plugin, DepotJoueur depotJoueur,
                              ServiceParrainage serviceParrainage, ServiceProfil serviceProfil,
                              EmpreinteIp empreinteIp, GestionnaireConfig config) {
        this.plugin = plugin;
        this.depotJoueur = depotJoueur;
        this.serviceParrainage = serviceParrainage;
        this.serviceProfil = serviceProfil;
        this.empreinteIp = empreinteIp;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoueurConnexion(PlayerJoinEvent event) {
        var joueur = event.getPlayer();
        var uuid = joueur.getUniqueId();
        String pseudo = joueur.getName();
        // Lus sur le thread principal. Derrière Velocity (forwarding moderne), l'IP est la vraie IP
        // du joueur et le virtual host est l'adresse qu'il a tapée, même après un /server.
        InetSocketAddress vhost = joueur.getVirtualHost();
        String hote = vhost != null ? vhost.getHostString() : null;
        String ip = joueur.getAddress() != null ? joueur.getAddress().getAddress().getHostAddress() : null;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var existant = depotJoueur.trouverParUUID(uuid);
                Instant premiereConnexion;

                if (existant.isPresent()) {
                    // Joueur connu : mise à jour du pseudo et de la date de connexion
                    depotJoueur.mettreAJourConnexion(uuid, pseudo);
                    premiereConnexion = Timestamp.valueOf(existant.get().premiereConnexion()).toInstant();
                } else {
                    // Nouveau joueur : création de l'entrée avec code généré
                    String code = serviceParrainage.genererCode();
                    depotJoueur.insererJoueur(uuid, pseudo, code);
                    premiereConnexion = Instant.now();

                    if (config.isGenerationAutoConnexion()) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            var j = Bukkit.getPlayer(uuid);
                            if (j != null && j.isOnline()) {
                                j.sendMessage(plugin.getGestionnaireLangue()
                                    .getMessage("code-genere", Map.of("code", code)));
                            }
                        });
                    }
                }

                if (ip != null) depotJoueur.mettreAJourIp(uuid, empreinteIp.calculer(ip));
                serviceParrainage.traiterArrivee(uuid, pseudo, hote, premiereConnexion, false);
                serviceParrainage.chargerAttente(uuid);
                serviceProfil.recharger(uuid);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur BDD lors de la connexion de " + pseudo, e);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoueurDeconnexion(PlayerQuitEvent event) {
        serviceProfil.retirer(event.getPlayer().getUniqueId());
        serviceParrainage.oublierAttente(event.getPlayer().getUniqueId());
    }
}
