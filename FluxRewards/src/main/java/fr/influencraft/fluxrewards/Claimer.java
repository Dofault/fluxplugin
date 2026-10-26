package fr.influencraft.fluxrewards;

import fr.influencraft.flux.api.inventory.Inventories;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.ListProvider;
import fr.influencraft.flux.api.list.ListQuery;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.reward.ClaimResult;
import fr.influencraft.flux.api.reward.PendingReward;
import fr.influencraft.flux.api.reward.RewardSource;
import fr.influencraft.flux.api.reward.Rewards;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * One claim for every reward source of the suite: sources are claimed one after the other in the configured order
 * ({@code claim-order}, then the others), each one offered the room actually left in the inventory. Also keeps,
 * per online player, the list of pending rewards (menu list {@code rewards}, {@code %fluxrewards_pending%},
 * reminders), refreshed on join, after a claim, when a source reports a change and periodically.
 */
final class Claimer implements ListProvider {

    private static final long TIMEOUT_SECONDS = 10;

    private final FluxRewards plugin;
    private final Map<UUID, List<PendingReward>> pending = new ConcurrentHashMap<>();
    private final Set<UUID> claiming = ConcurrentHashMap.newKeySet();

    Claimer(FluxRewards plugin) {
        this.plugin = plugin;
    }

    /** Sources in claim order. */
    List<RewardSource> ordered() {
        List<String> order = plugin.getConfig().getStringList("claim-order");
        List<RewardSource> sources = new ArrayList<>(Rewards.sources());
        sources.sort(Comparator.comparingInt((RewardSource s) -> {
            int i = order.indexOf(s.id());
            return i < 0 ? Integer.MAX_VALUE : i;
        }).thenComparing(RewardSource::id));
        sources.removeIf(s -> plugin.getConfig().getStringList("disabled-sources").contains(s.id()));
        return sources;
    }

    /** Claims everything that fits. Ignored while a claim of this player is still running. */
    void claimAll(Player player) {
        if (!claiming.add(player.getUniqueId())) return;
        step(player, ordered(), 0, ClaimResult.NONE);
    }

    private void step(Player player, List<RewardSource> sources, int index, ClaimResult total) {
        if (index >= sources.size() || !player.isOnline()) {
            finish(player, total);
            return;
        }
        CompletableFuture<ClaimResult> future;
        try {
            future = sources.get(index).claim(player, Inventories.freeSlots(player));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Reward source " + sources.get(index).id() + " failed for " + player.getName() + ": " + e);
            future = CompletableFuture.completedFuture(ClaimResult.NONE);
        }
        future.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((result, error) -> sync(() -> {
            if (error != null) {
                plugin.getLogger().warning("Reward source " + sources.get(index).id() + " failed for " + player.getName() + ": " + error);
                plugin.messages().send(player, "source-error");
            }
            step(player, sources, index + 1, total.plus(result == null ? ClaimResult.NONE : result));
        }));
    }

    private void finish(Player player, ClaimResult total) {
        claiming.remove(player.getUniqueId());
        if (!player.isOnline()) return;
        if (total.delivered() == 0 && total.remaining() == 0) {
            plugin.messages().send(player, "nothing-to-claim");
        } else {
            if (total.delivered() > 0) plugin.messages().send(player, "claimed", "%count%", String.valueOf(total.delivered()));
            if (total.remaining() > 0) plugin.messages().send(player, "remaining", "%count%", String.valueOf(total.remaining()));
        }
        refresh(player);
    }

    /** Asks every source again for the player's pending rewards. */
    void refresh(Player player) {
        List<RewardSource> sources = ordered();
        List<CompletableFuture<List<PendingReward>>> futures = new ArrayList<>();
        for (RewardSource s : sources) {
            CompletableFuture<List<PendingReward>> f;
            try {
                f = s.pending(player);
            } catch (RuntimeException e) {
                f = CompletableFuture.completedFuture(List.of());
            }
            futures.add(f.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).exceptionally(e -> List.of()));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((v, e) -> sync(() -> {
            List<PendingReward> all = new ArrayList<>();
            for (CompletableFuture<List<PendingReward>> f : futures) all.addAll(f.getNow(List.of()));
            pending.put(player.getUniqueId(), List.copyOf(all));
            Lists.invalidate(player, "rewards");
            var view = fr.influencraft.flux.api.FluxApi.view(player);
            if (view != null) view.refreshButtons();
        }));
    }

    void forget(UUID uuid) {
        pending.remove(uuid);
        claiming.remove(uuid);
    }

    /** Pending rewards last read for the player (empty until the first refresh). */
    List<PendingReward> pending(UUID uuid) {
        return pending.getOrDefault(uuid, List.of());
    }

    private void sync(Runnable task) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }

    // ---------------------------------------------------------------- menu list "rewards"

    @Override
    public List<ListEntry> entries(ListQuery query) {
        List<ListEntry> out = new ArrayList<>();
        int i = 0;
        for (PendingReward r : pending(query.viewer().getUniqueId())) {
            ItemStack icon = Items.of(r.icon(), query.viewer());
            if (icon != null) icon.setAmount(Math.max(1, Math.min(64, r.amount())));
            out.add(ListEntry.of(String.valueOf(i++)).item(icon)
                    .set("label", r.label())
                    .set("amount", r.amount())
                    .set("slots", r.slots())
                    .set("source", plugin.messages().raw("sources." + r.source()).isEmpty() ? r.source()
                            : plugin.messages().raw("sources." + r.source())));
        }
        return out;
    }
}
