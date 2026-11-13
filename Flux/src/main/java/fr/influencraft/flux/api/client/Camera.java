package fr.influencraft.flux.api.client;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player's view seen through another entity, his mouse used as a pointer (FluxTopDown's camera from above).
 *
 * <ul>
 *   <li>{@link #view}: the client renders the world from that entity's eyes (vanilla camera packet), the player keeps
 *   his own body, position and keys. Null puts the camera back on himself.</li>
 *   <li>The mouse still turns the player's own head, which nobody sees: read its rotation as a pointer and bring it
 *   back with {@link #turn} (relative or absolute, no teleport).</li>
 *   <li>{@link #hideSelf}: he does not see his own body (the server and the others still do).</li>
 *   <li>{@link #clicks}: left and right clicks on an entity (an {@code Interaction} in front of the camera) reach the
 *   handler, on the server thread, however far the player stands from it; the server never handles them.</li>
 * </ul>
 * Java clients only: Bedrock players have no camera packet (check {@link Clients#bedrock} first).
 */
public final class Camera {

    public interface Service {
        void view(Player player, Entity camera);

        void turn(Player player, float yaw, boolean relativeYaw, float pitch, boolean relativePitch);

        void hideSelf(Player player, boolean hidden);
    }

    /** A click on a watched entity: {@code left} = attack key, else use key (repeated while held). */
    @FunctionalInterface
    public interface Click {
        void clicked(Player player, boolean left);
    }

    private static volatile Service service;
    private static final Map<Integer, Click> TARGETS = new ConcurrentHashMap<>();

    private Camera() {
    }

    public static void bind(Service s) {
        service = s;
    }

    public static boolean available() {
        return service != null;
    }

    /** The player sees through {@code camera} (null: through his own eyes again). */
    public static void view(Player player, Entity camera) {
        Service s = service;
        if (s != null) s.view(player, camera);
    }

    /** Turns the player's head on his side (his pointer). */
    public static void turn(Player player, float yaw, boolean relativeYaw, float pitch, boolean relativePitch) {
        Service s = service;
        if (s != null) s.turn(player, yaw, relativeYaw, pitch, relativePitch);
    }

    public static void hideSelf(Player player, boolean hidden) {
        Service s = service;
        if (s != null) s.hideSelf(player, hidden);
    }

    /** Clicks on this entity go to {@code click} (null stops). */
    public static void clicks(Entity target, Click click) {
        if (click == null) TARGETS.remove(target.getEntityId());
        else TARGETS.put(target.getEntityId(), click);
    }

    /** The handler of clicks on this entity id, null when nobody watches it (read from the network threads). */
    public static Click target(int entityId) {
        return TARGETS.get(entityId);
    }
}
