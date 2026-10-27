package fr.influencraft.fluxreferral.service;

import fr.influencraft.fluxreferral.FluxReferralPlugin;
import fr.influencraft.fluxreferral.bdd.DepotEvenement;
import fr.influencraft.fluxreferral.bdd.DepotInvitation;
import fr.influencraft.fluxreferral.bdd.DepotJoueur;
import fr.influencraft.fluxreferral.configuration.GestionnaireConfig;
import fr.influencraft.fluxreferral.configuration.GestionnaireLangue;
import fr.influencraft.fluxreferral.modele.Invitation;
import fr.influencraft.fluxreferral.modele.JoueurParrainage;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Service principal gérant la logique métier du système de parrainage.
 *
 * <p>Deux façons de devenir filleul :</p>
 * <ul>
 *   <li><b>adresse personnelle</b> : le joueur se connecte via {@code <pseudo>.<domaine>} ; une
 *   invitation est créée, puis validée automatiquement quand il atteint le temps de jeu minimum ;</li>
 *   <li><b>code</b> : {@code /parrainage utiliser <code>}, validé tout de suite si le temps de jeu
 *   minimum est déjà atteint.</li>
 * </ul>
 * Les deux chemins aboutissent à {@link #enregistrer}, seul endroit qui crée un lien.
 *
 * @author Entr0pik
 */
public class ServiceParrainage {

    /** Alphabet sans caractères ambigus visuellement (sans O, 0, I, 1). */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LONGUEUR_CODE = 6;
    private static final int MAX_TENTATIVES = 20;

    private final FluxReferralPlugin plugin;
    private final DepotJoueur depotJoueur;
    private final DepotEvenement depotEvenement;
    private final DepotInvitation depotInvitation;
    private final GestionnaireConfig config;
    private final GestionnaireLangue langue;
    private final ServicePalier servicePalier;
    private final ServiceClassement serviceClassement;
    private final ServiceAdresse serviceAdresse;
    private final ServiceProfil serviceProfil;
    private final fr.influencraft.fluxreferral.bdd.DepotDecouverte depotDecouverte;
    private final SecureRandom random;

    /** Joueurs connectés ayant une invitation en attente (parcourus par la tâche de vérification). */
    private final Set<UUID> filleulsEnAttente = ConcurrentHashMap.newKeySet();

    public ServiceParrainage(FluxReferralPlugin plugin, DepotJoueur depotJoueur,
                             DepotEvenement depotEvenement, DepotInvitation depotInvitation,
                             GestionnaireConfig config, GestionnaireLangue langue,
                             ServicePalier servicePalier, ServiceClassement serviceClassement,
                             ServiceAdresse serviceAdresse, ServiceProfil serviceProfil,
                             fr.influencraft.fluxreferral.bdd.DepotDecouverte depotDecouverte) {
        this.plugin = plugin;
        this.depotJoueur = depotJoueur;
        this.depotEvenement = depotEvenement;
        this.depotInvitation = depotInvitation;
        this.config = config;
        this.langue = langue;
        this.servicePalier = servicePalier;
        this.serviceClassement = serviceClassement;
        this.serviceAdresse = serviceAdresse;
        this.serviceProfil = serviceProfil;
        this.depotDecouverte = depotDecouverte;
        this.random = new SecureRandom();
    }

    /**
     * Génère un code unique de 6 caractères avec vérification en base avant insertion.
     * Lance une exception après MAX_TENTATIVES échecs consécutifs (problème structurel anormal).
     */
    public String genererCode() throws SQLException {
        for (int tentative = 1; tentative <= MAX_TENTATIVES; tentative++) {
            StringBuilder sb = new StringBuilder(LONGUEUR_CODE);
            for (int i = 0; i < LONGUEUR_CODE; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String code = sb.toString();
            if (!depotJoueur.existeCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException(
            "Impossible de générer un code unique après " + MAX_TENTATIVES + " tentatives."
        );
    }

    // ====================================================================
    // Arrivée par adresse personnelle
    // ====================================================================

    /**
     * Traite la connexion d'un joueur : s'il est arrivé par l'adresse d'un parrain et qu'il est
     * nouveau, crée l'invitation. Doit être appelé depuis un thread asynchrone, après la création
     * de son entrée en base.
     *
     * @param hote               adresse utilisée pour se connecter (virtual host)
     * @param premiereConnexion  première connexion connue du joueur (entrée parr_joueurs)
     * @param forcer             true pour ignorer le délai d'arrivée (commande de test admin)
     * @return le pseudo du parrain si une invitation a été créée
     */
    public Optional<String> traiterArrivee(UUID filleulUuid, String pseudoFilleul, String hote,
                                           Instant premiereConnexion, boolean forcer) throws SQLException {
        Optional<String> libelle = serviceAdresse.extraireLibelle(hote);
        if (libelle.isEmpty()) return Optional.empty();
        Optional<JoueurParrainage> optParrain = serviceAdresse.resoudre(libelle.get());
        if (optParrain.isEmpty()) return Optional.empty();
        if (inviter(filleulUuid, pseudoFilleul, optParrain.get(), hote, premiereConnexion, forcer) != Arrivee.CREEE) {
            return Optional.empty();
        }
        // arrived by a personal address: that is how he discovered the server, no questionnaire
        depotDecouverte.enregistrer(filleulUuid, "adresse", optParrain.get().uuid(), hote);
        return Optional.of(optParrain.get().pseudo());
    }

    /** Outcome of {@link #inviter}. */
    public enum Arrivee { CREEE, DEJA_PARRAINE, TROP_ANCIEN, SOI_MEME, CIRCULAIRE }

    /**
     * Makes the player a pending referee of {@code parrain} (validated after the minimum play time), whatever the way
     * he named him: personal address or questionnaire. Same rules for both. Asynchronous thread.
     *
     * @param origine address typed, or "questionnaire"
     */
    public Arrivee inviter(UUID filleulUuid, String pseudoFilleul, JoueurParrainage parrain, String origine,
                              Instant premiereConnexion, boolean forcer) throws SQLException {
        if (depotEvenement.estDejaFilleul(filleulUuid) || depotInvitation.trouver(filleulUuid).isPresent()) {
            return Arrivee.DEJA_PARRAINE;
        }
        // only new players count: an old player naming a friend brings him nothing
        Duration anciennete = Duration.between(premiereConnexion, Instant.now());
        if (!forcer && anciennete.toHours() >= config.getDelaiArriveeHeures()) return Arrivee.TROP_ANCIEN;
        if (parrain.uuid().equals(filleulUuid)) return Arrivee.SOI_MEME;
        if (config.isBloquerCirculaire() && filleulUuid.equals(parrain.parrainUuid())) return Arrivee.CIRCULAIRE;

        if (!depotInvitation.inserer(filleulUuid, parrain.uuid(), origine)) return Arrivee.DEJA_PARRAINE;
        filleulsEnAttente.add(filleulUuid);
        plugin.getLogger().info(pseudoFilleul + " est arrivé par " + parrain.pseudo()
            + " (" + origine + "), parrainage en attente du temps de jeu minimum.");

        serviceProfil.recharger(filleulUuid);
        serviceProfil.recharger(parrain.uuid());

        String temps = formaterDuree(config.getTempsJeuMinimum());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player filleul = Bukkit.getPlayer(filleulUuid);
            if (filleul != null) {
                filleul.sendMessage(langue.getMessage("arrivee-filleul",
                    Map.of("parrain", parrain.pseudo(), "temps", temps)));
            }
            Player parrainEnLigne = Bukkit.getPlayer(parrain.uuid());
            if (parrainEnLigne != null) {
                parrainEnLigne.sendMessage(langue.getMessage("arrivee-notif-parrain",
                    Map.of("joueur", pseudoFilleul, "temps", temps)));
            }
        }, 60L);
        return Arrivee.CREEE;
    }

    /** Charge l'état « en attente » d'un joueur qui se connecte. Thread asynchrone. */
    public void chargerAttente(UUID uuid) throws SQLException {
        if (depotInvitation.trouver(uuid).isPresent()) {
            filleulsEnAttente.add(uuid);
        } else {
            filleulsEnAttente.remove(uuid);
        }
    }

    public void oublierAttente(UUID uuid) {
        filleulsEnAttente.remove(uuid);
    }

    /**
     * Passage périodique (thread principal) : relève le temps de jeu des filleuls en attente
     * connectés, valide ceux qui ont atteint le minimum, puis rafraîchit les profils en cache.
     */
    public void verifierAttentes() {
        record Releve(UUID uuid, String pseudo, long secondes) {}
        List<Releve> releves = new ArrayList<>();
        for (UUID uuid : filleulsEnAttente) {
            Player joueur = Bukkit.getPlayer(uuid);
            if (joueur == null) {
                filleulsEnAttente.remove(uuid);
                continue;
            }
            releves.add(new Releve(uuid, joueur.getName(), joueur.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L));
        }
        List<UUID> enLigne = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (Releve releve : releves) {
                try {
                    depotInvitation.mettreAJourTempsJoue(releve.uuid(), releve.secondes());
                    if (releve.secondes() >= config.getTempsJeuMinimum()) {
                        validerInvitation(releve.uuid(), releve.pseudo());
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Erreur BDD lors de la vérification de " + releve.pseudo(), e);
                }
            }
            for (UUID uuid : enLigne) serviceProfil.recharger(uuid);
        });
    }

    /**
     * Transforme l'invitation d'un filleul en parrainage validé. Thread asynchrone.
     * Ne contrôle pas le temps de jeu (fait par l'appelant).
     */
    public ResultatUtilisation validerInvitation(UUID filleulUuid, String pseudoFilleul) throws SQLException {
        Optional<Invitation> optInvitation = depotInvitation.trouver(filleulUuid);
        if (optInvitation.isEmpty()) {
            filleulsEnAttente.remove(filleulUuid);
            return ResultatUtilisation.echec(ResultatUtilisation.Type.CODE_INVALIDE);
        }
        Optional<JoueurParrainage> optParrain = depotJoueur.trouverParUUID(optInvitation.get().parrainUuid());
        // L'invitation est consommée quoi qu'il arrive : un seul essai par filleul
        depotInvitation.supprimer(filleulUuid);
        filleulsEnAttente.remove(filleulUuid);
        if (optParrain.isEmpty()) {
            return ResultatUtilisation.echec(ResultatUtilisation.Type.CODE_INVALIDE);
        }
        JoueurParrainage parrain = optParrain.get();

        if (memeConnexion(parrain.uuid(), filleulUuid)) {
            plugin.getLogger().warning("Parrainage refusé : " + pseudoFilleul + " et son parrain "
                + parrain.pseudo() + " jouent depuis la même adresse IP.");
            Bukkit.getScheduler().runTask(plugin, () -> {
                envoyer(filleulUuid, "validation-refus-meme-ip", Map.of("parrain", parrain.pseudo()));
                envoyer(parrain.uuid(), "validation-refus-meme-ip-parrain", Map.of("joueur", pseudoFilleul));
            });
            serviceProfil.recharger(filleulUuid);
            serviceProfil.recharger(parrain.uuid());
            return ResultatUtilisation.echec(ResultatUtilisation.Type.MEME_IP, parrain.pseudo());
        }

        ResultatUtilisation resultat = enregistrer(parrain, filleulUuid, pseudoFilleul);
        if (resultat.type() == ResultatUtilisation.Type.SUCCES) {
            Bukkit.getScheduler().runTask(plugin, () ->
                envoyer(filleulUuid, "validation-filleul", Map.of("parrain", parrain.pseudo())));
        } else {
            serviceProfil.recharger(filleulUuid);
            serviceProfil.recharger(parrain.uuid());
        }
        return resultat;
    }

    // ====================================================================
    // Code tapé à la main
    // ====================================================================

    /**
     * Tente d'utiliser un code de parrainage.
     * Doit être appelé depuis un thread asynchrone.
     * Le contrôle du temps de jeu est effectué en amont par le gestionnaire de commande
     * (sur le thread principal où le joueur est accessible).
     *
     * @return le résultat de l'opération, avec le pseudo du parrain en cas de succès
     */
    public ResultatUtilisation utiliserCode(UUID filleulUuid, String pseudoFilleul, String code) {
        try {
            // Vérif 1 : déjà parrainé (double vérification applicative + contrainte DB)
            if (depotEvenement.estDejaFilleul(filleulUuid)) {
                return ResultatUtilisation.echec(ResultatUtilisation.Type.DEJA_PARRAINE);
            }

            // Vérif 2 : déjà arrivé par l'adresse d'un parrain
            Optional<Invitation> invitation = depotInvitation.trouver(filleulUuid);
            if (invitation.isPresent()) {
                String pseudo = depotJoueur.trouverParUUID(invitation.get().parrainUuid())
                    .map(JoueurParrainage::pseudo).orElse("?");
                return ResultatUtilisation.echec(ResultatUtilisation.Type.EN_ATTENTE, pseudo);
            }

            // Vérif 3 : le code existe en base
            Optional<JoueurParrainage> optParrain = depotJoueur.trouverParCode(code);
            if (optParrain.isEmpty()) {
                return ResultatUtilisation.echec(ResultatUtilisation.Type.CODE_INVALIDE);
            }
            JoueurParrainage parrain = optParrain.get();

            // Vérif 4 : auto-parrainage
            if (parrain.uuid().equals(filleulUuid)) {
                return ResultatUtilisation.echec(ResultatUtilisation.Type.PROPRE_CODE);
            }

            // Vérif 5 : parrainage circulaire (filleul est le parrain du parrain)
            if (config.isBloquerCirculaire() && parrain.parrainUuid() != null
                    && parrain.parrainUuid().equals(filleulUuid)) {
                return ResultatUtilisation.echec(ResultatUtilisation.Type.CIRCULAIRE);
            }

            // Vérif 6 : même connexion que le parrain (double compte)
            if (memeConnexion(parrain.uuid(), filleulUuid)) {
                return ResultatUtilisation.echec(ResultatUtilisation.Type.MEME_IP);
            }

            ResultatUtilisation resultat = enregistrer(parrain, filleulUuid, pseudoFilleul);
            if (resultat.type() == ResultatUtilisation.Type.SUCCES) {
                depotDecouverte.enregistrer(filleulUuid, "joueur", parrain.uuid(), "code");
            }
            return resultat;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE,
                "Erreur BDD lors de l'utilisation du code '" + code + "' par " + pseudoFilleul, e);
            return ResultatUtilisation.echec(ResultatUtilisation.Type.ERREUR_BDD);
        }
    }

    // ====================================================================
    // Enregistrement commun
    // ====================================================================

    /**
     * Crée le lien parrain/filleul, distribue les récompenses et déclenche les paliers.
     * Thread asynchrone ; les commandes et messages repassent sur le thread principal.
     */
    private ResultatUtilisation enregistrer(JoueurParrainage parrain, UUID filleulUuid, String pseudoFilleul)
            throws SQLException {
        if (depotEvenement.estDejaFilleul(filleulUuid)) {
            return ResultatUtilisation.echec(ResultatUtilisation.Type.DEJA_PARRAINE);
        }

        depotEvenement.insererEvenement(parrain.uuid(), filleulUuid);
        depotJoueur.mettreAJourParrain(filleulUuid, parrain.uuid());
        depotJoueur.incrementerFilleuls(parrain.uuid());

        int nbFilleuls = depotJoueur.obtenirNbFilleuls(parrain.uuid());

        // Vérification des paliers (récompense laissée en attente, cf. ServicePalier)
        servicePalier.verifierEtDeclencher(parrain.uuid(), parrain.pseudo(), nbFilleuls);

        serviceClassement.rafraichir();
        serviceProfil.recharger(parrain.uuid());
        serviceProfil.recharger(filleulUuid);

        // Récompenses laissées en attente (rien n'est distribué automatiquement) : récupération
        // manuelle via /parrainage recuperer ou le menu, cf. ServiceRecuperation.
        final UUID parrainUuid = parrain.uuid();
        final String parrainPseudo = parrain.pseudo();
        Bukkit.getScheduler().runTask(plugin, () -> envoyer(parrainUuid, "utilisation-notif-parrain", Map.of(
            "joueur", pseudoFilleul,
            "compteur", String.valueOf(nbFilleuls)
        )));

        plugin.getLogger().info("Parrainage validé : " + pseudoFilleul + " → " + parrainPseudo
            + " (" + nbFilleuls + " filleul(s)).");
        return ResultatUtilisation.succes(parrainPseudo);
    }

    /** Vrai si le contrôle est actif et que les deux joueurs ont la même dernière IP connue. */
    private boolean memeConnexion(UUID a, UUID b) throws SQLException {
        if (!config.isBloquerMemeIp()) return false;
        Optional<String> ipA = depotJoueur.obtenirIpHash(a);
        Optional<String> ipB = depotJoueur.obtenirIpHash(b);
        return ipA.isPresent() && ipA.equals(ipB);
    }

    /** Envoie un message au joueur s'il est connecté. Thread principal. */
    private void envoyer(UUID uuid, String cle, Map<String, String> variables) {
        Player joueur = Bukkit.getPlayer(uuid);
        if (joueur != null) joueur.sendMessage(langue.getMessage(cle, variables));
    }

    /**
     * Supprime le lien de parrainage d'un filleul de façon atomique et rafraîchit le classement.
     * Doit être appelé depuis un thread asynchrone.
     *
     * @return UUID du parrain décrémenté, ou Optional.empty() si aucun lien n'existait
     */
    public Optional<UUID> supprimerParrain(UUID filleulUuid) throws SQLException {
        Optional<UUID> parrainUuid = depotEvenement.supprimerLienParrainage(filleulUuid);
        if (parrainUuid.isPresent()) {
            serviceClassement.rafraichir();
            serviceProfil.recharger(parrainUuid.get());
            serviceProfil.recharger(filleulUuid);
        }
        return parrainUuid;
    }

    /** Formate une durée en secondes en chaîne lisible (ex : "1h", "30min", "45s"). */
    public String formaterDuree(long secondes) {
        if (secondes < 60) return secondes + "s";
        if (secondes < 3600) return (secondes / 60) + "min";
        long heures = secondes / 3600;
        long minutes = (secondes % 3600) / 60;
        return minutes == 0 ? heures + "h" : heures + "h" + String.format("%02d", minutes);
    }
}
