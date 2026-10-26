package fr.influencraft.influbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Poll GET /api/mc/pending-commands (mineweb), exécute chaque commande depuis
 * la console, puis ack les ids livrés via POST /api/mc/commands/ack. Une même
 * file d'attente sert aux achats boutique, à la récompense de vote et au top 3
 * mensuel — cette classe ne fait aucune distinction entre les trois.
 */
public final class CommandDeliveryService {

    private final JavaPlugin plugin;
    private final String apiBaseUrl;
    private final String apiSecret;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public CommandDeliveryService(JavaPlugin plugin, String apiBaseUrl, String apiSecret) {
        this.plugin = plugin;
        this.apiBaseUrl = apiBaseUrl.endsWith("/")
                ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                : apiBaseUrl;
        this.apiSecret = apiSecret;
    }

    public void poll() {
        try {
            List<JsonObject> commands = fetchPendingCommands();
            if (commands.isEmpty()) return;

            List<Long> executedIds = new ArrayList<>();
            for (JsonObject command : commands) {
                long id = command.get("id").getAsLong();
                String commandLine = command.get("command").getAsString();

                // dispatchCommand doit être appelé sur le thread principal
                Bukkit.getScheduler().runTask(plugin, () ->
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandLine));

                executedIds.add(id);
            }

            ackCommands(executedIds);
        } catch (Exception e) {
            plugin.getLogger().warning("Erreur pendant le poll de pending-commands : " + e.getMessage());
        }
    }

    private List<JsonObject> fetchPendingCommands() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/api/mc/pending-commands"))
                .header("Authorization", "Bearer " + apiSecret)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            plugin.getLogger().warning("pending-commands a répondu " + response.statusCode());
            return List.of();
        }

        JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
        List<JsonObject> result = new ArrayList<>();
        for (var element : array) {
            result.add(element.getAsJsonObject());
        }
        return result;
    }

    private void ackCommands(List<Long> ids) throws Exception {
        JsonObject body = new JsonObject();
        JsonArray idsArray = new JsonArray();
        ids.forEach(idsArray::add);
        body.add("ids", idsArray);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/api/mc/commands/ack"))
                .header("Authorization", "Bearer " + apiSecret)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            plugin.getLogger().warning("commands/ack a répondu " + response.statusCode());
        }
    }
}
