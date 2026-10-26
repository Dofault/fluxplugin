package fr.influencraft.fluxrewards;

import fr.influencraft.flux.api.reward.ClaimResult;
import fr.influencraft.flux.api.reward.PendingReward;
import fr.influencraft.flux.api.reward.RewardSource;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** The daily login reward ({@code daily}) and the playtime tiers ({@code playtime}) as reward sources. */
final class LoginSources {

    private LoginSources() {
    }

    static RewardSource daily(FluxRewards plugin) {
        return new RewardSource() {
            @Override
            public String id() {
                return "daily";
            }

            @Override
            public CompletableFuture<List<PendingReward>> pending(Player player) {
                RewardTier tier = plugin.daily().availableDay(player);
                return CompletableFuture.completedFuture(tier == null ? List.of()
                        : List.of(new PendingReward(id(), tier.name(), plugin.getConfig().getString("icons.daily", "CLOCK"), 1, tier.slots())));
            }

            @Override
            public CompletableFuture<ClaimResult> claim(Player player, int freeSlots) {
                if (plugin.daily().availableDay(player) == null) return CompletableFuture.completedFuture(ClaimResult.NONE);
                boolean ok = plugin.daily().claimDay(player, freeSlots, false);
                return CompletableFuture.completedFuture(new ClaimResult(ok ? 1 : 0, ok ? 0 : 1));
            }
        };
    }

    static RewardSource playtime(FluxRewards plugin) {
        return new RewardSource() {
            @Override
            public String id() {
                return "playtime";
            }

            @Override
            public CompletableFuture<List<PendingReward>> pending(Player player) {
                List<PendingReward> out = new ArrayList<>();
                String icon = plugin.getConfig().getString("icons.playtime", "EXPERIENCE_BOTTLE");
                for (RewardTier t : plugin.daily().availablePlaytime(player)) out.add(new PendingReward(id(), t.name(), icon, 1, t.slots()));
                return CompletableFuture.completedFuture(out);
            }

            @Override
            public CompletableFuture<ClaimResult> claim(Player player, int freeSlots) {
                int[] r = plugin.daily().claimPlaytime(player, 0, freeSlots, false);
                return CompletableFuture.completedFuture(new ClaimResult(r[0], r[1]));
            }
        };
    }
}
