package fr.influencraft.fluxtools.modules;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Summary of a job's XP actions (Flux jobs service) for the "XP" button of the jobs menu: one line per kind of
 * action, at most 3 plus a count of the others.
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

    static List<String> format(org.bukkit.entity.Player player, String jobId) {
        List<fr.influencraft.flux.api.jobs.JobAction> actions = fr.influencraft.flux.api.jobs.Jobs.get().actions(player, jobId);
        // one line per kind of action (the block grid lists one action per block)
        Map<String, Double> byTrigger = new java.util.LinkedHashMap<>();
        for (var action : actions) byTrigger.putIfAbsent(action.label() != null ? action.label() : action.trigger(), action.xp());
        List<String> lines = new ArrayList<>();
        int cap = 3, i = 0;
        for (Map.Entry<String, Double> e : byTrigger.entrySet()) {
            if (i++ >= cap) break;
            String label = TRIGGER_LABELS.getOrDefault(e.getKey(), e.getKey());
            lines.add("&8» &7" + label + " &8► &#2ECF7B" + fr.influencraft.flux.api.util.Format.decimal(e.getValue()) + " &7XP");
        }
        if (byTrigger.size() > cap) {
            lines.add("&8» &7... et &f" + (byTrigger.size() - cap) + " &7autre(s) action(s)");
        }
        return lines;
    }
}
