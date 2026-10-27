package fr.influencraft.flux.api.client;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;

/**
 * Registry tags as one player's client sees them (e.g. let one player walk on powder snow): the server keeps the real
 * tags, only this client is told otherwise. Implemented by Flux's packet adapter (packetevents).
 */
public final class ClientTags {

    /** What an adapter provides. */
    public interface Provider {
        /** Sends every entity type tag of the server to the player, with {@code extra} types added to some tags. */
        void sendEntityTypeTags(Player player, Map<String, Set<EntityType>> extra);
    }

    private static volatile Provider provider;

    private ClientTags() {
    }

    /** Called by Flux. */
    public static void use(Provider p) {
        provider = p;
    }

    public static boolean available() {
        return provider != null;
    }

    /** False when no packet adapter is available. */
    public static boolean sendEntityTypeTags(Player player, Map<String, Set<EntityType>> extra) {
        Provider p = provider;
        if (p == null) return false;
        p.sendEntityTypeTags(player, extra);
        return true;
    }

    /** The real tags again. */
    public static boolean reset(Player player) {
        return sendEntityTypeTags(player, Map.of());
    }
}
