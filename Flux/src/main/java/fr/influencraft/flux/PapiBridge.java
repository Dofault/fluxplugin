package fr.influencraft.flux;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.PlaceholderProvider;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Exposes the placeholders registered with {@link FluxApi#registerPlaceholders} to PlaceholderAPI (chat, TAB,
 * scoreboards...), one expansion per prefix. A prefix already provided by another expansion is left alone.
 * Only loaded when PlaceholderAPI is installed.
 */
final class PapiBridge {

    private final Flux plugin;
    private final Map<String, Expansion> registered = new HashMap<>();

    PapiBridge(Flux plugin) {
        this.plugin = plugin;
    }

    /** Registers the new prefixes and drops the expansions whose prefix is gone. */
    void sync() {
        Set<String> wanted = new HashSet<>(FluxApi.placeholderPrefixes());
        registered.entrySet().removeIf(e -> {
            if (wanted.contains(e.getKey())) return false;
            e.getValue().unregister();
            return true;
        });
        var manager = me.clip.placeholderapi.PlaceholderAPIPlugin.getInstance().getLocalExpansionManager();
        for (String prefix : wanted) {
            if (registered.containsKey(prefix)) continue;
            if (manager.getExpansion(prefix) != null) continue; // the plugin registered its own expansion
            Expansion expansion = new Expansion(prefix);
            if (expansion.register()) registered.put(prefix, expansion);
        }
    }

    void shutdown() {
        registered.values().forEach(PlaceholderExpansion::unregister);
        registered.clear();
    }

    private final class Expansion extends PlaceholderExpansion {
        private final String prefix;

        Expansion(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String getIdentifier() {
            return prefix;
        }

        @Override
        public String getAuthor() {
            return "Flux";
        }

        @Override
        public String getVersion() {
            return plugin.getPluginMeta().getVersion();
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public String onPlaceholderRequest(Player player, String params) {
            PlaceholderProvider provider = FluxApi.placeholders(prefix);
            return provider == null ? null : provider.request(player, params);
        }
    }
}
