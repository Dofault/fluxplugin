package fr.influencraft.fluxspawner;

import java.util.Locale;

/**
 * A quantity written in a config either as a fixed number ({@code 500}) or as a duration of play ({@code 1.5h},
 * {@code 30m}, {@code 90s}), turned into a number by whoever reads it (hours of production for kills, of the reference
 * income for money, of optimal play for items). Hours keep every spawner type and level on the same footing.
 */
record Amount(double fixed, double hours) {

    static final Amount ZERO = new Amount(0, 0);

    boolean timed() {
        return hours > 0;
    }

    /** {@code value} read from YAML: a number, or a text ending with h, m or s. */
    static Amount parse(Object value) {
        if (value == null) return ZERO;
        if (value instanceof Number n) return new Amount(Math.max(0, n.doubleValue()), 0);
        String s = value.toString().trim().toLowerCase(Locale.ROOT).replace(',', '.');
        if (s.isEmpty()) return ZERO;
        try {
            if (s.endsWith("h")) return new Amount(0, Math.max(0, Double.parseDouble(s.substring(0, s.length() - 1))));
            if (s.endsWith("m")) return new Amount(0, Math.max(0, Double.parseDouble(s.substring(0, s.length() - 1)) / 60));
            if (s.endsWith("s")) return new Amount(0, Math.max(0, Double.parseDouble(s.substring(0, s.length() - 1)) / 3600));
            return new Amount(Math.max(0, Double.parseDouble(s)), 0);
        } catch (NumberFormatException e) {
            return ZERO;
        }
    }

    /** Hours of the amount, a fixed number being read as seconds (durations of a tank, of a capacity). */
    double asHours() {
        return timed() ? hours : fixed / 3600;
    }

    /**
     * Rounds a computed amount up to a number that reads well (2 significant digits above 100: 5 287 -> 5 300), so
     * players are asked for round figures whatever the weights behind.
     */
    static long nice(double value) {
        if (value <= 0) return 0;
        if (value <= 100) return (long) Math.ceil(value);
        double magnitude = Math.pow(10, Math.floor(Math.log10(value)) - 1);
        return (long) (Math.ceil(value / magnitude) * magnitude);
    }
}
