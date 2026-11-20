package fr.influencraft.fluxcosmetics;

import fr.influencraft.flux.api.client.Camera;
import fr.influencraft.flux.api.client.Hotbar;
import fr.influencraft.flux.api.client.Sidebar;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * One player in the wardrobe.
 *
 * <ul>
 *   <li>A <b>mannequin</b> with his skin and his equipment stays where he stood: what he looks at (later dressed in
 *   the outfit he tries on).</li>
 *   <li>The <b>camera is himself</b>: a client looking through another entity draws no hotbar (26.2), and his hotbar
 *   is the wardrobe's menu. He flies, invisible, unable to move by himself (walk and fly speed 0), and is put back on
 *   a sphere around the mannequin, where his own view direction looks right at it: his mouse turns the camera around
 *   himself and nothing has to turn his head. He is moved as soon as his mouse moves, from the network thread, by
 *   {@link Camera#moveSelf}, which never touches his rotation (a server teleport resets it and makes the server deaf
 *   to his mouse until his client answers: the camera did not turn at all, 2026-11-20).</li>
 *   <li>While <b>slot 1</b> is held the mouse moves the camera, forward and backward bring it closer or farther. Any
 *   other slot holds the camera still: his head is turned back to it as soon as his mouse moves
 *   ({@link Camera#turns}, without a packet adapter at the next tick).</li>
 *   <li>The camera never goes through blocks: it comes closer when something stands between it and the mannequin.</li>
 *   <li>His client is told he plays in creative ({@link Hotbar#bars}): no life, food nor experience bars on the
 *   screen. The server never sees him in creative.</li>
 * </ul>
 * Other players no longer see him meanwhile (an invisible player still shows his armour): they see the mannequin.
 */
final class WardrobeView {

    /** Degrees his head may be off the still camera before it is turned back. */
    private static final float SLACK = 0.5f;
    /** Room kept between the camera and a block in the way. */
    private static final double WALL_GAP = 0.3;
    /**
     * His size meanwhile: his body (1.8 blocks under his eyes at full size) would go into the floor or a wall where his
     * eyes are free, and the server would refuse the move and put him back (kicked for too many packets, 2026-11-20).
     */
    private static final double SCALE = 0.1;
    /** Eyes of a standing (flying) player at full size. */
    private static final double STANDING_EYE = 1.62;
    /** Least time between two moves sent from his mouse; the tick sends the last one. */
    private static final long MOVE_GAP_NANOS = 40_000_000L;

    static final int CAMERA_SLOT = 0;
    static final int LEAVE_SLOT = 8;

    private final FluxCosmetics plugin;
    final Player player;
    private final Settings settings;
    private final Location back;
    private final World world;
    /** The point the camera looks at. */
    private final Vector target;

    Mannequin mannequin;
    private int heldBefore;
    private boolean allowFlight, flying, madeInvisible;
    private float walkSpeed, flySpeed;

    /** Where the camera stands around the target: the view direction of the player (yaw, pitch) and its distance. */
    private float yaw, pitch;
    private double zoom;
    /** Distance the camera really stands at (zoom, closer when a block is in the way), read by the network thread. */
    private volatile double reach;
    /** His eyes above his feet while he flies, at {@link #SCALE}. */
    private final double eyeHeight = STANDING_EYE * SCALE;
    /** The shrink, a transient modifier: never saved, gone after a crash. */
    private static final org.bukkit.NamespacedKey SHRINK = new org.bukkit.NamespacedKey("fluxcosmetics", "wardrobe");
    /** Last move sent from his mouse (network thread). */
    private volatile long lastMove;
    /** True while slot 1 is held: his mouse moves the camera. Read by the network thread. */
    private volatile boolean orbiting = true;
    /** The still camera's direction, read by the network thread. */
    private volatile float stillYaw, stillPitch;
    /**
     * His mouse, read in his packets as soon as they come (network thread), never in his server location (late, and
     * not updated while a teleport waits for his answer).
     */
    private volatile float mouseYaw, mousePitch;
    /** Where the tick last put him, to move him only when the camera really moves. */
    private Vector placed;
    /** True while the wardrobe itself teleports him. */
    boolean moving;
    boolean closing;
    private int ticks;

    WardrobeView(FluxCosmetics plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.settings = plugin.settings();
        this.back = player.getLocation();
        this.world = back.getWorld();
        this.target = back.toVector().add(new Vector(0, settings.targetHeight(), 0));
    }

    // ================================================================== opening and closing

    void open() {
        Location stand = back.clone();
        stand.setPitch(0);
        mannequin = world.spawn(stand, Mannequin.class, CreatureSpawnEvent.SpawnReason.CUSTOM, m -> {
            m.addScoreboardTag(Wardrobe.TAG);
            m.setPersistent(false);
            m.setInvulnerable(true);
            m.setCollidable(false);
            m.setImmovable(true);
            m.setSilent(true);
            m.setDescription(null);
            m.setProfile(ResolvableProfile.resolvableProfile(player.getPlayerProfile()));
            m.setMainHand(player.getMainHand());
            m.setSkinParts(player.getClientOption(com.destroystokyo.paper.ClientOption.SKIN_PARTS));
            dress(m.getEquipment());
        });

        heldBefore = player.getInventory().getHeldItemSlot();
        allowFlight = player.getAllowFlight();
        flying = player.isFlying();
        walkSpeed = player.getWalkSpeed();
        flySpeed = player.getFlySpeed();

        player.closeInventory();
        player.getInventory().setHeldItemSlot(CAMERA_SLOT);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setWalkSpeed(0);
        player.setFlySpeed(0);
        player.setFallDistance(0);
        var scale = player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
        if (scale != null) {
            scale.removeModifier(SHRINK);
            scale.addTransientModifier(new org.bukkit.attribute.AttributeModifier(SHRINK, SCALE - 1,
                    org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
        if (!player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            // an invisible player's arm is not drawn in front of his view
            madeInvisible = true;
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }

        // the camera starts in front of him, looking at his face
        yaw = back.getYaw() + 180;
        pitch = settings.startPitch();
        zoom = settings.distance();
        stillYaw = yaw;
        stillPitch = pitch;
        mouseYaw = yaw;
        mousePitch = pitch;
        reach = Math.min(zoom, free(yaw, pitch));
        // the one real teleport: his view is turned to the mannequin, then only his mouse turns it
        Vector start = feet(yaw, pitch, reach);
        teleport(new Location(world, start.getX(), start.getY(), start.getZ(), yaw, pitch));
        placed = start;

        Hotbar.show(player, hotbar());
        Hotbar.bars(player, false);
        Sidebar.hide(player, true);
        Camera.turns(player, (p, y, x) -> {
            // his client answers each move with his rotation, unchanged: not his mouse (that answer looped forever)
            boolean turned = Math.abs(wrap(y - mouseYaw)) > 0.01f || Math.abs(x - mousePitch) > 0.01f;
            mouseYaw = y;
            mousePitch = x;
            if (orbiting) {
                if (!turned) return;
                float clamped = Math.clamp(x, settings.minPitch(), settings.maxPitch());
                if (clamped != x) Camera.turn(p, 0, true, clamped, false);
                long now = System.nanoTime();
                if (now - lastMove < MOVE_GAP_NANOS) return;
                lastMove = now;
                Vector f = feet(y, clamped, reach);
                Camera.moveSelf(p, f.getX(), f.getY(), f.getZ());
                return;
            }
            float sy = stillYaw, sx = stillPitch;
            if (Math.abs(wrap(y - sy)) > SLACK || Math.abs(x - sx) > SLACK) Camera.turn(p, sy, false, sx, false);
        });
        hint();
    }

    /** Everything back as it was; {@code bringBack}: where he stood when he opened it. */
    void close(boolean bringBack) {
        closing = true;
        Camera.turns(player, null);
        if (mannequin != null && mannequin.isValid()) mannequin.remove();
        if (!player.isOnline()) return;
        Hotbar.clear(player);
        Hotbar.bars(player, true);
        Sidebar.hide(player, false);
        player.getInventory().setHeldItemSlot(heldBefore);
        player.setFlySpeed(flySpeed);
        player.setWalkSpeed(walkSpeed);
        player.setFlying(flying && allowFlight);
        player.setAllowFlight(allowFlight);
        player.setFallDistance(0);
        var scale = player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
        if (scale != null) scale.removeModifier(SHRINK);
        if (madeInvisible) player.removePotionEffect(PotionEffectType.INVISIBILITY);
        madeInvisible = false;
        if (bringBack) teleport(back);
    }

    // ================================================================== every tick

    void tick() {
        ticks++;
        if (!mannequin.isValid()) {
            plugin.wardrobe().close(player, true);
            return;
        }
        if (orbiting) {
            yaw = mouseYaw;
            float x = mousePitch;
            pitch = Math.clamp(x, settings.minPitch(), settings.maxPitch());
            var input = player.getCurrentInput();
            if (input.isForward() != input.isBackward()) {
                double step = input.isForward() ? -settings.zoomSpeed() : settings.zoomSpeed();
                zoom = Math.clamp(zoom + step, settings.minDistance(), settings.maxDistance());
            }
        }
        if (!player.isFlying()) player.setFlying(true);
        reach = Math.min(zoom, free(yaw, pitch));
        Vector f = feet(yaw, pitch, reach);
        // moved: zoom, a block in the way, his mouse since the last packet; else now and then against any drift
        if (placed == null || placed.distanceSquared(f) > 1e-4 || ticks % 20 == 0) {
            placed = f;
            Camera.moveSelf(player, f.getX(), f.getY(), f.getZ());
        }
        if (ticks % 40 == 0) Hotbar.show(player, hotbar());
        if (ticks % 30 == 0) hint();
    }

    /** He holds another slot: slot 1 sets the camera free, any other holds it still where it is. */
    void held(int slot) {
        boolean free = slot == CAMERA_SLOT;
        if (free == orbiting) return;
        if (!free) {
            stillYaw = yaw;
            stillPitch = pitch;
        }
        orbiting = free;
        hint();
    }

    // ================================================================== camera

    /** Unit vector of a view direction. */
    private static Vector direction(float yaw, float pitch) {
        return new Location(null, 0, 0, 0, yaw, pitch).getDirection();
    }

    /** His feet when his eyes look at the target from that direction and distance. Thread-safe (no world access). */
    private Vector feet(float yaw, float pitch, double distance) {
        Vector eye = target.clone().subtract(direction(yaw, pitch).multiply(distance));
        return eye.setY(eye.getY() - eyeHeight);
    }

    /** How far the camera can go back from the target in this direction before a block. */
    private double free(float yaw, float pitch) {
        Vector backwards = direction(yaw, pitch).multiply(-1);
        RayTraceResult hit = world.rayTraceBlocks(target.toLocation(world), backwards, zoom + WALL_GAP,
                FluidCollisionMode.NEVER, true);
        double d = hit == null ? zoom : Math.max(0.4, hit.getHitPosition().distance(target) - WALL_GAP);
        // his (small) body must be free too, else the server refuses the move
        while (d > 0.4 && world.hasCollisionsIn(body(feet(yaw, pitch, d)))) d -= 0.1;
        return Math.max(0.4, d);
    }

    private org.bukkit.util.BoundingBox body(Vector feet) {
        double half = 0.3 * SCALE, height = 1.8 * SCALE;
        return new org.bukkit.util.BoundingBox(feet.getX() - half, feet.getY(), feet.getZ() - half,
                feet.getX() + half, feet.getY() + height, feet.getZ() + half);
    }

    private void teleport(Location to) {
        moving = true;
        try {
            player.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            moving = false;
        }
    }

    private static float wrap(float degrees) {
        float d = degrees % 360;
        return d > 180 ? d - 360 : d < -180 ? d + 360 : d;
    }

    // ================================================================== what he sees

    /** The mannequin wears what he wears (the outfit he tries on will be drawn over it). */
    private void dress(EntityEquipment equipment) {
        if (equipment == null) return;
        var own = player.getInventory();
        equipment.setHelmet(copy(own.getHelmet()));
        equipment.setChestplate(copy(own.getChestplate()));
        equipment.setLeggings(copy(own.getLeggings()));
        equipment.setBoots(copy(own.getBoots()));
        equipment.setItemInMainHand(copy(own.getItemInMainHand()));
        equipment.setItemInOffHand(copy(own.getItemInOffHand()));
    }

    private static ItemStack copy(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private ItemStack[] hotbar() {
        var m = plugin.messages();
        ItemStack[] items = new ItemStack[9];
        items[CAMERA_SLOT] = Items.dress(Items.of(settings.cameraItem(), player), m.raw("items.camera-name"),
                Lore.create().description(m.raw("items.camera-description")).tags(), player);
        items[LEAVE_SLOT] = Items.dress(Items.of(settings.leaveItem(), player), m.raw("items.leave-name"),
                Lore.create().description(m.raw("items.leave-description")).left(m.raw("items.leave-click")).tags(), player);
        return items;
    }

    private void hint() {
        plugin.messages().actionBar(player, orbiting ? "hint-camera" : "hint-still");
    }
}
