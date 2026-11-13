package fr.influencraft.fluxjobs;

import fr.influencraft.flux.api.jobs.JobAction;
import fr.influencraft.flux.api.util.Format;
import org.bukkit.command.CommandSender;

/** Texts of FluxJobs (lang/<language>.yml), for the classes that are not given the plugin. */
final class Lang {

    private static volatile FluxJobs plugin;

    private Lang() {
    }

    static void bind(FluxJobs p) {
        plugin = p;
    }

    static String t(String key, String... vars) {
        FluxJobs p = plugin;
        return p == null ? key : p.messages().raw(key, vars);
    }

    static void send(CommandSender to, String key, String... vars) {
        FluxJobs p = plugin;
        if (p != null) p.messages().send(to, key, vars);
    }

    /** Label of a kind of action ({@code actions.triggers.<trigger>}), the trigger id when unknown. */
    static String triggerLabel(String trigger) {
        String label = t("actions.triggers." + trigger);
        return label.isEmpty() || label.equals("actions.triggers." + trigger) ? trigger : label;
    }

    /** Money line of a paid action: none, per health point, per action or every n actions. */
    static String money(JobAction a, String amount) {
        if (a.money() == null) return t("actions.money-none");
        if (a.perHealth()) return t("actions.money-per-health", "%amount%", amount);
        if (a.every() <= 1) return t("actions.money-per-action", "%amount%", amount);
        return t("actions.money-every", "%amount%", amount, "%every%", Format.decimal(a.every()));
    }
}
