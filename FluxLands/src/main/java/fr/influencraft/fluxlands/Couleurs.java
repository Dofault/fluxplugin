package fr.influencraft.fluxlands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Textes de Lands (noms colorés des villes, niveaux, rôles, nations, drapeaux) convertis pour
 * Flux. Lands écrit en MiniMessage ({@code <#FFD54F>}, {@code <bold>}) et, depuis la
 * 8.x, avec des jetons de thème ({@code <t:regular>}) définis dans
 * {@code Lands/Locale/theme.yml}. Flux lit les couleurs {@code &} et {@code &#RRGGBB} :
 * on remplace les jetons par leur style, on passe par MiniMessage puis on resérialise en
 * couleurs {@code &}.
 */
final class Couleurs {

    /** {@code <t:name>} opens a theme token, {@code </t>} goes back to the enclosing one (as in Lands' locale). */
    private static final Pattern JETON = Pattern.compile("<t:([a-zA-Z0-9_\\-]+)>|</t>");
    private static final Pattern DECORATION = Pattern.compile("<(b|bold|i|italic|em|u|underlined|st|strikethrough|obf|obfuscated)>");

    private static Map<String, String> jetons = Map.of();
    private static final String PREFIXE_DEFAUT = "<t:prefixe>ᴠɪʟʟᴇꜱ</t><t:prefixe_fleche> ▸ </t>";
    /** Lands' chat prefix ({@code general.prefix.normal} of its French locale), with its theme tokens. */
    private static String prefixe = PREFIXE_DEFAUT;

    private Couleurs() {
    }

    /**
     * Reads Lands' theme again (start and /fluxlands reload): native tokens ({@code theme.tokens}, used by its menus)
     * and the server's message tokens ({@code theme.custom}: texte, valeur, ville, commande...), plus its chat prefix.
     * Custom names never reuse a native one (docs/notifications/villes.md), so one map holds both.
     */
    static void charger(Plugin lands) {
        Map<String, String> m = new HashMap<>();
        String pre = PREFIXE_DEFAUT;
        if (lands != null) {
            File f = new File(lands.getDataFolder(), "Locale/theme.yml");
            if (f.exists()) {
                YamlConfiguration theme = YamlConfiguration.loadConfiguration(f);
                for (String section : new String[]{"theme.tokens", "theme.custom"}) {
                    ConfigurationSection s = theme.getConfigurationSection(section);
                    if (s != null) for (String k : s.getKeys(false)) m.put(k, s.getString(k, ""));
                }
            }
            File locale = new File(lands.getDataFolder(), "Locale/fr-FR.yml");
            if (locale.exists()) pre = YamlConfiguration.loadConfiguration(locale).getString("general.prefix.normal", pre);
        }
        jetons = m;
        prefixe = pre;
    }

    /** Lands' chat prefix, as theme-token text. */
    static String prefixe() {
        return prefixe;
    }

    private static Component composant(String brut) {
        if (brut == null || brut.isEmpty()) return Component.empty();
        String s = brut;
        if (s.indexOf('<') < 0) return fr.influencraft.flux.api.text.Texts.colors(s); // déjà en & (ou texte nu)
        Matcher mt = JETON.matcher(s);
        StringBuilder sb = new StringBuilder();
        java.util.Deque<String> ouverts = new java.util.ArrayDeque<>();
        while (mt.find()) {
            String remplacement;
            if (mt.group(1) != null) {
                ouverts.push(mt.group(1));
                remplacement = jetons.getOrDefault(mt.group(1), "");
            } else {
                // closing: undo the decorations of the closed token, then the enclosing token's style again
                String ferme = ouverts.isEmpty() ? "" : jetons.getOrDefault(ouverts.pop(), "");
                StringBuilder r = new StringBuilder();
                Matcher d = DECORATION.matcher(ferme);
                while (d.find()) r.append("<!").append(d.group(1)).append('>');
                if (!ouverts.isEmpty()) r.append(jetons.getOrDefault(ouverts.peek(), ""));
                remplacement = r.toString();
            }
            mt.appendReplacement(sb, Matcher.quoteReplacement(remplacement));
        }
        mt.appendTail(sb);
        s = sb.toString();
        try {
            return MiniMessage.miniMessage().deserialize(s);
        } catch (RuntimeException e) {
            return Component.text(s.replaceAll("<[^>]*>", ""));
        }
    }

    /** Texte coloré au format & / &#RRGGBB. */
    static String legacy(String brut) {
        return fr.influencraft.flux.api.text.Texts.ampersand(composant(brut));
    }

    /** Texte sans aucune couleur. */
    static String nu(String brut) {
        String s = PlainTextComponentSerializer.plainText().serialize(composant(brut));
        return s.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "").replaceAll("&#[0-9a-fA-F]{6}", "").trim();
    }
}
