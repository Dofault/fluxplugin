package fr.influencraft.fluxpass;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * pass.yml: the season (dates, name), how many tiers, the points to reach each tier and the rewards of both tracks.
 * The season id (its start) keys the data of the players: a new start date is a new season, everybody starts again.
 */
final class Season {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    enum Track {
        FREE, PREMIUM;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Track of(String text) {
            return text != null && text.equalsIgnoreCase("premium") ? PREMIUM : text != null && text.equalsIgnoreCase("free") ? FREE : null;
        }
    }

    /** A reward of one track of one tier: what the menu shows and what the console runs on claim. */
    record Reward(String icon, int amount, List<String> lines, List<String> commands) {
    }

    final String id;
    final String name;
    final long start, end;
    final int tiers;
    private final int[] required;
    private final Map<Integer, Reward> free = new HashMap<>(), premium = new HashMap<>();

    private Season(String id, String name, long start, long end, int tiers, int[] required) {
        this.id = id;
        this.name = name;
        this.start = start;
        this.end = end;
        this.tiers = tiers;
        this.required = required;
    }

    static Season load(YamlConfiguration yml, Consumer<String> warn) {
        String startText = yml.getString("season.start", "2026-01-01 00:00");
        long start = millis(startText, warn), end = millis(yml.getString("season.end", "2099-01-01 00:00"), warn);
        int tiers = Math.max(1, yml.getInt("tiers", 50));
        int[] required = new int[tiers + 1];
        ConfigurationSection pts = yml.getConfigurationSection("points-per-tier");
        if (pts != null) {
            for (String key : pts.getKeys(false)) {
                String[] range = key.split("-", 2);
                try {
                    int from = Integer.parseInt(range[0].trim()), to = range.length > 1 ? Integer.parseInt(range[1].trim()) : from;
                    for (int t = Math.max(1, from); t <= Math.min(tiers, to); t++) required[t] = Math.max(0, pts.getInt(key));
                } catch (NumberFormatException e) {
                    warn.accept("pass.yml points-per-tier: unreadable key " + key);
                }
            }
        }
        Season s = new Season(startText.replace(' ', 'T'), yml.getString("season.name", ""), start, end, tiers, required);
        ConfigurationSection rewards = yml.getConfigurationSection("rewards");
        if (rewards != null) {
            for (String key : rewards.getKeys(false)) {
                int tier;
                try {
                    tier = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    warn.accept("pass.yml rewards: " + key + " is not a tier");
                    continue;
                }
                for (Track track : Track.values()) {
                    ConfigurationSection r = rewards.getConfigurationSection(key + "." + track.key());
                    if (r == null) continue;
                    (track == Track.FREE ? s.free : s.premium).put(tier, new Reward(r.getString("icon", "CHEST"),
                            Math.max(1, Math.min(64, r.getInt("amount", 1))), new ArrayList<>(r.getStringList("lines")),
                            new ArrayList<>(r.getStringList("commands"))));
                }
            }
        }
        return s;
    }

    private static long millis(String text, Consumer<String> warn) {
        try {
            return LocalDateTime.parse(text.trim(), DATE).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            warn.accept("pass.yml: unreadable date " + text + " (yyyy-MM-dd HH:mm)");
            return 0;
        }
    }

    /** Points to reach {@code tier} from the tier before (0 for a tier out of range). */
    int required(int tier) {
        return tier < 1 || tier > tiers ? 0 : required[tier];
    }

    Reward reward(int tier, Track track) {
        return (track == Track.FREE ? free : premium).get(tier);
    }

    boolean running(long now) {
        return now >= start && now < end;
    }
}
