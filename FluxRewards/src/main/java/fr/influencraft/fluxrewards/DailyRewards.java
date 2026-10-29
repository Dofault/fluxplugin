package fr.influencraft.fluxrewards;

import fr.influencraft.flux.api.util.Format;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import fr.influencraft.flux.api.reward.ClaimResult;
import fr.influencraft.flux.api.reward.Reward;
import fr.influencraft.flux.api.reward.Rewards;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Daily login and playtime rewards.
 *
 * <p><b>Login</b>: each login day moves one day forward in a cycle; the reward of the day is claimed in the loyalty
 * menu ({@code /fidelite}) that same day, else it is missed. With {@code strict-streak}, a day without login brings
 * the player back to day 1.</p>
 * <p><b>Playtime</b>: tiers of total playtime on the server (vanilla statistic), claimed in the loyalty menu once
 * reached (never missed).</p>
 * <p>A claim runs at once: the reward goes through Flux ({@link Rewards}) and is claimed in the same click, so it
 * only stays in {@code /rewards} when it does not fit in the inventory. Rewards handed to Flux before 2026-10-29
 * (given as soon as earned) stay claimable there.</p>
 *
 * <p>Main thread only; files are written asynchronously ({@code players/<uuid>.yml}, keys unchanged since
 * InfluRecompenses). The config also accepts the former French keys (connexion.jours, temps-de-jeu.paliers...).</p>
 */
public final class DailyRewards {

    public enum State { CLAIMED, AVAILABLE, MISSED, LOCKED }

    private final FluxRewards plugin;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

    private ZoneId zone;
    private int cycle;
    private boolean strictStreak;
    private List<RewardTier> days = List.of();
    private List<RewardTier> playtimeTiers = List.of();

    public DailyRewards(FluxRewards plugin) {
        this.plugin = plugin;
    }

    // ================================================================ config

    public void load() {
        FileConfiguration c = plugin.getConfig();
        try {
            zone = ZoneId.of(c.getString("timezone", c.getString("fuseau-horaire", "Europe/Paris")));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Invalid timezone, Europe/Paris used");
            zone = ZoneId.of("Europe/Paris");
        }
        ConfigurationSection daily = section(c, "daily", "connexion");
        strictStreak = daily != null && (daily.getBoolean("strict-streak", daily.getBoolean("serie-stricte", false)));
        List<RewardTier> readDays = read(daily == null ? null : section(daily, "days", "jours"), false);
        int lastDay = readDays.isEmpty() ? 1 : (int) readDays.get(readDays.size() - 1).threshold();
        cycle = Math.max(1, daily == null ? lastDay : daily.getInt("cycle", lastDay));
        days = readDays;
        ConfigurationSection playtime = section(c, "playtime", "temps-de-jeu");
        playtimeTiers = read(playtime == null ? null : section(playtime, "tiers", "paliers"), true);
        plugin.getLogger().info(days.size() + " login day(s) (cycle of " + cycle + "), " + playtimeTiers.size() + " playtime tier(s).");
    }

    /** The section under its key, or under its former name; the jar's defaults never hide a former name. */
    private static ConfigurationSection section(ConfigurationSection parent, String key, String formerKey) {
        if (!parent.contains(key, true) && parent.contains(formerKey, true)) return parent.getConfigurationSection(formerKey);
        ConfigurationSection s = parent.getConfigurationSection(key);
        return s != null ? s : parent.getConfigurationSection(formerKey);
    }

    /** Keys = day number, or a duration ("1h", "90m", "2h30") for playtime. */
    private List<RewardTier> read(ConfigurationSection section, boolean duration) {
        List<RewardTier> out = new ArrayList<>();
        if (section == null) return out;
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) continue;
            long threshold = duration ? Format.parseDuration(key) : parseLong(key);
            if (threshold <= 0) {
                plugin.getLogger().warning("Unreadable key « " + key + " » in " + section.getCurrentPath() + ", ignored");
                continue;
            }
            String defaultName = duration
                    ? plugin.messages().raw("playtime-name", "%duration%", Format.duration(threshold))
                    : plugin.messages().raw("day-name", "%day%", String.valueOf(threshold));
            List<String> lore = s.isList("lore") ? s.getStringList("lore") : s.getStringList("description");
            List<String> commands = new ArrayList<>();
            for (String cmd : s.isList("commands") ? s.getStringList("commands") : s.getStringList("commandes")) {
                commands.add(cmd.replace("{joueur}", "%player%"));
            }
            out.add(new RewardTier(threshold, s.getString("name", s.getString("nom", defaultName)), lore, commands,
                    Math.max(0, s.getInt("slots", 1))));
        }
        out.sort(Comparator.comparingLong(RewardTier::threshold));
        return List.copyOf(out);
    }

    public int cycle() {
        return cycle;
    }

    public List<RewardTier> days() {
        return days;
    }

    public List<RewardTier> playtimeTiers() {
        return playtimeTiers;
    }

    // ================================================================ player data

    /** Data of a loaded (online) player, or null. */
    public PlayerData data(UUID uuid) {
        return players.get(uuid);
    }

    public PlayerData load(UUID uuid) {
        File f = file(uuid);
        PlayerData d = new PlayerData();
        if (f.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            d.days = y.getInt("jours", 0);
            String last = y.getString("dernier-jour");
            if (last != null && !last.isEmpty()) {
                try {
                    d.lastDay = LocalDate.parse(last);
                } catch (RuntimeException ignored) {
                    // unreadable date: the next login day starts cleanly
                }
            }
            d.claimedDays.addAll(y.getIntegerList("jours-recuperes"));
            for (Object o : y.getList("temps-recuperes", List.of())) d.claimedPlaytime.add(((Number) o).longValue());
            d.playtime = y.getLong("temps-joue", 0);
        }
        players.put(uuid, d);
        return d;
    }

    public void unload(UUID uuid) {
        PlayerData d = players.remove(uuid);
        if (d != null) save(uuid, d, false);
    }

    public void saveAll() {
        players.forEach((uuid, d) -> save(uuid, d, true));
    }

    private void save(UUID uuid, PlayerData d, boolean now) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("jours", d.days);
        y.set("dernier-jour", d.lastDay != null ? d.lastDay.toString() : null);
        y.set("jours-recuperes", new ArrayList<>(d.claimedDays));
        y.set("temps-recuperes", new ArrayList<>(d.claimedPlaytime));
        y.set("temps-joue", d.playtime);
        String content = y.saveToString();
        Runnable write = () -> {
            try {
                File f = file(uuid);
                f.getParentFile().mkdirs();
                java.nio.file.Files.writeString(f.toPath(), content);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save the rewards of " + uuid, e);
            }
        };
        if (now || !plugin.isEnabled()) write.run();
        else Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
    }

    private File file(UUID uuid) {
        return new File(new File(plugin.getDataFolder(), "joueurs"), uuid + ".yml");
    }

    // ================================================================ login

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    /** Counts the login day if not counted yet (join, midnight). True when a new day was just counted. */
    public boolean countDay(Player player) {
        PlayerData d = players.get(player.getUniqueId());
        if (d == null) return false;
        LocalDate now = today();
        if (now.equals(d.lastDay)) return false;
        if (strictStreak && d.lastDay != null && !d.lastDay.plusDays(1).equals(now)) {
            d.days = 0;
            d.claimedDays.clear();
        }
        d.days++;
        d.lastDay = now;
        if (d.dayOfCycle(cycle) == 1) d.claimedDays.clear(); // new cycle
        save(player.getUniqueId(), d, false);
        return true;
    }

    public State dayState(PlayerData d, int day) {
        int current = d.dayOfCycle(cycle);
        if (d.claimedDays.contains(day)) return State.CLAIMED;
        if (day == current && today().equals(d.lastDay)) return State.AVAILABLE;
        if (day < current) return State.MISSED;
        return State.LOCKED;
    }

    /** Tier of a day of the cycle (null: day without reward). */
    public RewardTier dayTier(int day) {
        for (RewardTier t : days) if (t.threshold() == day) return t;
        return null;
    }

    /** Today's tier when it is available, else null. */
    public RewardTier availableDay(Player player) {
        PlayerData d = players.get(player.getUniqueId());
        if (d == null) return null;
        int day = d.dayOfCycle(cycle);
        if (day == 0 || dayState(d, day) != State.AVAILABLE) return null;
        RewardTier t = dayTier(day);
        return t != null ? t : new RewardTier(day, plugin.messages().raw("day-name", "%day%", String.valueOf(day)), List.of(), List.of(), 0);
    }

    /** Key of today's login reward in Flux. */
    public String dayKey(PlayerData d) {
        return "daily:" + d.lastDay;
    }

    /** Claims today's reward (main thread). NONE when nothing is available. */
    public ClaimResult claimDay(Player player) {
        PlayerData d = players.get(player.getUniqueId());
        RewardTier tier = availableDay(player);
        if (d == null || tier == null) return ClaimResult.NONE;
        d.claimedDays.add((int) tier.threshold());
        save(player.getUniqueId(), d, false);
        return claim(player, Reward.of("daily", tier.name()).key(dayKey(d))
                .icon(plugin.getConfig().getString("icons.daily", "CLOCK")).slots(tier.slots()).commands(tier.commands()), tier);
    }

    /** Through Flux, claimed at once: what does not fit stays in /rewards. */
    private ClaimResult claim(Player player, Reward reward, RewardTier tier) {
        if (tier.commands().isEmpty()) return ClaimResult.NONE;
        Rewards.give(player, reward);
        return Rewards.claimKey(player, reward.key(), true);
    }

    // ================================================================ playtime

    /** Playtime in seconds (server statistic when online, last known value otherwise). */
    public long playtime(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        PlayerData d = players.get(uuid);
        if (p != null) {
            long seconds = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;
            if (d != null) d.playtime = seconds;
            return seconds;
        }
        return d != null ? d.playtime : 0;
    }

    public State playtimeState(UUID uuid, PlayerData d, RewardTier t) {
        if (d.claimedPlaytime.contains(t.threshold())) return State.CLAIMED;
        return playtime(uuid) >= t.threshold() ? State.AVAILABLE : State.LOCKED;
    }

    /** Playtime tiers reached (claimed or not): progress of the path. */
    public int playtimeReached(UUID uuid) {
        long t = playtime(uuid);
        int n = 0;
        for (RewardTier tier : playtimeTiers) if (t >= tier.threshold()) n++;
        return n;
    }

    public List<RewardTier> availablePlaytime(Player player) {
        PlayerData d = players.get(player.getUniqueId());
        if (d == null) return List.of();
        List<RewardTier> out = new ArrayList<>();
        for (RewardTier t : playtimeTiers) if (playtimeState(player.getUniqueId(), d, t) == State.AVAILABLE) out.add(t);
        return out;
    }

    /** Key of a playtime tier in Flux. */
    public static String playtimeKey(RewardTier tier) {
        return "playtime:" + tier.threshold();
    }

    /** Claims a playtime tier reached (main thread). NONE when not available. */
    public ClaimResult claimPlaytime(Player player, RewardTier tier) {
        PlayerData d = players.get(player.getUniqueId());
        if (d == null || playtimeState(player.getUniqueId(), d, tier) != State.AVAILABLE) return ClaimResult.NONE;
        d.claimedPlaytime.add(tier.threshold());
        save(player.getUniqueId(), d, false);
        return claim(player, Reward.of("playtime", tier.name()).key(playtimeKey(tier))
                .icon(plugin.getConfig().getString("icons.playtime", "EXPERIENCE_BOTTLE")).slots(tier.slots()).commands(tier.commands()), tier);
    }

    /** Whether something can be claimed in the loyalty menu: today's reward or a playtime tier. */
    public boolean claimable(Player player) {
        return availableDay(player) != null || !availablePlaytime(player).isEmpty();
    }

    // ================================================================ admin (tests)

    /** Puts the player on day {@code day} of the cycle, today, reward not claimed. */
    public void setDay(Player player, int day) {
        PlayerData d = players.get(player.getUniqueId());
        if (d == null) return;
        int base = d.days <= 0 ? 0 : ((d.days - 1) / cycle) * cycle;
        d.days = base + Math.max(1, Math.min(day, cycle));
        d.lastDay = today();
        d.claimedDays.removeIf(j -> j >= day);
        save(player.getUniqueId(), d, false);
    }

    public void reset(Player player) {
        PlayerData d = players.get(player.getUniqueId());
        if (d == null) return;
        d.days = 0;
        d.lastDay = null;
        d.claimedDays.clear();
        d.claimedPlaytime.clear();
        countDay(player);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
