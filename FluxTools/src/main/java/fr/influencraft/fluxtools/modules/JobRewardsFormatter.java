package fr.influencraft.fluxtools.modules;

import com.willfp.eco.util.NumberUtils;
import com.willfp.ecojobs.api.EcoJobsAPI;
import com.willfp.ecojobs.jobs.Job;
import com.willfp.ecojobs.jobs.Jobs;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * Reproduit à l'identique ce que fait Job.injectPlaceholdersInto côté EcoJobs (interne, pas
 * exposé en API) pour pouvoir afficher rewards-description en dehors des menus natifs EcoJobs :
 * substitue %level% par le niveau réel du joueur dans chaque formule de level-placeholders,
 * évalue via NumberUtils.evaluateExpression (même moteur que EcoJobs, package com.willfp.eco.util),
 * puis injecte le résultat formaté dans le texte de rewards-description. Ne duplique aucune
 * valeur : lit toujours EcoJobs/jobs/<job>.yml sur disque (EcoJobsConfig.readJob).
 */
final class JobRewardsFormatter {

    private JobRewardsFormatter() {
    }

    static List<String> format(Player player, String jobId) {
        Job job = Jobs.getByID(jobId);
        if (job == null) return List.of();

        YamlConfiguration config = EcoJobsConfig.readJob(jobId);
        int level = Math.max(1, EcoJobsAPI.getJobLevel(player, job));
        int descriptionLevel = closestDefinedLevel(config, level);

        List<String> lines = config.getStringList("rewards-description." + descriptionLevel);
        if (lines.isEmpty()) return List.of();

        for (Map<?, ?> entry : config.getMapList("level-placeholders")) {
            Object id = entry.get("id");
            Object value = entry.get("value");
            if (id == null || value == null) continue;

            String formula = value.toString().replace("%level%", String.valueOf(level));
            double result = NumberUtils.evaluateExpression(formula);
            String formatted = NumberUtils.format(result);
            String token = "%" + id + "%";

            lines = lines.stream().map(line -> line.replace(token, formatted)).toList();
        }

        return lines;
    }

    // rewards-description est indexé par niveau (clé "1", "2"...) — reprend la plus haute
    // clé <= au niveau réel du joueur, ou la plus petite clé définie si aucune ne convient.
    private static int closestDefinedLevel(YamlConfiguration config, int playerLevel) {
        ConfigurationSection section = config.getConfigurationSection("rewards-description");
        if (section == null) return 1;

        int smallest = Integer.MAX_VALUE;
        int best = -1;
        for (String key : section.getKeys(false)) {
            try {
                int keyLevel = Integer.parseInt(key);
                smallest = Math.min(smallest, keyLevel);
                if (keyLevel <= playerLevel && keyLevel > best) {
                    best = keyLevel;
                }
            } catch (NumberFormatException ignored) {
                // Clé non numérique, ignorée.
            }
        }
        return best >= 0 ? best : (smallest == Integer.MAX_VALUE ? 1 : smallest);
    }
}
