package fr.influencraft.fluxdungeons.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One script of a monster ({@code scripts:} of {@code mobs/*.yml}), in the EliteScript format:
 *
 * <pre>
 * WakeUp:
 *   Events: [EliteMobDamagedByPlayerEvent]
 *   Conditions: {isAlive: true}          # blocking
 *   Cooldowns: {local: 200, global: 20}  # ticks
 *   Zone: {shape: CYLINDER, radius: 4, Target: {targetType: SELF}}
 *   Actions:
 *     - {action: SET_MOB_AI, bValue: true, Target: {targetType: SELF}}
 *     - {action: DAMAGE, multiplier: 0.5, wait: 10, Target: {targetType: ZONE_FULL}}
 * </pre>
 *
 * Kept as the maps read from YAML: the engine reads the keys it needs when it runs ({@link ScriptEngine}).
 */
public final class Script {

    final String name;
    final Set<String> events = new LinkedHashSet<>();
    final Zone zone;
    final Map<?, ?> conditions;
    final int localCooldown, globalCooldown;
    final List<Map<?, ?>> actions = new ArrayList<>();

    private Script(String name, Map<?, ?> m) {
        this.name = name;
        if (m.get("Events") instanceof List<?> l) for (Object e : l) events.add(String.valueOf(e));
        Map<?, ?> z = map(m, "Zone");
        zone = z == null ? null : new Zone(z);
        conditions = map(m, "Conditions");
        Map<?, ?> c = map(m, "Cooldowns");
        localCooldown = c == null ? 0 : (int) range(c.get("local"));
        globalCooldown = c == null ? 0 : (int) range(c.get("global"));
        if (m.get("Actions") instanceof List<?> l) for (Object a : l) if (a instanceof Map<?, ?> am) actions.add(am);
    }

    /** Every script of an {@code eliteScript} / {@code scripts} section, by name. */
    public static Map<String, Script> parse(Object section) {
        Map<String, Script> out = new LinkedHashMap<>();
        if (section instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> body) out.put(String.valueOf(e.getKey()), new Script(String.valueOf(e.getKey()), body));
            }
        }
        return out;
    }

    public boolean listens(String event) {
        return events.contains(event);
    }

    // ---------------------------------------------------------------- reading the maps (keys are case-insensitive)

    static Object get(Map<?, ?> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        if (v != null) return v;
        for (Map.Entry<?, ?> e : m.entrySet()) if (String.valueOf(e.getKey()).equalsIgnoreCase(key)) return e.getValue();
        return null;
    }

    static Map<?, ?> map(Map<?, ?> m, String key) {
        return get(m, key) instanceof Map<?, ?> r ? r : null;
    }

    static String text(Map<?, ?> m, String key, String fallback) {
        Object v = get(m, key);
        return v == null ? fallback : String.valueOf(v);
    }

    static double num(Map<?, ?> m, String key, double fallback) {
        Object v = get(m, key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) return range(s);
        return fallback;
    }

    static boolean bool(Map<?, ?> m, String key, boolean fallback) {
        Object v = get(m, key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }

    static List<String> list(Map<?, ?> m, String key) {
        Object v = get(m, key);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
        else if (v != null) out.add(String.valueOf(v));
        return out;
    }

    /** A number or a range {@code 40~80} (picked at random). */
    static double range(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0;
        String s = String.valueOf(v).trim();
        try {
            int tilde = s.indexOf('~');
            if (tilde > 0) {
                double lo = Double.parseDouble(s.substring(0, tilde)), hi = Double.parseDouble(s.substring(tilde + 1));
                return lo + ThreadLocalRandom.current().nextDouble() * Math.max(0, hi - lo);
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
