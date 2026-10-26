package fr.influencraft.influrecompenses;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durées de la config (« 1h », « 90m », « 2h30 », « 45s ») et leur affichage. */
final class Duree {

    private static final Pattern FORMAT = Pattern.compile("^(?:(\\d+)h)?(?:(\\d+)m?)?(?:(\\d+)s)?$");

    private Duree() {
    }

    /** @return secondes, ou -1 si illisible */
    static long lire(String s) {
        String t = s.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        if (t.isEmpty()) return -1;
        if (t.endsWith("s") && !t.contains("h") && !t.contains("m")) {
            try {
                return Long.parseLong(t.substring(0, t.length() - 1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        Matcher m = FORMAT.matcher(t);
        if (!m.matches()) return -1;
        long h = m.group(1) != null ? Long.parseLong(m.group(1)) : 0;
        long min = m.group(2) != null ? Long.parseLong(m.group(2)) : 0;
        long sec = m.group(3) != null ? Long.parseLong(m.group(3)) : 0;
        // « 30 » seul (sans unité) = minutes ; « 2h30 » = 2 h 30 min
        return h * 3600 + min * 60 + sec;
    }

    /** « 45 min », « 3h », « 2h30 ». */
    static String texte(long secondes) {
        long h = secondes / 3600, m = (secondes % 3600) / 60;
        if (h == 0) return m + " min";
        return m == 0 ? h + "h" : h + "h" + String.format("%02d", m);
    }
}
