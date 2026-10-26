package fr.influencraft.influbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.willfp.eco.util.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Backend du bouton Vote de /menu (DeluxeMenus, clic droit, `[player] votekeys claim`) :
 * réclame les clés de vote (Ordinaire à chaque vote, Phénomène bonus de la flamme) qui
 * s'accumulent en attente côté mineweb (pending_commands.requires_claim, cf. poll.get.ts)
 * au lieu d'être livrées automatiquement au moment du vote. Vérifie qu'il reste un slot
 * d'inventaire PRINCIPAL libre avant tout appel réseau — les clés ExcellentCrates sont des
 * items physiques (Virtual: false, cf. ExcellentCrates/keys/*.yml), pas un compteur virtuel.
 * Ordre volontaire : le check d'inventaire passe avant le fetch/ack mineweb, pour qu'un
 * inventaire plein ne fasse jamais perdre une clé (elle reste en attente côté serveur).
 */
public final class VoteKeysClaimCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final String apiBaseUrl;
    private final String apiSecret;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public VoteKeysClaimCommand(JavaPlugin plugin, String apiBaseUrl, String apiSecret) {
        this.plugin = plugin;
        this.apiBaseUrl = apiBaseUrl.endsWith("/")
                ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                : apiBaseUrl;
        this.apiSecret = apiSecret;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Cette commande est réservée aux joueurs.");
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("claim")) {
            return false;
        }

        // firstEmpty() ne regarde que le stockage principal (36 slots), jamais
        // l'armure/l'offhand — même base fiable que le placeholder PAPI
        // %player_has_empty_slot% (cf. piège %player_empty_slots% dans CLAUDE.md).
        if (player.getInventory().firstEmpty() == -1) {
            send(player, "&#FFD94D&lVote &8► &#FF5C5CLibère un emplacement dans ton inventaire "
                    + "pour récupérer tes clés de vote.");
            return true;
        }

        String username = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<JsonObject> pending = fetchPending(username);
                if (pending.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> send(player,
                            "&#FFD94D&lVote &8► &#B376E5Tu n'as aucune clé de vote à récupérer."));
                    return;
                }

                List<Long> ids = new ArrayList<>();
                for (JsonObject entry : pending) {
                    long id = entry.get("id").getAsLong();
                    String commandLine = entry.get("command").getAsString();

                    // dispatchCommand doit être appelé sur le thread principal
                    Bukkit.getScheduler().runTask(plugin, () ->
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandLine));

                    ids.add(id);
                }

                ackCommands(ids);

                int count = ids.size();
                Bukkit.getScheduler().runTask(plugin, () -> send(player,
                        "&#FFD94D&lVote &8► &#2ECF7BTu as récupéré " + count
                                + (count > 1 ? " clés de vote !" : " clé de vote !")));
            } catch (Exception e) {
                plugin.getLogger().warning("Erreur pendant la réclamation des clés de vote de "
                        + username + " : " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> send(player,
                        "&#FFD94D&lVote &8► &#FF5C5CErreur pendant la récupération de tes clés, "
                                + "réessaie plus tard."));
            }
        });

        return true;
    }

    private List<JsonObject> fetchPending(String username) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/api/mc/vote-keys/" + username))
                .header("Authorization", "Bearer " + apiSecret)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
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

    private void send(Player player, String message) {
        player.sendMessage(StringUtils.format(message, player));
    }
}
