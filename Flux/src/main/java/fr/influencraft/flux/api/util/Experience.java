package fr.influencraft.flux.api.util;

import org.bukkit.entity.Player;

/**
 * Experience of a player counted in points, with the vanilla formulas. {@link Player#getTotalExperience()} is not
 * reliable (the enchanting table and the anvil take levels without lowering it): the total is computed from the
 * level and the progress bar, and set back the same way. Main thread only.
 */
public final class Experience {

    private Experience() {
    }

    /** Points needed to go from {@code level} to the next one. */
    public static int toNext(int level) {
        if (level <= 15) return 2 * level + 7;
        if (level <= 30) return 5 * level - 38;
        return 9 * level - 158;
    }

    /** Points needed to reach {@code level} from 0. */
    public static long forLevel(int level) {
        long total = 0;
        for (int i = 0; i < level; i++) total += toNext(i);
        return total;
    }

    /** Every point the player has: his levels and the progress towards the next one. */
    public static long total(Player player) {
        int level = player.getLevel();
        return forLevel(level) + Math.round(player.getExp() * toNext(level));
    }

    /** Points the player would lose giving his {@code levels} top levels (his progress bar stays). */
    public static long pointsOfLevels(Player player, int levels) {
        int level = player.getLevel();
        int kept = Math.max(0, level - Math.max(0, levels));
        return forLevel(level) - forLevel(kept);
    }

    /** Level (with the fraction of the next one) that {@code points} make from 0. */
    public static double levelOf(long points) {
        int level = 0;
        long left = Math.max(0, points);
        while (left >= toNext(level)) {
            left -= toNext(level);
            level++;
        }
        return level + (double) left / toNext(level);
    }

    /** Sets the player's experience to {@code points} (levels and progress recomputed). */
    public static void set(Player player, long points) {
        long remaining = Math.max(0, points);
        int level = 0;
        while (remaining >= toNext(level)) {
            remaining -= toNext(level);
            level++;
        }
        player.setLevel(level);
        player.setExp((float) remaining / toNext(level));
        player.setTotalExperience((int) Math.min(Integer.MAX_VALUE, Math.max(0, points)));
    }

    /** Takes {@code points}; false (nothing taken) when the player has fewer. */
    public static boolean take(Player player, long points) {
        long total = total(player);
        if (points < 0 || total < points) return false;
        set(player, total - points);
        return true;
    }

    /** Gives {@code points} (no Mending repair). */
    public static void give(Player player, long points) {
        if (points > 0) set(player, total(player) + points);
    }
}
