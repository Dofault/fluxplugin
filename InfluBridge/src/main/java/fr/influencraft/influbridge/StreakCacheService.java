package fr.influencraft.influbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rafraîchit périodiquement le streak de vote (flamme) de chaque joueur en ligne
 * depuis GET /api/vote/streak/<username> (mineweb), pour lecture instantanée par
 * FlameStreakExpansion — jamais d'appel réseau synchrone depuis un placeholder.
 */
public final class StreakCacheService {

    public record StreakData(
            int current,
            int best,
            String tierName,
            int nextMilestone,
            double legendaryChancePercent) {
    }

    private final JavaPlugin plugin;
    private final String apiBaseUrl;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, StreakData> cache = new ConcurrentHashMap<>();

    public StreakCacheService(JavaPlugin plugin, String apiBaseUrl) {
        this.plugin = plugin;
        this.apiBaseUrl = apiBaseUrl.endsWith("/")
                ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                : apiBaseUrl;
    }

    public StreakData get(String username) {
        return cache.get(username.toLowerCase());
    }

    public void refresh() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            String username = player.getName();
            try {
                StreakData data = fetchStreak(username);
                if (data != null) {
                    cache.put(username.toLowerCase(), data);
                }
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "Erreur pendant le rafraîchissement du streak de " + username + " : " + e.getMessage());
            }
        }
    }

    private StreakData fetchStreak(String username) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/api/vote/streak/" + username))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return new StreakData(
                json.get("current").getAsInt(),
                json.get("best").getAsInt(),
                json.get("tierName").getAsString(),
                json.get("nextMilestone").getAsInt(),
                json.get("legendaryChancePercent").getAsDouble());
    }
}
