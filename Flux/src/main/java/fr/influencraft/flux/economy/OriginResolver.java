package fr.influencraft.flux.economy;

import fr.influencraft.flux.api.economy.Origin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds the origin of a money movement made without one, from the call stack: the outermost plugin that is not part
 * of the money plumbing. Outermost because the plumbing sits inside (CMI's economy under Vault under eco under
 * EcoJobs) and a command dispatched by a plugin (EcoBattlepass running {@code cmi money give}) belongs to the plugin
 * that dispatched it. Class rules ({@code ledger.classes}: class name prefix to origin) name what the plugin alone
 * cannot tell (CMI: salary, {@code /pay}, a typed {@code /money}). A walk costs a few microseconds; classes are
 * mapped to their plugin once.
 */
final class OriginResolver {

    /** Plugins that only carry money for others (lower case). */
    private volatile Set<String> plumbing = Set.of("flux", "vault", "vaultunlocked", "cmi", "cmilib", "cmieinjector", "eco");
    private volatile Map<String, String> classes = Map.of();

    private static final ClassValue<String> PLUGIN_OF = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            try {
                return JavaPlugin.getProvidingPlugin(type).getName();
            } catch (IllegalArgumentException | IllegalStateException e) {
                return ""; // server or library class
            }
        }
    };

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    void configure(ConfigurationSection ledger) {
        if (ledger == null) return;
        if (ledger.isList("plumbing")) {
            plumbing = Set.copyOf(ledger.getStringList("plumbing").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
        }
        Map<String, String> rules = new LinkedHashMap<>();
        // class names have dots, which YAML paths split into sections: read the leaves with their full path
        ConfigurationSection c = ledger.getConfigurationSection("classes");
        if (c != null) {
            for (String k : c.getKeys(true)) if (!c.isConfigurationSection(k)) rules.put(k, c.getString(k, ""));
        }
        // longest prefixes first, so a precise rule wins over a broad one
        Map<String, String> sorted = new LinkedHashMap<>();
        rules.entrySet().stream().sorted((a, b) -> b.getKey().length() - a.getKey().length())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        classes = sorted;
    }

    /** Origin of the movement being made on this thread. Never null ({@link Origin#UNKNOWN} when nothing fits). */
    Origin resolve() {
        Map<String, String> rules = classes;
        Set<String> skip = plumbing;
        String found = WALKER.walk(frames -> {
            String best = null;
            var it = frames.limit(400).iterator();
            while (it.hasNext()) {
                Class<?> type = it.next().getDeclaringClass();
                String rule = rule(rules, type.getName());
                if (rule != null) {
                    best = rule;
                    continue;
                }
                String plugin = PLUGIN_OF.get(type);
                if (!plugin.isEmpty() && !skip.contains(plugin.toLowerCase(Locale.ROOT))) best = plugin;
            }
            return best;
        });
        return found == null ? Origin.UNKNOWN : Origin.parse(found);
    }

    private static String rule(Map<String, String> rules, String className) {
        if (rules.isEmpty()) return null;
        for (Map.Entry<String, String> r : rules.entrySet()) if (className.startsWith(r.getKey())) return r.getValue();
        return null;
    }
}
