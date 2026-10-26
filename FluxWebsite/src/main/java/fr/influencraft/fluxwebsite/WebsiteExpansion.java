package fr.influencraft.fluxwebsite;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

/**
 * Website placeholders. The identifier stays "influbridge" (former plugin name) so that menus and HUDs
 * keep working:
 * <ul>
 *   <li>{@code %influbridge_voteparty_current%} / {@code %influbridge_voteparty_required%}: Vote Party counter</li>
 *   <li>{@code %influbridge_flame_*%}: vote streak computed by the website ({@link StreakCacheService}, null when
 *   api-base-url is not set): streak, best, tier, next_milestone, legendary_chance</li>
 * </ul>
 * The game-only placeholders (compass, empty slots, jobs) moved to FluxTools as {@code %fluxtools_...%}.
 */
public final class WebsiteExpansion extends PlaceholderExpansion {

    private final StreakCacheService streakCacheService;
    private final VotePartyService votePartyService;

    public WebsiteExpansion(StreakCacheService streakCacheService, VotePartyService votePartyService) {
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
        if (params.equals("voteparty_current")) return String.valueOf(votePartyService.getCurrent());
        if (params.equals("voteparty_required")) return String.valueOf(votePartyService.getVotesRequired());

        if (player == null || streakCacheService == null) return "";
        StreakCacheService.StreakData data = streakCacheService.get(player.getName());
        if (data == null) return "";
        return switch (params) {
            case "flame_streak" -> String.valueOf(data.current());
            case "flame_best" -> String.valueOf(data.best());
            case "flame_tier" -> data.tierName();
            case "flame_next_milestone" -> data.nextMilestone() > 0 ? String.valueOf(data.nextMilestone()) : "-";
            case "flame_legendary_chance" -> String.format("%.2f", data.legendaryChancePercent());
            default -> null;
        };
    }
}
