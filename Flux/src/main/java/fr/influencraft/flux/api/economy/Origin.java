package fr.influencraft.flux.api.economy;

import org.bukkit.plugin.Plugin;

import java.util.Locale;

/**
 * Where a money movement comes from: the plugin and its feature ({@code FluxShop:sell}, {@code FluxTools:quest}).
 * Shown in the account history ({@code /compte}) and in the gain / loss messages ({@code {origin}}).
 *
 * <p>The label players read is looked up, in order: Flux's config {@code ledger.origins."<plugin>:<key>"}, the
 * plugin's own lang file {@code origins.<key>} (a plugin of the suite names its features itself), Flux's config
 * {@code ledger.origins.<plugin>}, then the plugin name. Third-party plugins (EcoJobs, Lands, CMI...) are named in
 * Flux's config only.</p>
 *
 * <pre>
 *   Economy.main().give(player, 120, Origin.of(this, "quest").detail(quest.name()).notifyPlayer());
 * </pre>
 *
 * A movement made without an origin is still recorded: Flux finds the plugin that made it (see {@link Ledger}).
 *
 * @param plugin name of the plugin (as in plugin.yml)
 * @param key    feature of the plugin, {@code ""} for the plugin as a whole
 * @param detail what exactly (item sold, quest...): shown in the history, never merged with other movements; may be null
 * @param verb   word of the message ({@code money.verbs.<verb>} in Flux's lang): gained / lost by default, or
 *               bought, sold, received, given, won, bet...; null = by the sign of the movement
 * @param announce true = Flux sends the standard gain / loss message (style.yml {@code money}), false = the plugin
 *                 shows its own feedback
 */
public record Origin(String plugin, String key, String detail, String verb, boolean announce) {

    /** No origin known: the stack of the call decides (see {@link Ledger}). */
    public static final Origin UNKNOWN = new Origin("", "", null, null, false);

    public Origin {
        plugin = plugin == null ? "" : plugin;
        key = key == null ? "" : key.toLowerCase(Locale.ROOT);
    }

    public static Origin of(Plugin plugin, String key) {
        return new Origin(plugin.getName(), key, null, null, false);
    }

    public static Origin of(String plugin, String key) {
        return new Origin(plugin, key, null, null, false);
    }

    /** {@code FluxShop:sell} form (as stored), {@code FluxShop} alone for a plugin-wide origin. */
    public static Origin parse(String id) {
        if (id == null || id.isEmpty()) return UNKNOWN;
        int sep = id.indexOf(':');
        return sep < 0 ? of(id, "") : of(id.substring(0, sep), id.substring(sep + 1));
    }

    public Origin detail(String text) {
        return new Origin(plugin, key, text == null || text.isBlank() ? null : text, verb, announce);
    }

    public Origin verb(String word) {
        return new Origin(plugin, key, detail, word, announce);
    }

    /** Flux sends the standard gain / loss message to the player (style.yml {@code money}). */
    public Origin notifyPlayer() {
        return new Origin(plugin, key, detail, verb, true);
    }

    public boolean unknown() {
        return plugin.isEmpty();
    }

    /** Stored form: {@code plugin:key} or {@code plugin}. */
    public String id() {
        return key.isEmpty() ? plugin : plugin + ":" + key;
    }
}
