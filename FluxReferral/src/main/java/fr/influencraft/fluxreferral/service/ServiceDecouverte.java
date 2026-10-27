package fr.influencraft.fluxreferral.service;

import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.fluxreferral.FluxReferralPlugin;
import fr.influencraft.fluxreferral.bdd.DepotDecouverte;
import fr.influencraft.fluxreferral.bdd.DepotJoueur;
import fr.influencraft.fluxreferral.configuration.GestionnaireConfig;
import fr.influencraft.fluxreferral.configuration.GestionnaireLangue;
import fr.influencraft.fluxreferral.modele.JoueurParrainage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * How each player discovered the server.
 * <ul>
 *   <li>arrived by the personal address of a player: recorded by {@link ServiceParrainage#traiterArrivee}, no
 *   question;</li>
 *   <li>otherwise, a new player gets a window (Flux dialog): one button per source of the config (TikTok, vote site...)
 *   and "By a player" with a field for his name. Answering "by a player" counts as an arrival by his address
 *   (pending referral, same rules). Closed without answer: asked again at the next login while the player is new.</li>
 * </ul>
 */
public class ServiceDecouverte {

    public static final String SOURCE_JOUEUR = "joueur";
    public static final String SOURCE_AUTRE = "autre";

    private final FluxReferralPlugin plugin;
    private final DepotDecouverte depot;
    private final DepotJoueur depotJoueur;
    private final ServiceParrainage parrainage;
    private final GestionnaireConfig config;
    private final GestionnaireLangue langue;

    public ServiceDecouverte(FluxReferralPlugin plugin, DepotDecouverte depot, DepotJoueur depotJoueur,
                             ServiceParrainage parrainage, GestionnaireConfig config, GestionnaireLangue langue) {
        this.plugin = plugin;
        this.depot = depot;
        this.depotJoueur = depotJoueur;
        this.parrainage = parrainage;
        this.config = config;
        this.langue = langue;
    }

    /** After the join is processed (asynchronous thread): asks the question when the player still has to answer. */
    public void proposer(UUID uuid, Instant premiereConnexion) throws SQLException {
        if (!config.isQuestionnaireActif()) return;
        if (Duration.between(premiereConnexion, Instant.now()).toHours() >= config.getDelaiArriveeHeures()) return;
        if (depot.trouver(uuid).isPresent()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) ouvrir(p, premiereConnexion, null);
        }, config.getDelaiQuestionnaireTicks());
    }

    private String texte(String cle, Map<String, String> vars) {
        return langue.getMessageBrut(cle, vars);
    }

    /**
     * {@code /parrainage admin questionnaire [joueur]}: without a player, a preview for the sender (answers shown,
     * nothing recorded); with a player, asks him again for real (his former answer stays if he had one).
     */
    public void montrer(Player cible, boolean apercu) {
        if (apercu) {
            apercu(cible);
            return;
        }
        async(() -> {
            var j = depotJoueur.trouverParUUID(cible.getUniqueId());
            Instant premiere = j.map(x -> java.sql.Timestamp.valueOf(x.premiereConnexion()).toInstant()).orElse(Instant.now());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (cible.isOnline()) ouvrir(cible, premiere, null);
            });
        });
    }

    /** Preview: both steps, answers shown in the chat, nothing recorded. */
    private void apercu(Player p) {
        question(p).onSubmit((player, answer) -> {
            if (SOURCE_AUTRE.equals(answer.button())) {
                autre(player, null).onSubmit((pl, a) -> {
                    if ("retour".equals(a.button())) apercu(pl);
                    else pl.sendMessage(fr.influencraft.flux.api.text.Texts.parse(texte("decouverte-apercu",
                            Map.of("source", SOURCE_AUTRE, "joueur", a.text("texte")))));
                }).open();
                return;
            }
            if (!SOURCE_JOUEUR.equals(answer.button())) {
                player.sendMessage(fr.influencraft.flux.api.text.Texts.parse(texte("decouverte-apercu", Map.of("source", answer.button(), "joueur", ""))));
                return;
            }
            pseudo(player, null).onSubmit((pl, a) -> {
                if ("retour".equals(a.button())) apercu(pl);
                else pl.sendMessage(fr.influencraft.flux.api.text.Texts.parse(texte("decouverte-apercu",
                        Map.of("source", SOURCE_JOUEUR, "joueur", a.text("pseudo")))));
            }).open();
        }).open();
    }

    /** Step 1: welcome and question, one real button per source plus "By a player" (no window title: too high). */
    private Dialogs.Form question(Player p) {
        Dialogs.Form form = Dialogs.form(p, "")
                .body(texte("decouverte-titre", Map.of()))
                .body(texte("decouverte-question", Map.of()))
                .spacer(1);
        for (Map.Entry<String, String> source : config.getSourcesQuestionnaire().entrySet()) {
            form.action(source.getKey(), source.getValue());
        }
        return form.action(SOURCE_JOUEUR, texte("decouverte-bouton-joueur", Map.of()))
                .action(SOURCE_AUTRE, texte("decouverte-bouton-autre", Map.of())).columns(1);
    }

    /** Step 2 ("Other"): the player writes how he found the server. */
    private Dialogs.Form autre(Player p, String erreur) {
        Dialogs.Form form = Dialogs.form(p, "")
                .body(texte("decouverte-autre", Map.of()))
                .spacer(1);
        if (erreur != null) form.body(erreur);
        return form.input("texte", texte("decouverte-autre-libelle", Map.of()), "", 64)
                .action("valider", texte("decouverte-bouton-valider", Map.of()))
                .action("retour", texte("decouverte-bouton-retour", Map.of()))
                .columns(2);
    }

    private void ouvrirAutre(Player p, Instant premiereConnexion, String erreur) {
        autre(p, erreur).onSubmit((player, answer) -> {
            if ("retour".equals(answer.button())) {
                ouvrir(player, premiereConnexion, null);
                return;
            }
            String texte = answer.text("texte");
            if (texte.isBlank()) {
                ouvrirAutre(player, premiereConnexion, texte("decouverte-erreur-autre", Map.of()));
                return;
            }
            UUID uuid = player.getUniqueId();
            async(() -> {
                depot.enregistrer(uuid, SOURCE_AUTRE, null, texte);
                envoyer(uuid, "decouverte-merci", Map.of());
            });
        }).open();
    }

    /** Step 2 ("By a player"): the name of the player who invited him. */
    private Dialogs.Form pseudo(Player p, String erreur) {
        Dialogs.Form form = Dialogs.form(p, "")
                .body(texte("decouverte-champ", Map.of()))
                .spacer(1);
        if (erreur != null) form.body(erreur);
        return form.input("pseudo", texte("decouverte-champ-libelle", Map.of()), "", 16)
                .action("valider", texte("decouverte-bouton-valider", Map.of()))
                .action("retour", texte("decouverte-bouton-retour", Map.of()))
                .columns(2);
    }

    private void ouvrir(Player p, Instant premiereConnexion, String erreur) {
        question(p).onSubmit((player, answer) -> {
            if (SOURCE_JOUEUR.equals(answer.button())) ouvrirPseudo(player, premiereConnexion, null);
            else if (SOURCE_AUTRE.equals(answer.button())) ouvrirAutre(player, premiereConnexion, null);
            else repondre(player, premiereConnexion, answer.button(), "");
        }).open();
    }

    private void ouvrirPseudo(Player p, Instant premiereConnexion, String erreur) {
        pseudo(p, erreur).onSubmit((player, answer) -> {
            if ("retour".equals(answer.button())) ouvrir(player, premiereConnexion, null);
            else repondre(player, premiereConnexion, SOURCE_JOUEUR, answer.text("pseudo"));
        }).open();
    }

    private void repondre(Player p, Instant premiereConnexion, String source, String saisie) {
        UUID uuid = p.getUniqueId();
        String pseudoFilleul = p.getName();
        if (!SOURCE_JOUEUR.equals(source)) {
            async(() -> {
                depot.enregistrer(uuid, source, null, "questionnaire");
                envoyer(uuid, "decouverte-merci", Map.of());
            });
            return;
        }
        if (saisie.isBlank()) {
            ouvrirPseudo(p, premiereConnexion, texte("decouverte-erreur-vide", Map.of()));
            return;
        }
        async(() -> {
            Optional<JoueurParrainage> parrain = depotJoueur.trouverParPseudo(saisie);
            if (parrain.isEmpty()) parrain = depotJoueur.trouverParCode(saisie.toUpperCase(Locale.ROOT));
            if (parrain.isEmpty()) {
                rouvrir(uuid, premiereConnexion, texte("decouverte-erreur-inconnu", Map.of("joueur", saisie)));
                return;
            }
            JoueurParrainage j = parrain.get();
            ServiceParrainage.Arrivee arrivee = parrainage.inviter(uuid, pseudoFilleul, j, "questionnaire", premiereConnexion, false);
            if (arrivee == ServiceParrainage.Arrivee.SOI_MEME) {
                rouvrir(uuid, premiereConnexion, texte("decouverte-erreur-soi", Map.of()));
                return;
            }
            depot.enregistrer(uuid, SOURCE_JOUEUR, j.uuid(), "questionnaire");
            // a pending referral sends its own welcome message; otherwise just thanks
            if (arrivee != ServiceParrainage.Arrivee.CREEE) envoyer(uuid, "decouverte-merci-joueur", Map.of("joueur", j.pseudo()));
        });
    }

    private void rouvrir(UUID uuid, Instant premiereConnexion, String erreur) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) ouvrirPseudo(p, premiereConnexion, erreur);
        });
    }

    private void envoyer(UUID uuid, String cle, Map<String, String> vars) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(langue.getMessage(cle, vars));
        });
    }

    private interface Tache {
        void executer() throws SQLException;
    }

    private void async(Tache tache) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                tache.executer();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur BDD du questionnaire de découverte", e);
            }
        });
    }

    // ------------------------------------------------------------------ admin

    /** {@code /parrainage admin decouverte <joueur>}: how this player discovered the server. */
    public void consulter(CommandSender sender, String nom) {
        OfflinePlayer cible = Bukkit.getOfflinePlayer(nom);
        async(() -> {
            Optional<DepotDecouverte.Decouverte> d = depot.trouver(cible.getUniqueId());
            String message;
            if (d.isEmpty()) {
                message = texte("decouverte-info-aucune", Map.of("joueur", nom));
            } else {
                DepotDecouverte.Decouverte dec = d.get();
                String parrainNom = dec.parrain() == null ? "" : depotJoueur.trouverParUUID(dec.parrain())
                        .map(JoueurParrainage::pseudo).orElse(dec.parrain().toString());
                message = texte("decouverte-info", Map.of("joueur", nom, "source", libelle(dec.source(), parrainNom),
                        "detail", dec.detail() == null ? "" : net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(dec.detail()),
                        "date", dec.date() == null ? "?" : fr.influencraft.flux.api.util.Format.date(dec.date().getTime())));
            }
            String m = message;
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(fr.influencraft.flux.api.text.Texts.parse(m)));
        });
    }

    /** {@code /parrainage admin decouvertes}: players per source. */
    public void statistiques(CommandSender sender) {
        async(() -> {
            Map<String, Integer> compte = depot.compter();
            int total = compte.values().stream().mapToInt(Integer::intValue).sum();
            StringBuilder sb = new StringBuilder(texte("decouverte-stats-entete", Map.of("total", String.valueOf(total))));
            for (Map.Entry<String, Integer> e : compte.entrySet()) {
                int pct = total == 0 ? 0 : Math.round(e.getValue() * 100f / total);
                sb.append("<newline>").append(texte("decouverte-stats-ligne", Map.of("source", libelle(e.getKey(), ""),
                        "nombre", String.valueOf(e.getValue()), "pourcent", String.valueOf(pct))));
            }
            String m = sb.toString();
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(fr.influencraft.flux.api.text.Texts.parse(m)));
        });
    }

    private String libelle(String source, String parrain) {
        if ("adresse".equals(source)) return texte("decouverte-source-adresse", Map.of("joueur", parrain));
        if (SOURCE_JOUEUR.equals(source)) return parrain.isEmpty() ? texte("decouverte-source-joueurs", Map.of())
                : texte("decouverte-source-joueur", Map.of("joueur", parrain));
        if (SOURCE_AUTRE.equals(source)) return texte("decouverte-bouton-autre", Map.of());
        return config.getSourcesQuestionnaire().getOrDefault(source, source);
    }
}
