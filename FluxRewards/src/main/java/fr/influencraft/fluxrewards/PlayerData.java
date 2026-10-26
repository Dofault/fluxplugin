package fr.influencraft.fluxrewards;

import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;

/** Login days and claimed rewards of a player. Changed on the main thread; a copy is written to disk asynchronously. */
public final class PlayerData {

    /** Login days counted (current streak when the streak is strict). */
    int days;
    /** Last login day counted, null before the first login. */
    LocalDate lastDay;
    /** Days of the current cycle already claimed. */
    final Set<Integer> claimedDays = new TreeSet<>();
    /** Playtime tiers claimed (threshold in seconds). */
    final Set<Long> claimedPlaytime = new TreeSet<>();
    /** Last known playtime (seconds), for an offline player. */
    long playtime;

    /** Day of the cycle (1 to cycle), 0 before the first login. */
    int dayOfCycle(int cycle) {
        return days <= 0 ? 0 : (days - 1) % cycle + 1;
    }
}
