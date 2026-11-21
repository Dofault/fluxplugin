package fr.influencraft.flux.api.cosmetics;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Player tags: the picture shown before a player's name in the chat, the tab list and over his head, chosen in the
 * cosmetics menu. Kept by the cosmetics plugin of the suite (FluxCosmetics, the {@link Service}); other plugins add
 * tags of their own here (FluxFamily: relationship status, divorced), listed after the server's ones. A tag's image
 * is a glyph id of the {@code fluxtags} pack ({@code FluxCosmetics/assets/tags/gen_tags.py}), chosen per player,
 * null while the tag is locked.
 *
 * <pre>
 *   Tags.register(this, "situation", "Situation", "Ta situation amoureuse.", p -> "celibataire");
 *   Tags.unregisterAll(this);   // in disable()
 * </pre>
 * Registering needs no provider: the tags show once the cosmetics plugin runs.
 */
public final class Tags {

    /** A tag of another plugin: shown name, description, and the image of a player (null = locked). */
    public record Provided(Plugin owner, String id, String name, String description, Function<Player, String> image) {
    }

    /** What the cosmetics plugin provides. */
    public interface Service {
        /** Id of the tag the player wears ({@code rang} by default, {@code aucun} for none). */
        String selected(UUID player);

        /** Makes the player wear a tag, no unlock check, his name redrawn when he is online. */
        void select(UUID player, String tag);
    }

    private static final Map<String, Provided> PROVIDED = new ConcurrentHashMap<>();
    private static volatile Service service;

    private Tags() {
    }

    public static void register(Plugin owner, String id, String name, String description, Function<Player, String> image) {
        PROVIDED.put(id, new Provided(owner, id, name, description, image));
    }

    public static void unregisterAll(Plugin owner) {
        PROVIDED.values().removeIf(t -> t.owner() == owner);
    }

    /** Tags of the other plugins, by id. */
    public static Map<String, Provided> provided() {
        return Map.copyOf(PROVIDED);
    }

    /** Set by the cosmetics plugin (null when it stops). */
    public static void provide(Service s) {
        service = s;
    }

    public static boolean available() {
        return service != null;
    }

    /** Id of the tag the player wears, null without the cosmetics plugin. */
    public static String selected(UUID player) {
        Service s = service;
        return s == null ? null : s.selected(player);
    }

    /** Makes the player wear a tag (no unlock check: the caller just unlocked it). Nothing without the provider. */
    public static void select(UUID player, String tag) {
        Service s = service;
        if (s != null) s.select(player, tag);
    }
}
