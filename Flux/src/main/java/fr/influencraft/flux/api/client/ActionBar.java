package fr.influencraft.flux.api.client;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who writes a player's action bar when two plugins would: a plugin showing something that needs it for a while (the
 * wardrobe's controls) claims it, and a plugin that refreshes it all the time (the rpg life and mana bars) leaves it
 * alone meanwhile ({@link #free}). One line, one owner: no flicker between two texts sent in turn.
 *
 * <pre>
 *   ActionBar.claim(this, player);          // the wardrobe opens
 *   if (ActionBar.free(this, player)) ...   // before each refresh of a lasting bar
 *   ActionBar.release(this, player);        // the wardrobe closes
 * </pre>
 * Short one-off messages (a spell refused...) are not concerned. FluxCosmetics claims it; FluxDungeons' rpg bars give way.
 */
public final class ActionBar {

    private static final Map<UUID, Plugin> CLAIMS = new ConcurrentHashMap<>();

    private ActionBar() {
    }

    /** {@code plugin} writes his action bar until {@link #release}. */
    public static void claim(Plugin plugin, Player player) {
        CLAIMS.put(player.getUniqueId(), plugin);
    }

    /** Gives his action bar back (only the plugin that holds it). */
    public static void release(Plugin plugin, Player player) {
        CLAIMS.remove(player.getUniqueId(), plugin);
    }

    /** Whether {@code plugin} may write his action bar now: nobody else claims it. */
    public static boolean free(Plugin plugin, Player player) {
        Plugin owner = CLAIMS.get(player.getUniqueId());
        return owner == null || owner.equals(plugin);
    }

    /** A plugin stops: its claims go. */
    public static void unregisterAll(Plugin plugin) {
        CLAIMS.values().removeIf(p -> p.equals(plugin));
    }
}
