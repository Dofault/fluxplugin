package fr.influencraft.fluxreferral.configuration;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.text.Messages;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.fluxreferral.FluxReferralPlugin;
import net.kyori.adventure.text.Component;

import java.io.File;
import java.util.Map;

/**
 * Player-facing texts of the referral system, read through Flux's {@link Messages}: {@code lang/<language>.yml}
 * (suite language), new keys appended on update, legacy {@code &} codes and MiniMessage mixed, {@code [/command]}
 * clickable. Since 2026-11-09 (before: {@code langue_fr.yml} with a {@code messages:} section, prefix in config.yml).
 *
 * <ul>
 *   <li>{@code prefix} goes before every chat message; a text starting with {@code !} is sent without it.</li>
 *   <li>Variables are written {@code {name}}; {@code {code}} and {@code {adresse}} become click-to-copy.</li>
 * </ul>
 */
public class GestionnaireLangue {

    private final FluxReferralPlugin plugin;
    private final Messages messages;

    public GestionnaireLangue(FluxReferralPlugin plugin) {
        this.plugin = plugin;
        this.messages = Messages.load(plugin, FluxApi.language());
        retireAncienFichier();
    }

    public void charger() {
        messages.reload();
    }

    /** Flux's messages of the plugin (paged help: {@code help.*}). */
    public Messages messages() {
        return messages;
    }

    /**
     * Chat message: prefix (unless the text starts with {@code !}), variables, click-to-copy code and address. A list
     * key gives one line per entry, each with its own prefix rule.
     */
    public Component getMessage(String cle, Map<String, String> variables) {
        Component out = null;
        for (String ligne : messages.rawList(cle)) {
            if (ligne.isEmpty()) continue;
            Component c = chat(remplacer(ligne, variables, true));
            out = out == null ? c : out.append(Component.newline()).append(c);
        }
        return out != null ? out : chat("&#FF5C5C[" + cle + "]");
    }

    public Component getMessage(String cle) {
        return getMessage(cle, Map.of());
    }

    /**
     * Raw text of a key, variables replaced as plain text, without prefix (a leading {@code !} is dropped): dialog
     * texts, reward labels, staff alerts, or pieces put into another message.
     */
    public String getMessageBrut(String cle, Map<String, String> variables) {
        String brut = remplacer(messages.raw(cle), variables, false);
        return brut.startsWith("!") ? brut.substring(1) : brut;
    }

    /** A text already filled, sent as a chat message: prefix unless it starts with {@code !}. */
    public Component chat(String brut) {
        String texte = brut.startsWith("!") ? brut.substring(1) : messages.raw("prefix") + brut;
        return Texts.parseClickable(texte, null);
    }

    private String remplacer(String brut, Map<String, String> variables, boolean copiable) {
        for (Map.Entry<String, String> e : variables.entrySet()) {
            String valeur = e.getValue() == null ? "" : e.getValue();
            if (copiable && "code".equals(e.getKey())) valeur = copie(valeur, "code-survol-copier");
            else if (copiable && "adresse".equals(e.getKey())) valeur = copie(valeur, "adresse-survol-copier");
            brut = brut.replace("{" + e.getKey() + "}", valeur);
        }
        return brut;
    }

    /** MiniMessage click-to-copy around a value, with its tooltip (quotes and backslashes escaped). */
    private String copie(String valeur, String cleSurvol) {
        String survol = messages.raw(cleSurvol).replace("\\", "\\\\").replace("'", "\\'");
        String echappee = valeur.replace("\\", "\\\\").replace("'", "\\'");
        return "<click:copy_to_clipboard:'" + echappee + "'><hover:show_text:'" + survol + "'>" + valeur + "</hover></click>";
    }

    /** The former {@code langue_fr.yml} is no longer read: renamed once so nobody edits it by mistake. */
    private void retireAncienFichier() {
        File ancien = new File(plugin.getDataFolder(), "langue_fr.yml");
        if (ancien.exists() && ancien.renameTo(new File(plugin.getDataFolder(), "langue_fr.yml.old"))) {
            plugin.getLogger().info("langue_fr.yml is replaced by lang/" + messages.language() + ".yml (renamed langue_fr.yml.old)");
        }
    }
}
