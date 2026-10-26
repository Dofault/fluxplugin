package fr.influencraft.fluxwebsite;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Sounds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Vote Party: counts the votes confirmed by the website (every player and vote site; the website runs
 * {@code influvoteparty add} from the console for each one) and, when the goal is reached, rewards every online
 * player. The counter persists in {@code voteparty.yml}.
 *
 * <p>Like every vote reward, the Vote Party reward is claimed by the player: the list of online players is sent to
 * the website ({@code POST /api/mc/vote-party/reward}), which keeps each reward pending (commands from its admin
 * panel, or {@code voteparty.reward-commands}). When the website cannot be reached, the commands are kept here per
 * UUID ({@code pending} in voteparty.yml, {@code %player%} resolved only on delivery) and claimed with the other
 * vote rewards.</p>
 */
final class VoteParty {

    private final FluxWebsite plugin;
    private final WebApi api;
    private final File file;
    private final AtomicInteger current = new AtomicInteger();
    /** Reward commands kept locally per player. Main thread only. */
    private final Map<UUID, List<String>> pending = new HashMap<>();

    private int votesRequired;
    private List<String> rewardCommands;

    VoteParty(FluxWebsite plugin, WebApi api) {
        this.plugin = plugin;
        this.api = api;
        this.file = new File(plugin.getDataFolder(), "voteparty.yml");
        reload();
        load();
    }

    void reload() {
        votesRequired = Math.max(1, plugin.getConfig().getInt("voteparty.votes-required", 30));
        rewardCommands = plugin.getConfig().getStringList("voteparty.reward-commands");
    }

    private String setting(String key) {
        return plugin.getConfig().getString("voteparty." + key, "");
    }

    int current() {
        return current.get();
    }

    int votesRequired() {
        return votesRequired;
    }

    /** Adds votes one by one, so that every goal crossed triggers a party. Main thread. */
    void add(int amount) {
        for (int i = 0; i < amount; i++) {
            boolean reached = current.incrementAndGet() >= votesRequired;
            if (reached) current.set(0);
            save();
            if (reached) trigger();
        }
    }

    /** Admin correction, never below 0. */
    void remove(int amount) {
        current.updateAndGet(v -> Math.max(0, v - amount));
        save();
    }

    private void trigger() {
        String broadcast = setting("broadcast-message").replace("%votes-required%", String.valueOf(votesRequired));
        if (!broadcast.isBlank()) {
            for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(Texts.parse(broadcast, p));
            Bukkit.getConsoleSender().sendMessage(Texts.parse(broadcast));
        }
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player p : players) {
            showTitle(p);
            Sounds.play(p, setting("sound"));
        }
        if (players.isEmpty()) return;
        if (!api.ready()) {
            storeLocally(players);
            return;
        }
        JsonObject body = new JsonObject();
        JsonArray list = new JsonArray();
        for (Player p : players) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", p.getUniqueId().toString());
            entry.addProperty("name", p.getName());
            list.add(entry);
        }
        body.add("players", list);
        JsonArray commands = new JsonArray();
        rewardCommands.forEach(commands::add);
        body.add("commands", commands);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean sent = post(body);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!sent) {
                    storeLocally(players);
                    return;
                }
                String message = setting("reward-message");
                for (Player p : players) {
                    if (!p.isOnline()) continue;
                    if (!message.isBlank()) p.sendMessage(Texts.parseClickable(message, p));
                    Rewards.changed(p);
                }
            });
        });
    }

    private boolean post(JsonObject body) {
        try {
            WebApi.Response r = api.post("/api/mc/vote-party/reward", body);
            if (r.ok()) return true;
            plugin.getLogger().warning("vote-party/reward answered " + r.status() + ": Vote Party rewards kept locally.");
        } catch (Exception e) {
            plugin.getLogger().warning("Website unreachable for the Vote Party (" + e.getMessage() + "): rewards kept locally.");
        }
        return false;
    }

    /** Fallback: rewards kept in voteparty.yml, claimed with the other vote rewards. Main thread. */
    private void storeLocally(List<Player> players) {
        if (rewardCommands.isEmpty()) return;
        String message = setting("reward-stored-message");
        for (Player p : players) {
            pending.computeIfAbsent(p.getUniqueId(), u -> new ArrayList<>()).addAll(rewardCommands);
            if (p.isOnline() && !message.isBlank()) p.sendMessage(Texts.parseClickable(message, p));
            Rewards.changed(p);
        }
        save();
    }

    private void showTitle(Player player) {
        String title = setting("title"), subtitle = setting("subtitle");
        if (title.isBlank() && subtitle.isBlank()) return;
        player.showTitle(Title.title(title.isBlank() ? Component.empty() : Texts.parse(title, player),
                subtitle.isBlank() ? Component.empty() : Texts.parse(subtitle, player),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1))));
    }

    /** Vote Party rewards kept locally for this player. Main thread. */
    int pendingCount(UUID uuid) {
        List<String> commands = pending.get(uuid);
        return commands == null ? 0 : commands.size();
    }

    /** Delivers at most {@code max} locally kept rewards (one slot each) and returns how many. Main thread. */
    int claim(Player player, int max) {
        List<String> commands = pending.get(player.getUniqueId());
        if (commands == null || commands.isEmpty() || max <= 0) return 0;
        List<String> delivered = new ArrayList<>(commands.subList(0, Math.min(max, commands.size())));
        commands.subList(0, delivered.size()).clear();
        if (commands.isEmpty()) pending.remove(player.getUniqueId());
        save();
        for (String c : delivered) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", player.getName()));
        return delivered.size();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        current.set(yaml.getInt("current", 0));
        ConfigurationSection section = yaml.getConfigurationSection("pending");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                List<String> commands = section.getStringList(key);
                if (!commands.isEmpty()) pending.put(UUID.fromString(key), new ArrayList<>(commands));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("voteparty.yml: invalid UUID ignored: " + key);
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("current", current.get());
        pending.forEach((uuid, commands) -> yaml.set("pending." + uuid, commands));
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save voteparty.yml: " + e.getMessage());
        }
    }
}
