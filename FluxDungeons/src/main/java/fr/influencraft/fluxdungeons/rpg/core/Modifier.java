package fr.influencraft.fluxdungeons.rpg.core;

/**
 * One change to a stat, from one source (class, level, an item, a set bonus, a buff, a skill). Sources never write a
 * stat: they give modifiers, and {@link StatSheet} combines them in a fixed order.
 *
 * <ul>
 *   <li>{@link Op#ADD}: added to the base ({@code +40 max_health}, {@code +0.05 crit_chance});</li>
 *   <li>{@link Op#PERCENT}: summed with the other percents, then applied once ({@code +10 %} and {@code +15 %} of max
 *   health = {@code +25 %}, never compounded);</li>
 *   <li>{@link Op#MULTIPLY}: each one multiplies the result (rare: a boss phase, a curse); compounded on purpose,
 *   so kept for a few sources only.</li>
 * </ul>
 */
public record Modifier(String stat, Op op, double value, String source) {

    public enum Op { ADD, PERCENT, MULTIPLY }

    public static Modifier add(String stat, double value, String source) {
        return new Modifier(stat, Op.ADD, value, source);
    }

    public static Modifier percent(String stat, double value, String source) {
        return new Modifier(stat, Op.PERCENT, value, source);
    }

    public static Modifier multiply(String stat, double value, String source) {
        return new Modifier(stat, Op.MULTIPLY, value, source);
    }
}
