package fr.influencraft.fluxsorter;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.Map;

/**
 * Vue de la config avec les clés anglaises (version publique). Une config écrite avec les clés
 * françaises d'avant la version publique (nos serveurs) est lue telle quelle : chaque segment de
 * chemin français est renommé à la volée, le fichier n'est jamais réécrit. Valeurs absentes =
 * config par défaut du jar.
 */
final class Settings {

    private static final Map<String, String> FR_TO_EN = Map.ofEntries(
            Map.entry("niveaux", "levels"),
            Map.entry("rayon", "radius"),
            Map.entry("intervalle-ticks", "interval-ticks"),
            Map.entry("vol", "flight"),
            Map.entry("ticks-par-bloc", "ticks-per-block"),
            Map.entry("actif", "enabled"),
            Map.entry("particules", "particles"),
            Map.entry("distance-joueur", "player-distance"),
            Map.entry("objet", "item"),
            Map.entry("nom", "name"),
            Map.entry("hors-ville", "outside-land"),
            Map.entry("joueur-introuvable", "player-not-found"));

    private Settings() {
    }

    static ConfigurationSection of(Configuration file) {
        MemoryConfiguration view = new MemoryConfiguration();
        if (file.getDefaults() != null) view.setDefaults(file.getDefaults());
        for (String path : file.getKeys(true)) {
            if (file.isConfigurationSection(path)) continue;
            Object value = file.get(path);
            StringBuilder en = new StringBuilder();
            for (String segment : path.split("\\.")) {
                if (en.length() > 0) en.append('.');
                en.append(FR_TO_EN.getOrDefault(segment, segment));
            }
            view.set(en.toString(), value);
        }
        return view;
    }
}
