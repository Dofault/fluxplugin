package fr.influencraft.fluxwebsite;

import com.google.gson.JsonObject;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.PlayerSettings;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Vote info block ({@code vote-reminder.message}): sent periodically to the players who can vote on at least one
 * site (pending rewards alone never trigger it), and on demand by {@code /voteinfo} (always). Each player can turn
 * the periodic reminder off ({@code /votenotif off}, setting {@code fluxwebsite:vote-reminder}).
 *
 * <p>The first reminder after a start waits {@code initial-delay-seconds} instead of a full interval: a deliberate
 * phase shift so that it never falls at the same time as another periodic message (chat games every 10 min, a
 * reminder every 5 min shifted by 2 min 30 never meet).</p>
 *
 * <p>Variables of the block: %available%, %total% (vote sites), %pending% (rewards waiting, line skipped at 0),
 * %tickets% (raffle tickets not bet, skipped at 0), %lots% and %prochain_lot% (open raffles, skipped when none).
 * {@code [/command]} shortcuts and URLs are clickable.</p>
 */
final class VoteReminder {

    static final String SETTING = "fluxwebsite:vote-reminder";

    private record Status(int availableSites, int totalSites, int pendingRewards, int raffleTickets, List<String[]> raffles) {
    }

    private final FluxWebsite plugin;
    private final WebApi api;
    private final VoteParty party;
    private BukkitTask task;
    private volatile List<Map.Entry<String, Instant>> raffles = List.of();
    private volatile long rafflesAt;

    VoteReminder(FluxWebsite plugin, WebApi api, VoteParty party) {
        this.plugin = plugin;
        this.api = api;
        this.party = party;
    }

    boolean enabledFor(OfflinePlayer player) {
        return PlayerSettings.flag(player, SETTING, true);
    }

    void setEnabled(Player player, boolean enabled) {
        PlayerSettings.set(player, SETTING, enabled);
    }

    boolean globallyEnabled() {
        return plugin.getConfig().getBoolean("vote-reminder.enabled", true);
    }

    long intervalSeconds() {
        return Math.max(60, plugin.getConfig().getLong("vote-reminder.interval-seconds", 300));
    }

    /** (Re)starts the loop; the previous task is cancelled first. */
    void start() {
        if (task != null) task.cancel();
        task = null;
        if (!globallyEnabled()) return;
        long delay = Math.max(0, plugin.getConfig().getLong("vote-reminder.initial-delay-seconds", 150)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, delay, intervalSeconds() * 20L);
    }

    private void tick() {
        if (plugin.getConfig().getStringList("vote-reminder.message").isEmpty()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!enabledFor(player)) continue;
            String name = player.getName();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    Status status = fetch(name);
                    if (status == null || status.availableSites() <= 0) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline() && enabledFor(player)) send(player, status);
                    });
                } catch (Exception e) {
                    plugin.getLogger().warning("Vote reminder of " + name + " failed: " + e.getMessage());
                }
            });
        }
    }

    /** {@code /voteinfo}: always shown, even when no site is available or the reminder is off. */
    void showTo(Player player) {
        String name = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Status status;
            try {
                status = api.ready() ? fetch(name) : null;
            } catch (Exception e) {
                plugin.getLogger().warning("/voteinfo of " + name + " failed: " + e.getMessage());
                status = null;
            }
            Status resolved = status;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (resolved == null) plugin.messages().send(player, "vote-info-unavailable");
                else send(player, resolved);
            });
        });
    }

    private void send(Player player, Status status) {
        // still on the website + already moved into Flux
        int pending = status.pendingRewards() + (int) fr.influencraft.flux.api.reward.Rewards.pending(player.getUniqueId()).stream()
                .filter(r -> r.reward().source().equals("votes")).count();
        for (String raw : plugin.getConfig().getStringList("vote-reminder.message")) {
            if (pending <= 0 && raw.contains("%pending%")) continue;
            if (status.raffleTickets() <= 0 && raw.contains("%tickets%")) continue;
            if (status.raffles().isEmpty() && (raw.contains("%lots%") || raw.contains("%prochain_lot%"))) continue;
            String line = Texts.replace(raw, "%pending%", String.valueOf(pending),
                    "%available%", String.valueOf(status.availableSites()), "%total%", String.valueOf(status.totalSites()),
                    "%tickets%", String.valueOf(status.raffleTickets()), "%lots%", formatRaffles(status.raffles()),
                    "%prochain_lot%", status.raffles().isEmpty() ? "" : status.raffles().get(0)[1]);
            player.sendMessage(Texts.parseClickable(line, player));
        }
    }

    /** "Clé Ordinaire ×5 &8(&f02:14:33&8) &8• &fClé Virale &8(&f11:02:10&8)": name and time left before the draw. */
    private static String formatRaffles(List<String[]> list) {
        StringBuilder out = new StringBuilder();
        for (String[] raffle : list) {
            if (!out.isEmpty()) out.append(" &8• &f");
            out.append(raffle[0]).append(" &8(&f").append(raffle[1]).append("&8)&f");
        }
        return out.toString();
    }

    /** Same format as the website: 00:00:00 under 24 h, otherwise "N day(s)" ({@code vote-days}). */
    private String countdown(long seconds) {
        if (seconds >= 86_400) {
            long days = seconds / 86_400;
            return plugin.messages().raw(days > 1 ? "vote-days" : "vote-day", "%days%", String.valueOf(days));
        }
        return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private Status fetch(String name) throws Exception {
        WebApi.Response r = api.get("/api/mc/vote-status/" + name, true);
        if (!r.ok()) return null;
        JsonObject json = r.json().getAsJsonObject();
        return new Status(json.get("availableSites").getAsInt(), json.get("totalSites").getAsInt(),
                json.get("pendingRewards").getAsInt(), json.has("raffleTickets") ? json.get("raffleTickets").getAsInt() : 0,
                openRaffles());
    }

    /** Open raffles, read on the public page of the website and kept 30 s: one call for every player of a round. */
    private List<String[]> openRaffles() {
        long now = System.currentTimeMillis();
        if (now - rafflesAt > 30_000) {
            rafflesAt = now;
            try {
                WebApi.Response r = api.get("/api/vote/raffles", false);
                if (r.ok()) {
                    List<Map.Entry<String, Instant>> fresh = new ArrayList<>();
                    for (var element : r.json().getAsJsonObject().getAsJsonArray("current")) {
                        JsonObject raffle = element.getAsJsonObject();
                        if (!"open".equals(raffle.get("phase").getAsString()) || raffle.get("endsAt").isJsonNull()) continue;
                        fresh.add(Map.entry(Texts.plain(Texts.colors(raffle.get("title").getAsString())),
                                Instant.parse(raffle.get("endsAt").getAsString())));
                    }
                    fresh.sort(Map.Entry.comparingByValue());
                    raffles = fresh;
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Raffles unavailable: " + e.getMessage());
            }
        }
        List<String[]> out = new ArrayList<>();
        for (var raffle : raffles) {
            long seconds = Math.max(0, raffle.getValue().getEpochSecond() - now / 1000);
            if (seconds > 0) out.add(new String[]{raffle.getKey(), countdown(seconds)});
        }
        return out;
    }
}
