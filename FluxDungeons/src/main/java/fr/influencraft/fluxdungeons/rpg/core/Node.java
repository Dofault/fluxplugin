package fr.influencraft.fluxdungeons.rpg.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of a parsed YAML tree (maps, lists, scalars) with typed getters that record every problem with its
 * path instead of failing at the first one: a config is checked whole, then refused with the full list
 * ({@link ConfigException}). The core of the rpg reads its configs through it, without Bukkit.
 */
public final class Node {

    private final String path;
    private final Map<String, Object> map;
    private final List<String> problems;

    private Node(String path, Map<String, Object> map, List<String> problems) {
        this.path = path;
        this.map = map;
        this.problems = problems;
    }

    /** Root of a file; problems are collected in the given list. */
    public static Node root(String file, Object yaml, List<String> problems) {
        Map<String, Object> m = yaml instanceof Map<?, ?> raw ? keys(raw) : new LinkedHashMap<>();
        if (!(yaml instanceof Map<?, ?>)) problems.add(file + ": empty or not a map");
        return new Node(file, m, problems);
    }

    /** YAML reads {@code 2:} as a number: every key becomes text, in nested maps and lists too. */
    private static Map<String, Object> keys(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) out.put(String.valueOf(e.getKey()), value(e.getValue()));
        return out;
    }

    private static Object value(Object v) {
        if (v instanceof Map<?, ?> m) return keys(m);
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) out.add(value(o));
            return out;
        }
        return v;
    }

    public String path() {
        return path;
    }

    public List<String> problems() {
        return problems;
    }

    public void problem(String key, String message) {
        problems.add(path + "." + key + ": " + message);
    }

    public boolean has(String key) {
        return map.containsKey(key);
    }

    public Iterable<String> keys() {
        return map.keySet();
    }

    @SuppressWarnings("unchecked")
    public Node child(String key) {
        Object v = map.get(key);
        if (v instanceof Map<?, ?> m) return new Node(path + "." + key, (Map<String, Object>) m, problems);
        if (v != null) problem(key, "must be a section");
        return new Node(path + "." + key, new LinkedHashMap<>(), problems);
    }

    /** Like {@link #child} but records a problem when absent. */
    public Node required(String key) {
        if (!map.containsKey(key)) problem(key, "missing");
        return child(key);
    }

    public double number(String key, double fallback) {
        Object v = map.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
        problem(key, "must be a number (found \"" + v + "\")");
        return fallback;
    }

    /** A number that must be present. */
    public double number(String key) {
        if (!map.containsKey(key)) {
            problem(key, "missing");
            return 0;
        }
        return number(key, 0);
    }

    /** A number within [min, max], recorded as a problem otherwise. */
    public double number(String key, double fallback, double min, double max) {
        double v = number(key, fallback);
        if (v < min || v > max) {
            problem(key, v + " out of range [" + min + ", " + max + "]");
            return Math.max(min, Math.min(max, v));
        }
        return v;
    }

    public String text(String key, String fallback) {
        Object v = map.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    public boolean flag(String key, boolean fallback) {
        Object v = map.get(key);
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        problem(key, "must be true or false");
        return fallback;
    }

    public List<String> texts(String key) {
        Object v = map.get(key);
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        if (v instanceof List<?> l) {
            for (Object o : l) out.add(String.valueOf(o));
        } else problem(key, "must be a list");
        return out;
    }

    @SuppressWarnings("unchecked")
    public List<Node> list(String key) {
        Object v = map.get(key);
        List<Node> out = new ArrayList<>();
        if (v == null) return out;
        if (!(v instanceof List<?> l)) {
            problem(key, "must be a list");
            return out;
        }
        for (int i = 0; i < l.size(); i++) {
            Object o = l.get(i);
            if (o instanceof Map<?, ?> m) out.add(new Node(path + "." + key + "[" + i + "]", (Map<String, Object>) m, problems));
            else problems.add(path + "." + key + "[" + i + "]: must be a section");
        }
        return out;
    }

    /** Every entry of a section of numbers (stat -> value). */
    public Map<String, Double> numbers() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String k : map.keySet()) out.put(k, number(k, 0));
        return out;
    }
}
