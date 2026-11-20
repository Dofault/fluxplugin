package fr.influencraft.fluxcosmetics;

import fr.influencraft.flux.api.client.Camera;
import fr.influencraft.flux.api.client.Hotbar;
import fr.influencraft.flux.api.client.Sidebar;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import net.kyori.adventure.key.Key;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * One player in the wardrobe.
 *
 * <ul>
 *   <li>A <b>mannequin</b> with his skin and his equipment stays where he stood: what he looks at (later dressed in
 *   the outfit he tries on).</li>
 *   <li>The <b>camera is himself</b>: a client looking through another entity draws no hotbar (26.2), and his hotbar
 *   shows the wardrobe's item. He flies, invisible, shrunk (his body never touches a block where his eyes are free,
 *   else the server refuses the move), unable to move by himself (walk and fly speed 0), on a sphere around the
 *   mannequin where his view looks right at it.</li>
 *   <li><b>Keys drive the camera</b>: left and right turn it around the mannequin, forward and backward raise or lower
 *   it around the mannequin, the mouse wheel brings it closer or farther (his held slot is put back at once,
 *   {@link Wardrobe}). Moving the mouse forward or back slides the camera and the point it looks at up or down
 *   (feet to head), its angle unchanged; his view is turned back to the camera's after each move of the mouse
 *   ({@link Camera#turns}).</li>
 *   <li>He is moved by {@link Camera#moveSelf}, which never touches his rotation (a server teleport resets it and makes
 *   the server deaf to his rotation until his client answers, 2026-11-20); his view is turned with the camera by
 *   {@link Camera#turn}.</li>
 *   <li>The camera never goes through blocks: it comes closer when something stands between it and the mannequin.</li>
 *   <li>His client is told he plays in creative ({@link Hotbar#bars}): no life, food nor experience bars on the
 *   screen. The server never sees him in creative.</li>
 * </ul>
 * Nothing shown in his hotbar: left click leaves, right click will open the choice of cosmetics. Other
 * players no longer see him meanwhile (an invisible player still shows his armour): they see the mannequin.
 */
final class WardrobeView {

    /** The slot of the wardrobe's item: the middle of the hotbar. */
    static final int SLOT = 4;

    /** Degrees his view may be off the camera's before it is turned back. */
    private static final float SLACK = 0.5f;
    /** Room kept between the camera and a block in the way. */
    private static final double WALL_GAP = 0.3;
    /** Eyes of the camera above the mannequin's feet at least: a floor is assumed there. */
    private static final double FLOOR = 0.25;
    /** Distance kept sideways from the mannequin's middle while level with his body, and his height. */
    private static final double CLEARANCE = 0.5, MANNEQUIN_HEIGHT = 2.0;
    /** Closest the camera comes to the mannequin, whatever stands in the way. */
    private static final double MIN_REACH = 0.4;
    /** His size meanwhile (1.8 blocks of body under his eyes at full size would go into the floor or a wall). */
    private static final double SCALE = 0.1;
    /** Eyes of a standing (flying) player at full size. */
    private static final double STANDING_EYE = 1.62;
    /** Least time between two moves sent from his mouse; the tick sends the last one. */
    private static final long MOVE_GAP_NANOS = 40_000_000L;
    /** Share of the way to the wanted zoom made each tick: the wheel's notches glide instead of jumping. */
    private static final double ZOOM_GLIDE = 0.35;
    /** The shrink, a transient modifier: never saved, gone after a crash. */
    private static final NamespacedKey SHRINK = new NamespacedKey("fluxcosmetics", "wardrobe");

    private final FluxCosmetics plugin;
    final Player player;
    private final Settings settings;
    private final Location back;
    private final World world;
    /** The mannequin's feet; the camera looks at {@link #height} above them. */
    private final Vector base;
    /** Height of the point looked at above the mannequin's feet, slid by the mouse (read by the network thread). */
    private volatile double height;
    /** Until when his rotation packets still carry a mouse move already counted (nanos). */
    private volatile long settling;
    private final double eyeHeight = STANDING_EYE * SCALE;

    Mannequin mannequin;
    private int heldBefore;
    private boolean allowFlight, flying, madeInvisible;
    private float walkSpeed, flySpeed;

    /** The camera's direction (his view), read by the network thread to turn his mouse back. */
    private volatile float yaw, pitch;
    /** Distance now, and the one the wheel asked for. */
    private double zoom, wantedZoom;
    /** Distance the camera really stands at (zoom, closer when a block is in the way), read by the network thread. */
    private volatile double reach;
    /** Last move sent from his mouse (network thread). */
    private volatile long lastMove;
    /** Where he was last put, to move him only when the camera really moves. */
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
        this.base = back.toVector();
        this.height = settings.targetHeight();
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
            // never silent: a client plays no sound bound to a silent entity, and his music is heard on it
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
        player.getInventory().setHeldItemSlot(SLOT);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setWalkSpeed(0);
        player.setFlySpeed(0);
        player.setFallDistance(0);
        var scale = player.getAttribute(Attribute.SCALE);
        if (scale != null) {
            scale.removeModifier(SHRINK);
            scale.addTransientModifier(new AttributeModifier(SHRINK, SCALE - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
        if (!player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            // an invisible player's arm is not drawn in front of his view
            madeInvisible = true;
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }

        // the camera starts in front of him, looking at his face
        yaw = wrap(back.getYaw() + 180);
        pitch = settings.startPitch();
        zoom = wantedZoom = settings.distance();
        // the one real teleport: his view is set to the camera's here, then turned by packets only
        reach = reach();
        Vector start = feet(yaw, pitch, reach);
        teleport(new Location(world, start.getX(), start.getY(), start.getZ(), yaw, pitch));
        placed = start;

        // his music is heard on the mannequin: around his head it glitched, the server being behind his camera
        Camera.hearAt(player, mannequin);
        Hotbar.show(player, hotbar());
        Hotbar.bars(player, false);
        Sidebar.hide(player, true);
        // the mouse never turns the view: forward or back slides the camera up or down, then the view goes back
        Camera.turns(player, (p, y, x) -> {
            float cy = yaw, cx = pitch;
            float up = cx - x;
            boolean sideways = Math.abs(wrap(y - cy)) > SLACK;
            // his client answers each move with his rotation, unchanged: not a mouse move (that answer looped)
            if (Math.abs(up) <= SLACK && !sideways) return;
            long now = System.nanoTime();
            if (now - settling > 0) {
                // forward (looking up) raises the camera
                height = Math.clamp(height + up * settings.mouseHeight(), settings.minHeight(), settings.maxHeight());
                // the packets already on their way still carry this move: not counted twice
                settling = now + (p.getPing() + 25) * 1_000_000L;
            }
            Camera.turn(p, cy, false, cx, false);
            if (now - lastMove < MOVE_GAP_NANOS) return;
            lastMove = now;
            Vector f = feet(cy, cx, reach);
            // under the floor until the tick raises the angle: left to the tick
            if (f.getY() + eyeHeight < base.getY() + FLOOR) return;
            Camera.moveSelf(p, f.getX(), f.getY(), f.getZ());
        });
        hint();
    }

    /** Everything back as it was; {@code bringBack}: where he stood when he opened it. */
    void close(boolean bringBack) {
        closing = true;
        Camera.turns(player, null);
        Camera.hearAt(player, null);
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
        var scale = player.getAttribute(Attribute.SCALE);
        if (scale != null) scale.removeModifier(SHRINK);
        if (madeInvisible) player.removePotionEffect(PotionEffectType.INVISIBILITY);
        madeInvisible = false;
        if (bringBack) teleport(back);
    }

    // ================================================================== controls

    void tick() {
        ticks++;
        if (!mannequin.isValid()) {
            plugin.wardrobe().close(player, true);
            return;
        }
        var input = player.getCurrentInput();
        // left (Q on AZERTY) takes the camera to its left around him: its view turns right, the yaw grows
        int sideways = (input.isLeft() ? 1 : 0) - (input.isRight() ? 1 : 0);
        // forward (Z on AZERTY) raises the camera: it looks further down
        int upwards = (input.isForward() ? 1 : 0) - (input.isBackward() ? 1 : 0);
        boolean turned = sideways != 0 || upwards != 0;
        if (turned) {
            yaw = wrap(yaw + sideways * settings.turnSpeed());
            pitch = Math.clamp(pitch + upwards * settings.pitchSpeed(), settings.minPitch(), settings.maxPitch());
        }
        if (Math.abs(wantedZoom - zoom) > 1e-3) zoom += (wantedZoom - zoom) * ZOOM_GLIDE;
        if (!player.isFlying()) player.setFlying(true);

        // a floor is assumed at the mannequin's feet, whatever is really there (holes glitched the camera): the
        // camera never goes under it, its angle rises instead of it coming closer (it ended inside the mannequin)
        turned |= keepAboveFloor(zoom);
        double d = reach();
        if (keepAboveFloor(d)) {
            turned = true;
            d = reach();
        }
        reach = d;
        if (turned) Camera.turn(player, yaw, false, pitch, false);
        Vector f = feet(yaw, pitch, d);
        // moved: keys, zoom, a block in the way, put back by the server; else now and then against any drift
        // (every 5 ticks: Paper may put him back after a refused move without any event)
        if (placed == null || placed.distanceSquared(f) > 1e-4 || ticks % 5 == 0) {
            placed = f;
            Camera.moveSelf(player, f.getX(), f.getY(), f.getZ());
        }
        if (ticks % 40 == 0) Hotbar.show(player, hotbar());
        if (ticks % 30 == 0) hint();
    }

    /**
     * Raises the camera's angle so that at {@code distance} its eyes stay {@link #FLOOR} above the mannequin's feet.
     * True when the angle changed.
     */
    private boolean keepAboveFloor(double distance) {
        // pitch down is positive: the eyes stand sin(pitch) * distance above the point looked at
        double need = (FLOOR - height) / Math.max(0.05, distance);
        if (need <= -1) return false;
        float lowest = (float) Math.toDegrees(Math.asin(Math.min(1, need)));
        lowest = Math.min(lowest, settings.maxPitch());
        if (pitch >= lowest) return false;
        pitch = lowest;
        return true;
    }

    /** The server put him back after a move it refused: placed again at the next tick. */
    void resync() {
        placed = null;
    }

    /** The mouse wheel turned by {@code notches}: up (negative) brings the camera closer. */
    void wheel(int notches) {
        wantedZoom = Math.clamp(wantedZoom + notches * settings.zoomStep(), settings.minDistance(), settings.maxDistance());
    }

    /** Right click: the choice of cosmetics (to come). */
    void menu() {
        plugin.messages().send(player, "menu-soon");
    }

    // ================================================================== camera

    /** Unit vector of a view direction. */
    private static Vector direction(float yaw, float pitch) {
        return new Location(null, 0, 0, 0, yaw, pitch).getDirection();
    }

    /** His feet when his eyes look at the target from that direction and distance. */
    private Vector feet(float yaw, float pitch, double distance) {
        Vector eye = target().subtract(direction(yaw, pitch).multiply(distance));
        return eye.setY(eye.getY() - eyeHeight);
    }

    /** The point the camera looks at (a new vector). */
    private Vector target() {
        return base.clone().setY(base.getY() + height);
    }

    /** The zoom, or less when a block stands between the mannequin and the camera or where his body would be. */
    private double reach() {
        float y = yaw, x = pitch;
        Vector target = target();
        Vector backwards = direction(y, x).multiply(-1);
        RayTraceResult hit = world.rayTraceBlocks(target.toLocation(world), backwards, zoom + WALL_GAP,
                FluidCollisionMode.NEVER, true);
        double d = hit == null ? zoom : Math.max(MIN_REACH, hit.getHitPosition().distance(target) - WALL_GAP);
        while (d > MIN_REACH && world.hasCollisionsIn(body(feet(y, x, d)))) d -= 0.1;
        d = Math.max(MIN_REACH, d);
        // never inside the mannequin: far enough from his middle, sideways, while level with his body
        double level = Math.cos(Math.toRadians(x));
        if (level > 0.2 && height + Math.sin(Math.toRadians(x)) * d < MANNEQUIN_HEIGHT) d = Math.max(d, CLEARANCE / level);
        return d;
    }

    private BoundingBox body(Vector feet) {
        double half = 0.3 * SCALE, height = 1.8 * SCALE;
        return new BoundingBox(feet.getX() - half, feet.getY(), feet.getZ() - half,
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

    static float wrap(float degrees) {
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

    /**
     * His hotbar, seen empty. The middle slot holds an item nobody sees (empty model, empty name, no tooltip): with
     * an empty hand his client would send nothing for a right click in the air, the click of the cosmetics menu.
     */
    private static ItemStack[] hotbar() {
        ItemStack item = new ItemStack(Material.PAPER);
        item.setData(DataComponentTypes.ITEM_MODEL, Key.key("minecraft", "air"));
        item.setData(DataComponentTypes.CUSTOM_NAME, Component.empty());
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        ItemStack[] items = new ItemStack[9];
        items[SLOT] = item;
        return items;
    }

    private void hint() {
        plugin.messages().actionBar(player, "hint");
    }
}
