package fr.influencraft.flux.api.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Number, amount, duration and date formatting shared by the suite. Units and words come from Flux's language
 * file ({@code format.*} keys), so every plugin writes "2 h 30 min" or "2 h 30 min" the same way.
 *
 * <pre>
 *   Format.number(12500.7)      "12 500"       (rounded down, space grouping)
 *   Format.decimal(2.5)         "2,5"          (at most 2 decimals, no trailing zero)
 *   Format.duration(9000)       "2 h 30 min"   (two largest units)
 *   Format.parseAmount("2,5k")  2500           (k, m suffixes; -1 when invalid)
 *   Format.parseDuration("1h30") 5400          (h, m, s, d; a bare number is minutes)
 * </pre>
 */
public final class Format {

    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
            "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX"};
    private static final Pattern DURATION = Pattern.compile("(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m(?:in)?)?(?:(\\d+)s)?");
    private static final Pattern H_MIN = Pattern.compile("(\\d+)h(\\d+)");

    /** Words of the current language ({@code format.<key>} in Flux's language file), set by Flux. */
    private static volatile Function<String, String> words = key -> switch (key) {
        case "day" -> "d";
        case "hour" -> "h";
        case "minute" -> "min";
        case "second" -> "s";
        case "now" -> "now";
        case "ago" -> "%duration% ago";
        case "just-now" -> "just now";
        case "yes" -> "yes";
        case "no" -> "no";
        case "date" -> "dd/MM HH:mm";
        default -> key;
    };
    private static volatile Locale locale = Locale.FRANCE;

    private Format() {
    }

    /** Called by Flux once its language file is loaded. */
    public static void configure(Function<String, String> wordsOfLanguage, Locale numberLocale) {
        words = wordsOfLanguage;
        locale = numberLocale;
    }

    public static String word(String key) {
        return words.apply(key);
    }

    // ---------------------------------------------------------------- numbers

    /** Whole number, rounded down, thousands grouped with a space. */
    public static String number(double value) {
        return grouping("#,##0").format(Math.floor(value + 1e-9));
    }

    /** At most two decimals, without trailing zeros, thousands grouped. */
    public static String decimal(double value) {
        return grouping("#,##0.##").format(value);
    }

    /** Percentage with at most one decimal: {@code 12.5} gives "12,5". */
    public static String percent(double value) {
        return grouping("#,##0.#").format(value);
    }

    private static DecimalFormat grouping(String pattern) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(locale);
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat(pattern, symbols);
    }

    /** Positive whole amount typed by a player ("10 000", "10k", "2,5k", "1m"), or -1. */
    public static long parseAmount(String text) {
        if (text == null) return -1;
        String t = text.toLowerCase(Locale.ROOT).replace(" ", "").replace(" ", "").replace(" ", "").replace(",", ".");
        double multiplier = 1;
        if (t.endsWith("k")) {
            multiplier = 1_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            multiplier = 1_000_000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            double v = Double.parseDouble(t) * multiplier;
            return v >= 1 && v < Long.MAX_VALUE ? (long) Math.floor(v) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String roman(int n) {
        return n > 0 && n < ROMAN.length ? ROMAN[n] : String.valueOf(n);
    }

    public static String yesNo(boolean value) {
        return word(value ? "yes" : "no");
    }

    /** Text bar of {@code width} characters ({@code |}) filled by progress / total. */
    public static String bar(double progress, double total, int width, String filledColor, String emptyColor) {
        int filled = total <= 0 ? width : (int) Math.max(0, Math.min(width, Math.floor(progress * width / total)));
        return filledColor + "|".repeat(filled) + emptyColor + "|".repeat(width - filled);
    }

    // ---------------------------------------------------------------- durations and dates

    /** The two largest units: "3 d 4 h", "2 h 30 min", "5 min", "12 s"; {@code format.now} at 0 or less. */
    public static String duration(long seconds) {
        if (seconds <= 0) return word("now");
        long d = seconds / 86400, h = seconds % 86400 / 3600, m = seconds % 3600 / 60;
        if (d > 0) return d + " " + word("day") + (h > 0 ? " " + h + " " + word("hour") : "");
        if (h > 0) return h + " " + word("hour") + (m > 0 ? " " + m + " " + word("minute") : "");
        if (m > 0) return m + " " + word("minute");
        return seconds + " " + word("second");
    }

    /**
     * Duration written in a config or by a player: "1d", "2h30", "90m", "45s", "1h20m"; a bare number is minutes.
     * Returns seconds, or -1 when unreadable.
     */
    public static long parseDuration(String text) {
        if (text == null) return -1;
        String t = text.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (t.isEmpty()) return -1;
        try {
            if (t.chars().allMatch(Character::isDigit)) return Long.parseLong(t) * 60;
            Matcher hm = H_MIN.matcher(t);
            if (hm.matches()) return Long.parseLong(hm.group(1)) * 3600 + Long.parseLong(hm.group(2)) * 60;
            Matcher m = DURATION.matcher(t);
            if (!m.matches()) return -1;
            long total = 0;
            if (m.group(1) != null) total += Long.parseLong(m.group(1)) * 86400;
            if (m.group(2) != null) total += Long.parseLong(m.group(2)) * 3600;
            if (m.group(3) != null) total += Long.parseLong(m.group(3)) * 60;
            if (m.group(4) != null) total += Long.parseLong(m.group(4));
            return total;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Date and time of a timestamp (ms), pattern {@code format.date}. */
    public static String date(long millis) {
        return new SimpleDateFormat(word("date"), locale).format(new Date(millis));
    }

    /** "2 h 5 min ago" ({@code format.ago}), {@code format.just-now} under a minute. */
    public static String ago(long millis) {
        long s = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (s < 60) return word("just-now");
        return word("ago").replace("%duration%", duration(s));
    }

    // ---------------------------------------------------------------- text

    /** Splits a text into lines of at most {@code max} characters, on spaces. */
    public static List<String> wrap(String text, int max) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) return lines;
        StringBuilder current = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (!current.isEmpty() && current.length() + 1 + word.length() > max) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) current.append(' ');
            current.append(word);
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }
}
