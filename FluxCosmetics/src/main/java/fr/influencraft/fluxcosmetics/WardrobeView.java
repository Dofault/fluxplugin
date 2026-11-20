package fr.influencraft.fluxcosmetics;

import fr.influencraft.flux.api.client.Camera;
import fr.influencraft.flux.api.client.Hotbar;
import fr.influencraft.flux.api.client.Sidebar;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
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
 *   it, the mouse wheel brings it closer or farther (his held slot is put back at once, {@link Wardrobe}). Moving the
 *   mouse forward or back raises or lowers it too (his pitch is the camera's, read as soon as it moves,
 *   {@link Camera#turns}); its sideways moves are turned back at once.</li>
 *   <li>He is moved by {@link Camera#moveSelf}, which never touches his rotation (a server teleport resets it and makes
 *   the server deaf to his rotation until his client answers, 2026-11-20); his view is turned with the camera by
 *   {@link Camera#turn}.</li>
 *   <li>The camera never goes through blocks: it comes closer when something stands between it and the mannequin.</li>
 *   <li>His client is told he plays in creative ({@link Hotbar#bars}): no life, food nor experience bars on the
 *   screen. The server never sees him in creative.</li>
 * </ul>
 * One item in his hotbar, in the middle: left click leaves, right click will open the choice of cosmetics. Other
 * players no longer see him meanwhile (an invisible player still shows his armour): they see the mannequin.
 */
final class WardrobeView {

    /** The slot of the wardrobe's item: the middle of the hotbar. */
    static final int SLOT = 4;

    /** Degrees his view may be off the camera's before it is turned back. */
    private static final float SLACK = 0.5f;
    /** Room kept between the camera and a block in the way. */
    private static final double WALL_GAP = 0.3;
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
    /** The point the camera looks at. */
    private final Vector target;
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
        // the mouse forward or back raises or lowers the camera; sideways belongs to the keys and is turned back
        Camera.turns(player, (p, y, x) -> {
            float cy = yaw;
            if (Math.abs(wrap(y - cy)) > SLACK) Camera.turn(p, cy, false, 0, true);
            float clamped = Math.clamp(x, settings.minPitch(), settings.maxPitch());
            if (clamped != x) Camera.turn(p, 0, true, clamped, false);
            // his client answers each move with his rotation, unchanged: not a mouse move (that answer looped)
            if (Math.abs(clamped - pitch) <= 0.01f) return;
            pitch = clamped;
            long now = System.nanoTime();
            if (now - lastMove < MOVE_GAP_NANOS) return;
            lastMove = now;
            Vector f = feet(cy, clamped, reach);
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
        if (sideways != 0 || upwards != 0) {
            yaw = wrap(yaw + sideways * settings.turnSpeed());
            pitch = Math.clamp(pitch + upwards * settings.pitchSpeed(), settings.minPitch(), settings.maxPitch());
            Camera.turn(player, yaw, false, pitch, false);
        }
        if (Math.abs(wantedZoom - zoom) > 1e-3) zoom += (wantedZoom - zoom) * ZOOM_GLIDE;
        if (!player.isFlying()) player.setFlying(true);

        reach = reach();
        Vector f = feet(yaw, pitch, reach);
        // moved: keys, zoom, a block in the way; else now and then against any drift
        if (placed.distanceSquared(f) > 1e-4 || ticks % 20 == 0) {
            placed = f;
            Camera.moveSelf(player, f.getX(), f.getY(), f.getZ());
        }
        if (ticks % 40 == 0) Hotbar.show(player, hotbar());
        if (ticks % 30 == 0) hint();
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
        Vector eye = target.clone().subtract(direction(yaw, pitch).multiply(distance));
        return eye.setY(eye.getY() - eyeHeight);
    }

    /** The zoom, or less when a block stands between the mannequin and the camera or where his body would be. */
    private double reach() {
        float y = yaw, x = pitch;
        Vector backwards = direction(y, x).multiply(-1);
        RayTraceResult hit = world.rayTraceBlocks(target.toLocation(world), backwards, zoom + WALL_GAP,
                FluidCollisionMode.NEVER, true);
        double d = hit == null ? zoom : Math.max(MIN_REACH, hit.getHitPosition().distance(target) - WALL_GAP);
        while (d > MIN_REACH && world.hasCollisionsIn(body(feet(y, x, d)))) d -= 0.1;
        return Math.max(MIN_REACH, d);
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
     * The one item of his hotbar, in the middle. It looks like a spyglass (model of the config) on an item without a
     * use of its own: a real spyglass would bring its scope up on the right click.
     */
    private ItemStack[] hotbar() {
        var m = plugin.messages();
        ItemStack item = Items.dress(Items.of(settings.item(), player), m.raw("items.camera-name"),
                Lore.create().description(m.raw("items.camera-description"))
                        .left(m.raw("items.camera-left")).right(m.raw("items.camera-right")).tags(), player);
        if (!settings.itemModel().isBlank()) item.setData(DataComponentTypes.ITEM_MODEL, Key.key(settings.itemModel()));
        ItemStack[] items = new ItemStack[9];
        items[SLOT] = item;
        return items;
    }

    private void hint() {
        plugin.messages().actionBar(player, "hint");
    }
}
