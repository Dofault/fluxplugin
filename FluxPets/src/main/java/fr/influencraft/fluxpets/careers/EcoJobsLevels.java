package fr.influencraft.fluxpets.careers;

import com.willfp.ecojobs.api.EcoJobsAPI;
import com.willfp.ecojobs.jobs.Job;
import com.willfp.ecojobs.jobs.Jobs;
import org.bukkit.entity.Player;

/** Seul endroit qui touche à l'API EcoJobs : chargé uniquement si EcoJobs est actif. */
final class EcoJobsLevels {

    private EcoJobsLevels() {
    }

    /** @return le niveau du joueur dans ce métier, -1 si le métier n'existe pas. */
    static int level(Player player, String jobId) {
        Job job = Jobs.getByID(jobId);
        return job == null ? -1 : EcoJobsAPI.getJobLevel(player, job);
    }

    /** @return le nom affiché du métier, ou son identifiant. */
    static String name(String jobId) {
        Job job = Jobs.getByID(jobId);
        return job == null ? jobId : job.getName();
    }
}
