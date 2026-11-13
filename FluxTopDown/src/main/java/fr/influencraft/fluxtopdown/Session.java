package fr.influencraft.fluxtopdown;

import fr.influencraft.flux.api.client.Camera;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One player in the view from above. Four entities, all non persistent:
 * <ul>
 *   <li>{@code camera}: an invisible mannequin hung over the hero, the player's view ({@link Camera#view});</li>
 *   <li>{@code cursor}: a text display standing on the pointed spot, seen through blocks;</li>
 *   <li>{@code target}: an interaction box around the camera's eyes, what every click of the player lands on;</li>
 *   <li>{@code avatar}: a mannequin with the player's skin standing where he stands, facing where he walks or casts:
 *   the player's own body is hidden (to himself and to the others) since his head follows the mouse.</li>
 * </ul>
 * The player sits on an invisible seat (as MC GUI does): a client whose camera is another entity sends its position
 * and rotation only while riding. The server walks him by moving the seat along a path ({@link Paths}), with its own
 * gravity and steps; his keys move nothing (sneaking does not get him off).
 */
final class Session {

    private static final NamespacedKey MODIFIER = new NamespacedKey("fluxtopdown", "view");
    private static final double EYE = 1.62;

    final Player player;
    private final FluxTopDown plugin;
    final Mannequin camera;
    final Mannequin avatar;
    final TextDisplay cursor;
    final Interaction target;
    /** What he sits on: a seated player's client keeps sending its rotation (the mouse) while the camera is elsewhere. */
    final org.bukkit.entity.ArmorStand seat;
    /** His feet height minus the seat's, measured once he sits (NaN before). */
    private double seatOffset = Double.NaN;
    boolean closing;
    /** Velocity of a leap in progress (null when none), moved by the server. */
    Vector leapVel;
    final BossBar bar;
    final int previousSlot;

    // pointer, in screen units from the centre (x right, y up)
    volatile double sx, sy;
    private float lastYaw, lastPitch;
    private volatile boolean primed, pitchReset;
    private double distance;

    // walking
    List<Vector> path = List.of();
    int pathIndex;
    LivingEntity chase;
    long nextRepath;
    double vy;
    private Vector lastPos;

    // fighting
    Settings.Spell aiming;
    final Map<Integer, Long> readyAt = new HashMap<>();
    long busyUntil, leapStart, leapUntil, nextBasic, faceUntil;
    float facing;
    Runnable onLand;

    Session(FluxTopDown plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.previousSlot = player.getInventory().getHeldItemSlot();
        Settings s = plugin.settings();
        World w = player.getWorld();
        Location at = player.getLocation();
        facing = at.getYaw();
        distance = s.cameraDistance();
        Location eye = eye(s, at, distance);
        camera = w.spawn(eye.clone().subtract(0, EYE, 0), Mannequin.class, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, m -> {
            m.setPersistent(false);
            m.setVisibleByDefault(false);
            m.setInvisible(true);
            m.setInvulnerable(true);
            m.setSilent(true);
            m.setGravity(false);
            m.setCollidable(false);
            m.setImmovable(true);
            m.setDescription(null);
            m.setRotation((float) s.cameraYaw(), (float) s.cameraPitch());
            // F5 must not move the view: no third person distance on the camera
            Attribute distanceAttr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("camera_distance"));
            AttributeInstance a = distanceAttr == null ? null : m.getAttribute(distanceAttr);
            if (a != null) a.addTransientModifier(new AttributeModifier(MODIFIER, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
        });
        cursor = w.spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.text(MiniMessage.miniMessage().deserialize(s.cursorText()));
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(0));
            d.setShadowed(false);
            d.setSeeThrough(true);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(1);
        });
        target = w.spawn(eye.clone().subtract(0, 1.5, 0), Interaction.class, i -> {
            i.setPersistent(false);
            i.setVisibleByDefault(false);
            i.setInteractionWidth(3f);
            i.setInteractionHeight(3f);
            i.setResponsive(false);
        });
        seat = w.spawn(at, org.bukkit.entity.ArmorStand.class, a -> {
            a.setPersistent(false);
            a.setVisibleByDefault(false);
            a.setMarker(true);
            a.setInvisible(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
        });
        avatar = w.spawn(at, Mannequin.class, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, m -> {
            m.setPersistent(false);
            m.setInvulnerable(true);
            m.setCollidable(false);
            m.setImmovable(true);
            m.setGravity(false);
            m.setSilent(true);
            m.setDescription(null);
            m.setProfile(ResolvableProfile.resolvableProfile(player.getPlayerProfile()));
            m.customName(player.name());
            m.setCustomNameVisible(true);
        });
        dressAvatar();
        for (Entity e : List.of(camera, target, seat)) player.showEntity(plugin, e);
        // the server's pointer mark, when the client does not draw its own
        if (!s.clientPointer()) player.showEntity(plugin, cursor);
        bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
    }

    void open() {
        Settings s = plugin.settings();
        player.getInventory().setHeldItemSlot(s.restSlot());
        seat.addPassenger(player);
        for (Player other : plugin.getServer().getOnlinePlayers()) if (other != player) other.hidePlayer(plugin, player);
        Camera.hideSelf(player, true);
        Camera.view(player, camera);
        // the camera reaches his client with the next entity update: say it again once it is there
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && camera.isValid()) Camera.view(player, camera);
        }, 3L);
        // right clicks only: left clicks are read from the arm swing (Sessions#onSwing), sent even when the box is missed
        // the pointer starts in the middle of the screen
        if (s.clientPointer()) Camera.turn(player, (float) s.cameraYaw(), false, 0f, false);
        Camera.turns(player, (p, yaw, pitch) -> turned(yaw, pitch));
        Camera.clicks(target, (p, left) -> {
            if (!left) plugin.sessions().clicked(this, false);
        });
        hideOwnGear();
        player.showBossBar(bar);
    }

    void close() {
        closing = true;
        Camera.clicks(target, null);
        Camera.turns(player, null);
        if (player.getVehicle() == seat) player.leaveVehicle();
        if (player.isOnline()) {
            Camera.view(player, null);
            Camera.hideSelf(player, false);
            for (Player other : plugin.getServer().getOnlinePlayers()) if (other != player) other.showPlayer(plugin, player);
            player.hideBossBar(bar);
            player.getInventory().setHeldItemSlot(previousSlot);
            player.updateInventory();
        }
        for (Entity e : List.of(cursor, target, camera, avatar, seat)) if (e.isValid()) e.remove();
    }

    /** His invisible body still shows its armour and held items to himself: send him empty ones (look only). */
    void hideOwnGear() {
        Map<EquipmentSlot, ItemStack> empty = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) empty.put(slot, ItemStack.empty());
        // on his head, the item his client draws as the pointer
        if (plugin.settings().clientPointer()) empty.put(EquipmentSlot.HEAD, Camera.pointerItem());
        try {
            player.sendEquipmentChange(player, empty);
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Own gear not hidden: " + e.getMessage());
        }
    }

    /** The avatar wears the player's armour and the configured weapon. */
    void dressAvatar() {
        var from = player.getEquipment();
        var to = avatar.getEquipment();
        to.setHelmet(from.getHelmet());
        to.setChestplate(from.getChestplate());
        to.setLeggings(from.getLeggings());
        to.setBoots(from.getBoots());
        to.setItemInMainHand(new ItemStack(plugin.settings().weapon()));
    }

    // ---------------------------------------------------------------- camera and pointer

    /** Forward, right and up unit vectors of the camera. */
    static Vector[] axes(Settings s) {
        double yaw = Math.toRadians(s.cameraYaw()), pitch = Math.toRadians(s.cameraPitch());
        Vector f = new Vector(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vector r = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vector u = r.clone().crossProduct(f);
        return new Vector[]{f, r, u};
    }

    private static Location eye(Settings s, Location feet, double distance) {
        Vector f = axes(s)[0];
        Location focus = feet.clone().add(0, 1, 0);
        Location eye = focus.subtract(f.clone().multiply(distance));
        eye.setYaw((float) s.cameraYaw());
        eye.setPitch((float) s.cameraPitch());
        return eye;
    }

    /**
     * The mouse turned his (unseen) head: the pointer moves, stopped at the screen's edges, and is shown to him at once
     * (network thread, before the server's tick). The spot is first found on the floor level of the last pointed spot;
     * the tick then puts the pointer on the real spot (blocks), usually the same.
     */
    synchronized void turned(float yaw, float pitch) {
        Settings s = plugin.settings();
        if (s.clientPointer()) {
            pointerTurned(s, yaw, pitch);
            return;
        }
        if (!primed) {
            lastYaw = yaw;
            lastPitch = pitch;
            primed = true;
            return;
        }
        float dyaw = wrap(yaw - lastYaw);
        float dpitch = pitch - lastPitch;
        if (pitchReset) {
            // the client took the pitch we sent back: count from there
            if (Math.abs(pitch) < 25) {
                dpitch = pitch;
                pitchReset = false;
            } else dpitch = 0;
        }
        lastYaw = yaw;
        lastPitch = pitch;
        sx = clamp(sx + dyaw * s.cursorSpeed(), s.cursorWidth());
        sy = clamp(sy - dpitch * s.cursorSpeed(), s.cursorHeight());
        if (!pitchReset && Math.abs(pitch) > 55) {
            pitchReset = true;
            Camera.turn(player, 0f, true, 0f, false);
        }
        double[] e = eyeAt;
        if (e == null) return;
        Vector[] ax = axes(s);
        Vector dir = ax[0].clone().add(ax[1].clone().multiply(sx)).add(ax[2].clone().multiply(sy)).normalize();
        if (dir.getY() > -1e-3) return;
        double t = (spotY - e[1]) / dir.getY();
        Camera.place(player, cursor, e[0] + dir.getX() * t, spotY + 0.05, e[2] + dir.getZ() * t);
    }

    /** Packets of head turn left before a correction sent at the screen's edge is taken for lost. */
    private int correcting;

    /**
     * Client pointer: his client draws the pointer from his head's turn (Camera#pointerItem); the server reads the
     * same turn the same way, and turns his head back when it goes past the screen's edges.
     */
    private void pointerTurned(Settings s, float yaw, float pitch) {
        double[] raw = Camera.pointer(yaw, pitch, s.cameraYaw());
        double x = clamp(raw[0], Camera.POINTER_WIDTH), y = clamp(raw[1], Camera.POINTER_HEIGHT);
        sx = x;
        sy = y;
        boolean out = Math.abs(x - raw[0]) > 0.01 || Math.abs(y - raw[1]) > 0.01;
        if (correcting > 0) {
            correcting = out ? correcting - 1 : 0;
            return;
        }
        if (out) {
            correcting = 20;
            float backYaw = (float) ((x - raw[0]) / Camera.POINTER_SPEED);
            float backPitch = (float) (-(y - raw[1]) / Camera.POINTER_SPEED);
            Camera.turn(player, backYaw, true, backPitch, true);
        }
    }

    /** Eyes of the camera at the last tick (x, y, z) and the height of the last pointed spot: for {@link #turned}. */
    private volatile double[] eyeAt;
    private volatile double spotY;

    /** Camera, click box and pointer follow the hero. */
    void place() {
        Settings s = plugin.settings();
        Location feet = player.getLocation();
        Vector[] ax = axes(s);
        Vector f = ax[0];
        // never through a roof: closer under it (at once), back out slowly
        Location focus = feet.clone().add(0, 1, 0);
        double want = s.cameraDistance();
        RayTraceResult hit = feet.getWorld().rayTraceBlocks(focus, f.clone().multiply(-1), want, FluidCollisionMode.NEVER, true, Session::seen);
        if (hit != null) want = Math.max(s.cameraMinDistance(), hit.getHitPosition().distance(focus.toVector()) - 0.6);
        distance = want < distance ? want : Math.min(want, distance + 0.35);
        Location eye = eye(s, feet, distance);
        Location camAt = eye.clone().subtract(0, EYE, 0);
        camera.teleport(camAt);
        camera.setRotation((float) s.cameraYaw(), (float) s.cameraPitch());
        target.teleport(eye.clone().subtract(0, 1.5, 0));
        // the pointer: a mark standing on the pointed spot, drawn over the blocks, its centre on the spot
        eyeAt = new double[]{eye.getX(), eye.getY(), eye.getZ()};
        Location spot = pointed();
        spotY = spot.getY();
        cursor.teleport(spot.add(0, 0.05, 0));
        double px = 0.025 * s.cursorScale();
        Vector3f t = new Vector3f((float) (-s.hotspotX() * px), (float) (-s.hotspotY() * px), 0);
        Transformation now = cursor.getTransformation();
        if (now.getTranslation().distance(t) > 1e-4 || now.getScale().x() != s.cursorScale()) {
            cursor.setTransformation(new Transformation(t, new Quaternionf(), new Vector3f(s.cursorScale()), new Quaternionf()));
        }
    }

    /** Where the pointer's ray meets the world (blocks you can walk through ignored), else the hero's floor level. */
    Location pointed() {
        Settings s = plugin.settings();
        Vector[] ax = axes(s);
        Location feet = player.getLocation();
        Location eye = eye(s, feet, distance);
        Vector dir = ax[0].clone().add(ax[1].clone().multiply(sx)).add(ax[2].clone().multiply(sy)).normalize();
        RayTraceResult hit = feet.getWorld().rayTraceBlocks(eye, dir, 80, FluidCollisionMode.NEVER, true, Session::seen);
        if (hit != null) {
            // past the camera's roof shortening the ray may start inside a block: take the first face seen from above
            Vector p = hit.getHitPosition();
            return new Location(feet.getWorld(), p.getX(), p.getY(), p.getZ());
        }
        if (Math.abs(dir.getY()) < 1e-3) return feet.clone();
        double t = (feet.getY() - eye.getY()) / dir.getY();
        Vector p = eye.toVector().add(dir.multiply(Math.max(0, t)));
        return new Location(feet.getWorld(), p.getX(), feet.getY(), p.getZ());
    }

    /** The avatar stands where the player stands, turned to where he goes or acts. */
    void moveAvatar(long tick) {
        Location at = player.getLocation();
        if (tick >= faceUntil && lastPos != null) {
            double dx = at.getX() - lastPos.getX(), dz = at.getZ() - lastPos.getZ();
            if (dx * dx + dz * dz > 0.0004) facing = yawOf(dx, dz);
        }
        at.setYaw(facing);
        at.setPitch(0);
        avatar.teleport(at);
        avatar.setRotation(facing, 0);
        lastPos = at.toVector();
    }

    /** Keeps him seated (a teleport by another plugin takes him off): back on the seat where he now stands. */
    void ride() {
        if (player.getVehicle() != seat) {
            seat.teleport(player.getLocation());
            seat.addPassenger(player);
            seatOffset = Double.NaN;
        } else if (Double.isNaN(seatOffset)) {
            seatOffset = player.getLocation().getY() - seat.getLocation().getY();
        }
    }

    /** Puts his feet at {@code feet}: the server walks him by moving his seat. */
    void moveFeet(Vector feet) {
        double off = Double.isNaN(seatOffset) ? 0 : seatOffset;
        Location l = new Location(player.getWorld(), feet.getX(), feet.getY() - off, feet.getZ());
        seat.teleport(l, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    /** Stops walking and chasing. */
    void stop() {
        path = List.of();
        pathIndex = 0;
        chase = null;
    }

    void face(Vector towards, long tick, int ticks) {
        Location at = player.getLocation();
        double dx = towards.getX() - at.getX(), dz = towards.getZ() - at.getZ();
        if (dx * dx + dz * dz < 1e-4) return;
        facing = yawOf(dx, dz);
        faceUntil = tick + ticks;
    }

    /** Blocks the camera and the pointer see: invisible walls (barriers above the rooms' fences) are looked through. */
    private static boolean seen(org.bukkit.block.Block b) {
        org.bukkit.Material m = b.getType();
        return m != org.bukkit.Material.BARRIER && m != org.bukkit.Material.LIGHT && m != org.bukkit.Material.STRUCTURE_VOID;
    }

    static float yawOf(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static float wrap(float a) {
        a %= 360;
        if (a >= 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }

    private static double clamp(double v, double max) {
        return Math.max(-max, Math.min(max, v));
    }
}
