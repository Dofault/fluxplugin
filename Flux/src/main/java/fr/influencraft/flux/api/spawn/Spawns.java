package fr.influencraft.flux.api.spawn;

import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Spawn groups (2026-11-19): a player may belong to one group of {@code plugins/Flux/spawns.yml}, whose place replaces
 * the server's spawn for him ({@code /spawn}, respawn after a death, first join). Players in no group go to the
 * server's spawn as before (CMI). New players are put in the {@code first-join} group (the tutorial spawn).
 *
 * <p>The group is also what decides who sees content made for that moment of the game: the band of a FluxMusic stage,
 * the stands of FluxMascot. Quests read it with the placeholder {@code %flux_spawn%} (group id, {@code none}) and
 * change it with {@code /fluxspawn set <player> <group|none>}.</p>
 *
 * <pre>
 *   if (Spawns.in(player, stageGroup)) show(player);
 * </pre>
 */
public final class Spawns {

    /** No group: the server's own spawn. */
    public static final String NONE = "";

    /** Implemented by Flux. */
    public interface Backend {
        /** Group of an online player, {@link #NONE} when none (or not read yet). */
        String group(Player player);

        /** Puts the player in a group ({@link #NONE}: out of every group), saved at once. */
        void set(OfflinePlayer player, String group);

        /** Place of a group, null when the group is unknown or has no place. */
        Location location(String group);
    }

    private static volatile Backend backend;

    private Spawns() {
    }

    public static void bind(Backend flux) {
        backend = flux;
    }

    public static boolean available() {
        return backend != null;
    }

    /** Group of the player, {@link #NONE} when none or without Flux. */
    public static String group(Player player) {
        Backend b = backend;
        return b == null || player == null ? NONE : b.group(player);
    }

    /**
     * True when the player is in this group. An empty or null group means everybody (content shown to all), so a
     * plugin can call it with its optional setting as it is.
     */
    public static boolean in(Player player, String group) {
        if (group == null || group.isEmpty()) return true;
        return group.equalsIgnoreCase(group(player));
    }

    public static void set(OfflinePlayer player, String group) {
        Backend b = backend;
        if (b != null) b.set(player, group == null ? NONE : group);
    }

    public static Location location(String group) {
        Backend b = backend;
        return b == null || group == null || group.isEmpty() ? null : b.location(group);
    }
}
