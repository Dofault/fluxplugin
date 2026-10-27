package fr.influencraft.fluxreferral.configuration;

import fr.influencraft.fluxreferral.FluxReferralPlugin;
import fr.influencraft.fluxreferral.modele.Palier;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gestionnaire de la configuration principale du plugin.
 *
 * @author Entr0pik
 */
public class GestionnaireConfig {

    private final FluxReferralPlugin plugin;
    private FileConfiguration config;
    // Lus une fois par chargement : les placeholders du menu les consultent à chaque rafraîchissement
    private List<Palier> paliers = List.of();

    public GestionnaireConfig(FluxReferralPlugin plugin) {
        this.plugin = plugin;
        charger();
    }

    public void charger() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        this.paliers = List.copyOf(lirePaliers());
        plugin.getLogger().info("Configuration chargée avec succès.");
    }

    // --- Base de données ---
    public String getModeBDD()          { return config.getString("base-de-donnees.mode", "auto"); }
    public String getHoteBDD()          { return config.getString("base-de-donnees.hote", "localhost"); }
    public int    getPortBDD()          { return config.getInt("base-de-donnees.port", 3306); }
    public String getNomBDD()           { return config.getString("base-de-donnees.nom", "influencraft"); }
    public String getUtilisateurBDD()   { return config.getString("base-de-donnees.utilisateur", "root"); }
    public String getMotDePasseBDD()    { return config.getString("base-de-donnees.mot-de-passe", "motdepasse"); }
    public int    getTaillePool()       { return config.getInt("base-de-donnees.taille-pool", 10); }
    public String getPrefixeTables()    { return config.getString("base-de-donnees.prefixe-tables", "parr_"); }
    public String getFichierLocalBDD()  { return config.getString("base-de-donnees.fichier-local", "parrainage.db"); }

    // --- Langue ---
    public String getLangue()           { return config.getString("langue", "fr"); }

    // --- Préfixe messages ---
    public String getPrefixe()          { return config.getString("prefixe", "<dark_gray>[<gold>Parrainage</gold>]</dark_gray>"); }

    // --- Parrainage ---
    public long    getTempsJeuMinimum()       { return config.getLong("parrainage.temps-jeu-minimum", 3600L); }
    public boolean isBloquerCirculaire()      { return config.getBoolean("parrainage.bloquer-circulaire", true); }
    public boolean isGenerationAutoConnexion(){ return config.getBoolean("parrainage.generation-auto-connexion", false); }
    public boolean isBloquerMemeIp()          { return config.getBoolean("parrainage.bloquer-meme-ip", true); }
    public long    getIntervalleVerification(){ return Math.max(10L, config.getLong("parrainage.intervalle-verification", 60L)); }

    // --- Adresse personnelle (<pseudo>.<domaine>) ---
    public boolean isAdresseActive()          { return config.getBoolean("adresse.active", true); }
    public String  getDomaine() {
        String d = config.getString("adresse.domaine", "influencraft.fr").trim().toLowerCase(Locale.ROOT);
        return d.startsWith(".") ? d.substring(1) : d;
    }
    /** Sub-domains of the site and the server, never a personal address (play, www, map...). */
    public java.util.Set<String> getLibellesReserves() {
        return config.getStringList("adresse.reserves").stream().map(d -> d.trim().toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
    }

    // --- Questionnaire: how the player discovered the server ---
    public boolean isQuestionnaireActif()     { return config.getBoolean("decouverte.questionnaire", true); }
    public boolean isAlerteStaff()            { return config.getBoolean("decouverte.alerte-staff", true); }
    public long    getDelaiQuestionnaireTicks() { return Math.max(20L, config.getLong("decouverte.delai-secondes", 5L) * 20L); }
    /** Answers of the questionnaire other than "a player": id -> label, in order. */
    public java.util.Map<String, String> getSourcesQuestionnaire() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        var section = config.getConfigurationSection("decouverte.sources");
        if (section == null) {
            out.put("tiktok", "TikTok");
            out.put("vote", "Site de vote");
            return out;
        }
        for (String id : section.getKeys(false)) out.put(id.toLowerCase(Locale.ROOT), section.getString(id, id));
        return out;
    }
    /** Délai après la première connexion pendant lequel une arrivée par adresse est encore prise en compte. */
    public long    getDelaiArriveeHeures()    { return config.getLong("adresse.delai-arrivee-heures", 48L); }

    // --- Récompenses de parrainage (distribuées à chaque nouveau filleul) ---
    public List<String> getCommandesParrain() {
        return config.getStringList("recompenses-parrainage.parrain.commandes");
    }

    public List<String> getCommandesFilleul() {
        return config.getStringList("recompenses-parrainage.filleul.commandes");
    }

    /** Lignes affichées dans le menu pour décrire la récompense du parrain (couleurs &). */
    public List<String> getDescriptionParrain() {
        return config.getStringList("recompenses-parrainage.parrain.description");
    }

    public List<String> getDescriptionFilleul() {
        return config.getStringList("recompenses-parrainage.filleul.description");
    }

    // --- Paliers ---
    public List<Palier> getPaliers() {
        return paliers;
    }

    private List<Palier> lirePaliers() {
        List<Palier> paliers = new ArrayList<>();
        List<Map<?, ?>> liste = config.getMapList("paliers");
        for (Map<?, ?> entree : liste) {
            int filleuls = ((Number) entree.get("filleuls")).intValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> recompenses = (Map<String, Object>) entree.get("recompenses");
            @SuppressWarnings("unchecked")
            Map<String, Object> parrainBlock = recompenses != null
                ? (Map<String, Object>) recompenses.get("parrain") : null;
            @SuppressWarnings("unchecked")
            List<String> commandes = parrainBlock != null
                ? (List<String>) parrainBlock.get("commandes") : List.of();
            boolean annonceGlobale = recompenses != null
                && Boolean.TRUE.equals(recompenses.get("annonce-globale"));
            Object nom = entree.get("nom");
            List<String> description = new ArrayList<>();
            if (entree.get("description") instanceof List<?> lignes) {
                for (Object ligne : lignes) description.add(String.valueOf(ligne));
            }
            paliers.add(new Palier(filleuls, commandes != null ? commandes : List.of(), annonceGlobale,
                nom != null ? String.valueOf(nom) : filleuls + " filleul" + (filleuls > 1 ? "s" : ""),
                description));
        }
        paliers.sort(Comparator.comparingInt(Palier::filleuls));
        return paliers;
    }

    // --- Classement ---
    public int  getTailleClassement()          { return config.getInt("classement.taille-affichage", 10); }
    public long getIntervaleRafraichissement() { return config.getLong("classement.intervalle-rafraichissement", 300L); }
}
