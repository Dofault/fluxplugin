package fr.influencraft.fluxwebsite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.influencraft.flux.api.reward.Reward;
import fr.influencraft.flux.api.reward.Rewards;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vote rewards kept on the website ({@code pending_commands} with {@code requires_claim}: key of each vote, streak
 * bonus, monthly top 3 and goal, raffle prizes, Vote Party) are moved into Flux, which keeps them with every other
 * reward of the suite until the player claims them ({@code /rewards}, or {@code /voteclaims} for the votes only).
 *
 * <p>Collected when the player joins and before every claim (Flux {@link Rewards#collector}). Each reward is given
 * to Flux with the key {@code vote:<website id>} <b>before</b> being acknowledged on the website: after a crash
 * between the two, the next collection finds it again and Flux ignores the duplicate key.</p>
 */
final class VoteRewards {

    private final FluxWebsite plugin;
    private final WebApi api;
    /** Players being collected (the website is read asynchronously). */
    private final Set<UUID> collecting = ConcurrentHashMap.newKeySet();

    VoteRewards(FluxWebsite plugin, WebApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    private List<JsonObject> fetch(String name) throws Exception {
        WebApi.Response r = api.get("/api/mc/vote-claims/" + name, true);
        if (r.status() == 404) r = api.get("/api/mc/vote-keys/" + name, true); // website not updated yet: former route
        if (!r.ok()) throw new IllegalStateException("vote-claims answered " + r.status());
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : r.json().getAsJsonArray()) out.add(e.getAsJsonObject());
        return out;
    }

    /** Moves the player's website rewards into Flux (asynchronous); {@code then} runs on the main thread afterwards. */
    void collect(Player player, Runnable then) {
        if (!api.ready() || !collecting.add(player.getUniqueId())) {
            if (then != null) then.run();
            return;
        }
        String name = player.getName();
        String icon = plugin.getConfig().getString("vote-rewards.icon", "TRIPWIRE_HOOK");
        String defaultLabel = plugin.messages().raw("vote-reward");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Long> moved = new ArrayList<>();
            try {
                for (JsonObject entry : fetch(name)) {
                    long id = entry.get("id").getAsLong();
                    String label = entry.has("label") && !entry.get("label").isJsonNull() ? entry.get("label").getAsString() : defaultLabel;
                    Reward reward = Reward.of("votes", label).key("vote:" + id).icon(icon).slots(1)
                            .commands(entry.get("command").getAsString());
                    // kept by Flux (or already there): only then acknowledged on the website
                    if (Rewards.give(player, reward) || Rewards.given(player.getUniqueId(), "vote:" + id)) moved.add(id);
                }
                if (!moved.isEmpty()) api.ack(moved);
            } catch (Exception e) {
                plugin.getLogger().warning("Vote rewards of " + name + " not collected: " + e.getMessage());
            } finally {
                collecting.remove(player.getUniqueId());
                if (then != null) Bukkit.getScheduler().runTask(plugin, then);
            }
        });
    }

    /** {@code /voteclaims}: collects, then claims the vote rewards only. */
    void command(Player player) {
        collect(player, () -> {
            if (!player.isOnline()) return;
            var result = Rewards.claim(player, "votes", false);
            if (result.delivered() == 0 && result.remaining() == 0) plugin.messages().send(player, "votes-none");
            if (result.delivered() > 0) plugin.messages().send(player, "votes-claimed", "%count%", String.valueOf(result.delivered()));
            if (result.remaining() > 0) plugin.messages().send(player, "votes-remaining", "%count%", String.valueOf(result.remaining()));
        });
    }
}
