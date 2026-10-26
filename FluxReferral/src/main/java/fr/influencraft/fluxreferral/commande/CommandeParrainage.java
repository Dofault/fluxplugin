package fr.influencraft.fluxreferral.commande;

import fr.influencraft.fluxreferral.FluxReferralPlugin;
import fr.influencraft.fluxreferral.bdd.DepotJoueur;
import fr.influencraft.fluxreferral.configuration.GestionnaireConfig;
import fr.influencraft.fluxreferral.configuration.GestionnaireLangue;
import fr.influencraft.fluxreferral.modele.JoueurParrainage;
import fr.influencraft.fluxreferral.modele.Palier;
import fr.influencraft.fluxreferral.service.ResultatUtilisation;
import fr.influencraft.fluxreferral.service.ServiceClassement;
import fr.influencraft.fluxreferral.service.ServicePalier;
import fr.influencraft.fluxreferral.service.ServiceParrainage;
import fr.influencraft.fluxreferral.service.ServiceProfil;
import fr.influencraft.fluxreferral.service.ServiceRecuperation;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Gestionnaire unique de la commande /parrainage — sous-commandes joueur et admin intégrées.
 * Les commandes admin sont accessibles via /parrainage admin [sous-commande].
 * La console peut exécuter toutes les sous-commandes admin sans vérification de permission.
 *
 * @author Entr0pik
 */
public class CommandeParrainage implements CommandExecutor, TabCompleter {

    // --- Entrées de l'aide ---
    private record EntreeAide(String commande, String permission, String cleDescription) {}

    private static final List<EntreeAide> COMMANDES_JOUEUR = List.of(
        new EntreeAide("/parrainage adresse",                "parrainage.commande.code",        "aide-desc-adresse"),
        new EntreeAide("/parrainage code",                   "parrainage.commande.code",        "aide-desc-code"),
        new EntreeAide("/parrainage utiliser \\<code>",      "parrainage.commande.utiliser",    "aide-desc-utiliser"),
        new EntreeAide("/parrainage info",                   "parrainage.commande.info.soi",    "aide-desc-info-soi"),
        new EntreeAide("/parrainage info \\<joueur>",        "parrainage.commande.info.autres", "aide-desc-info-autres"),
        new EntreeAide("/parrainage top",                    "parrainage.commande.top",         "aide-desc-top"),
        new EntreeAide("/parrainage recuperer",              "parrainage.commande.recuperer",   "aide-desc-recuperer")
    );

    private static final List<EntreeAide> COMMANDES_ADMIN = List.of(
        new EntreeAide("/parrainage admin recharger",                              "parrainage.admin.recharger",      "aide-admin-desc-recharger"),
        new EntreeAide("/parrainage admin reinitialiser \\<joueur>",               "parrainage.admin.reinitialiser",  "aide-admin-desc-reinitialiser"),
        new EntreeAide("/parrainage admin definirparrain \\<joueur> \\<parrain>",  "parrainage.admin.definirparrain", "aide-admin-desc-definirparrain"),
        new EntreeAide("/parrainage admin supprimerparrain \\<joueur>",            "parrainage.admin.supprimerparrain","aide-admin-desc-supprimerparrain"),
        new EntreeAide("/parrainage admin forcerpalier \\<joueur> \\<n>",          "parrainage.admin.forcerpalier",   "aide-admin-desc-forcerpalier"),
        new EntreeAide("/parrainage admin info \\<joueur>",                        "parrainage.admin.info",           "aide-admin-desc-info"),
        new EntreeAide("/parrainage admin simuler \\<joueur> \\<adresse>",         "parrainage.admin.simuler",        "aide-admin-desc-simuler"),
        new EntreeAide("/parrainage admin valider \\<joueur>",                     "parrainage.admin.valider",        "aide-admin-desc-valider")
    );

    private final FluxReferralPlugin plugin;
    private final DepotJoueur depotJoueur;
    private final ServiceParrainage serviceParrainage;
    private final ServicePalier servicePalier;
    private final ServiceClassement serviceClassement;
    private final ServiceProfil serviceProfil;
    private final ServiceRecuperation serviceRecuperation;
    private final GestionnaireConfig config;
    private final GestionnaireLangue langue;

    public CommandeParrainage(FluxReferralPlugin plugin, DepotJoueur depotJoueur,
                               ServiceParrainage serviceParrainage, ServicePalier servicePalier,
                               ServiceClassement serviceClassement, ServiceProfil serviceProfil,
                               ServiceRecuperation serviceRecuperation,
                               GestionnaireConfig config, GestionnaireLangue langue) {
        this.plugin = plugin;
        this.depotJoueur = depotJoueur;
        this.serviceParrainage = serviceParrainage;
        this.servicePalier = servicePalier;
        this.serviceClassement = serviceClassement;
        this.serviceProfil = serviceProfil;
        this.serviceRecuperation = serviceRecuperation;
        this.config = config;
        this.langue = langue;
    }

    // ====================================================================
    // Point d'entrée
    // ====================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Branche admin — console et joueurs autorisés
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            traiterAdmin(sender, args);
            return true;
        }

        // Commandes joueur uniquement
        if (!(sender instanceof Player joueur)) {
            sender.sendMessage(langue.getMessage("joueur-seulement"));
            return true;
        }

        if (args.length == 0) {
            traiterAide(joueur);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "adresse", "lien", "ip" -> {
                if (!joueur.hasPermission("parrainage.commande.code")) {
                    joueur.sendMessage(langue.getMessage("permission-refusee")); return true;
                }
                traiterAdresse(joueur);
            }
            case "recuperer" -> {
                if (!joueur.hasPermission("parrainage.commande.recuperer")) {
                    joueur.sendMessage(langue.getMessage("permission-refusee")); return true;
                }
                traiterRecuperer(joueur, args);
            }
            case "code" -> {
                if (!joueur.hasPermission("parrainage.commande.code")) {
                    joueur.sendMessage(langue.getMessage("permission-refusee")); return true;
                }
                traiterCode(joueur);
            }
            case "utiliser" -> {
                if (!joueur.hasPermission("parrainage.commande.utiliser")) {
                    joueur.sendMessage(langue.getMessage("permission-refusee")); return true;
                }
                if (args.length < 2) {
                    joueur.sendMessage(langue.getMessage("usage-invalide",
                        Map.of("usage", "/parrainage utiliser <code>")));
                    return true;
                }
                traiterUtiliser(joueur, args[1]);
            }
            case "info" -> {
                if (!joueur.hasPermission("parrainage.commande.info.soi")) {
                    joueur.sendMessage(langue.getMessage("permission-refusee")); return true;
                }
                traiterInfo(joueur, args.length > 1 ? args[1] : null);
            }
            case "top" -> {
                if (!joueur.hasPermission("parrainage.commande.top")) {
                    joueur.sendMessage(langue.getMessage("permission-refusee")); return true;
                }
                traiterTop(joueur);
            }
            case "aide" -> {
                if (!joueur.hasPermission("parrainage.commande.aide")) {
                    joueur.sendMessage(langue.getMessage("permission-refusee")); return true;
                }
                traiterAide(joueur);
            }
            default -> traiterAide(joueur);
        }
        return true;
    }

    // ====================================================================
    // Commandes joueur
    // ====================================================================

    private void traiterAide(Player joueur) {
        List<EntreeAide> accessibles = COMMANDES_JOUEUR.stream()
            .filter(e -> joueur.hasPermission(e.permission()))
            .toList();

        if (accessibles.isEmpty()) {
            joueur.sendMessage(langue.getMessage("permission-refusee"));
            return;
        }

        joueur.sendMessage(langue.getMessage("aide-entete"));
        for (EntreeAide entree : accessibles) {
            String desc = langue.getMessageBrut(entree.cleDescription(), Map.of());
            joueur.sendMessage(langue.getMessage("aide-ligne", Map.of(
                "commande",    entree.commande(),
                "description", desc
            )));
        }
        if (aPermissionAdminQuelconque(joueur)) {
            joueur.sendMessage(langue.getMessage("aide-pied"));
        }
    }

    /** Adresse personnelle cliquable (copie dans le presse-papier), appelée par le menu. */
    private void traiterAdresse(Player joueur) {
        var profil = serviceProfil.obtenir(joueur.getUniqueId());
        if (profil == null) {
            joueur.sendMessage(langue.getMessage("chargement"));
            return;
        }
        joueur.sendMessage(langue.getMessage("adresse-affichage", Map.of(
            "adresse", profil.adresse(),
            "code", profil.code(),
            "temps", serviceParrainage.formaterDuree(config.getTempsJeuMinimum()))));
    }

    /** /parrainage recuperer : donne tout ce qui est en attente (parrain et filleul), en vérifiant la place disponible. */
    /**
     * /parrainage recuperer             : tout ce qui est en attente, d'un coup
     * /parrainage recuperer filleul     : une seule récompense de filleul (la suivante en attente)
     * /parrainage recuperer palier <n>  : le bonus de ce palier précis
     * Les deux formes avec argument sont celles utilisées par les cases du chemin du menu :
     * chaque case récupère sa propre récompense, pas tout d'un coup.
     */
    private void traiterRecuperer(Player joueur, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("filleul")) {
            serviceRecuperation.recupererUnFilleul(joueur, resultat -> traiterResultatRecuperation(joueur, resultat));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("palier")) {
            if (args.length < 3) {
                joueur.sendMessage(langue.getMessage("usage-invalide", Map.of("usage", "/parrainage recuperer palier <filleuls>")));
                return;
            }
            int seuil;
            try {
                seuil = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                joueur.sendMessage(langue.getMessage("usage-invalide", Map.of("usage", "/parrainage recuperer palier <filleuls>")));
                return;
            }
            serviceRecuperation.recupererPalier(joueur, seuil, resultat -> traiterResultatRecuperation(joueur, resultat));
            return;
        }
        serviceRecuperation.recuperer(joueur, resultat -> traiterResultatRecuperation(joueur, resultat));
    }

    private void traiterResultatRecuperation(Player joueur, ServiceRecuperation.Resultat resultat) {
        if (resultat.dejaEnCours()) {
            joueur.sendMessage(langue.getMessage("recuperation-en-cours"));
            return;
        }
        if (resultat.total() == 0) {
            joueur.sendMessage(langue.getMessage(
                resultat.inventairePlein() ? "recuperation-inventaire-plein" : "recuperation-rien"));
            return;
        }
        joueur.sendMessage(langue.getMessage(
            resultat.inventairePlein() ? "recuperation-partielle" : "recuperation-succes",
            Map.of("compteur", String.valueOf(resultat.total()))));
        if (resultat.inventairePlein()) {
            joueur.sendMessage(langue.getMessage("recuperation-inventaire-plein"));
        }
    }

    private void traiterCode(Player joueur) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<JoueurParrainage> opt = depotJoueur.trouverParUUID(joueur.getUniqueId());
                if (opt.isPresent() && opt.get().code() != null && !opt.get().code().isEmpty()) {
                    String code = opt.get().code();
                    Bukkit.getScheduler().runTask(plugin, () ->
                        joueur.sendMessage(langue.getMessage("code-affichage", Map.of("code", code))));
                } else {
                    String code = serviceParrainage.genererCode();
                    depotJoueur.mettreAJourCode(joueur.getUniqueId(), code);
                    Bukkit.getScheduler().runTask(plugin, () ->
                        joueur.sendMessage(langue.getMessage("code-genere", Map.of("code", code))));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur BDD lors de l'affichage du code pour " + joueur.getName(), e);
                Bukkit.getScheduler().runTask(plugin, () -> joueur.sendMessage(langue.getMessage("erreur-bdd")));
            }
        });
    }

    private void traiterUtiliser(Player joueur, String code) {
        // Contrôle du temps de jeu sur le thread principal (statistique du joueur connecté)
        if (config.getTempsJeuMinimum() > 0) {
            long tempsJoueTicks    = joueur.getStatistic(Statistic.PLAY_ONE_MINUTE);
            long tempsJoueSecondes = tempsJoueTicks / 20L;
            if (tempsJoueSecondes < config.getTempsJeuMinimum()) {
                joueur.sendMessage(langue.getMessage("utilisation-temps-jeu-insuffisant",
                    Map.of("temps", serviceParrainage.formaterDuree(config.getTempsJeuMinimum()))));
                return;
            }
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ResultatUtilisation resultat = serviceParrainage.utiliserCode(
                joueur.getUniqueId(), joueur.getName(), code.toUpperCase());

            Bukkit.getScheduler().runTask(plugin, () -> {
                switch (resultat.type()) {
                    case SUCCES ->
                        joueur.sendMessage(langue.getMessage("utilisation-succes",
                            Map.of("parrain", resultat.pseudoParrain())));
                    case DEJA_PARRAINE ->
                        joueur.sendMessage(langue.getMessage("utilisation-deja-parraine"));
                    case EN_ATTENTE ->
                        joueur.sendMessage(langue.getMessage("utilisation-en-attente",
                            Map.of("parrain", resultat.pseudoParrain())));
                    case MEME_IP ->
                        joueur.sendMessage(langue.getMessage("utilisation-meme-ip"));
                    case CODE_INVALIDE ->
                        joueur.sendMessage(langue.getMessage("utilisation-code-invalide"));
                    case PROPRE_CODE ->
                        joueur.sendMessage(langue.getMessage("utilisation-propre-code"));
                    case CIRCULAIRE ->
                        joueur.sendMessage(langue.getMessage("utilisation-circulaire"));
                    case ERREUR_BDD ->
                        joueur.sendMessage(langue.getMessage("erreur-bdd"));
                }
            });
        });
    }

    private void traiterInfo(Player joueur, String cibleNom) {
        if (cibleNom != null && !cibleNom.equalsIgnoreCase(joueur.getName())
                && !joueur.hasPermission("parrainage.commande.info.autres")) {
            joueur.sendMessage(langue.getMessage("permission-refusee"));
            return;
        }

        var cible    = cibleNom != null ? Bukkit.getOfflinePlayer(cibleNom) : joueur;
        String nomCible = cibleNom != null ? cibleNom : joueur.getName();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<JoueurParrainage> opt = depotJoueur.trouverParUUID(cible.getUniqueId());
                if (opt.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                        joueur.sendMessage(langue.getMessage("info-joueur-introuvable")));
                    return;
                }
                JoueurParrainage j = opt.get();

                String pseudoParrain = null;
                if (j.parrainUuid() != null) {
                    pseudoParrain = depotJoueur.trouverParUUID(j.parrainUuid())
                        .map(JoueurParrainage::pseudo).orElse("Inconnu");
                }

                String prochainPalier = config.getPaliers().stream()
                    .filter(p -> p.filleuls() > j.nbFilleuls())
                    .findFirst()
                    .map(Palier::filleuls)
                    .map(String::valueOf)
                    .orElse(null);

                final String ppFinal  = pseudoParrain;
                final String palierFinal = prochainPalier;

                Bukkit.getScheduler().runTask(plugin, () -> {
                    joueur.sendMessage(langue.getMessage("info-entete"));
                    if (ppFinal == null) {
                        joueur.sendMessage(langue.getMessage("info-parrain-aucun"));
                    } else {
                        joueur.sendMessage(langue.getMessage("info-parrain", Map.of("parrain", ppFinal)));
                    }
                    joueur.sendMessage(langue.getMessage("info-nb-filleuls",
                        Map.of("compteur", String.valueOf(j.nbFilleuls()))));
                    joueur.sendMessage(langue.getMessage("info-code",
                        Map.of("code", j.code())));
                    if (palierFinal != null) {
                        joueur.sendMessage(langue.getMessage("info-prochain-palier",
                            Map.of("palier", palierFinal)));
                    } else {
                        joueur.sendMessage(langue.getMessage("info-palier-max"));
                    }
                });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur BDD lors de l'info pour " + nomCible, e);
                Bukkit.getScheduler().runTask(plugin, () -> joueur.sendMessage(langue.getMessage("erreur-bdd")));
            }
        });
    }

    private void traiterTop(Player joueur) {
        var classement = serviceClassement.obtenirClassement();
        joueur.sendMessage(langue.getMessage("classement-entete"));
        if (classement.isEmpty()) {
            joueur.sendMessage(langue.getMessage("classement-vide"));
            return;
        }
        for (int i = 0; i < classement.size(); i++) {
            var entree = classement.get(i);
            joueur.sendMessage(langue.getMessage("classement-ligne", Map.of(
                "rang",     String.valueOf(i + 1),
                "joueur",   entree.getKey(),
                "compteur", String.valueOf(entree.getValue())
            )));
        }
    }

    // ====================================================================
    // Commandes admin
    // ====================================================================

    private void traiterAdmin(CommandSender sender, String[] args) {
        if (args.length < 2) {
            // /parrainage admin sans sous-commande → aide admin
            if (!aPermissionAdminQuelconque(sender)) {
                sender.sendMessage(langue.getMessage("permission-refusee"));
                return;
            }
            traiterAdminAide(sender);
            return;
        }

        String sousCommande = args[1].toLowerCase();

        if (!aPermissionAdmin(sender, sousCommande)) {
            sender.sendMessage(langue.getMessage("permission-refusee"));
            return;
        }

        switch (sousCommande) {
            case "aide"             -> traiterAdminAide(sender);
            case "recharger"        -> traiterAdminRecharger(sender);
            case "reinitialiser"    -> {
                if (args.length < 3) { usageAdmin(sender, "reinitialiser \\<joueur>"); return; }
                traiterAdminReinitialiser(sender, args[2]);
            }
            case "definirparrain"   -> {
                if (args.length < 4) { usageAdmin(sender, "definirparrain \\<joueur> \\<parrain>"); return; }
                traiterAdminDefinirParrain(sender, args[2], args[3]);
            }
            case "supprimerparrain" -> {
                if (args.length < 3) { usageAdmin(sender, "supprimerparrain \\<joueur>"); return; }
                traiterAdminSupprimerParrain(sender, args[2]);
            }
            case "forcerpalier"     -> {
                if (args.length < 4) { usageAdmin(sender, "forcerpalier \\<joueur> \\<n>"); return; }
                traiterAdminForcerPalier(sender, args[2], args[3]);
            }
            case "info"             -> {
                if (args.length < 3) { usageAdmin(sender, "info \\<joueur>"); return; }
                traiterAdminInfo(sender, args[2]);
            }
            case "simuler"          -> {
                if (args.length < 4) { usageAdmin(sender, "simuler \\<joueur> \\<adresse>"); return; }
                traiterAdminSimuler(sender, args[2], args[3]);
            }
            case "valider"          -> {
                if (args.length < 3) { usageAdmin(sender, "valider \\<joueur>"); return; }
                traiterAdminValider(sender, args[2]);
            }
            default -> traiterAdminAide(sender);
        }
    }

    private void traiterAdminAide(CommandSender sender) {
        List<EntreeAide> accessibles = COMMANDES_ADMIN.stream()
            .filter(e -> aPermissionAdmin(sender, e.permission().replace("parrainage.admin.", "")))
            .toList();

        if (accessibles.isEmpty()) {
            sender.sendMessage(langue.getMessage("permission-refusee"));
            return;
        }

        sender.sendMessage(langue.getMessage("aide-admin-entete"));
        for (EntreeAide entree : accessibles) {
            String desc = langue.getMessageBrut(entree.cleDescription(), Map.of());
            sender.sendMessage(langue.getMessage("aide-admin-ligne", Map.of(
                "commande",    entree.commande(),
                "description", desc
            )));
        }
    }

    private void traiterAdminRecharger(CommandSender sender) {
        plugin.rechargerConfiguration();
        sender.sendMessage(langue.getMessage("admin-rechargement-succes"));
    }

    private void traiterAdminReinitialiser(CommandSender sender, String nomJoueur) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var cible = Bukkit.getOfflinePlayer(nomJoueur);
                depotJoueur.reinitialiser(cible.getUniqueId());
                serviceClassement.rafraichir();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(langue.getMessage("admin-reinitialisation-succes",
                        Map.of("joueur", nomJoueur)));
                    sender.sendMessage(langue.getMessage("admin-reinitialisation-avertissement"));
                });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur BDD lors de la réinitialisation de " + nomJoueur, e);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(langue.getMessage("erreur-bdd")));
            }
        });
    }

    private void traiterAdminDefinirParrain(CommandSender sender, String nomJoueur, String nomParrain) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var cible  = Bukkit.getOfflinePlayer(nomJoueur);
                var parrain = Bukkit.getOfflinePlayer(nomParrain);

                if (depotJoueur.trouverParUUID(parrain.getUniqueId()).isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(langue.getMessage("info-joueur-introuvable")));
                    return;
                }
                depotJoueur.mettreAJourParrain(cible.getUniqueId(), parrain.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () ->
                    sender.sendMessage(langue.getMessage("admin-definir-parrain-succes",
                        Map.of("joueur", nomJoueur, "parrain", nomParrain))));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur BDD lors de la définition du parrain pour " + nomJoueur, e);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(langue.getMessage("erreur-bdd")));
            }
        });
    }

    private void traiterAdminSupprimerParrain(CommandSender sender, String nomJoueur) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var filleulUuid = Bukkit.getOfflinePlayer(nomJoueur).getUniqueId();
                var parrainUuid = serviceParrainage.supprimerParrain(filleulUuid);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (parrainUuid.isEmpty()) {
                        sender.sendMessage(langue.getMessage("admin-joueur-pas-parraine"));
                    } else {
                        sender.sendMessage(langue.getMessage("admin-supprimer-parrain-succes",
                            Map.of("joueur", nomJoueur)));
                    }
                });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur BDD lors de la suppression du parrain de " + nomJoueur, e);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(langue.getMessage("erreur-bdd")));
            }
        });
    }

    private void traiterAdminForcerPalier(CommandSender sender, String nomJoueur, String nStr) {
        int n;
        try {
            n = Integer.parseInt(nStr);
        } catch (NumberFormatException e) {
            usageAdmin(sender, "forcerpalier \\<joueur> \\<n>");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<JoueurParrainage> opt = depotJoueur.trouverParUUID(
                    Bukkit.getOfflinePlayer(nomJoueur).getUniqueId());
                if (opt.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(langue.getMessage("info-joueur-introuvable")));
                    return;
                }
                JoueurParrainage j = opt.get();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    servicePalier.forcerPalier(j.uuid(), j.pseudo(), n);
                    sender.sendMessage(langue.getMessage("admin-forcer-palier-succes",
                        Map.of("joueur", nomJoueur, "palier", String.valueOf(n))));
                });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur BDD lors du forçage du palier pour " + nomJoueur, e);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(langue.getMessage("erreur-bdd")));
            }
        });
    }

    private void traiterAdminInfo(CommandSender sender, String nomJoueur) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<JoueurParrainage> opt = depotJoueur.trouverParUUID(
                    Bukkit.getOfflinePlayer(nomJoueur).getUniqueId());
                if (opt.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(langue.getMessage("info-joueur-introuvable")));
                    return;
                }
                JoueurParrainage j = opt.get();

                String pseudoParrain = null;
                if (j.parrainUuid() != null) {
                    pseudoParrain = depotJoueur.trouverParUUID(j.parrainUuid())
                        .map(JoueurParrainage::pseudo).orElse(j.parrainUuid().toString());
                }
                String prochainPalier = config.getPaliers().stream()
                    .filter(p -> p.filleuls() > j.nbFilleuls())
                    .findFirst().map(p -> String.valueOf(p.filleuls())).orElse(null);

                final String ppFinal = pseudoParrain;
                final String palierFinal = prochainPalier;

                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(langue.getMessage("info-entete"));
                    if (ppFinal == null) {
                        sender.sendMessage(langue.getMessage("info-parrain-aucun"));
                    } else {
                        sender.sendMessage(langue.getMessage("info-parrain", Map.of("parrain", ppFinal)));
                    }
                    sender.sendMessage(langue.getMessage("info-nb-filleuls",
                        Map.of("compteur", String.valueOf(j.nbFilleuls()))));
                    sender.sendMessage(langue.getMessage("info-code", Map.of("code", j.code())));
                    if (palierFinal != null) {
                        sender.sendMessage(langue.getMessage("info-prochain-palier",
                            Map.of("palier", palierFinal)));
                    } else {
                        sender.sendMessage(langue.getMessage("info-palier-max"));
                    }
                });
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur BDD lors de l'info admin pour " + nomJoueur, e);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(langue.getMessage("erreur-bdd")));
            }
        });
    }

    /**
     * Rejoue la détection d'arrivée comme si le joueur (connecté) s'était connecté avec cette
     * adresse, sans le délai d'arrivée. Sert à tester sans enregistrement DNS.
     */
    private void traiterAdminSimuler(CommandSender sender, String nomJoueur, String hote) {
        Player cible = Bukkit.getPlayerExact(nomJoueur);
        if (cible == null) {
            sender.sendMessage(langue.getMessage("joueur-non-connecte"));
            return;
        }
        var uuid = cible.getUniqueId();
        String pseudo = cible.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var resultat = serviceParrainage.traiterArrivee(uuid, pseudo, hote, Instant.now(), true);
                serviceParrainage.chargerAttente(uuid);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(resultat.isPresent()
                    ? langue.getMessage("admin-simuler-succes", Map.of("joueur", pseudo, "parrain", resultat.get()))
                    : langue.getMessage("admin-simuler-echec", Map.of("joueur", pseudo, "adresse", hote))));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur BDD lors de la simulation pour " + pseudo, e);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(langue.getMessage("erreur-bdd")));
            }
        });
    }

    /** Valide tout de suite l'invitation d'un joueur, sans attendre le temps de jeu minimum. */
    private void traiterAdminValider(CommandSender sender, String nomJoueur) {
        var offline = Bukkit.getOfflinePlayer(nomJoueur);
        var uuid = offline.getUniqueId();
        String pseudo = offline.getName() != null ? offline.getName() : nomJoueur;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var resultat = serviceParrainage.validerInvitation(uuid, pseudo);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(
                    resultat.type() == ResultatUtilisation.Type.SUCCES
                        ? langue.getMessage("admin-valider-succes", Map.of("joueur", pseudo, "parrain", resultat.pseudoParrain()))
                        : langue.getMessage("admin-valider-echec", Map.of("joueur", pseudo, "raison", resultat.type().name()))));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur BDD lors de la validation pour " + pseudo, e);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(langue.getMessage("erreur-bdd")));
            }
        });
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    /** Vérifie si le sender a la permission pour une sous-commande admin. Console : toujours autorisée. */
    private boolean aPermissionAdmin(CommandSender sender, String sousCommande) {
        if (sender instanceof ConsoleCommandSender) return true;
        return sender.hasPermission("parrainage.admin." + sousCommande)
            || sender.hasPermission("parrainage.admin.*");
    }

    /** Vérifie si le sender a au moins une permission admin (pour le pied de page d'aide). */
    private boolean aPermissionAdminQuelconque(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) return true;
        return COMMANDES_ADMIN.stream().anyMatch(e -> sender.hasPermission(e.permission()));
    }

    private void usageAdmin(CommandSender sender, String sousUsage) {
        sender.sendMessage(langue.getMessage("usage-invalide",
            Map.of("usage", "/parrainage admin " + sousUsage)));
    }

    // ====================================================================
    // Complétion automatique
    // ====================================================================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>(List.of("adresse", "code", "utiliser", "recuperer", "info", "top", "aide"));
            if (aPermissionAdminQuelconque(sender)) suggestions.add("admin");
            return suggestions.stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            List<String> sousCommandes = List.of("aide", "recharger", "reinitialiser",
                "definirparrain", "supprimerparrain", "forcerpalier", "info", "simuler", "valider");
            return sousCommandes.stream()
                .filter(s -> s.startsWith(args[1].toLowerCase()))
                .filter(s -> aPermissionAdmin(sender, s))
                .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("recuperer")) {
            return List.of("filleul", "palier").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
