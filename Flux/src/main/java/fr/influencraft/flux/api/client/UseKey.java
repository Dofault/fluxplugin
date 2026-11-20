package fr.influencraft.flux.api.client;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The use key of a player (right click) as an action key: while a plugin claims it, every right click runs the
 * plugin's action and reaches nothing else, whatever it points at (the air, a block, an entity at any distance).
 * The server alone cannot tell a right click in the air with an empty hand (no packet then, no Bukkit event), nor
 * one at an entity out of reach: packetevents reads the client's packets instead. The client still sends nothing for
 * a click in the air with nothing in its hand: give him an item to hold on his screen ({@link Hotbar}).
 * One click is one action, even when the client sends several packets for it. Java only.
 * Used by FluxCosmetics: the wardrobe's right click opens the cosmetics menu, the camera near or far.
 */
public final class UseKey {

    private record Claim(Plugin plugin, Runnable action) {
    }

    private static final Map<UUID, Claim> CLAIMS = new ConcurrentHashMap<>();

    private UseKey() {
    }

    /** {@code action} runs on the server thread each time {@code player} right clicks, until {@link #release}. */
    public static void claim(Plugin plugin, Player player, Runnable action) {
        CLAIMS.put(player.getUniqueId(), new Claim(plugin, action));
    }

    public static void release(Player player) {
        CLAIMS.remove(player.getUniqueId());
    }

    /** A plugin stops: its claims go. */
    public static void unregisterAll(Plugin plugin) {
        CLAIMS.values().removeIf(c -> c.plugin().equals(plugin));
    }

    /** The action claimed for that player's use key, null when it works as usual (network thread). */
    public static Runnable claimed(UUID player) {
        Claim c = CLAIMS.get(player);
        return c == null ? null : c.action();
    }
}
