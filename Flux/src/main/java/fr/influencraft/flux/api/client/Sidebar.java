package fr.influencraft.flux.api.client;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player's sidebar (the scoreboard on the right of his screen) taken off while something else needs the screen
 * (FluxDungeons' class showcase), whichever plugin draws it (Typewriter today): Flux holds back the packets that show
 * it and puts back the last one when it is given back. Nothing is asked of the plugin that draws it, and its
 * scoreboard keeps being updated meanwhile. Thread-safe (read from the network threads).
 */
public final class Sidebar {

    /** What the packet adapter provides. */
    public interface Service {
        /** Shows this objective in the player's sidebar ({@code ""}: none), without going through the filter. */
        void show(Player player, String objective);
    }

    private static volatile Service service;
    private static final Set<UUID> HIDDEN = ConcurrentHashMap.newKeySet();
    /** The objective each player's sidebar was last told to show ({@code ""}: none). */
    private static final Map<UUID, String> LAST = new ConcurrentHashMap<>();
    /** Flux's own scoreboard holds the sidebar of these players: the objective it shows. */
    private static final Map<UUID, String> CLAIMED = new ConcurrentHashMap<>();
    /** What other plugins asked to show meanwhile, shown again when the claim ends. */
    private static final Map<UUID, String> BLOCKED = new ConcurrentHashMap<>();

    private Sidebar() {
    }

    public static void bind(Service s) {
        service = s;
    }

    /** Takes the sidebar off this player's screen ({@code true}) or gives it back as it now is. */
    public static void hide(Player player, boolean hidden) {
        Service s = service;
        if (s == null) return;
        UUID id = player.getUniqueId();
        if (hidden) {
            // only a sidebar Flux saw being shown can be put back: an unknown one is left alone
            String last = LAST.get(id);
            if (last != null && !last.isEmpty() && HIDDEN.add(id)) s.show(player, "");
        } else if (HIDDEN.remove(id) && player.isOnline()) {
            String last = LAST.get(id);
            if (last != null && !last.isEmpty()) s.show(player, last);
        }
    }

    public static boolean hidden(UUID player) {
        return HIDDEN.contains(player);
    }

    /** Called by the adapter: the sidebar of this player was told to show this objective. */
    public static void told(UUID player, String objective) {
        LAST.put(player, objective == null ? "" : objective);
    }

    /**
     * Flux's scoreboard takes this player's sidebar: only this objective may show there, what other plugins (Typewriter)
     * ask meanwhile is held back and shown again on {@link #release}.
     */
    public static void claim(Player player, String objective) {
        CLAIMED.put(player.getUniqueId(), objective);
    }

    /** The sidebar goes back to other plugins: the last one they asked for is shown again. */
    public static void release(UUID player) {
        if (CLAIMED.remove(player) == null) return;
        String blocked = BLOCKED.remove(player);
        Service s = service;
        Player online = org.bukkit.Bukkit.getPlayer(player);
        if (blocked == null || s == null || online == null) return;
        LAST.put(player, blocked);
        if (!HIDDEN.contains(player) && !blocked.isEmpty()) s.show(online, blocked);
    }

    /**
     * Called by the adapter for each sidebar display asked to a player: false when it must be held back because Flux's
     * scoreboard holds the sidebar and it is another objective (it is kept for {@link #release}).
     */
    public static boolean allowed(UUID player, String objective) {
        String claimed = CLAIMED.get(player);
        String name = objective == null ? "" : objective;
        if (claimed == null || claimed.equals(name)) return true;
        BLOCKED.put(player, name);
        return false;
    }

    /** Called by Flux when a player leaves. */
    public static void forget(UUID player) {
        HIDDEN.remove(player);
        LAST.remove(player);
        CLAIMED.remove(player);
        BLOCKED.remove(player);
    }
}
