package fr.influencraft.fluxtools.modules;

import fr.influencraft.flux.api.jobs.Jobs;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code %fluxtools_job_*%} placeholders, read through the jobs service of Flux (EcoJobs today).
 * <ul>
 *   <li>{@code job_active_<job>}: "true"/"false", the player has joined this job</li>
 *   <li>{@code job_can_join_<job>}: "true"/"false", not active yet and a job slot is free</li>
 *   <li>{@code job_join_price_<job>} / {@code job_leave_price_<job>}: price as configured in the job plugin</li>
 *   <li>{@code job_rewards_<job>_<n>}: n-th line of the job's rewards at the player's level</li>
 *   <li>{@code job_xp_<job>_<n>}: n-th line of the job's XP actions summary</li>
 * </ul>
 */
final class JobPlaceholders {

    private JobPlaceholders() {
    }

    static String request(Player player, String params) {
        if (!Jobs.available()) return null;
        Jobs.Provider jobs = Jobs.get();
        if (params.startsWith("job_active_")) {
            return String.valueOf(player != null && jobs.active(player, params.substring("job_active_".length())));
        }
        if (params.startsWith("job_can_join_")) {
            return String.valueOf(player != null && jobs.canJoin(player, params.substring("job_can_join_".length())));
        }
        if (params.startsWith("job_join_price_")) {
            return player == null ? "" : jobs.joinPrice(player, params.substring("job_join_price_".length()));
        }
        if (params.startsWith("job_leave_price_")) {
            return player == null ? "" : jobs.leavePrice(player, params.substring("job_leave_price_".length()));
        }
        if (params.startsWith("job_rewards_")) {
            if (player == null) return "";
            return line(params.substring("job_rewards_".length()), job -> jobs.rewards(player, job));
        }
        if (params.startsWith("job_xp_")) {
            return player == null ? "" : line(params.substring("job_xp_".length()), job -> JobXpFormatter.format(player, job));
        }
        return null;
    }

    /** "<job>_<n>": n-th line (1-based) of the lines produced for the job, empty when out of range. */
    private static String line(String remainder, java.util.function.Function<String, List<String>> lines) {
        int underscore = remainder.lastIndexOf('_');
        if (underscore < 0) return "";
        int n;
        try {
            n = Integer.parseInt(remainder.substring(underscore + 1));
        } catch (NumberFormatException e) {
            return "";
        }
        List<String> all = lines.apply(remainder.substring(0, underscore));
        return n < 1 || n > all.size() ? "" : all.get(n - 1);
    }
}
