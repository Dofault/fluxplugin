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

        /**
         * Shows {@code item} in a slot of his own inventory window on his side only (0 crafting result, 5 to 8 helmet to
         * boots, 9 to 35 storage, 36 to 44 hotbar, 45 off hand); the server's inventory is untouched.
         */
        void ownSlot(Player player, int slot, org.bukkit.inventory.ItemStack item);

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

    /**
     * Head pointer (pack: assets/minecraft/shaders/core/item.vsh, item flux:pointer): screen units per degree of head
     * turn and the area the pointer stays in. The shader holds the same numbers.
     */
    public static final double POINTER_SPEED = 0.025, POINTER_WIDTH = 1.15, POINTER_HEIGHT = 0.62;

    /**
     * The item that, worn by a player who looks through another entity (sent to him only, on his head), is drawn by
     * his client as a mouse pointer following his head every frame: no wait for the server.
     */
    public static org.bukkit.inventory.ItemStack pointerItem() {
        org.bukkit.inventory.ItemStack item = org.bukkit.inventory.ItemStack.of(org.bukkit.Material.PAPER);
        item.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key("flux", "pointer"));
        return item;
    }

    /**
     * Where the head pointer is for this head turn, as the shader draws it before it stops at the edges:
     * {x right, y up} in screen units. {@code cameraYaw}: the yaw the camera looks to.
     */
    public static double[] pointer(float yaw, float pitch, double cameraYaw) {
        double turn = (yaw - cameraYaw) % 360;
        if (turn >= 180) turn -= 360;
        if (turn < -180) turn += 360;
        return new double[]{turn * POINTER_SPEED, -pitch * POINTER_SPEED};
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

    public static void hideSelf(Player player, boolean hidden) {
        Service s = service;
        if (s != null) s.hideSelf(player, hidden);
    }

    /** Window slots of the worn items, for {@link #ownSlot}. */
    public static final int SLOT_HEAD = 5, SLOT_CHEST = 6, SLOT_LEGS = 7, SLOT_FEET = 8, SLOT_OFF_HAND = 45;

    /** {@code item} in a slot of his inventory window, seen by him only (his client draws what he wears from it). */
    public static void ownSlot(Player player, int slot, org.bukkit.inventory.ItemStack item) {
        Service s = service;
        if (s != null) s.ownSlot(player, slot, item);
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
