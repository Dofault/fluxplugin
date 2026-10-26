package fr.influencraft.fluxwebsite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;

/**
 * Website commands to run right away ({@code pending_commands} without {@code requires_claim}: shop purchases),
 * polled every {@code poll-interval-seconds}. Each command runs from the console, then is acknowledged. Rewards to
 * claim by the player go through {@link VoteRewards} instead. Runs off the main thread.
 */
final class ShopDelivery {

    private final FluxWebsite plugin;
    private final WebApi api;

    ShopDelivery(FluxWebsite plugin, WebApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    void poll() {
        try {
            WebApi.Response r = api.get("/api/mc/pending-commands", true);
            if (!r.ok()) {
                plugin.getLogger().warning("pending-commands answered " + r.status());
                return;
            }
            List<Long> done = new ArrayList<>();
            for (JsonElement element : r.json().getAsJsonArray()) {
                JsonObject command = element.getAsJsonObject();
                String line = command.get("command").getAsString();
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line));
                done.add(command.get("id").getAsLong());
            }
            if (!done.isEmpty()) api.ack(done);
        } catch (Exception e) {
            plugin.getLogger().warning("pending-commands poll failed: " + e.getMessage());
        }
    }
}
