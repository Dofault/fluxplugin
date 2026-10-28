package fr.influencraft.fluxtools;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * XP d'outil par bloc, calculée au chargement depuis les métiers EcoJobs du serveur (section {@code xp-metiers} de
 * la config) : un changement d'XP dans EcoJobs suit sur les outils au prochain {@code /fluxitems reload}.
 * <p>1 point = 1 seconde de farm. Chaque métier a sa propre échelle (une XP de Mineur ne vaut pas une XP de
 * Rochiste) : son bloc de référence se mine à {@code par-minute} unités par minute, donc
 * points(bloc) = 60 x XP(bloc) / (par-minute x XP(référence)). Ne dépend pas du joueur (métier actif, niveau,
 * bonus d'EcoJobs). Les {@code points} écrits dans le fichier d'un outil (rythmes forcés) passent devant.</p>
 */
final class PointsMetiers {

    private PointsMetiers() {
    }

    /** famille -> {bloc: points}. */
    static Map<String, Map<String, Double>> calculer(ConfigurationSection conf, File plugins, Logger log) {
        Map<String, Map<String, Double>> out = new HashMap<>();
        if (conf == null) return out;
        File dossier = new File(plugins, conf.getString("dossier-ecojobs", "EcoJobs/jobs"));
        ConfigurationSection metiers = conf.getConfigurationSection("metiers");
        ConfigurationSection familles = conf.getConfigurationSection("familles");
        if (metiers == null || familles == null) return out;
        Map<String, Map<String, Double>> parMetier = new HashMap<>();
        for (String job : metiers.getKeys(false)) {
            Map<String, Double> xp = xpMetier(new File(dossier, job + ".yml"));
            String base = metiers.getString(job + ".bloc", "");
            double parMinute = metiers.getDouble(job + ".par-minute", 0);
            Double xpBase = xp.get(base);
            if (xp.isEmpty() || xpBase == null || xpBase <= 0 || parMinute <= 0) {
                log.warning("xp-metiers : métier " + job + " illisible (fichier, bloc de référence " + base + " ou rythme), ignoré.");
                continue;
            }
            double xpMinute = parMinute * xpBase;
            Map<String, Double> pts = new LinkedHashMap<>();
            xp.forEach((bloc, v) -> pts.put(bloc, Math.round(6000 * v / xpMinute) / 100.0));
            parMetier.put(job, pts);
        }
        for (String famille : familles.getKeys(false)) {
            Map<String, Double> pts = new HashMap<>();
            for (String job : familles.getStringList(famille)) {
                Map<String, Double> p = parMetier.get(job.toLowerCase(Locale.ROOT));
                if (p != null) pts.putAll(p); // métier suivant de la liste prioritaire, comme le générateur
            }
            out.put(famille.toLowerCase(Locale.ROOT), pts);
        }
        return out;
    }

    /** {bloc: XP} d'un métier EcoJobs ({@code xp-gain-methods}, valeur ou multiplicateur, premier trouvé). */
    private static Map<String, Double> xpMetier(File fichier) {
        Map<String, Double> xp = new LinkedHashMap<>();
        if (!fichier.isFile()) return xp;
        YamlConfiguration c = YamlConfiguration.loadConfiguration(fichier);
        for (Map<?, ?> m : c.getMapList("xp-gain-methods")) {
            Object valeur = m.get("value") != null ? m.get("value") : m.get("multiplier");
            double v;
            try {
                v = valeur == null ? 1 : Double.parseDouble(valeur.toString());
            } catch (NumberFormatException e) {
                continue;
            }
            if (!(m.get("filters") instanceof Map<?, ?> filtres) || !(filtres.get("blocks") instanceof List<?> blocs)) continue;
            for (Object b : blocs) xp.putIfAbsent(b.toString().toLowerCase(Locale.ROOT), v);
        }
        return xp;
    }
}
