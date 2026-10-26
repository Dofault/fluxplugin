package fr.influencraft.fluxreferral.configuration;

import fr.influencraft.fluxreferral.FluxReferralPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Gestionnaire des fichiers de langue du plugin.
 * Charge le fichier langue_{code}.yml et résout les messages avec MiniMessage.
 * Les variables {code} et {adresse} sont automatiquement rendues cliquables (copie presse-papier).
 *
 * @author Entr0pik
 */
public class GestionnaireLangue {

    private final FluxReferralPlugin plugin;
    private final GestionnaireConfig gestionnaireConfig;
    private final MiniMessage miniMessage;
    private FileConfiguration langue;

    public GestionnaireLangue(FluxReferralPlugin plugin, GestionnaireConfig gestionnaireConfig) {
        this.plugin = plugin;
        this.gestionnaireConfig = gestionnaireConfig;
        this.miniMessage = MiniMessage.miniMessage();
        charger();
    }

    public void charger() {
        String codeLangue = gestionnaireConfig.getLangue();
        String nomFichier = "langue_" + codeLangue + ".yml";
        File fichierLangue = new File(plugin.getDataFolder(), nomFichier);

        if (!fichierLangue.exists()) {
            plugin.saveResource(nomFichier, false);
        }

        this.langue = YamlConfiguration.loadConfiguration(fichierLangue);

        InputStream defautStream = plugin.getResource(nomFichier);
        if (defautStream != null) {
            YamlConfiguration defaut = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defautStream, StandardCharsets.UTF_8)
            );
            langue.setDefaults(defaut);
        }

        plugin.getLogger().info("Fichier de langue '" + nomFichier + "' chargé avec succès.");
    }

    /**
     * Résout un message localisé. Le placeholder {prefixe} est injecté automatiquement.
     * Les placeholders {code} et {adresse} sont rendus cliquables (copy_to_clipboard + hover).
     * Tous les autres placeholders sont substitués en texte brut.
     *
     * @param cle       clé dans messages.* du fichier de langue
     * @param variables paires clé/valeur à substituer (ex: "joueur" → "Steve")
     * @return composant Adventure désérialisé
     */
    public Component getMessage(String cle, Map<String, String> variables) {
        String brut = langue.getString("messages." + cle, "<red>[Parrainage] Message manquant : " + cle);
        brut = brut.replace("{prefixe}", gestionnaireConfig.getPrefixe());
        for (Map.Entry<String, String> entree : variables.entrySet()) {
            if ("code".equals(entree.getKey())) {
                brut = brut.replace("{code}", construireTagCopie(entree.getValue(), "code-survol-copier"));
            } else if ("adresse".equals(entree.getKey())) {
                brut = brut.replace("{adresse}", construireTagCopie(entree.getValue(), "adresse-survol-copier"));
            } else {
                brut = brut.replace("{" + entree.getKey() + "}", entree.getValue());
            }
        }
        return miniMessage.deserialize(brut);
    }

    public Component getMessage(String cle) {
        return getMessage(cle, Map.of());
    }

    /**
     * Retourne le texte brut MiniMessage d'un message après substitution des variables.
     * Le placeholder {code} est remplacé en texte simple (sans click event).
     * Utilisé pour les annonces globales multi-joueurs et les textes embarqués dans d'autres messages.
     */
    public String getMessageBrut(String cle, Map<String, String> variables) {
        String brut = langue.getString("messages." + cle, "<red>[Parrainage] Message manquant : " + cle);
        brut = brut.replace("{prefixe}", gestionnaireConfig.getPrefixe());
        for (Map.Entry<String, String> entree : variables.entrySet()) {
            brut = brut.replace("{" + entree.getKey() + "}", entree.getValue());
        }
        return brut;
    }

    /**
     * Construit la balise MiniMessage pour un code cliquable.
     * Produit un click:copy_to_clipboard avec infobulle hover.
     * Les guillemets simples présents dans les valeurs sont échappés pour l'attribut MiniMessage.
     */
    private String construireTagCopie(String valeurCode, String cleSurvol) {
        String tooltipBrut = langue.getString("messages." + cleSurvol, "<gray>Clique pour copier");
        tooltipBrut = tooltipBrut.replace("{prefixe}", gestionnaireConfig.getPrefixe());

        // Échapper les antislash puis les guillemets simples (délimiteurs MiniMessage)
        String tooltipEchappe = tooltipBrut.replace("\\", "\\\\").replace("'", "\\'");
        String codeEchappe   = valeurCode  .replace("\\", "\\\\").replace("'", "\\'");

        return "<click:copy_to_clipboard:'" + codeEchappe + "'>"
             + "<hover:show_text:'"         + tooltipEchappe + "'>"
             + valeurCode
             + "</hover></click>";
    }
}
