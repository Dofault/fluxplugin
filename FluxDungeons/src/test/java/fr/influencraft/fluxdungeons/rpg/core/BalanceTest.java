package fr.influencraft.fluxdungeons.rpg.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * balance.yml against its own targets, at every checked level: time for each damage class alone to kill a normal
 * monster, an elite and a mini-boss; time for a party of the four classes to kill a boss; time each class lasts alone.
 * The healer is not a damage class: he must be able to kill alone (slower, never stuck).
 */
class BalanceTest {

    private final Rpg rpg = Rpg.shipped();
    private final Simulator sim = new Simulator(rpg.balance, rpg.stats);
    private static final List<String> DAMAGE = List.of("warrior", "archer", "mage", "cryomancer");
    private static final int FIGHTS = 40;

    @Test
    void fightsLastAsLongAsTheTargets() {
        List<String> failures = new ArrayList<>();
        StringBuilder table = new StringBuilder("\nlevel class     normal  elite  miniboss  | dies vs normal  elite\n");
        for (int level : rpg.balance.checkedLevels()) {
            for (ClassDef c : rpg.classes.values()) {
                double n = sim.averageTimeToKill(List.of(c), level, "normal", FIGHTS);
                double e = sim.averageTimeToKill(List.of(c), level, "elite", FIGHTS);
                double m = sim.averageTimeToKill(List.of(c), level, "miniboss", 10);
                double dn = sim.averageTimeToDie(c, level, "normal", FIGHTS);
                double de = sim.averageTimeToDie(c, level, "elite", FIGHTS);
                table.append(String.format("%5d %-8s %7.1f %6.1f %9.1f  | %14.1f %6.1f%n", level, c.id(), n, e, m, dn, de));
                if (DAMAGE.contains(c.id())) {
                    check(failures, level, c.id(), "normal", n);
                    check(failures, level, c.id(), "elite", e);
                    check(failures, level, c.id(), "miniboss", m);
                } else {
                    double max = rpg.balance.timeToKill("normal").max() * 3;
                    if (n > max) failures.add(level + " " + c.id() + " normal " + fmt(n) + " s > " + fmt(max) + " (support, x3)");
                    if (m >= Simulator.limit()) failures.add(level + " " + c.id() + " never kills a miniboss alone");
                }
                if (dn < rpg.balance.surviveNormal()) failures.add(level + " " + c.id() + " dies in " + fmt(dn) + " s to a normal monster");
                if (de < rpg.balance.surviveElite()) failures.add(level + " " + c.id() + " dies in " + fmt(de) + " s to an elite");
            }
            double boss = sim.averageTimeToKill(List.copyOf(rpg.classes.values()), level, "boss", 10);
            table.append(String.format("%5d party of 4 vs boss: %.1f s%n", level, boss));
            check(failures, level, "party", "boss", boss);
        }
        System.out.println(table);
        assertTrue(failures.isEmpty(), String.join("\n", failures) + table);
    }

    /** No class is more than twice as fast as another damage class against the same monster (none useless). */
    @Test
    void damageClassesStayClose() {
        for (int level : rpg.balance.checkedLevels()) {
            double fastest = Double.MAX_VALUE, slowest = 0;
            for (String id : DAMAGE) {
                double t = sim.averageTimeToKill(List.of(rpg.classes.get(id)), level, "elite", FIGHTS);
                fastest = Math.min(fastest, t);
                slowest = Math.max(slowest, t);
            }
            assertTrue(slowest / fastest <= 2, "level " + level + ": slowest " + fmt(slowest) + " s vs fastest " + fmt(fastest) + " s");
        }
    }

    private void check(List<String> failures, int level, String who, String rank, double seconds) {
        Balance.Band band = rpg.balance.timeToKill(rank);
        if (!band.contains(seconds)) failures.add(level + " " + who + " " + rank + " " + fmt(seconds) + " s outside [" + band.min() + ", " + band.max() + "]");
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }
}
