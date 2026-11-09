package fr.influencraft.fluxdungeons.mob;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Spells kept apart from the monsters, to be used by any of them: {@code spells/<folder>/<id>.yml}, one spell per file
 * (the file name is its id), written as an ability ({@code type}, {@code every}, {@code animation}, {@code power}...).
 * A monster takes one with {@code - spell: <id>} in its {@code abilities}, and may change any value there
 * ({@code every}, {@code power}...): the monster's values win over the spell's.
 */
public final class Spells {

    private static final Map<String, Map<String, Object>> SPELLS = new HashMap<>();

    private Spells() {
    }

    /** Reads every spell of the folder (sub-folders included). */
    public static void load(File dir, Logger log) {
        SPELLS.clear();
        read(dir, log);
        if (!SPELLS.isEmpty()) log.info(SPELLS.size() + " spell(s) loaded.");
    }

    private static void read(File dir, Logger log) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                read(f, log);
                continue;
            }
            if (!f.getName().endsWith(".yml")) continue;
            String id = f.getName().substring(0, f.getName().length() - 4);
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            if (!y.isString("type")) {
                log.warning("Spell " + f + " has no type: ignored.");
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (String k : y.getKeys(false)) values.put(k, y.get(k));
            SPELLS.put(id, values);
        }
    }

    /** An ability as written in a monster file, completed by its spell when it names one. */
    static Map<String, Object> resolve(Map<?, ?> entry, String monster, Logger log) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object name = entry.get("spell");
        if (name != null) {
            Map<String, Object> spell = SPELLS.get(String.valueOf(name));
            if (spell == null) log.warning("Monster " + monster + ": unknown spell " + name + ".");
            else out.putAll(spell);
        }
        entry.forEach((k, v) -> {
            if (!"spell".equals(k)) out.put(String.valueOf(k), v);
        });
        if (name != null) out.putIfAbsent("spell", String.valueOf(name));
        return out;
    }

    public static List<String> ids() {
        return SPELLS.keySet().stream().sorted().toList();
    }
}
