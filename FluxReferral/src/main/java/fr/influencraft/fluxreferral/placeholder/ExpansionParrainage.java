package fr.influencraft.fluxreferral.placeholder;

import fr.influencraft.fluxreferral.FluxReferralPlugin;
import fr.influencraft.fluxreferral.configuration.GestionnaireConfig;
import fr.influencraft.fluxreferral.modele.Palier;
import fr.influencraft.fluxreferral.modele.Profil;
import fr.influencraft.fluxreferral.service.ServiceClassement;
import fr.influencraft.fluxreferral.service.ServiceParrainage;
import fr.influencraft.fluxreferral.service.ServiceProfil;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Extension PlaceholderAPI pour le système de parrainage, identifiant {@code parrainage}.
 * Tout est lu dans le cache mémoire ({@link ServiceProfil}, {@link ServiceClassement}) :
 * aucun accès base de données pendant l'affichage d'un menu.
 *
 * <p>Index (n, i, j) à partir de 1. Une entrée absente renvoie une chaîne vide (sauf mention).</p>
 * <ul>
 *   <li>{@code code}, {@code adresse}, {@code domaine}, {@code filleuls}, {@code en_attente},
 *       {@code rang} (0 sans filleul), {@code parrain} (« Aucun »), {@code a_parrain} (oui/attente/non),
 *       {@code parrain_attente}, {@code attente_progression} (« 34/60 min »)</li>
 *   <li>{@code prochain_palier} (seuil ou MAX), {@code restant}, {@code progression} (barre),
 *       {@code pourcentage}, {@code temps_requis}</li>
 *   <li>{@code palier_<i>_seuil|nom|etat|restant|ligne_<j>}, {@code paliers_total} ;
 *       état = {@code verrouille}, {@code disponible} (atteint, pas récupéré) ou {@code obtenu} (récupéré)</li>
 *   <li>{@code filleul_<f>_etat} (mêmes 3 états, pour une case du chemin des filleuls)</li>
 *   <li>{@code a_recuperer} (total de récompenses en attente, parrain et filleul confondus),
 *       {@code filleul_recompense_attente} (oui/non, récompense d'accueil de ce joueur en tant que filleul)</li>
 *   <li>{@code attente_<n>_pseudo|progression}, {@code attente_<n>} (ligne prête à afficher),
 *       {@code recent_<n>}, {@code recompense_parrain_<j>}, {@code recompense_filleul_<j>}</li>
 *   <li>{@code top_<n>_pseudo|filleuls}</li>
 * </ul>
 *
 * @author Entr0pik
 */
public class ExpansionParrainage extends PlaceholderExpansion {

    private static final int LONGUEUR_BARRE = 10;

    private final FluxReferralPlugin plugin;
    private final ServiceProfil serviceProfil;
    private final ServiceClassement serviceClassement;
    private final ServiceParrainage serviceParrainage;
    private final GestionnaireConfig config;

    public ExpansionParrainage(FluxReferralPlugin plugin, ServiceProfil serviceProfil,
                                ServiceClassement serviceClassement, ServiceParrainage serviceParrainage) {
        this.plugin = plugin;
        this.serviceProfil = serviceProfil;
        this.serviceClassement = serviceClassement;
        this.serviceParrainage = serviceParrainage;
        this.config = plugin.getGestionnaireConfig();
    }

    @Override
    public @NotNull String getIdentifier() { return "parrainage"; }

    @Override
    public @NotNull String getAuthor() { return "Entr0pik"; }

    @Override
    public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }

    /** Persiste l'enregistrement entre les rechargements de PlaceholderAPI. */
    @Override
    public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        // Données indépendantes du joueur
        if (params.startsWith("top_")) return traiterTop(params);
        if (params.startsWith("recompense_parrain_")) return ligne(player, config.getDescriptionParrain(), params, "recompense_parrain_");
        if (params.startsWith("recompense_filleul_")) return ligne(player, config.getDescriptionFilleul(), params, "recompense_filleul_");
        switch (params) {
            case "domaine"       -> { return config.getDomaine(); }
            case "temps_requis"  -> { return serviceParrainage.formaterDuree(config.getTempsJeuMinimum()); }
            case "paliers_total" -> { return String.valueOf(config.getPaliers().size()); }
            default -> { }
        }

        if (player == null) return "";
        Profil p = serviceProfil.obtenir(player.getUniqueId());
        if (p == null) {
            // Profil pas encore chargé (connexion) : 0 pour les compteurs lus par les chemins du menu
            return switch (params) {
                case "filleuls", "en_attente", "rang", "restant", "pourcentage" -> "0";
                default -> "…";
            };
        }

        if (params.startsWith("palier_")) return traiterPalier(player, p, params);
        if (params.startsWith("filleul_")) return traiterFilleul(p, params);
        if (params.startsWith("attente_") && !params.equals("attente_progression")) return traiterAttente(p, params);
        if (params.startsWith("recent_")) {
            int n = entier(params.substring("recent_".length()));
            return n >= 1 && n <= p.recents().size() ? p.recents().get(n - 1) : "";
        }

        Palier prochain = prochainPalier(p);
        return switch (params) {
            case "code"            -> p.code();
            case "adresse", "lien" -> p.adresse();
            case "filleuls"        -> String.valueOf(p.nbFilleuls());
            case "en_attente"      -> String.valueOf(p.attente().size());
            case "rang"            -> String.valueOf(p.rang());
            case "parrain"         -> p.parrain() != null ? p.parrain() : "Aucun";
            case "a_parrain"       -> p.parrain() != null ? "oui" : (p.parrainAttente() != null ? "attente" : "non");
            case "parrain_attente" -> p.parrainAttente() != null ? p.parrainAttente() : "";
            case "attente_progression" -> progressionTemps(p.tempsJoueAttente());
            case "prochain_palier" -> prochain != null ? String.valueOf(prochain.filleuls()) : "MAX";
            case "restant"         -> prochain != null ? String.valueOf(prochain.filleuls() - p.nbFilleuls()) : "0";
            case "pourcentage"     -> String.valueOf(pourcentage(p, prochain));
            case "progression"     -> barre(p, prochain);
            case "a_recuperer"     -> String.valueOf(aRecuperer(p));
            case "filleul_recompense_attente" -> p.filleulRecompenseEnAttente() ? "oui" : "non";
            default                -> null;
        };
    }

    // ====================================================================

    private Palier prochainPalier(Profil p) {
        for (Palier palier : config.getPaliers()) {
            if (palier.filleuls() > p.nbFilleuls()) return palier;
        }
        return null;
    }

    /** Avancement vers le prochain palier, depuis le palier précédent. */
    private int pourcentage(Profil p, Palier prochain) {
        if (prochain == null) return 100;
        int depart = 0;
        for (Palier palier : config.getPaliers()) {
            if (palier.filleuls() <= p.nbFilleuls()) depart = palier.filleuls();
        }
        int etendue = prochain.filleuls() - depart;
        return etendue <= 0 ? 0 : (int) Math.floor(100.0 * (p.nbFilleuls() - depart) / etendue);
    }

    private String barre(Profil p, Palier prochain) {
        int pleins = Math.round(pourcentage(p, prochain) * LONGUEUR_BARRE / 100f);
        return "&#FFD94D" + "■".repeat(pleins) + "&8" + "■".repeat(LONGUEUR_BARRE - pleins);
    }

    private String traiterPalier(OfflinePlayer player, Profil p, String params) {
        // palier_<i>_<champ>
        String reste = params.substring("palier_".length());
        int sep = reste.indexOf('_');
        if (sep < 0) return "";
        int i = entier(reste.substring(0, sep));
        String champ = reste.substring(sep + 1);
        List<Palier> paliers = config.getPaliers();
        if (i < 1 || i > paliers.size()) return "";
        Palier palier = paliers.get(i - 1);

        if (champ.startsWith("ligne_")) return ligne(player, palier.description(), champ, "ligne_");
        return switch (champ) {
            case "seuil"   -> String.valueOf(palier.filleuls());
            case "nom"     -> palier.nom();
            case "restant" -> String.valueOf(Math.max(0, palier.filleuls() - p.nbFilleuls()));
            case "etat"    -> !p.paliersObtenus().contains(palier.filleuls()) ? "verrouille"
                                : p.paliersRecuperes().contains(palier.filleuls()) ? "obtenu" : "disponible";
            default        -> "";
        };
    }

    /** filleul_<f>_etat : verrouille / disponible (atteint, récompense pas récupérée) / obtenu. */
    private String traiterFilleul(Profil p, String params) {
        String reste = params.substring("filleul_".length());
        int sep = reste.indexOf('_');
        if (sep < 0 || !reste.endsWith("_etat")) return "";
        int f = entier(reste.substring(0, sep));
        if (f < 1) return "";
        if (f > p.nbFilleuls()) return "verrouille";
        return f <= p.filleulsRecompensesDonnees() ? "obtenu" : "disponible";
    }

    private int aRecuperer(Profil p) {
        int filleuls = Math.max(0, p.nbFilleuls() - p.filleulsRecompensesDonnees());
        int paliers = p.paliersObtenus().size() - p.paliersRecuperes().size();
        return filleuls + paliers + (p.filleulRecompenseEnAttente() ? 1 : 0);
    }

    private String traiterAttente(Profil p, String params) {
        // attente_<n> ou attente_<n>_<champ>
        String reste = params.substring("attente_".length());
        int sep = reste.indexOf('_');
        int n = entier(sep < 0 ? reste : reste.substring(0, sep));
        String champ = sep < 0 ? "" : reste.substring(sep + 1);
        if (n < 1 || n > p.attente().size()) return "";
        Profil.Filleul f = p.attente().get(n - 1);
        return switch (champ) {
            case "pseudo"      -> f.pseudo();
            case "progression" -> progressionTemps(f.tempsJoue());
            case ""            -> "&f" + f.pseudo() + " &8· &#B376E5" + progressionTemps(f.tempsJoue());
            default            -> "";
        };
    }

    /** « 34/60 min » ou « 1h20/2h » selon le temps requis. */
    private String progressionTemps(long secondes) {
        long requis = config.getTempsJeuMinimum();
        long fait = Math.min(secondes, requis);
        if (requis <= 3600) return (fait / 60) + "/" + (requis / 60) + " min";
        return serviceParrainage.formaterDuree(fait) + "/" + serviceParrainage.formaterDuree(requis);
    }

    /** Ligne de description de la config ; ses propres placeholders (%img_bitblock%...) sont résolus. */
    private String ligne(OfflinePlayer player, List<String> lignes, String params, String prefixe) {
        int j = entier(params.substring(prefixe.length()));
        if (j < 1 || j > lignes.size()) return "";
        String texte = lignes.get(j - 1);
        return texte.indexOf('%') >= 0 ? PlaceholderAPI.setPlaceholders(player, texte) : texte;
    }

    private String traiterTop(String params) {
        // Format attendu : top_{position}_pseudo ou top_{position}_filleuls
        String[] parties = params.split("_", 3);
        if (parties.length < 3) return "";
        int position = entier(parties[1]);
        return switch (parties[2]) {
            case "pseudo"   -> {
                String pseudo = serviceClassement.obtenirPseudoPosition(position);
                yield "N/A".equals(pseudo) ? "-" : pseudo;
            }
            case "filleuls" -> String.valueOf(serviceClassement.obtenirNbFilleulsPosition(position));
            default         -> "";
        };
    }

    private static int entier(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
