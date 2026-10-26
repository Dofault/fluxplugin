package fr.influencraft.influlands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
 * FluxMenu. Lands écrit en MiniMessage ({@code <#FFD54F>}, {@code <bold>}) et, depuis la
 * 8.x, avec des jetons de thème ({@code <t:regular>}) définis dans
 * {@code Lands/Locale/theme.yml}. FluxMenu lit les couleurs {@code &} et {@code &#RRGGBB} :
 * on remplace les jetons par leur style, on passe par MiniMessage puis on resérialise en
 * couleurs {@code &}.
 */
final class Couleurs {

    private static final Pattern JETON = Pattern.compile("<t:([a-zA-Z0-9_\\-]+)>");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexCharacter('#').hexColors().build();

    private static Map<String, String> jetons = Map.of();

    private Couleurs() {
    }

    /** Relit les jetons du thème de Lands (appelé au démarrage et au /influlands reload). */
    static void charger(Plugin lands) {
        Map<String, String> m = new HashMap<>();
        if (lands != null) {
            File f = new File(lands.getDataFolder(), "Locale/theme.yml");
            if (f.exists()) {
                ConfigurationSection s = YamlConfiguration.loadConfiguration(f).getConfigurationSection("theme.tokens");
                if (s != null) for (String k : s.getKeys(false)) m.put(k, s.getString(k, ""));
            }
        }
        jetons = m;
    }

    private static Component composant(String brut) {
        if (brut == null || brut.isEmpty()) return Component.empty();
        String s = brut;
        if (s.indexOf('<') < 0) return LEGACY.deserialize(s); // déjà en & (ou texte nu)
        Matcher mt = JETON.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (mt.find()) mt.appendReplacement(sb, Matcher.quoteReplacement(jetons.getOrDefault(mt.group(1), "")));
        mt.appendTail(sb);
        s = sb.toString().replace("</t>", "");
        try {
            return MiniMessage.miniMessage().deserialize(s);
        } catch (RuntimeException e) {
            return Component.text(s.replaceAll("<[^>]*>", ""));
        }
    }

    /** Texte coloré au format & / &#RRGGBB. */
    static String legacy(String brut) {
        return LEGACY.serialize(composant(brut));
    }

    /** Texte sans aucune couleur. */
    static String nu(String brut) {
        String s = PlainTextComponentSerializer.plainText().serialize(composant(brut));
        return s.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "").replaceAll("&#[0-9a-fA-F]{6}", "").trim();
    }
}
