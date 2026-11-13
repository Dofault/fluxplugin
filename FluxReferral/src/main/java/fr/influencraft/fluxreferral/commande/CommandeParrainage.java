package fr.influencraft.fluxreferral.commande;

import fr.influencraft.fluxreferral.FluxReferralPlugin;
import fr.influencraft.flux.api.text.ChatHelp;
import fr.influencraft.fluxreferral.bdd.DepotInvitation;
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

    /** Permission of the « admin » line of the player help: any admin permission of the referral system. */
    private static final String ANY_ADMIN = "fluxreferral.admin";
    private static final String OTHERS = "fluxreferral.commande.info.autres";

    private static final List<String> ADMIN_SUBCOMMANDS = List.of("aide", "recharger", "reinitialiser",
        "definirparrain", "supprimerparrain", "forcerpalier", "info", "simuler", "valider", "decouverte", "decouvertes",
        "questionnaire");

    /** Player help (Flux's ChatHelp, as FluxCommerce): sub-command, lang key, permission (null = everybody). */
    private List<ChatHelp.Entry> help() {
        return List.of(
            entry("adresse", "adresse", "fluxreferral.commande.code"),
            entry("code", "code", "fluxreferral.commande.code"),
            entry("utiliser", "utiliser", "fluxreferral.commande.utiliser"),
            entry("filleuls", "filleuls", "fluxreferral.commande.filleuls"),
            entry("info", "info", "fluxreferral.commande.info.soi"),
            entry("info", "info-autres", OTHERS),
            entry("top", "top", "fluxreferral.commande.top"),
            entry("recuperer", "recuperer", "fluxreferral.commande.recuperer"),
            entry("aide", "aide", null),
            entry("admin aide", "admin", ANY_ADMIN));
    }

    private List<ChatHelp.Entry> adminHelp() {
        List<ChatHelp.Entry> out = new ArrayList<>();
        for (String sub : ADMIN_SUBCOMMANDS) {
            if (!sub.equals("aide")) out.add(entry("admin " + sub, "admin-" + sub, "fluxreferral.admin." + sub));
        }
        out.add(entry("admin aide", "admin-aide", ANY_ADMIN));
        return out;
    }

    /** {@code sub} followed by its arguments as the lang file writes them ({@code help.args.<key>}). */
    private ChatHelp.Entry entry(String sub, String key, String permission) {
        return new ChatHelp.Entry((sub + " " + langue.messages().raw("help.args." + key)).trim(), key, permission);
    }

    private final FluxReferralPlugin plugin;
    private final DepotJoueur depotJoueur;
    private final ServiceParrainage serviceParrainage;
    private final ServicePalier servicePalier;
    private final ServiceClassement serviceClassement;
    private final ServiceProfil serviceProfil;
    private final ServiceRecuperation serviceRecuperation;
    private final DepotInvitation depotInvitation;
    private final GestionnaireConfig config;
    private final GestionnaireLangue langue;

    public CommandeParrainage(FluxReferralPlugin plugin, DepotJoueur depotJoueur,
                               ServiceParrainage serviceParrainage, ServicePalier servicePalier,
                               ServiceClassement serviceClassement, ServiceProfil serviceProfil,
                               ServiceRecuperation serviceRecuperation, DepotInvitation depotInvitation,
                               GestionnaireConfig config, GestionnaireLangue langue) {
        this.plugin = plugin;
        this.depotJoueur = depotJoueur;
        this.serviceParrainage = serviceParrainage;
        this.servicePalier = servicePalier;
        this.serviceClassement = serviceClassement;
        this.serviceProfil = serviceProfil;
        this.serviceRecuperation = serviceRecuperation;
        this.depotInvitation = depotInvitation;
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
            traiterAdmin(sender, label, args);
            return true;
        }

        String sous = args.length == 0 ? "aide" : args[0].toLowerCase(java.util.Locale.ROOT);
        if (sous.equals("aide") || sous.equals("help")) {
            aide(sender, label, args, false, 1);
            return true;
        }

        // Commandes joueur uniquement
        if (!(sender instanceof Player joueur)) {
            sender.sendMessage(langue.getMessage("joueur-seulement"));
            return true;
        }

        switch (sous) {
            case "adresse", "lien", "ip" -> {
                if (!joueur.hasPermission("fluxreferral.commande.code")) {
                    joueur.sendMessage(langue.getMessage("permission-refusee")); return true;
                }
                traiterAdresse(joueur);
            }
            case "recuperer" -> {
                if (!joueur.hasPermission("fluxreferral.commande.recuperer")) {
                    joueur.sendMessage(langue.getMessage("permission-refusee")); return true;
                }
                traiterRecuperer(joueur, args);
            }
            case "code" -> {
                if (!joueur.hasPermission("fluxreferral.commande.code")) {
                    joueur.sendMessage(langue.getMessage("permission-refusee")); return true;
                }
                traiterCode(joueur);
            }
            case "utiliser" -> {
                if (!joueur.hasPermission("fluxreferral.commande.utiliser")) {
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
                if (!joueur.hasPermission("fluxreferral.commande.info.soi")) {
                    joueur.sendMessage(langue.getMessage("permission-refusee")); return true;
                }
                traiterInfo(joueur, args.length > 1 ? args[1] : null);
            }
            case "top" -> {
                if (!joueur.hasPermission("fluxreferral.commande.top")) {
                    joueur.sendMessage(langue.getMessage("permission-refusee")); return true;
                }
                traiterTop(joueur);
            }
            case "filleuls" -> {
                if (!joueur.hasPermission("fluxreferral.commande.filleuls")) {
                    joueur.sendMessage(langue.getMessage("permission-refusee")); return true;
                }
                traiterFilleuls(joueur, args.length > 1 ? args[1] : null);
            }
            default -> aide(sender, label, args, false, 1);
        }
        return true;
    }

    // ====================================================================
    // Commandes joueur
    // ====================================================================

    /**
     * Paged help (Flux's ChatHelp, the look of Lands' help, as FluxCommerce); {@code pageArg}: index of the page
     * number in {@code args}. Only the commands the sender may use are listed.
     */
    private void aide(CommandSender sender, String label, String[] args, boolean admin, int pageArg) {
        int page = 1;
        if (args.length > pageArg) {
            try {
                page = Integer.parseInt(args[pageArg]);
            } catch (NumberFormatException ignored) {
                // first page
            }
        }
        ChatHelp.show(sender, langue.messages(), label, admin ? adminHelp() : help(), admin ? "admin aide" : "aide",
            admin, page, permission -> autorise(sender, permission));
    }

    /** Help filter: admin nodes follow {@link #aPermissionAdmin}, the console may use every admin command. */
    private boolean autorise(CommandSender sender, String permission) {
        if (permission.equals(ANY_ADMIN)) return aPermissionAdminQuelconque(sender);
        if (permission.startsWith(ANY_ADMIN + ".")) return aPermissionAdmin(sender, permission.substring(ANY_ADMIN.length() + 1));
        return sender.hasPermission(permission);
    }

    /**
     * {@code /parrainage filleuls [joueur]}: names of the validated referrals (in the order they came), then those
     * still waiting for their play time. Read from the database, so it works for an offline player too.
     */
    private void traiterFilleuls(Player joueur, String cibleNom) {
        boolean soi = cibleNom == null || cibleNom.equalsIgnoreCase(joueur.getName());
        if (!soi && !joueur.hasPermission(OTHERS)) {
            joueur.sendMessage(langue.getMessage("permission-refusee"));
            return;
        }
        var cible = soi ? joueur : Bukkit.getOfflinePlayer(cibleNom);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (!soi && depotJoueur.trouverParUUID(cible.getUniqueId()).isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> joueur.sendMessage(langue.getMessage("info-joueur-introuvable")));
                    return;
                }
                List<String> filleuls = depotJoueur.listerFilleuls(cible.getUniqueId());
                var attente = depotInvitation.listerParParrain(cible.getUniqueId());
                String nom = soi ? joueur.getName() : (cible.getName() != null ? cible.getName() : cibleNom);
                Bukkit.getScheduler().runTask(plugin, () -> afficherFilleuls(joueur, soi, nom, filleuls, attente));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Database error while listing the referrals of " + cible.getUniqueId(), e);
                Bukkit.getScheduler().runTask(plugin, () -> joueur.sendMessage(langue.getMessage("erreur-bdd")));
            }
        });
    }

    private void afficherFilleuls(Player joueur, boolean soi, String nom, List<String> filleuls,
                                  List<fr.influencraft.fluxreferral.modele.Profil.Filleul> attente) {
        if (filleuls.isEmpty() && attente.isEmpty()) {
            joueur.sendMessage(soi ? langue.getMessage("filleuls-aucun") : langue.getMessage("filleuls-aucun-autre", Map.of("joueur", nom)));
            return;
        }
        joueur.sendMessage(langue.getMessage(soi ? "filleuls-entete" : "filleuls-entete-autre",
            Map.of("joueur", nom, "compteur", String.valueOf(filleuls.size()))));
        if (!filleuls.isEmpty()) {
            String separateur = langue.getMessageBrut("filleuls-separateur", Map.of());
            String liste = String.join(separateur, filleuls.stream()
                .map(f -> langue.getMessageBrut("filleuls-nom", Map.of("joueur", f))).toList());
            joueur.sendMessage(langue.getMessage("filleuls-liste", Map.of("liste", liste)));
        }
        if (!attente.isEmpty()) {
            joueur.sendMessage(langue.getMessage("filleuls-attente-entete", Map.of("compteur", String.valueOf(attente.size()))));
            for (var f : attente) {
                joueur.sendMessage(langue.getMessage("filleuls-attente-ligne", Map.of(
                    "joueur", f.pseudo(), "progression", serviceParrainage.progressionTemps(f.tempsJoue()))));
            }
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
                && !joueur.hasPermission("fluxreferral.commande.info.autres")) {
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
                    joueur.sendMessage(langue.getMessage("info-entete", Map.of("joueur", j.pseudo())));
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

    private void traiterAdmin(CommandSender sender, String label, String[] args) {
        String sousCommande = args.length < 2 ? "aide" : args[1].toLowerCase(java.util.Locale.ROOT);
        if (sousCommande.equals("aide") || sousCommande.equals("help") || !ADMIN_SUBCOMMANDS.contains(sousCommande)) {
            // /parrainage admin [aide [page]], or an unknown sub-command: admin help
            if (!aPermissionAdminQuelconque(sender)) sender.sendMessage(langue.getMessage("permission-refusee"));
            else aide(sender, label, args, true, 2);
            return;
        }

        if (!aPermissionAdmin(sender, sousCommande)) {
            sender.sendMessage(langue.getMessage("permission-refusee"));
            return;
        }

        switch (sousCommande) {
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
            case "decouverte"       -> {
                if (args.length < 3) sender.sendMessage(langue.getMessage("admin-usage-decouverte"));
                else plugin.getServiceDecouverte().consulter(sender, args[2]);
            }
            case "decouvertes"      -> plugin.getServiceDecouverte().statistiques(sender);
            case "questionnaire"    -> {
                // without a player: preview for the sender, nothing recorded; with a player: asks him for real
                org.bukkit.entity.Player cible = args.length >= 3 ? Bukkit.getPlayerExact(args[2])
                        : sender instanceof org.bukkit.entity.Player p ? p : null;
                if (cible == null) sender.sendMessage(langue.getMessage("info-joueur-introuvable"));
                else plugin.getServiceDecouverte().montrer(cible, args.length < 3);
            }
            case "valider"          -> {
                if (args.length < 3) { usageAdmin(sender, "valider \\<joueur>"); return; }
                traiterAdminValider(sender, args[2]);
            }
            default -> aide(sender, label, args, true, 2);
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
                    sender.sendMessage(langue.getMessage("info-entete", Map.of("joueur", j.pseudo())));
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
        return sender.hasPermission("fluxreferral.admin." + sousCommande)
            || sender.hasPermission("fluxreferral.admin.*");
    }

    /** Vérifie si le sender a au moins une permission admin (pour le pied de page d'aide). */
    private boolean aPermissionAdminQuelconque(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) return true;
        return sender.hasPermission("fluxreferral.admin.*")
            || ADMIN_SUBCOMMANDS.stream().anyMatch(sub -> sender.hasPermission("fluxreferral.admin." + sub));
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
            List<String> suggestions = new ArrayList<>(List.of("adresse", "code", "utiliser", "filleuls", "recuperer", "info", "top", "aide"));
            if (aPermissionAdminQuelconque(sender)) suggestions.add("admin");
            return suggestions.stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return ADMIN_SUBCOMMANDS.stream()
                .filter(s -> s.startsWith(args[1].toLowerCase()))
                .filter(s -> aPermissionAdmin(sender, s))
                .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("filleuls") || args[0].equalsIgnoreCase("info"))
                && sender.hasPermission(OTHERS)) {
            return fr.influencraft.flux.api.util.Players.complete(args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("recuperer")) {
            return List.of("filleul", "palier").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
