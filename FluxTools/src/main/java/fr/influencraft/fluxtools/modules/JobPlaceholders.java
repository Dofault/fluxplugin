package fr.influencraft.fluxtools.modules;

import com.willfp.ecojobs.api.EcoJobsAPI;
import com.willfp.ecojobs.jobs.Job;
import com.willfp.ecojobs.jobs.Jobs;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code %fluxtools_job_*%} placeholders, kept apart from {@link ToolsExpansion} so that EcoJobs classes are only
 * loaded when EcoJobs is installed.
 * <ul>
 *   <li>{@code job_active_<job>}: "true"/"false", the player has joined this job</li>
 *   <li>{@code job_can_join_<job>}: "true"/"false", not active yet and a job slot is free
 *   ({@code ecojobs.limit.<n>} permission)</li>
 *   <li>{@code job_join_price_<job>} / {@code job_leave_price_<job>}: price as configured in EcoJobs</li>
 *   <li>{@code job_rewards_<job>_<n>}: n-th line of the job's rewards-description at the player's level</li>
 *   <li>{@code job_xp_<job>_<n>}: n-th line of the job's xp-gain-methods summary</li>
 * </ul>
 */
final class JobPlaceholders {

    private JobPlaceholders() {
    }

    static String request(Player player, String params) {
        if (params.startsWith("job_active_")) {
            Job job = Jobs.getByID(params.substring("job_active_".length()));
            return String.valueOf(player != null && job != null && EcoJobsAPI.hasJobActive(player, job));
        }
        if (params.startsWith("job_can_join_")) {
            Job job = Jobs.getByID(params.substring("job_can_join_".length()));
            return String.valueOf(player != null && job != null && EcoJobsAPI.canJoinJob(player, job));
        }
        if (params.startsWith("job_join_price_")) {
            Job job = Jobs.getByID(params.substring("job_join_price_".length()));
            return player == null || job == null ? "" : job.getJoinPrice().getDisplay(player);
        }
        if (params.startsWith("job_leave_price_")) {
            Job job = Jobs.getByID(params.substring("job_leave_price_".length()));
            return player == null || job == null ? "" : job.getLeavePrice().getDisplay(player);
        }
        if (params.startsWith("job_rewards_")) {
            if (player == null) return "";
            return line(params.substring("job_rewards_".length()), jobId -> JobRewardsFormatter.format(player, jobId));
        }
        if (params.startsWith("job_xp_")) {
            return line(params.substring("job_xp_".length()), JobXpFormatter::format);
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
