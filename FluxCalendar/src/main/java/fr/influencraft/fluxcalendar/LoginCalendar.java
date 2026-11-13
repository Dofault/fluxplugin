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
 * The login calendar: one reward per date of the month, claimed by the player on that date only, by clicking
 * today's case of the calendar ({@code /calendrier}). A date not claimed is missed; everything starts again on the
 * 1st of the next month. The reward of a date is, from the most precise: {@code days.<date>}, then
 * {@code weekdays.<monday..sunday>}, then {@code daily}. Claiming every date of a month gives {@code complete} too.
 *
 * <p>A claim runs at once: the reward goes through Flux ({@link Rewards}) and is claimed in the same click, so it
 * only stays in {@code /rewards} when it does not fit in the inventory.</p>
 *
 * <p>Main thread only; rows of {@code flux_calendar_players} are written asynchronously (files {@code joueurs/<uuid>.yml} before 2026-11-13, read once by {@code compat.CalendarFiles}). The config also accepts the
 * former French keys ({@code quotidien}, {@code jours-semaine} with lundi..dimanche, {@code jours},
 * {@code mois-complet}, {@code nom}, {@code description}, {@code commandes}, {@code grosse}).</p>
 */
public final class LoginCalendar {

    /** BEFORE_ARRIVAL: before the player's first day on the server or in the calendar (never shown as missed). */
    public enum State { CLAIMED, TODAY, MISSED, BEFORE_ARRIVAL, TO_COME }

    private final FluxCalendar plugin;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private ZoneId zone = ZoneId.of("Europe/Paris");
    private static final List<String> FRENCH_DAYS = List.of("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche");
    private DayReward daily;
    private Map<java.time.DayOfWeek, DayReward> weekdays = Map.of();
    private Map<Integer, DayReward> dates = Map.of();
    private DayReward complete;

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
        daily = reward(section(c, "daily", "quotidien"), "daily");
        complete = reward(section(c, "complete", "mois-complet"), "complete");
        Map<java.time.DayOfWeek, DayReward> week = new HashMap<>();
        ConfigurationSection w = section(c, "weekdays", "jours-semaine");
        if (w != null) {
            for (String key : w.getKeys(false)) {
                java.time.DayOfWeek dow = weekday(key);
                if (dow == null) plugin.getLogger().warning("Unknown weekday « " + key + " » in the config, ignored");
                else week.put(dow, reward(w.getConfigurationSection(key), key));
            }
        }
        weekdays = Map.copyOf(week);
        Map<Integer, DayReward> read = new HashMap<>();
        ConfigurationSection section = section(c, "days", "jours");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                int day = parse(key);
                if (day < 1 || day > 31 || section.getConfigurationSection(key) == null) {
                    plugin.getLogger().warning("Unreadable day « " + key + " » in the config, ignored");
                    continue;
                }
                read.put(day, reward(section.getConfigurationSection(key), key));
            }
        }
        dates = Map.copyOf(read);
        plugin.getLogger().info("Calendar: " + (daily != null ? "daily reward, " : "") + weekdays.size() + " weekday(s), "
                + dates.size() + " date(s)" + (complete != null ? ", month bonus." : "."));
    }

    /** The section under its key, or under its former French name. */
    private static ConfigurationSection section(ConfigurationSection parent, String key, String french) {
        return parent.contains(key, true) ? parent.getConfigurationSection(key) : parent.getConfigurationSection(french);
    }

    private DayReward reward(ConfigurationSection s, String key) {
        if (s == null) return null;
        List<String> commands = new ArrayList<>();
        for (String cmd : s.isList("commands") ? s.getStringList("commands") : s.getStringList("commandes")) {
            commands.add(cmd.replace("{joueur}", "%player%"));
        }
        return new DayReward(s.getString("name", s.getString("nom", plugin.messages().raw("day-name", "%day%", key))),
                s.isList("lore") ? s.getStringList("lore") : s.getStringList("description"), commands,
                Math.max(0, s.getInt("slots", 1)), s.getBoolean("big", s.getBoolean("grosse", false)));
    }

    private static java.time.DayOfWeek weekday(String key) {
        String k = key.toLowerCase(java.util.Locale.ROOT);
        int french = FRENCH_DAYS.indexOf(k);
        if (french >= 0) return java.time.DayOfWeek.of(french + 1);
        try {
            return java.time.DayOfWeek.valueOf(k.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Reward of a date of the current month, null when that date gives nothing. */
    public DayReward reward(int day) {
        LocalDate date = today().withDayOfMonth(day);
        DayReward r = dates.get(day);
        if (r == null) r = weekdays.get(date.getDayOfWeek());
        return r != null ? r : daily;
    }

    /** Reward of a month whose every date was claimed, null when none. */
    public DayReward complete() {
        return complete;
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    // ================================================================ player data

    /** Data of a loaded (online) player, or null. */
    public PlayerData data(UUID uuid) {
        return players.get(uuid);
    }

    private static String table() {
        return fr.influencraft.flux.api.data.Database.table("flux_calendar_players");
    }

    /** Table of the players' calendars (Flux's database). */
    public void open() throws java.sql.SQLException {
        try (java.sql.Connection c = fr.influencraft.flux.api.data.Database.connection(); java.sql.Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table() + " (uuid " + fr.influencraft.flux.api.data.Database.dialect().key(36)
                    + " NOT NULL PRIMARY KEY, days INTEGER NOT NULL, last_day VARCHAR(10), month VARCHAR(7), since VARCHAR(10),"
                    + " claimed TEXT NOT NULL)");
        }
    }

    public PlayerData load(UUID uuid) {
        PlayerData d = read(uuid);
        if (d == null) d = fr.influencraft.fluxcalendar.compat.CalendarFiles.read(plugin, uuid);
        if (d == null) d = new PlayerData();
        if (d.since == null) d.since = today();
        d.roll(today());
        players.put(uuid, d);
        return d;
    }

    private PlayerData read(UUID uuid) {
        try (java.sql.Connection c = fr.influencraft.flux.api.data.Database.connection();
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT days, last_day, month, since, claimed FROM " + table() + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                PlayerData d = new PlayerData();
                d.days = rs.getInt(1);
                d.lastDay = date(rs.getString(2));
                d.since = date(rs.getString(4));
                String month = rs.getString(3);
                if (month != null) {
                    try {
                        d.month = YearMonth.parse(month);
                        for (String n : rs.getString(5).split(",")) if (!n.isBlank()) d.claimed.add(Integer.parseInt(n.trim()));
                    } catch (RuntimeException ignored) {
                        // unreadable month: nothing claimed this month
                    }
                }
                return d;
            }
        } catch (java.sql.SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read the calendar of " + uuid, e);
            return null;
        }
    }

    public void unload(UUID uuid) {
        PlayerData d = players.remove(uuid);
        if (d != null) save(uuid, d, false);
    }

    public void saveAll() {
        players.forEach((uuid, d) -> save(uuid, d, true));
    }

    private void save(UUID uuid, PlayerData d, boolean now) {
        String lastDay = d.lastDay != null ? d.lastDay.toString() : null, month = d.month != null ? d.month.toString() : null,
                since = d.since != null ? d.since.toString() : null;
        String claimed = d.claimed.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        int days = d.days;
        Runnable write = () -> {
            try (java.sql.Connection c = fr.influencraft.flux.api.data.Database.connection()) {
                try (java.sql.PreparedStatement del = c.prepareStatement("DELETE FROM " + table() + " WHERE uuid = ?")) {
                    del.setString(1, uuid.toString());
                    del.executeUpdate();
                }
                try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO " + table()
                        + " (uuid, days, last_day, month, since, claimed) VALUES (?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, uuid.toString());
                    ps.setInt(2, days);
                    ps.setString(3, lastDay);
                    ps.setString(4, month);
                    ps.setString(5, since);
                    ps.setString(6, claimed);
                    ps.executeUpdate();
                }
            } catch (java.sql.SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save the calendar of " + uuid, e);
            }
        };
        if (now || !plugin.isEnabled()) write.run();
        else Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
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
        LocalDate date = today.withDayOfMonth(day);
        return date.isBefore(arrival(player)) || (d.since != null && date.isBefore(d.since)) ? State.BEFORE_ARRIVAL : State.MISSED;
    }

    /** First day the player came on the server. */
    private LocalDate arrival(Player player) {
        long first = player.getFirstPlayed();
        return first <= 0 ? today() : Instant.ofEpochMilli(first).atZone(zone).toLocalDate();
    }

    /** Whether today's reward waits to be claimed. */
    public boolean claimable(Player player) {
        int day = today().getDayOfMonth();
        DayReward r = reward(day);
        return r != null && !r.commands().isEmpty() && state(player, day) == State.TODAY;
    }

    /** Claims today's reward (main thread); NONE when there is nothing to claim today. */
    public ClaimResult claimToday(Player player) {
        PlayerData d = players.get(player.getUniqueId());
        if (d == null || !claimable(player)) return ClaimResult.NONE;
        LocalDate today = today();
        DayReward r = reward(today.getDayOfMonth());
        d.claimed.add(today.getDayOfMonth());
        save(player.getUniqueId(), d, false);
        ClaimResult result = give(player, r, "daily:" + today);
        if (complete != null && d.claimed.size() == today.lengthOfMonth()) {
            plugin.messages().send(player, "month-complete");
            result = result.plus(give(player, complete, "calendar-complete:" + YearMonth.from(today)));
        }
        return result;
    }

    /** Through Flux, claimed at once: what does not fit stays in /rewards. */
    private ClaimResult give(Player player, DayReward r, String key) {
        Reward reward = Reward.of("daily", r.name()).key(key)
                .icon(plugin.getConfig().getString("icons.daily", "CLOCK")).slots(r.slots()).commands(r.commands());
        Rewards.give(player, reward);
        return Rewards.claimKey(player, key, true);
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
