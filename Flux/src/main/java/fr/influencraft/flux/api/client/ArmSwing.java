package fr.influencraft.flux.api.client;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The arm swing of a player made by the server (an attack thrown with a right click, which the client does not swing
 * for), seen by the player himself and by everyone around. A Java client answers a swing it is shown of itself with a
 * swing packet of its own, which the server would take for a real left click (and an attack): Flux drops that echo
 * before the server reads it (packetevents). Without packetevents, and for Bedrock players (whose echo is not known),
 * nothing is shown: a missing swing is better than a false click.
 */
public final class ArmSwing {

    /** Longest wait for the echo (ping included): past it, the next swing of the player is his own again. */
    private static final long ECHO_MS = 1500;

    private record Pending(int count, long until) {
    }

    private static final Map<UUID, Pending> ECHOES = new ConcurrentHashMap<>();
    private static volatile boolean filtered;

    private ArmSwing() {
    }

    /** Swings the main hand of {@code player} (server thread). False when it could not be shown safely. */
    public static boolean show(Player player) {
        if (!filtered || Clients.bedrock(player)) return false;
        long now = System.currentTimeMillis();
        ECHOES.compute(player.getUniqueId(), (k, p) ->
                new Pending(p == null || p.until() < now ? 1 : p.count() + 1, now + ECHO_MS));
        player.swingMainHand();
        return true;
    }

    /** Flux's packet filter is running (set by the packetevents adapter). */
    public static void filtered(boolean on) {
        filtered = on;
        if (!on) ECHOES.clear();
    }

    /** True when this swing packet of {@code player} is the echo of a swing shown by the server (network thread). */
    public static boolean echo(UUID player) {
        long now = System.currentTimeMillis();
        boolean[] hit = {false};
        ECHOES.computeIfPresent(player, (k, p) -> {
            if (p.until() < now) return null;
            hit[0] = true;
            return p.count() <= 1 ? null : new Pending(p.count() - 1, p.until());
        });
        return hit[0];
    }

    public static void forget(UUID player) {
        ECHOES.remove(player);
    }
}
