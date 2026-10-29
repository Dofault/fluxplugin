package fr.influencraft.fluxcalendar;

import fr.influencraft.flux.api.reward.ClaimResult;
import fr.influencraft.flux.api.reward.Reward;
import fr.influencraft.flux.api.reward.Rewards;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The login calendar: one reward per date of the month ({@code days.<1..31>} of the config), claimed by the player
 * on that date only, by clicking today's case of the calendar ({@code /calendrier}). A date not claimed is missed;
 * everything starts again on the 1st of the next month.
 *
 * <p>A claim runs at once: the reward goes through Flux ({@link Rewards}) and is claimed in the same click, so it
 * only stays in {@code /rewards} when it does not fit in the inventory.</p>
 *
 * <p>Main thread only; files are written asynchronously ({@code joueurs/<uuid>.yml}). The config also accepts the
 * former French keys ({@code jours}, {@code nom}, {@code description}, {@code commandes}, {@code grosse}).</p>
 */
public final class LoginCalendar {

    public enum State { CLAIMED, TODAY, MISSED, BEFORE_ARRIVAL, TO_COME }

    private final FluxCalendar plugin;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private ZoneId zone = ZoneId.of("Europe/Paris");
    private Map<Integer, DayReward> days = Map.of();

    public LoginCalendar(FluxCalendar plugin) {
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
        ConfigurationSection section = c.contains("days", true) ? c.getConfigurationSection("days")
                : c.getConfigurationSection("jours");
        Map<Integer, DayReward> read = new HashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection s = section.getConfigurationSection(key);
                int day = parse(key);
                if (s == null || day < 1 || day > 31) {
                    plugin.getLogger().warning("Unreadable day « " + key + " » in the config, ignored");
                    continue;
                }
                List<String> commands = new ArrayList<>();
                for (String cmd : s.isList("commands") ? s.getStringList("commands") : s.getStringList("commandes")) {
                    commands.add(cmd.replace("{joueur}", "%player%"));
                }
                String defaultName = plugin.messages().raw("day-name", "%day%", String.valueOf(day));
                read.put(day, new DayReward(day, s.getString("name", s.getString("nom", defaultName)),
                        s.isList("lore") ? s.getStringList("lore") : s.getStringList("description"), commands,
                        Math.max(0, s.getInt("slots", 1)), s.getBoolean("big", s.getBoolean("grosse", false))));
            }
        }
        days = Map.copyOf(read);
        plugin.getLogger().info(days.size() + " calendar day(s) with a reward.");
    }

    /** Reward of a date of the month, null when that date gives nothing. */
    public DayReward reward(int day) {
        return days.get(day);
    }

    public LocalDate today() {
        return LocalDate.now(zone);
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
            d.lastDay = date(y.getString("dernier-jour"));
            String month = y.getString("mois");
            if (month != null) {
                try {
                    d.month = YearMonth.parse(month);
                    d.claimed.addAll(y.getIntegerList("jours-recuperes"));
                } catch (RuntimeException ignored) {
                    // unreadable month: nothing claimed this month
                }
            }
            // files of the former 28-day cycle have no month: their claimed days are not dates, dropped
        }
        d.roll(today());
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
        y.set("mois", d.month != null ? d.month.toString() : null);
        y.set("jours-recuperes", new ArrayList<>(d.claimed));
        String content = y.saveToString();
        Runnable write = () -> {
            try {
                File f = file(uuid);
                f.getParentFile().mkdirs();
                java.nio.file.Files.writeString(f.toPath(), content);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save the calendar of " + uuid, e);
            }
        };
        if (now || !plugin.isEnabled()) write.run();
        else Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
    }

    private File file(UUID uuid) {
        return new File(new File(plugin.getDataFolder(), "joueurs"), uuid + ".yml");
    }

    // ================================================================ days

    /** Counts the login day if not counted yet (join, midnight), new month included. True when a new day starts. */
    public boolean countDay(Player player) {
        PlayerData d = players.get(player.getUniqueId());
        if (d == null) return false;
        LocalDate now = today();
        d.roll(now);
        if (now.equals(d.lastDay)) return false;
        d.days++;
        d.lastDay = now;
        save(player.getUniqueId(), d, false);
        return true;
    }

    /** State of a date of the current month for this player. */
    public State state(Player player, int day) {
        PlayerData d = players.get(player.getUniqueId());
        LocalDate today = today();
        if (d == null) return State.TO_COME;
        d.roll(today);
        if (d.claimed.contains(day)) return State.CLAIMED;
        if (day == today.getDayOfMonth()) return State.TODAY;
        if (day > today.getDayOfMonth()) return State.TO_COME;
        return today.withDayOfMonth(day).isBefore(arrival(player)) ? State.BEFORE_ARRIVAL : State.MISSED;
    }

    /** First day the player came on the server. */
    private LocalDate arrival(Player player) {
        long first = player.getFirstPlayed();
        return first <= 0 ? today() : Instant.ofEpochMilli(first).atZone(zone).toLocalDate();
    }

    /** Whether today's reward waits to be claimed. */
    public boolean claimable(Player player) {
        DayReward r = days.get(today().getDayOfMonth());
        return r != null && !r.commands().isEmpty() && state(player, r.day()) == State.TODAY;
    }

    /** Claims today's reward (main thread); NONE when there is nothing to claim today. */
    public ClaimResult claimToday(Player player) {
        PlayerData d = players.get(player.getUniqueId());
        if (d == null || !claimable(player)) return ClaimResult.NONE;
        LocalDate today = today();
        DayReward r = days.get(today.getDayOfMonth());
        d.claimed.add(r.day());
        save(player.getUniqueId(), d, false);
        Reward reward = Reward.of("daily", r.name()).key("daily:" + today)
                .icon(plugin.getConfig().getString("icons.daily", "CLOCK")).slots(r.slots()).commands(r.commands());
        Rewards.give(player, reward);
        return Rewards.claimKey(player, reward.key(), true);
    }

    /** Dates of the current month claimed by the player. */
    public int claimedCount(Player player) {
        PlayerData d = players.get(player.getUniqueId());
        if (d == null) return 0;
        d.roll(today());
        return d.claimed.size();
    }

    // ================================================================ admin (tests)

    /** Forgets today's claim (or every claim of the month with {@code all}). */
    public void reset(Player player, boolean all) {
        PlayerData d = players.get(player.getUniqueId());
        if (d == null) return;
        if (all) d.claimed.clear();
        else d.claimed.remove(today().getDayOfMonth());
        save(player.getUniqueId(), d, false);
    }

    private static LocalDate date(String s) {
        try {
            return s == null || s.isEmpty() ? null : LocalDate.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
