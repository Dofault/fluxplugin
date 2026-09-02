package fr.influencraft.influbridge;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Résume xp-gain-methods (EcoJobs/jobs/<job>.yml) en texte lisible pour le bouton "XP" du menu
 * détail /métiers — contrairement à rewards-description (JobRewardsFormatter), ces montants sont
 * des valeurs brutes déjà lisibles en config (pas de formule %level% à évaluer), donc lecture
 * directe sans dépendance à NumberUtils. Plafonné à 3 entrées + une ligne de récapitulatif si un
 * job en définit plus (ex: rochiste en a 8, un par palier de rareté de bloc).
 */
final class JobXpFormatter {

    private JobXpFormatter() {
    }

    private static final Map<String, String> TRIGGER_LABELS = Map.ofEntries(
            Map.entry("mine_block", "Miner un bloc"),
            Map.entry("mine_block_cascade", "Miner en cascade"),
            Map.entry("catch_fish", "Pêcher un poisson"),
            Map.entry("kill", "Éliminer un mob"),
            Map.entry("place_block", "Poser un bloc"),
            Map.entry("smelt", "Fondre un item"),
            Map.entry("brew", "Brasser une potion"),
            Map.entry("enchant_item", "Enchanter un objet"),
            Map.entry("craft", "Fabriquer un item"),
            Map.entry("breed", "Reproduire un animal"),
            Map.entry("tame_animal", "Apprivoiser un animal"),
            Map.entry("shear_entity", "Tondre une entité"),
            Map.entry("consume", "Consommer un item"),
            Map.entry("drink", "Boire un item"),
            Map.entry("fill_bucket", "Remplir un seau"),
            Map.entry("villager_trade", "Commercer avec un villageois"),
            Map.entry("compost_item", "Composter un item"),
            Map.entry("plant_custom_crop", "Planter une culture"),
            Map.entry("harvest_custom_crop", "Récolter une culture"),
            Map.entry("bonemeal_crop", "Utiliser de la poudre d'os"),
            Map.entry("catch_entity", "Capturer une entité")
    );

    static List<String> format(String jobId) {
        YamlConfiguration config = EcoJobsConfig.readJob(jobId);
        List<Map<?, ?>> methods = config.getMapList("xp-gain-methods");

        List<String> lines = new ArrayList<>();
        int cap = 3;
        for (int i = 0; i < methods.size() && i < cap; i++) {
            Map<?, ?> method = methods.get(i);
            Object trigger = method.get("trigger");
            Object multiplier = method.get("multiplier");
            if (trigger == null || multiplier == null) continue;

            String label = TRIGGER_LABELS.getOrDefault(trigger.toString(), trigger.toString());
            lines.add("&8» &7" + label + " &8► &#2ECF7B" + multiplier + " &7XP");
        }
        if (methods.size() > cap) {
            lines.add("&8» &7... et &f" + (methods.size() - cap) + " &7autre(s) action(s)");
        }
        return lines;
    }
}
