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
    private String lastError;

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
        // Un seul WARN par cycle (et seulement si l'erreur change), pour ne pas
        // inonder les logs quand l'API du site est cassée : une ligne par joueur
        // et par cycle avait produit des milliers de WARN le 2026-10-11.
        String cycleError = null;
        String cycleErrorDetail = null;
        int failures = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            String username = player.getName();
            try {
                StreakData data = fetchStreak(username);
                if (data != null) {
                    cache.put(username.toLowerCase(), data);
                }
            } catch (Exception e) {
                failures++;
                if (cycleError == null) {
                    cycleError = e.toString();
                    cycleErrorDetail = username + " : " + cycleError;
                }
            }
        }
        if (cycleError == null) {
            lastError = null;
        } else if (!cycleError.equals(lastError)) {
            lastError = cycleError;
            plugin.getLogger().warning("Erreur pendant le rafraîchissement du streak (" + failures
                    + " joueur(s) touché(s), premier : " + cycleErrorDetail + ")");
        }
    }

    private StreakData fetchStreak(String username) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/api/vote/streak/" + username))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null; // joueur sans compte sur le site : normal
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " sur /api/vote/streak/" + username);
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return new StreakData(
                json.get("current").getAsInt(),
                json.get("best").getAsInt(),
                json.get("tierName").getAsString(),
                // Optionnel : 0 = dernier palier atteint, -1 = champ absent de l'API.
                json.has("nextMilestone") ? json.get("nextMilestone").getAsInt() : -1,
                json.get("legendaryChancePercent").getAsDouble());
    }
}
