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

    /** Starts the month of {@code today} when the stored one is older. */
    void roll(LocalDate today) {
        YearMonth now = YearMonth.from(today);
        if (!now.equals(month)) {
            month = now;
            claimed.clear();
        }
    }
}
