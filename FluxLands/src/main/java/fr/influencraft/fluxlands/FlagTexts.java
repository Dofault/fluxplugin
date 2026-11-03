package fr.influencraft.fluxlands;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Names and descriptions of Lands' flags (role permissions, town settings) as players read them. Lands 9 gives only
 * the flag id through its API ({@code entity_griefing}); its French texts are the items {@code entry_<flag>} of its
 * menus ({@code Locale/fr-FR_gui.yml}: {@code gui.list_role_settings} and {@code gui.list_area_settings}), read again at
 * start and on /fluxlands reload.
 */
final class FlagTexts {

    record Text(String name, List<String> description) {
    }

    private static final String[] SECTIONS = {"gui.list_role_settings.items.default", "gui.list_area_settings.items.default"};
    private static volatile Map<String, Text> texts = Map.of();

    private FlagTexts() {
    }

    static void charger(Plugin lands) {
        Map<String, Text> m = new HashMap<>();
        if (lands != null) {
            File f = new File(lands.getDataFolder(), "Locale/fr-FR_gui.yml");
            if (f.exists()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                for (String path : SECTIONS) {
                    ConfigurationSection s = y.getConfigurationSection(path);
                    if (s == null) continue;
                    for (String k : s.getKeys(false)) {
                        if (!k.startsWith("entry_")) continue;
                        String id = k.substring(6);
                        // the role permissions come first: a flag in both lists keeps that text
                        m.putIfAbsent(id, new Text(name(s.getString(k + ".name", "")), description(s.getStringList(k + ".lore"))));
                    }
                }
            }
        }
        texts = m;
    }

    /** Name of a flag, or {@code fallback} when Lands' menus have none. */
    static String name(String flagId, String fallback) {
        Text t = texts.get(flagId);
        return t != null && !t.name().isEmpty() ? t.name() : fallback;
    }

    /** Description paragraphs of a flag (empty when none). */
    static List<String> description(String flagId) {
        Text t = texts.get(flagId);
        return t == null ? List.of() : t.description();
    }

    /** « <click>%img_left_click% <state> Grief des entités ▸ <state> » -> « Grief des entités ». */
    private static String name(String raw) {
        String s = Couleurs.nu(raw.replaceAll("%img_[a-z_]+%", "").replaceAll("<v:[^>]*>", ""));
        int arrow = s.indexOf('▸');
        if (arrow >= 0) s = s.substring(0, arrow);
        return s.replace("✔", "").replace("✖", "").trim();
    }

    /** Lore of the menu item, its lines joined again: a « ➤ » line starts a new paragraph, lines with values dropped. */
    private static List<String> description(List<String> lore) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : lore) {
            if (raw.contains("<v:") || raw.contains("%")) continue;
            String line = Couleurs.nu(raw);
            boolean starts = line.startsWith("➤") || line.startsWith("•");
            line = line.replaceFirst("^[➤•]\\s*", "").trim();
            if (line.isEmpty()) continue;
            if (starts && current.length() > 0) {
                out.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(line);
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }
}
