package fr.influencraft.fluxdungeons.rpg.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * balance.yml against its own targets, at every checked level, for the classes players choose from (the hidden ones
 * are retired or being tried out): time for each class alone to kill a normal monster, an elite and a mini-boss; time
 * for a party of them to kill a boss; time each lasts alone; and how close the classes stay to each other, alone and
 * against a pack. Writes {@code target/balance-report.json} (read by assets/balance/dungeon_balance.py).
 */
class BalanceTest {

    private final Rpg rpg = Rpg.shipped();
    private final Simulator sim = new Simulator(rpg.balance, rpg.stats);
    private static final int FIGHTS = 40;

    private List<ClassDef> playable() {
        return rpg.classes.values().stream().filter(c -> !c.hidden()).toList();
    }

    @Test
    void fightsLastAsLongAsTheTargets() throws IOException {
        List<String> failures = new ArrayList<>();
        StringBuilder table = new StringBuilder("\nlevel class       normal  elite  miniboss  boss(solo) | pack3  pack6    room | dps1   dps6 | heal/s | dies: normal  elite\n");
        StringBuilder json = new StringBuilder("{\n");
        for (int level : rpg.balance.checkedLevels()) {
            json.append("  \"").append(level).append("\": {\n");
            StatSheet normal = sim.monster(level, "normal");
            json.append(String.format(Locale.ROOT, "    \"monster\": {\"health\": %.1f, \"attack\": %.1f, \"defense\": %.1f, \"k\": %.1f},%n",
                    normal.get("max_health"), normal.get("physical_attack"), normal.get("physical_defense"), CombatMath.k(rpg.balance, level)));
            List<ClassDef> classes = playable();
            for (int i = 0; i < classes.size(); i++) {
                ClassDef c = classes.get(i);
                StatSheet me = sim.player(c, level);
                double n = sim.averageTimeToKill(List.of(c), level, "normal", FIGHTS);
                double e = sim.averageTimeToKill(List.of(c), level, "elite", FIGHTS);
                double m = sim.averageTimeToKill(List.of(c), level, "miniboss", 10);
                double b = sim.averageBossTime(List.of(c), level, 10);
                double p3 = sim.averagePackTime(List.of(c), level, "normal", 3, 20);
                double p6 = sim.averagePackTime(List.of(c), level, "normal", 6, 20);
                double p10 = sim.averageRoomTime(List.of(c), level, 4, 5, 20);
                double d1 = sim.damagePerSecond(c, level, 1, 1), d6 = sim.damagePerSecond(c, level, 6, 1);
                double heal = sim.sustain(c, level, 1);
                double dn = sim.averageTimeToDie(c, level, "normal", FIGHTS);
                double de = sim.averageTimeToDie(c, level, "elite", FIGHTS);
                table.append(String.format(Locale.ROOT, "%5d %-10s %7.1f %6.1f %9.1f %10.1f  | %5.1f %6.1f %7.1f | %5.1f %6.1f | %6.1f | %12.1f %6.1f%n",
                        level, c.id(), n, e, m, b, p3, p6, p10, d1, d6, heal, dn, de));
                json.append(String.format(Locale.ROOT, "    \"%s\": {\"health\": %.1f, \"defense\": %.1f, \"regen\": %.2f, \"normal\": %.2f, \"elite\": %.2f, "
                                + "\"miniboss\": %.2f, \"boss\": %.2f, \"pack3\": %.2f, \"pack6\": %.2f, \"pack10\": %.2f, \"dps1\": %.2f, \"dps6\": %.2f, \"heal\": %.2f}%s%n",
                        c.id(), me.get("max_health"), me.get("physical_defense"), me.get("health_regen"), n, e, m, b, p3, p6, p10, d1, d6, heal,
                        i == classes.size() - 1 ? "" : ","));
                check(failures, level, c.id(), "normal", n);
                check(failures, level, c.id(), "elite", e);
                check(failures, level, c.id(), "miniboss", m);
                if (dn < rpg.balance.surviveNormal()) failures.add(level + " " + c.id() + " dies in " + fmt(dn) + " s to a normal monster");
                if (de < rpg.balance.surviveElite()) failures.add(level + " " + c.id() + " dies in " + fmt(de) + " s to an elite");
            }
            double boss = sim.averageBossTime(classes, level, 10);
            table.append(String.format(Locale.ROOT, "%5d party of %d vs boss: %.1f s%n", level, classes.size(), boss));
            check(failures, level, "party", "boss", boss);
            json.append("  }").append(level == rpg.balance.checkedLevels().get(rpg.balance.checkedLevels().size() - 1) ? "" : ",").append("\n");
        }
        json.append("}\n");
        Path out = Path.of("target", "balance-report.json");
        if (Files.isDirectory(out.getParent())) Files.writeString(out, json);
        System.out.println(table);
        assertTrue(failures.isEmpty(), String.join("\n", failures) + table);
    }

    /**
     * No class is far from the others: against one elite, a boss and a whole room (four packs of five monsters, one
     * after the other), the slowest takes at most 1.5 times as
     * long as the fastest (the druid pays his heals and his life in damage, never more than that).
     */
    @Test
    void classesStayClose() {
        List<String> failures = new ArrayList<>();
        for (int level : rpg.balance.checkedLevels()) {
            for (String what : List.of("elite", "room", "boss")) {
                double fastest = Double.MAX_VALUE, slowest = 0;
                for (ClassDef c : playable()) {
                    double t = switch (what) {
                        case "elite" -> sim.averageTimeToKill(List.of(c), level, "elite", FIGHTS);
                        case "room" -> sim.averageRoomTime(List.of(c), level, 4, 5, 20);
                        default -> sim.averageBossTime(List.of(c), level, 10);
                    };
                    fastest = Math.min(fastest, t);
                    slowest = Math.max(slowest, t);
                }
                if (slowest / fastest > 1.5) failures.add("level " + level + " " + what + ": slowest " + fmt(slowest) + " s vs fastest " + fmt(fastest) + " s");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private void check(List<String> failures, int level, String who, String rank, double seconds) {
        Balance.Band band = rpg.balance.timeToKill(rank);
        if (!band.contains(seconds)) failures.add(level + " " + who + " " + rank + " " + fmt(seconds) + " s outside [" + band.min() + ", " + band.max() + "]");
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
