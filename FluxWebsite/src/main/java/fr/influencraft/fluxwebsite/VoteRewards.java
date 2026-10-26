package fr.influencraft.fluxwebsite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.influencraft.flux.api.inventory.Inventories;
import fr.influencraft.flux.api.reward.ClaimResult;
import fr.influencraft.flux.api.reward.PendingReward;
import fr.influencraft.flux.api.reward.RewardSource;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Every reward of the vote system, kept on the website until the player claims it ({@code pending_commands} with
 * {@code requires_claim}: key of each vote, streak bonus, monthly top 3 and goal, raffle prizes, Vote Party), plus
 * the Vote Party rewards kept locally ({@link VoteParty}). Source {@code votes} of the suite's rewards, also claimed
 * alone with {@code /voteclaims}.
 *
 * <p>Each reward needs one slot: never more rewards than free slots, the others stay pending on the website
 * (nothing acknowledged). A command is acknowledged before it runs, so a double claim never delivers it twice.</p>
 */
final class VoteRewards implements RewardSource {

    private final FluxWebsite plugin;
    private final WebApi api;
    private final VoteParty party;

    VoteRewards(FluxWebsite plugin, WebApi api, VoteParty party) {
        this.plugin = plugin;
        this.api = api;
        this.party = party;
    }

    @Override
    public String id() {
        return "votes";
    }

    private List<JsonObject> fetch(String name) throws Exception {
        WebApi.Response r = api.get("/api/mc/vote-claims/" + name, true);
        if (r.status() == 404) r = api.get("/api/mc/vote-keys/" + name, true); // website not updated yet: former route
        if (!r.ok()) throw new IllegalStateException("vote-claims answered " + r.status());
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : r.json().getAsJsonArray()) out.add(e.getAsJsonObject());
        return out;
    }

    @Override
    public CompletableFuture<List<PendingReward>> pending(Player player) {
        String icon = plugin.getConfig().getString("vote-rewards.icon", "TRIPWIRE_HOOK");
        String label = plugin.messages().raw("vote-reward");
        int local = party.pendingCount(player.getUniqueId());
        String name = player.getName();
        return CompletableFuture.supplyAsync(() -> {
            List<PendingReward> out = new ArrayList<>();
            for (int i = 0; i < local; i++) out.add(new PendingReward(id(), plugin.messages().raw("vote-party-reward"), icon, 1, 1));
            if (!api.ready()) return out;
            try {
                for (JsonObject entry : fetch(name)) {
                    String own = entry.has("label") && !entry.get("label").isJsonNull() ? entry.get("label").getAsString() : label;
                    out.add(new PendingReward(id(), own, icon, 1, 1));
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Vote rewards of " + name + " unavailable: " + e.getMessage());
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<ClaimResult> claim(Player player, int freeSlots) {
        return claim(player, freeSlots, null);
    }

    /** {@code onError} (main thread) is told when the website could not be read; the local rewards are given anyway. */
    CompletableFuture<ClaimResult> claim(Player player, int freeSlots, Runnable onError) {
        int local = party.claim(player, freeSlots);
        int localLeft = party.pendingCount(player.getUniqueId());
        int room = freeSlots - local;
        CompletableFuture<ClaimResult> result = new CompletableFuture<>();
        if (!api.ready()) {
            result.complete(new ClaimResult(local, localLeft));
            return result;
        }
        String name = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<JsonObject> waiting = fetch(name);
                List<JsonObject> deliver = room > 0 ? waiting.subList(0, Math.min(room, waiting.size())) : List.of();
                List<Long> ids = new ArrayList<>();
                List<String> commands = new ArrayList<>();
                for (JsonObject entry : deliver) {
                    ids.add(entry.get("id").getAsLong());
                    commands.add(entry.get("command").getAsString());
                }
                if (!ids.isEmpty()) api.ack(ids); // before running them: never delivered twice
                int left = waiting.size() - deliver.size() + localLeft;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (String c : commands) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
                    result.complete(new ClaimResult(local + commands.size(), left));
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Vote rewards of " + name + " could not be claimed: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (onError != null) onError.run();
                    result.complete(new ClaimResult(local, localLeft));
                });
            }
        });
        return result;
    }

    /** {@code /voteclaims}: vote rewards only, with its own messages. */
    void command(Player player) {
        int free = Inventories.freeSlots(player);
        if (free == 0) {
            plugin.messages().send(player, "votes-no-room");
            return;
        }
        boolean[] failed = {false};
        claim(player, free, () -> {
            failed[0] = true;
            plugin.messages().send(player, "votes-error");
        }).thenAccept(r -> {
            if (r.delivered() == 0 && !failed[0]) plugin.messages().send(player, "votes-none");
            if (r.delivered() > 0) plugin.messages().send(player, "votes-claimed", "%count%", String.valueOf(r.delivered()));
            if (r.remaining() > 0) plugin.messages().send(player, "votes-remaining", "%count%", String.valueOf(r.remaining()));
            fr.influencraft.flux.api.reward.Rewards.changed(player);
        });
    }
}
