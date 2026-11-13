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
 * The player himself still walks with the game's physics: the session pushes him along a path ({@link Paths}) by
 * velocity, his own keys moving him nowhere (movement speed and jump strength at 0).
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
    final BossBar bar;
    final int previousSlot;

    // pointer, in screen units from the centre (x right, y up)
    double sx, sy;
    private float lastYaw, lastPitch;
    private boolean primed, pitchReset;
    private double distance;

    // walking
    List<Vector> path = List.of();
    int pathIndex;
    LivingEntity chase;
    long nextRepath;
    double vy;
    private Vector lastPos;
    private int stuck;

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
            d.setTeleportDuration(2);
        });
        target = w.spawn(eye.clone().subtract(0, 1.5, 0), Interaction.class, i -> {
            i.setPersistent(false);
            i.setVisibleByDefault(false);
            i.setInteractionWidth(3f);
            i.setInteractionHeight(3f);
            i.setResponsive(false);
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
        for (Entity e : List.of(camera, cursor, target)) player.showEntity(plugin, e);
        bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
    }

    void open() {
        Settings s = plugin.settings();
        hold(Attribute.MOVEMENT_SPEED);
        hold(Attribute.JUMP_STRENGTH);
        player.getInventory().setHeldItemSlot(s.restSlot());
        for (Player other : plugin.getServer().getOnlinePlayers()) if (other != player) other.hidePlayer(plugin, player);
        Camera.hideSelf(player, true);
        Camera.view(player, camera);
        // the camera reaches his client with the next entity update: say it again once it is there
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && camera.isValid()) Camera.view(player, camera);
        }, 3L);
        // right clicks only: left clicks are read from the arm swing (Sessions#onSwing), sent even when the box is missed
        Camera.clicks(target, (p, left) -> {
            if (!left) plugin.sessions().clicked(this, false);
        });
        hideOwnGear();
        player.showBossBar(bar);
    }

    void close() {
        Camera.clicks(target, null);
        if (player.isOnline()) {
            Camera.view(player, null);
            Camera.hideSelf(player, false);
            for (Player other : plugin.getServer().getOnlinePlayers()) if (other != player) other.showPlayer(plugin, player);
            player.hideBossBar(bar);
            player.getInventory().setHeldItemSlot(previousSlot);
            player.updateInventory();
        }
        release(Attribute.MOVEMENT_SPEED);
        release(Attribute.JUMP_STRENGTH);
        for (Entity e : List.of(cursor, target, camera, avatar)) if (e.isValid()) e.remove();
    }

    private void hold(Attribute attribute) {
        AttributeInstance a = player.getAttribute(attribute);
        if (a == null) return;
        a.removeModifier(MODIFIER);
        a.addTransientModifier(new AttributeModifier(MODIFIER, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
    }

    private void release(Attribute attribute) {
        AttributeInstance a = player.getAttribute(attribute);
        if (a != null) a.removeModifier(MODIFIER);
    }

    /** His invisible body still shows its armour and held items to himself: send him empty ones (look only). */
    void hideOwnGear() {
        Map<EquipmentSlot, ItemStack> empty = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) empty.put(slot, ItemStack.empty());
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

    /** The mouse turned his (unseen) head: the pointer moves, stopped at the screen's edges. */
    void readMouse() {
        Settings s = plugin.settings();
        Location l = player.getLocation();
        float yaw = l.getYaw(), pitch = l.getPitch();
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
    }

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
        Location spot = pointed().add(0, 0.05, 0);
        cursor.teleport(spot);
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

    /** Ticks without real progress along the path (a wall, a monster in the way). */
    int stuck(Vector now, boolean walking) {
        if (!walking) stuck = 0;
        else if (lastPos != null && now.clone().setY(0).distanceSquared(lastPos.clone().setY(0)) < 0.0025) stuck++;
        else stuck = 0;
        return stuck;
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
