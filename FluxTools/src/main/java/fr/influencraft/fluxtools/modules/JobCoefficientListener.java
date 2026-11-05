package fr.influencraft.fluxtools.modules;

import fr.influencraft.flux.api.jobs.JobLevelUpEvent;
import fr.influencraft.flux.api.FluxPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Locale;

/**
 * Tells the player how a job level changes what he earns: a job pays its base amount x level / 100, so level 1 pays
 * x0.01 and level 400 pays x4. Displayed only, the money itself is in the job's effects.
 */
final class JobCoefficientListener implements Listener {

    private final FluxPlugin plugin;

    JobCoefficientListener(FluxPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onLevelUp(JobLevelUpEvent event) {
        plugin.messages().send(event.player(), "job-coefficient",
                "%before%", coefficient(event.level() - 1), "%after%", coefficient(event.level()));
    }

    private static String coefficient(int level) {
        return String.format(Locale.FRANCE, "%.2f", Math.max(0, level) / 100.0);
    }
}
