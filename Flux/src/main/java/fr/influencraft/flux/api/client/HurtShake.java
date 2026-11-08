package fr.influencraft.flux.api.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How often a player's own screen may shake when he is hurt. Vanilla shakes the camera at every hit taken; a plugin
 * where players take many small hits quickly (FluxDungeons: no invulnerability) sets a minimum gap here, and Flux
 * drops the hurt packets about the player himself sent sooner (his hurt sound is still played at every hit). Other
 * players still see him flash red at every hit. Thread-safe (read from the network threads).
 */
public final class HurtShake {

    private static final Map<UUID, Long> GAP = new ConcurrentHashMap<>();

    private HurtShake() {
    }

    /** At most one shake of this player's screen every {@code millis} (0 or less: vanilla, every hit). */
    public static void limit(UUID player, long millis) {
        if (millis <= 0) GAP.remove(player);
        else GAP.put(player, millis);
    }

    public static void clear(UUID player) {
        GAP.remove(player);
    }

    /** Minimum gap between two shakes of this player's screen, 0 when vanilla. */
    public static long gap(UUID player) {
        Long g = GAP.get(player);
        return g == null ? 0 : g;
    }
}
