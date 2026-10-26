package fr.influencraft.fluxwebsite;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.willfp.eco.util.StringUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Vote Party : compte les votes confirmés côté mineweb (tous joueurs et tous
 * vote-sites confondus, cf. insertVote() dans poll.get.ts qui déclenche la
 * commande console "influvoteparty add" à chaque vote) et déclenche une
 * récompense — commandes + messages configurables dans config.yml — une fois
 * le seuil atteint. mineweb est la source réelle des votes sur ce serveur
 * (VotingPlugin n'est plus installé). Le compteur est persisté dans voteparty.yml pour survivre à un
 * redémarrage.
 *
 * Récompense : comme toutes les récompenses de vote, elle passe par /voteclaims
 * (2026-10-02). Au déclenchement, la liste des joueurs en ligne est envoyée au
 * site (POST /api/mc/vote-party/reward), qui met la récompense de chacun en
 * attente ; les commandes viennent du panel admin du site, ou à défaut de
 * voteparty.reward-commands (envoyées avec la requête).
 *
 * Site injoignable ou non configuré : la récompense est gardée en local par UUID
 * dans voteparty.yml (pending), et /voteclaims la rend avec les autres. On stocke
 * les commandes elles-mêmes, avec %player% résolu seulement à la livraison : un
 * joueur renommé entre-temps les récupère quand même.
 */
public final class VotePartyService {

    private final JavaPlugin plugin;
    private final String apiBaseUrl;
    private final String apiSecret;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final File storageFile;
    private final AtomicInteger current = new AtomicInteger();

    private volatile int votesRequired;
    private volatile List<String> rewardCommands;
    private volatile String broadcastMessage;
    private volatile String rewardMessage;
    private volatile String rewardStoredMessage;
    private volatile String title;
    private volatile String subtitle;
    // Clé de son vanilla (ex : "entity.player.levelup"), vide = aucun son.
    private volatile String sound;

    // Commandes de récompense gardées en local par joueur (site injoignable au
    // déclenchement, ou anciennes récompenses). Lu et écrit uniquement sur le thread principal.
    private final Map<UUID, List<String>> pendingRewards = new HashMap<>();

    public VotePartyService(JavaPlugin plugin, String apiBaseUrl, String apiSecret) {
        this.plugin = plugin;
        this.apiBaseUrl = apiBaseUrl.endsWith("/")
                ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                : apiBaseUrl;
        this.apiSecret = apiSecret;
        this.storageFile = new File(plugin.getDataFolder(), "voteparty.yml");
        reloadConfig();
        load();
    }

    public void reloadConfig() {
        var config = plugin.getConfig();
        this.votesRequired = Math.max(1, config.getInt("voteparty.votes-required", 30));
        this.rewardCommands = config.getStringList("voteparty.reward-commands");
        this.broadcastMessage = config.getString("voteparty.broadcast-message", "");
        this.rewardMessage = config.getString("voteparty.reward-message", "");
        this.rewardStoredMessage = config.getString("voteparty.reward-stored-message", "");
        this.title = config.getString("voteparty.title", "");
        this.subtitle = config.getString("voteparty.subtitle", "");
        this.sound = config.getString("voteparty.sound", "");
    }

    public int getCurrent() {
        return current.get();
    }

    public int getVotesRequired() {
        return votesRequired;
    }

    /**
     * Incrémente le compteur d'un vote confirmé. Appelé par la commande console
     * "influvoteparty add" (source réelle : mineweb).
     * Doit être appelé sur le thread principal.
     */
    public void addVote() {
        addVote(1);
    }

    /**
     * Ajoute {@code amount} votes d'un coup (correction manuelle admin via
     * "/influvoteparty add <montant>"). Incrémente un par un pour déclencher
     * correctement la récompense à chaque seuil franchi si amount dépasse
     * plusieurs fois votes-required.
     */
    public void addVote(int amount) {
        for (int i = 0; i < amount; i++) {
            int value = current.incrementAndGet();
            boolean reached = value >= votesRequired;
            if (reached) {
                current.set(0);
            }
            save();

            if (reached) {
                triggerVoteParty();
            }
        }
    }

    /**
     * Retire {@code amount} votes du compteur (correction manuelle admin via
     * "/influvoteparty remove <montant>"), plancher à 0.
     */
    public void removeVote(int amount) {
        current.updateAndGet(value -> Math.max(0, value - amount));
        save();
    }

    private void triggerVoteParty() {
        if (!broadcastMessage.isBlank()) {
            String message = broadcastMessage.replace("%votes-required%", String.valueOf(votesRequired));
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage(StringUtils.format(message, player));
            }
            Bukkit.getConsoleSender().sendMessage(StringUtils.format(message));
        }

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player player : players) {
            showTitle(player);
            if (!sound.isBlank()) {
                player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
            }
        }
        if (players.isEmpty()) {
            return;
        }

        if (!isSiteConfigured()) {
            storeLocally(players);
            return;
        }

        // Appel réseau hors du thread principal ; messages et repli local sur le thread principal.
        JsonObject body = new JsonObject();
        JsonArray playersJson = new JsonArray();
        for (Player player : players) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", player.getUniqueId().toString());
            entry.addProperty("name", player.getName());
            playersJson.add(entry);
        }
        body.add("players", playersJson);
        JsonArray commandsJson = new JsonArray();
        rewardCommands.forEach(commandsJson::add);
        body.add("commands", commandsJson);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean sent = postReward(body);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!sent) {
                    storeLocally(players);
                    return;
                }
                if (!rewardMessage.isBlank()) {
                    for (Player player : players) {
                        if (player.isOnline()) {
                            player.sendMessage(StringUtils.format(rewardMessage, player));
                        }
                    }
                }
            });
        });
    }

    private boolean isSiteConfigured() {
        return !apiBaseUrl.isBlank() && !apiSecret.isBlank() && !apiSecret.equals("CHANGE_ME");
    }

    private boolean postReward(JsonObject body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/api/mc/vote-party/reward"))
                    .header("Authorization", "Bearer " + apiSecret)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return true;
            }
            plugin.getLogger().warning("vote-party/reward a répondu " + response.statusCode()
                    + " : récompenses de la Vote Party gardées en local.");
        } catch (Exception e) {
            plugin.getLogger().warning("Site injoignable pour la Vote Party (" + e.getMessage()
                    + ") : récompenses gardées en local.");
        }
        return false;
    }

    /** Repli : récompense gardée dans voteparty.yml, rendue par /voteclaims. Thread principal. */
    private void storeLocally(List<Player> players) {
        if (rewardCommands.isEmpty()) {
            return;
        }
        for (Player player : players) {
            pendingRewards.computeIfAbsent(player.getUniqueId(), uuid -> new ArrayList<>())
                    .addAll(rewardCommands);
            if (player.isOnline() && !rewardStoredMessage.isBlank()) {
                player.sendMessage(StringUtils.format(rewardStoredMessage, player));
            }
        }
        save();
    }

    private void showTitle(Player player) {
        if (title.isBlank() && subtitle.isBlank()) {
            return;
        }
        player.showTitle(Title.title(
                toComponent(title, player), toComponent(subtitle, player),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1))));
    }

    private Component toComponent(String text, Player player) {
        return text.isBlank()
                ? Component.empty()
                : LegacyComponentSerializer.legacySection().deserialize(StringUtils.format(text, player));
    }

    private void dispatchRewards(Player player, List<String> commands) {
        for (String commandTemplate : commands) {
            String command = commandTemplate.replace("%player%", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }

    /** Nombre de récompenses Vote Party mises de côté pour ce joueur (thread principal). */
    public int getPendingCount(UUID uuid) {
        List<String> commands = pendingRewards.get(uuid);
        return commands == null ? 0 : commands.size();
    }

    /**
     * Livre au joueur au plus {@code max} commandes Vote Party gardées en local et
     * renvoie leur nombre ; les suivantes restent stockées. L'appelant a compté
     * les cases libres (cf. /voteclaims). Doit être appelé sur le thread principal.
     */
    public int claimPending(Player player, int max) {
        List<String> commands = pendingRewards.get(player.getUniqueId());
        if (commands == null || commands.isEmpty() || max <= 0) {
            return 0;
        }
        List<String> delivered = new ArrayList<>(commands.subList(0, Math.min(max, commands.size())));
        commands.subList(0, delivered.size()).clear();
        if (commands.isEmpty()) {
            pendingRewards.remove(player.getUniqueId());
        }
        save();
        dispatchRewards(player, delivered);
        return delivered.size();
    }

    private void load() {
        if (!storageFile.exists()) {
            current.set(0);
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storageFile);
        current.set(yaml.getInt("current", 0));

        ConfigurationSection pending = yaml.getConfigurationSection("pending");
        if (pending != null) {
            for (String key : pending.getKeys(false)) {
                try {
                    List<String> commands = pending.getStringList(key);
                    if (!commands.isEmpty()) {
                        pendingRewards.put(UUID.fromString(key), new ArrayList<>(commands));
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("voteparty.yml : UUID invalide ignoré : " + key);
                }
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("current", current.get());
        pendingRewards.forEach((uuid, commands) -> yaml.set("pending." + uuid, commands));
        try {
            yaml.save(storageFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Erreur pendant la sauvegarde de voteparty.yml : " + e.getMessage());
        }
    }
}
