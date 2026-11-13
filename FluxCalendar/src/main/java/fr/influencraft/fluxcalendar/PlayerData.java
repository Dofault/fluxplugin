package fr.influencraft.fluxcalendar;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;
import java.util.TreeSet;

/** Calendar of a player. Changed on the main thread; a copy is written to disk asynchronously. */
public final class PlayerData {

    /** Month of {@link #claimed}; another month means nothing claimed yet. */
    YearMonth month;
    /** Dates of {@link #month} whose reward was claimed. */
    final Set<Integer> claimed = new TreeSet<>();
    /** Last login day counted, null before the first login. */
    LocalDate lastDay;
    /** Login days counted since the first one. */
    int days;
    /** First day the calendar knew the player: earlier dates are never « missed » (the calendar did not exist yet). */
    LocalDate since;

    /** A calendar from stored values (dates as ISO text, null when unset); unreadable parts are left empty. */
    public static PlayerData of(int days, String lastDay, String month, String since, java.util.List<Integer> claimed) {
        PlayerData d = new PlayerData();
        d.days = days;
        d.lastDay = parse(lastDay);
        d.since = parse(since);
        try {
            if (month != null) {
                d.month = YearMonth.parse(month);
                d.claimed.addAll(claimed);
            }
        } catch (RuntimeException ignored) {
            // unreadable month: nothing claimed this month
        }
        return d;
    }

    private static LocalDate parse(String text) {
        try {
            return text == null ? null : LocalDate.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Starts the month of {@code today} when the stored one is older. */
    void roll(LocalDate today) {
        YearMonth now = YearMonth.from(today);
        if (!now.equals(month)) {
            month = now;
            claimed.clear();
        }
    }
}
