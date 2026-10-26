package fr.influencraft.fluxwebsite;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vote streak ("flame") of online players, computed by the website ({@code /api/vote/streak/<name>}) and cached
 * here, refreshed every {@code streak-cache-interval-seconds} off the main thread: a placeholder never calls the
 * network.
 */
final class FlameCache {

    record Flame(int current, int best, String tier, int nextMilestone, double legendaryChance) {
    }

    private final FluxWebsite plugin;
    private final WebApi api;
    private final Map<String, Flame> cache = new ConcurrentHashMap<>();
    private String lastError;

    FlameCache(FluxWebsite plugin, WebApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    Flame get(String name) {
        return cache.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    void refresh() {
        // one warning per cycle, and only when the error changes: a broken API once wrote thousands of lines
        String error = null, detail = null;
        int failures = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            String name = player.getName();
            try {
                WebApi.Response r = api.get("/api/vote/streak/" + name, false);
                if (r.status() == 404) continue; // no website account: normal
                if (!r.ok()) throw new IllegalStateException("HTTP " + r.status() + " on /api/vote/streak/" + name);
                JsonObject json = r.json().getAsJsonObject();
                cache.put(name.toLowerCase(java.util.Locale.ROOT), new Flame(json.get("current").getAsInt(),
                        json.get("best").getAsInt(), json.get("tierName").getAsString(),
                        json.has("nextMilestone") ? json.get("nextMilestone").getAsInt() : -1,
                        json.get("legendaryChancePercent").getAsDouble()));
            } catch (Exception e) {
                failures++;
                if (error == null) {
                    error = e.toString();
                    detail = name + ": " + error;
                }
            }
        }
        if (error == null) {
            lastError = null;
        } else if (!error.equals(lastError)) {
            lastError = error;
            plugin.getLogger().warning("Vote streak refresh failed (" + failures + " player(s), first: " + detail + ")");
        }
    }
}
