package fr.influencraft.influbridge;

import com.willfp.ecojobs.api.EcoJobsAPI;
import com.willfp.ecojobs.jobs.Job;
import com.willfp.ecojobs.jobs.Jobs;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Expose les placeholders PlaceholderAPI de InfluBridge (un seul identifiant
 * "influbridge" possible côté PAPI, donc un seul expansion pour tout le plugin) :
 * - %influbridge_flame_*% : streak de vote calculé par le site web (StreakCacheService,
 *   peut être null si api-base-url n'est pas configuré)
 * - %influbridge_voteparty_current% / %influbridge_voteparty_required% : compteur de
 *   Vote Party géré en jeu (VotePartyService), affiché entre autres dans MythicHUD
 * - %influbridge_compass_north% : frame (1-32) de la boussole HUD pointant le Nord,
 *   calculée à partir du yaw du joueur (même formule bearing->frame que
 *   CompassWaypointExtension côté Typewriter, avec un cap fixe de 180 au lieu d'un
 *   waypoint dynamique)
 * - %influbridge_empty_slots% : nombre de cases vides parmi les 36 slots de stockage
 *   du joueur (hotbar + inventaire principal), via getStorageContents() — exclut donc
 *   armure, offhand et item sur le curseur. Existe car l'expansion PlaceholderAPI
 *   "Player" (%player_empty_slots%) est buggée sur cette version (voir CLAUDE.md,
 *   section Pièges techniques) : elle compte l'armure/offhand/slot "body".
 * - %influbridge_job_active_<job>% : "true"/"false" — EcoJobsAPI.hasJobActive, le joueur a-t-il
 *   réellement rejoint ce métier (join/leave natif EcoJobs, plus de notion de "taux plein"),
 *   pour le menu DeluxeMenus /métiers (bouton rejoindre vs quitter via view_requirement, cf.
 *   gui_menus/metiers/).
 * - %influbridge_job_can_join_<job>% : "true"/"false" — EcoJobsAPI.canJoinJob (pas déjà actif
 *   ET slot disponible selon la permission native ecojobs.limit.<n>, accordée par rang en
 *   LuckPerms) — distingue "peut rejoindre" de "verrouillé, plus de slot" dans le menu.
 * - %influbridge_job_join_price_<job>% / %influbridge_job_leave_price_<job>% : affichage du
 *   prix réel configuré côté EcoJobs (job.getJoinPrice()/getLeavePrice().getDisplay(player)) —
 *   jamais dupliqué ici, la formule (ex: rang_weight × 1000 pour quitter) vit uniquement dans
 *   EcoJobs/jobs/<job>.yml.
 * - %influbridge_job_rewards_<job>_<n>% : n-ième ligne (1 à 3) de rewards-description du
 *   fichier EcoJobs/jobs/<job>.yml pour le niveau réel du joueur (JobRewardsFormatter, qui
 *   reproduit Job.injectPlaceholdersInto — substitue %level% puis évalue les formules de
 *   level-placeholders via NumberUtils.evaluateExpression, même moteur qu'EcoJobs) — texte déjà
 *   rédigé à la main côté EcoJobs, jamais dupliqué ici, montants toujours à jour. Chaîne vide
 *   si la ligne n'existe pas (jobs à une seule ligne de rewards-description, cas le plus courant).
 * - %influbridge_job_xp_<job>_<n>% : n-ième ligne (1 à 4) du résumé xp-gain-methods du job
 *   (JobXpFormatter — trigger + multiplier bruts, pas de formule à évaluer contrairement à
 *   job_rewards), pour le bouton "XP" du menu détail /métiers.
 */
public final class InfluBridgeExpansion extends PlaceholderExpansion {

    private final Plugin plugin;
    private final StreakCacheService streakCacheService;
    private final VotePartyService votePartyService;

    public InfluBridgeExpansion(
            Plugin plugin,
            StreakCacheService streakCacheService,
            VotePartyService votePartyService
    ) {
        this.plugin = plugin;
        this.streakCacheService = streakCacheService;
        this.votePartyService = votePartyService;
    }

    @Override
    public String getIdentifier() {
        return "influbridge";
    }

    @Override
    public String getAuthor() {
        return "dofault";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (votePartyService != null) {
            switch (params) {
                case "voteparty_current":
                    return String.valueOf(votePartyService.getCurrent());
                case "voteparty_required":
                    return String.valueOf(votePartyService.getVotesRequired());
                default:
                    break;
            }
        }

        if (params.equals("compass_north")) {
            if (player == null) return "";
            return String.valueOf(compassFrame(player.getLocation().getYaw()));
        }

        if (params.equals("empty_slots")) {
            if (player == null) return "";
            return String.valueOf(countEmptyStorageSlots(player));
        }

        if (params.startsWith("job_active_")) {
            if (player == null) return "false";
            String jobId = params.substring("job_active_".length());
            Job job = Jobs.getByID(jobId);
            return String.valueOf(job != null && EcoJobsAPI.hasJobActive(player, job));
        }

        if (params.startsWith("job_can_join_")) {
            if (player == null) return "false";
            String jobId = params.substring("job_can_join_".length());
            Job job = Jobs.getByID(jobId);
            return String.valueOf(job != null && EcoJobsAPI.canJoinJob(player, job));
        }

        if (params.startsWith("job_join_price_")) {
            if (player == null) return "";
            String jobId = params.substring("job_join_price_".length());
            Job job = Jobs.getByID(jobId);
            return job == null ? "" : job.getJoinPrice().getDisplay(player);
        }

        if (params.startsWith("job_leave_price_")) {
            if (player == null) return "";
            String jobId = params.substring("job_leave_price_".length());
            Job job = Jobs.getByID(jobId);
            return job == null ? "" : job.getLeavePrice().getDisplay(player);
        }

        if (params.startsWith("job_rewards_")) {
            if (player == null) return "";
            String remainder = params.substring("job_rewards_".length());
            int lastUnderscore = remainder.lastIndexOf('_');
            if (lastUnderscore < 0) return "";
            String jobId = remainder.substring(0, lastUnderscore);
            int line;
            try {
                line = Integer.parseInt(remainder.substring(lastUnderscore + 1));
            } catch (NumberFormatException e) {
                return "";
            }
            List<String> descriptions = JobRewardsFormatter.format(player, jobId);
            if (line < 1 || line > descriptions.size()) return "";
            return descriptions.get(line - 1);
        }

        if (params.startsWith("job_xp_")) {
            String remainder = params.substring("job_xp_".length());
            int lastUnderscore = remainder.lastIndexOf('_');
            if (lastUnderscore < 0) return "";
            String jobId = remainder.substring(0, lastUnderscore);
            int line;
            try {
                line = Integer.parseInt(remainder.substring(lastUnderscore + 1));
            } catch (NumberFormatException e) {
                return "";
            }
            List<String> lines = JobXpFormatter.format(jobId);
            if (line < 1 || line > lines.size()) return "";
            return lines.get(line - 1);
        }

        if (player == null || streakCacheService == null) return "";

        StreakCacheService.StreakData data = streakCacheService.get(player.getName());
        if (data == null) return "";

        return switch (params) {
            case "flame_streak" -> String.valueOf(data.current());
            case "flame_best" -> String.valueOf(data.best());
            case "flame_tier" -> data.tierName();
            case "flame_next_milestone" -> String.valueOf(data.nextMilestone());
            case "flame_legendary_chance" -> String.format("%.2f", data.legendaryChancePercent());
            default -> null;
        };
    }

    /**
     * Reprend le calcul bearing->frame de CompassWaypointExtension (Typewriter), avec
     * un cap fixe de 180 (Nord, convention de yaw Minecraft : 0=Sud, 90=Ouest, 180=Nord,
     * 270=Est) à la place du bearing dynamique vers un waypoint. Retourne 1-32.
     */
    private static int compassFrame(float yaw) {
        double yawNorm = ((yaw % 360.0) + 360.0) % 360.0;
        double relative = ((180.0 - yawNorm + 180.0) % 360.0 + 360.0) % 360.0;
        int frame = (int) Math.round(relative / 11.25) % 32;
        return frame + 1;
    }

    /**
     * getStorageContents() ne renvoie que les 36 slots de hotbar + inventaire
     * principal (mappé sur Inventory#getNonEquipmentItems() côté NMS) : contrairement
     * à getContents(), il exclut nativement armure, offhand et le slot "body".
     */
    private static int countEmptyStorageSlots(Player player) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int empty = 0;
        for (ItemStack item : contents) {
            if (item == null || item.getType() == Material.AIR) {
                empty++;
            }
        }
        return empty;
    }
}
