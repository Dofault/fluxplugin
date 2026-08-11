package fr.influencraft.influbridge;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

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
 */
public final class InfluBridgeExpansion extends PlaceholderExpansion {

    private final Plugin plugin;
    private final StreakCacheService streakCacheService;
    private final VotePartyService votePartyService;

    public InfluBridgeExpansion(Plugin plugin, StreakCacheService streakCacheService, VotePartyService votePartyService) {
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
