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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * /voteclaims (alias /votekeys, ancien nom) : point de retrait unique de toutes les
 * récompenses du système de vote, mises en attente côté mineweb
 * (pending_commands.requires_claim) : clé de chaque vote, bonus de la flamme, top 3
 * du mois, objectif du mois, lots de la tombola, Vote Party. Aussi déclenchée par le
 * clic droit sur le bouton Vote de /menu (DeluxeMenus, `[player] voteclaims`).
 *
 * Une récompense = au moins un objet physique : on n'en livre pas plus qu'il n'y a
 * de cases libres dans le stockage principal, les suivantes restent en attente côté
 * serveur (rien n'est acquitté) et le joueur est invité à faire de la place. Le
 * compte des cases passe avant tout appel réseau : un inventaire plein ne fait
 * jamais perdre une récompense.
 *
 * Rend aussi les anciennes récompenses du Vote Party gardées en local
 * (voteparty.yml, avant leur passage par le site, ou si le site était injoignable
 * au déclenchement), cf. VotePartyService.
 */
public final class VoteClaimsCommand implements CommandExecutor {

    private static final String PREFIX = "&#FFD94D&lVote &8► ";

    private final JavaPlugin plugin;
    private final VotePartyService votePartyService;
    private final String apiBaseUrl;
    private final String apiSecret;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public VoteClaimsCommand(JavaPlugin plugin, VotePartyService votePartyService,
                             String apiBaseUrl, String apiSecret) {
        this.plugin = plugin;
        this.votePartyService = votePartyService;
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
        // Sans argument = claim : le raccourci cliquable [/voteclaims] du rappel de vote
        // (VoteReminderService) exécute la commande nue. "claim" reste accepté (anciens menus).
        if (args.length > 1 || (args.length == 1 && !args[0].equalsIgnoreCase("claim"))) {
            return false;
        }

        int freeSlots = countFreeSlots(player);
        if (freeSlots == 0) {
            send(player, PREFIX + "&#FF5C5CLibère un emplacement dans ton inventaire "
                    + "pour récupérer tes récompenses de vote.");
            return true;
        }

        // Récompenses du Vote Party gardées en local : livrées tout de suite, sur le thread principal.
        int localCount = votePartyService.claimPending(player, freeSlots);
        int slotsLeft = freeSlots - localCount;
        int localLeft = votePartyService.getPendingCount(player.getUniqueId());

        String username = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<JsonObject> pending = slotsLeft > 0 ? fetchPending(username) : List.of();
                List<JsonObject> toDeliver = pending.subList(0, Math.min(slotsLeft, pending.size()));
                int remaining = (pending.size() - toDeliver.size()) + localLeft;

                List<Long> ids = new ArrayList<>();
                List<String> commands = new ArrayList<>();
                for (JsonObject entry : toDeliver) {
                    ids.add(entry.get("id").getAsLong());
                    commands.add(entry.get("command").getAsString());
                }
                if (!ids.isEmpty()) {
                    // Acquitté avant exécution : une commande ne doit jamais être livrée
                    // deux fois si le joueur relance /voteclaims pendant l'appel réseau.
                    ackCommands(ids);
                }

                int count = ids.size() + localCount;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // dispatchCommand doit être appelé sur le thread principal
                    for (String commandLine : commands) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandLine);
                    }
                    if (count == 0) {
                        send(player, PREFIX + "&#B376E5Tu n'as aucune récompense de vote à récupérer.");
                    } else {
                        send(player, claimedMessage(count));
                    }
                    if (remaining > 0) {
                        send(player, PREFIX + "&#B376E5Il t'en reste &f" + remaining
                                + "&#B376E5 : libère de la place et retape &f/voteclaims&#B376E5.");
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Erreur pendant la réclamation des récompenses de vote de "
                        + username + " : " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (localCount > 0) {
                        send(player, claimedMessage(localCount));
                    }
                    send(player, PREFIX + "&#FF5C5CErreur pendant la récupération de tes récompenses, "
                            + "réessaie plus tard.");
                });
            }
        });

        return true;
    }

    /** Cases vides du stockage principal (36 slots, jamais l'armure ni l'offhand). */
    private static int countFreeSlots(Player player) {
        int free = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType().isAir()) {
                free++;
            }
        }
        return free;
    }

    private static String claimedMessage(int count) {
        return PREFIX + "&#2ECF7BTu as récupéré " + count
                + (count > 1 ? " récompenses de vote !" : " récompense de vote !");
    }

    private List<JsonObject> fetchPending(String username) throws Exception {
        HttpResponse<String> response = get("/api/mc/vote-claims/" + username);
        // Site pas encore mis à jour (route absente) : ancienne route, même réponse.
        if (response.statusCode() == 404) {
            response = get("/api/mc/vote-keys/" + username);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("vote-claims a répondu " + response.statusCode());
        }

        JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
        List<JsonObject> result = new ArrayList<>();
        for (var element : array) {
            result.add(element.getAsJsonObject());
        }
        return result;
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + path))
                .header("Authorization", "Bearer " + apiSecret)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
            throw new IllegalStateException("commands/ack a répondu " + response.statusCode());
        }
    }

    private void send(Player player, String message) {
        player.sendMessage(StringUtils.format(message, player));
    }
}
