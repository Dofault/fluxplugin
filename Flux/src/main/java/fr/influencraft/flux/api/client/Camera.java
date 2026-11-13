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

 *   <li>{@link #clicks}: left and right clicks on an entity (an {@code Interaction} in front of the camera) reach the
 *   handler, on the server thread, however far the player stands from it; the server never handles them.</li>
 * </ul>
 * His client never draws his own body while it looks through another entity (Minecraft 26.2, LevelExtractor): nothing
 * he wears can be seen by him then. Java clients only: Bedrock players have no camera packet ({@link Clients#bedrock}).
 */
public final class Camera {

    public interface Service {
        void view(Player player, Entity camera);

        void turn(Player player, float yaw, boolean relativeYaw, float pitch, boolean relativePitch);


        /** Moves an entity on this player's screen only, at once (the server's own entity is not moved). */
        void place(Player viewer, Entity entity, double x, double y, double z);
    }

    /** The player turned his head (his mouse moved). Called on a network thread, as soon as his client says it. */
    @FunctionalInterface
    public interface Turn {
        void turned(Player player, float yaw, float pitch);
    }

    /** A click on a watched entity: {@code left} = attack key, else use key (repeated while held). */
    @FunctionalInterface
    public interface Click {
        void clicked(Player player, boolean left);
    }

    private static volatile Service service;
    private static final Map<Integer, Click> TARGETS = new ConcurrentHashMap<>();
    private static final Map<java.util.UUID, Turn> TURNS = new ConcurrentHashMap<>();

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

    /** His mouse goes to {@code turn} as soon as it moves, before the server's tick (null stops). */
    public static void turns(Player player, Turn turn) {
        if (turn == null) TURNS.remove(player.getUniqueId());
        else TURNS.put(player.getUniqueId(), turn);
    }

    /** The listener of this player's mouse, null when none (read from the network threads). */
    public static Turn turn(java.util.UUID player) {
        return TURNS.get(player);
    }

    /** Moves {@code entity} on {@code viewer}'s screen at once (a pointer following his mouse); thread-safe. */
    public static void place(Player viewer, Entity entity, double x, double y, double z) {
        Service s = service;
        if (s != null) s.place(viewer, entity, x, y, z);
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
