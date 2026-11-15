package fr.influencraft.flux.api.client;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The drop key of a player (Q on a QWERTY keyboard, A on an AZERTY one) used as an action key: while a plugin claims
 * it, pressing it runs the plugin's action instead of dropping the held item (packetevents holds the packet back,
 * Flux gives the client its inventory again, since it has already taken the item out on its side). Works with an
 * empty hand too, which no Bukkit event tells. Java only: a Bedrock player has no such key (give him another way).
 * Used by FluxPets: the attack of a ridden boss pet.
 */
public final class DropKey {

    private record Claim(Plugin plugin, Runnable action) {
    }

    private static final Map<UUID, Claim> CLAIMS = new ConcurrentHashMap<>();

    private DropKey() {
    }

    /** {@code action} runs on the server thread each time {@code player} presses his drop key, until {@link #release}. */
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

    /** The action claimed for that player's drop key, null when it drops as usual (network thread). */
    public static Runnable claimed(UUID player) {
        Claim c = CLAIMS.get(player);
        return c == null ? null : c.action();
    }
}
