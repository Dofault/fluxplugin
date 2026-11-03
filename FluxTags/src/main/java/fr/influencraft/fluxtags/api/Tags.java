package fr.influencraft.fluxtags.api;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Tags given by other plugins of the suite (FluxFamily: relationship status, divorced). A provided tag shows in
 * {@code /tags} after the special tags; its image (a glyph of the {@code fluxtags} pack, see
 * {@code assets/gen_tags.py}) is chosen per player, null while locked.
 *
 * <pre>
 *   Tags.register(this, "situation", "Situation", "Ta situation amoureuse.", p -> "celibataire");
 * </pre>
 */
public final class Tags {

    /** A tag of another plugin: shown name, description, and the image of a player (null = locked). */
    public record Provided(Plugin owner, String id, String name, String description, Function<Player, String> image) {
    }

    /** Implemented by FluxTags. */
    public interface Service {
        String selected(UUID player);

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

    public static Map<String, Provided> provided() {
        return Map.copyOf(PROVIDED);
    }

    public static void bind(Service s) {
        service = s;
    }

    public static boolean available() {
        return service != null;
    }

    /** Id of the tag the player picked ({@code rang} by default, {@code aucun} for none). */
    public static String selected(UUID player) {
        return service == null ? null : service.selected(player);
    }

    /** Makes the player wear a tag (no unlock check: the caller just unlocked it). */
    public static void select(UUID player, String tag) {
        if (service != null) service.select(player, tag);
    }
}
