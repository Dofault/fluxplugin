package fr.influencraft.influbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.willfp.eco.core.data.ProfileExtensions;
import com.willfp.eco.core.data.keys.PersistentDataKey;
import com.willfp.eco.core.data.keys.PersistentDataKeyType;
import com.willfp.eco.util.StringUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rappel de vote périodique : toutes les X minutes (config.yml → vote-reminder),
 * chaque joueur en ligne qui a au moins un site de vote disponible reçoit le bloc
 * d'infos du /vote, complété par le nombre de récompenses en attente.
 *
 * Chaque joueur peut couper ce rappel pour lui-même (bouton "Notif. de vote" de
 * /reglages → "/votenotif on|off"), préférence persistée par profil eco comme
 * les autres bascules du menu.
 *
 * Règle de déclenchement (volontaire) : SEULE la disponibilité d'un site de vote
 * décide d'envoyer le rappel. Les récompenses en attente ne déclenchent rien —
 * sinon un joueur qui ne veut pas réclamer ses clés serait spammé indéfiniment,
 * alors qu'un cooldown de vote finit toujours par se réarmer côté site.
 *
 * Décalage de phase : le premier rappel part après "initial-delay-seconds" et non
 * après un intervalle plein, pour ne pas tomber en même temps qu'un autre message
 * périodique (ChatGames tourne toutes les 10 min ; avec un rappel toutes les 5 min
 * décalé de 2 min 30, les deux ne coïncident jamais — un multiple impair de la
 * demi-période ne peut pas être un multiple de la période double). Le calage tient
 * tant que les deux tâches gardent leur phase relative, c'est-à-dire tant qu'aucun
 * des deux plugins n'est rechargé seul.
 *
 * Source unique : GET /api/mc/vote-status/<pseudo> côté mineweb (VotingPlugin
 * n'est plus installé), qui renvoie availableSites / totalSites / pendingRewards.
 * Endpoint dédié et volontairement léger — il ne calcule aucun classement,
 * contrairement à /api/vote/user/<pseudo> qui alimente le site. Les cooldowns y
 * sont résolus par UUID uniquement : l'IP vue par mineweb est celle du serveur
 * MC, jamais celle du joueur.
 */
public final class VoteReminderService {

    private static final Pattern COMMAND_SHORTCUT = Pattern.compile("\\[/[^\\]]+\\]");
    // Le match s'arrête sur le dernier caractère "utile" : une ponctuation finale
    // (point, virgule, parenthèse fermante...) appartient à la phrase, pas à l'URL.
    private static final Pattern URL = Pattern.compile("https?://[^\\s]*[^\\s.,;:!?)\\]]");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final JavaPlugin plugin;
    private final VotePartyService votePartyService;
    private final String apiBaseUrl;
    private final String apiSecret;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // Rechargeables à chaud via /influbridge reload — d'où l'absence de final.
    private volatile long intervalSeconds;
    private volatile long initialDelaySeconds;
    private volatile List<String> messageLines;
    private volatile boolean enabled;
    private BukkitTask task;

    // Préférence par joueur, activée par défaut — même mécanique que la boss bar
    // d'XP métier et l'action bar de gain BitBlock.
    private final PersistentDataKey<Boolean> enabledKey;

    public VoteReminderService(JavaPlugin plugin, VotePartyService votePartyService,
                               String apiBaseUrl, String apiSecret) {
        this.plugin = plugin;
        this.votePartyService = votePartyService;
        this.apiBaseUrl = apiBaseUrl.endsWith("/")
                ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1)
                : apiBaseUrl;
        this.apiSecret = apiSecret;
        readConfig();
        this.enabledKey = new PersistentDataKey<>(
                new NamespacedKey(plugin, "vote_reminder_enabled"),
                PersistentDataKeyType.BOOLEAN,
                true);
    }

    private void readConfig() {
        var config = plugin.getConfig();
        this.enabled = config.getBoolean("vote-reminder.enabled", true);
        this.intervalSeconds = Math.max(60, config.getLong("vote-reminder.interval-seconds", 300));
        this.initialDelaySeconds =
                Math.max(0, config.getLong("vote-reminder.initial-delay-seconds", 150));
        this.messageLines = config.getStringList("vote-reminder.message");
    }

    /**
     * Relit config.yml et réarme la boucle : intervalle, texte du bloc et
     * interrupteur global sont pris en compte sans redémarrage du serveur.
     */
    public void reload() {
        readConfig();
        start();
    }

    public boolean isEnabled(OfflinePlayer player) {
        return ProfileExtensions.getProfile(player).read(enabledKey);
    }

    public void setEnabled(Player player, boolean enabled) {
        ProfileExtensions.getProfile((OfflinePlayer) player).write(enabledKey, enabled);
    }

    /**
     * (Re)démarre la boucle de rappel. Le timer lui-même est synchrone (il ne fait
     * que lire la liste des joueurs en ligne) et délègue chaque appel réseau à une
     * tâche asynchrone, comme /votekeys claim. Rappelable : la tâche précédente est
     * annulée d'abord, pour ne jamais en laisser deux tourner en parallèle.
     */
    public void start() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!enabled) {
            return;
        }
        long periodTicks = intervalSeconds * 20L;
        task = Bukkit.getScheduler().runTaskTimer(
                plugin, this::tick, initialDelaySeconds * 20L, periodTicks);
    }

    /** Intervalle courant, en secondes — affiché par /influbridge reload. */
    public long getIntervalSeconds() {
        return intervalSeconds;
    }

    public boolean isGloballyEnabled() {
        return enabled;
    }

    private void tick() {
        if (messageLines.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isEnabled(player)) {
                continue;
            }
            String username = player.getName();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    VoteStatus status = fetchStatus(username);
                    if (status == null || status.availableSites() <= 0) {
                        return;
                    }

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline() && isEnabled(player)) {
                            send(player, status);
                        }
                    });
                } catch (Exception e) {
                    plugin.getLogger().warning("Erreur pendant le rappel de vote de "
                            + username + " : " + e.getMessage());
                }
            });
        }
    }

    /**
     * Affiche le bloc à la demande (commande /vote, cf. l'alias de commands.yml).
     * Contrairement au rappel automatique, il s'affiche toujours : ni la
     * disponibilité d'un site ni la préférence "Notif. de vote" du joueur ne le
     * conditionnent, puisque c'est lui qui l'a demandé.
     */
    public void showTo(Player player) {
        String username = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            VoteStatus status;
            try {
                status = fetchStatus(username);
            } catch (Exception e) {
                plugin.getLogger().warning("Erreur pendant l'affichage de /vote pour "
                        + username + " : " + e.getMessage());
                status = null;
            }

            VoteStatus resolved = status;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (resolved == null) {
                    player.sendMessage(StringUtils.format(
                            "&#FFD94D&lVote &8► &#FF5C5CInfos de vote indisponibles, réessaie plus tard. "
                                    + "&#B376E5Tu peux voter sur &#2BC4E8&nhttps://influencraft.fr/vote",
                            player));
                    return;
                }
                send(player, resolved);
            });
        });
    }

    private void send(Player player, VoteStatus status) {
        // Clés du site + clés du Vote Party mises de côté (inventaire plein au déclenchement).
        int pending = status.pendingRewards() + votePartyService.getPendingCount(player.getUniqueId());
        for (String rawLine : messageLines) {
            // Une ligne qui parle des récompenses en attente n'a aucun sens quand il
            // n'y en a pas : elle est simplement sautée, le reste du bloc est envoyé.
            if (pending <= 0 && rawLine.contains("%pending%")) {
                continue;
            }
            // Idem pour les tickets de tombola (à miser sur le site).
            if (status.raffleTickets() <= 0 && rawLine.contains("%tickets%")) {
                continue;
            }
            // Et pour la tombola, s'il n'y a aucun lot ouvert.
            if (status.raffles().isEmpty() && (rawLine.contains("%lots%") || rawLine.contains("%prochain_lot%"))) {
                continue;
            }

            String line = rawLine
                    .replace("%pending%", String.valueOf(pending))
                    .replace("%available%", String.valueOf(status.availableSites()))
                    .replace("%total%", String.valueOf(status.totalSites()))
                    .replace("%tickets%", String.valueOf(status.raffleTickets()))
                    .replace("%lots%", formatRaffles(status.raffles()))
                    // Temps avant le prochain tirage (le lot le plus proche), sans le nommer
                    .replace("%prochain_lot%", status.raffles().isEmpty() ? "" : status.raffles().get(0)[1]);

            player.sendMessage(clickableUrls(
                    clickableShortcuts(LEGACY.deserialize(StringUtils.format(line, player)))));
        }
    }

    /**
     * Rend cliquables les raccourcis de commande écrits [/commande] (même écriture
     * que les fiches CMI/CustomText/*.txt, où c'est CMI qui les rend cliquables).
     * Le style du texte est conservé : le remplacement réutilise le builder du
     * segment matché, qui hérite de la couleur de la ligne.
     */
    private Component clickableShortcuts(Component line) {
        return line.replaceText(TextReplacementConfig.builder()
                .match(COMMAND_SHORTCUT)
                .replacement((matchResult, builder) -> {
                    String shortcut = matchResult.group();
                    String command = shortcut.substring(1, shortcut.length() - 1);
                    return builder
                            .clickEvent(ClickEvent.runCommand(command))
                            .hoverEvent(HoverEvent.showText(
                                    Component.text(command, NamedTextColor.GRAY)));
                })
                .build());
    }

    /**
     * Rend cliquables les URL écrites en clair. Le client Minecraft ne détecte rien
     * tout seul : un lien n'est cliquable que si le serveur pose un clickEvent. Les
     * fiches CMI/CustomText/*.txt l'obtenaient de CMI ; depuis que /vote passe par
     * InfluBridge (2026-09-21), c'est à nous de le faire.
     * Le style (bleu #2BC4E8 + souligné, cf. docs/notifications/liens.md) vient de
     * la ligne elle-même et est conservé : on réutilise le builder du segment matché.
     */
    private Component clickableUrls(Component line) {
        return line.replaceText(TextReplacementConfig.builder()
                .match(URL)
                .replacement((matchResult, builder) -> {
                    String url = matchResult.group();
                    return builder
                            .clickEvent(ClickEvent.openUrl(url))
                            .hoverEvent(HoverEvent.showText(
                                    Component.text(url, NamedTextColor.GRAY)));
                })
                .build());
    }

    /**
     * Réponse de /api/mc/vote-status : sites votables maintenant, total, récompenses en
     * attente, tickets de tombola non misés.
     */
    private record VoteStatus(int availableSites, int totalSites, int pendingRewards, int raffleTickets,
                              List<String[]> raffles) {
    }

    /** « Clé Ordinaire ×5 &8(&f02:14:33&8) &8• &fClé Virale &8(&f11:02:10&8) » : nom + temps avant le tirage. */
    private static String formatRaffles(List<String[]> raffles) {
        StringBuilder out = new StringBuilder();
        for (String[] raffle : raffles) {
            if (out.length() > 0) {
                out.append(" &8• &f");
            }
            out.append(raffle[0]).append(" &8(&f").append(raffle[1]).append("&8)&f");
        }
        return out.toString();
    }

    /** Même format que le site : 00:00:00 sous 24 h, sinon « N jour(s) ». */
    private static String formatCountdown(long seconds) {
        if (seconds >= 86_400) {
            long days = seconds / 86_400;
            return days + (days > 1 ? " jours" : " jour");
        }
        return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private VoteStatus fetchStatus(String username) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/api/mc/vote-status/" + username))
                .header("Authorization", "Bearer " + apiSecret)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return new VoteStatus(
                json.get("availableSites").getAsInt(),
                json.get("totalSites").getAsInt(),
                json.get("pendingRewards").getAsInt(),
                // Absent d'un site pas encore mis à jour : 0, la ligne est sautée.
                json.has("raffleTickets") ? json.get("raffleTickets").getAsInt() : 0,
                openRaffles());
    }

    // Lots de tombola ouverts, lus sur la page publique du site (/api/vote/raffles) et
    // gardés 30 s : un seul appel pour tous les joueurs d'un même rappel.
    private volatile List<java.util.Map.Entry<String, java.time.Instant>> rafflesCache = List.of();
    private volatile long rafflesFetchedAt = 0;

    private List<String[]> openRaffles() {
        long now = System.currentTimeMillis();
        if (now - rafflesFetchedAt > 30_000) {
            rafflesFetchedAt = now;
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiBaseUrl + "/api/vote/raffles"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    List<java.util.Map.Entry<String, java.time.Instant>> fresh = new java.util.ArrayList<>();
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    for (var element : json.getAsJsonArray("current")) {
                        JsonObject raffle = element.getAsJsonObject();
                        if (!"open".equals(raffle.get("phase").getAsString()) || raffle.get("endsAt").isJsonNull()) {
                            continue;
                        }
                        fresh.add(java.util.Map.entry(
                                StringUtils.format(raffle.get("title").getAsString()).replaceAll("§.", ""),
                                java.time.Instant.parse(raffle.get("endsAt").getAsString())));
                    }
                    fresh.sort(java.util.Map.Entry.comparingByValue());
                    rafflesCache = fresh;
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Lots de tombola indisponibles : " + e.getMessage());
            }
        }
        List<String[]> out = new java.util.ArrayList<>();
        for (var raffle : rafflesCache) {
            long seconds = Math.max(0, raffle.getValue().getEpochSecond() - now / 1000);
            if (seconds > 0) {
                out.add(new String[]{raffle.getKey(), formatCountdown(seconds)});
            }
        }
        return out;
    }
}
