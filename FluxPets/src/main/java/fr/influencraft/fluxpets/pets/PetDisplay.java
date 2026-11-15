package fr.influencraft.fluxpets.pets;

import com.willfp.eco.core.config.interfaces.Config;
import fr.influencraft.fluxpets.Texts;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.TrackerUpdateAction;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Modèle 3D (BetterModel) du familier actif, qui suit vraiment son maître (au lieu du point fixe collé à la
 * caméra d'EcoPets), comme quelqu'un qui l'accompagne :
 * - tant que le maître est entre distance-min et distance-max (zone de confort), il ne bouge pas : il le suit
 *   seulement du regard, même si le maître tourne autour de lui ;
 * - il ne se déplace qu'en sortant de cette zone : trop loin, il revient jusqu'à être bien dans la zone ; trop
 *   près, il recule un peu ; puis il s'arrête (animation walk en se déplaçant, idle sinon) ;
 * - au-delà de teleport-distance ou dans un autre monde : réapparaît derrière le joueur ;
 * - plusieurs familiers actifs : les suivants marchent en file derrière le premier, chacun suivant le précédent
 *   avec la même logique entre ecart-file-min et ecart-file-max (sans les distances min / max du joueur).
 * Familier « vol: true » : flotte au-dessus des pieds du joueur avec un léger flottement ; « vol: false » :
 * posé sur le sol sous sa position.
 * Pas de modèle pour un familier caché (menu), un joueur mort, invisible ou spectateur. Nom au-dessus du modèle
 * (TextDisplay passager), traînée du familier (trail). Porte-armure marker : aucune hitbox, jamais sauvegardé.
 * Réglages : section pet-follow de config.yml. Chargée seulement si BetterModel est actif.
 * <p>
 * Mounts (2026-11-14): a pet with a {@code mount} section carries an invisible seat (an Interaction entity as high as
 * its back) that its owner right clicks to ride it, from the pet's mount level. A walking pet is then a real horse
 * (2026-11-15): an invisible, tamed, invulnerable horse with an invisible saddle, its speed, jump and size from the
 * mount section, ridden by the owner. The rider's own game drives the horse, so it handles exactly like a horse
 * (turns with the camera, charged jump, steps). The model stays on its carrier, which rides the horse behind him
 * (second passenger: his game moves it with the horse, no lag), lowered by its height on the horse so it stands on the
 * ground. Never on the horse itself (BetterModel sends its displays first among the horse's passengers: the client
 * then does not let the rider drive). When the carrier cannot ride (refused mount, logged), it is put where the
 * horse is every tick instead, a little ahead along its speed. The horse is removed when he gets off and the model
 * goes back to following him. A flying mount is still moved by the server along the rider's keys
 * ({@link Player#getCurrentInput()}). A big pet ({@link Pet#size()}) keeps its edge, not its centre, at the follow
 * distances. {@link PetMountMoveEvent} lets an effect
 * clear the way first (the mole digs).
 */
public final class PetDisplay implements org.bukkit.event.Listener {

    private static final class Follower {
        final Player owner;
        final Pet pet;
        final ArmorStand carrier;
        /** Model of the pet: on the carrier, or on the horse while ridden. */
        EntityTracker tracker;
        /** The horse its owner rides (walking mounts), null when not ridden. */
        org.bukkit.entity.AbstractHorse horse;
        /** Where the horse was last tick (its speed puts the model a little ahead when the carrier cannot ride). */
        double hx = Double.NaN, hy, hz;
        /** How far the model is lowered while its carrier rides the horse (blocks), NaN when not set. */
        double modelDrop = Double.NaN;
        /** Ticks since the mount (the carrier's ride is checked once, after the first ticks). */
        int rideTicks;
        final boolean hasWalk;
        TextDisplay label;
        /** Seat of a mountable pet (its owner rides it), null otherwise. */
        org.bukkit.entity.Interaction seat;
        /** Vertical speed of a ridden pet (blocks per tick). */
        double vy;
        double x, y, z;
        float yaw;
        double lastPX, lastPZ;
        boolean walking;
        /** en route vers sa distance but (sorti de la zone de confort) */
        boolean moving;
        double goal;
        /** dernière lumière appliquée au modèle (-1 : jamais) */
        int blockLight = -1, skyLight = -1;

        Follower(Player owner, Pet pet, ArmorStand carrier, EntityTracker tracker, boolean hasWalk) {
            this.owner = owner;
            this.pet = pet;
            this.carrier = carrier;
            this.tracker = tracker;
            this.hasWalk = hasWalk;
        }
    }

    /** Players whose pets another plugin shows itself (FluxDungeons during a run): no model here. */
    public static final Set<UUID> SUPPRESSED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Familiers affichés de chaque joueur, dans l'ordre de ses familiers actifs (le premier suit le joueur). */
    private final Map<UUID, List<Follower>> followers = new HashMap<>();
    /** Modèles introuvables déjà signalés (un avertissement par modèle, pas un par tick). */
    private final Set<String> missing = new HashSet<>();
    private long tick;

    private double height, minDistance, maxDistance, snapDistance, smoothing, minSpeed, bobAmplitude,
            labelHeight, gapMin, gapMax;

    public PetDisplay() {
        reload();
        Bukkit.getScheduler().runTaskTimer(Pets.plugin(), this::tickAll, 1L, 1L);
    }

    public void reload() {
        ConfigurationSection c = Pets.plugin().getConfig().getConfigurationSection("pet-follow");
        height = get(c, "height", 0.9);
        minDistance = Math.max(0, get(c, "min-distance", 1.5));
        maxDistance = Math.max(minDistance + 0.5, get(c, "max-distance", 4.0));
        snapDistance = get(c, "teleport-distance", 16);
        smoothing = get(c, "smoothing", 0.18);
        minSpeed = get(c, "min-speed", 0.45);
        bobAmplitude = get(c, "bob-amplitude", 0.06);
        labelHeight = get(c, "name-height", 1.3);
        gapMin = Math.max(0.3, get(c, "queue-gap-min", 1.2));
        gapMax = Math.max(gapMin + 0.3, get(c, "queue-gap-max", 2.2));
        missing.clear();
        // familiers rechargés : nouveaux objets Pet, les modèles sont recréés au tick suivant
        shutdown();
    }

    private static double get(ConfigurationSection c, String path, double def) {
        return c == null ? def : c.getDouble(path, def);
    }

    /** Familiers à afficher pour ce joueur (ses familiers actifs, dans l'ordre), aucun s'il est caché, mort... */
    private List<Pet> wanted(Player player) {
        if (!Pets.enabled() || !Pets.settings().getBool("pet-entity.enabled") || player.isDead()
                || player.isInvisible() || player.getGameMode() == GameMode.SPECTATOR || PetData.hidden(player)
                || SUPPRESSED.contains(player.getUniqueId())) {
            return List.of();
        }
        return PetData.actives(player);
    }

    private void tickAll() {
        tick++;
        for (Iterator<Map.Entry<UUID, List<Follower>>> it = followers.entrySet().iterator(); it.hasNext(); ) {
            List<Follower> list = it.next().getValue();
            if (!list.isEmpty() && !list.get(0).owner.isOnline()) {
                list.forEach(this::remove);
                it.remove();
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            List<Pet> pets = wanted(player);
            List<Follower> list = followers.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
            // modèles en trop (familier rangé, porteur disparu) retirés, les autres remis dans l'ordre des familiers
            // actifs, les manquants ajoutés en bout de file
            for (Iterator<Follower> it = list.iterator(); it.hasNext(); ) {
                Follower f = it.next();
                if (!f.carrier.isValid() || !pets.contains(f.pet)) {
                    remove(f);
                    it.remove();
                }
            }
            list.sort(java.util.Comparator.comparingInt(f -> pets.indexOf(f.pet)));
            for (Pet pet : pets) {
                if (list.stream().noneMatch(f -> f.pet.equals(pet))) {
                    Follower f = spawn(player, pet, list);
                    if (f != null) {
                        list.add(f);
                    }
                }
            }
            list.sort(java.util.Comparator.comparingInt(f -> pets.indexOf(f.pet)));
            Location p = player.getLocation();
            for (int i = 0; i < list.size(); i++) {
                Follower f = list.get(i);
                if (f.horse != null && !ridden(f)) dismountHorse(f);
                if (ridden(f)) {
                    if (f.horse != null) rideHorse(f); else ride(f);
                } else if (i == 0) {
                    // le premier suit le joueur, dans la zone de confort distance-min / distance-max
                    double e = reach(f.pet);
                    move(f, p.getX(), p.getZ(), minDistance + e, maxDistance + e, null);
                } else {
                    // les suivants marchent en file : chacun suit le précédent, entre ecart-file-min et -max
                    Follower lead = list.get(i - 1);
                    double e = reach(f.pet) + reach(lead.pet);
                    move(f, lead.x, lead.z, gapMin + e, gapMax + e, lead);
                }
            }
        }
    }

    private Follower spawn(Player owner, Pet pet, List<Follower> before) {
        var renderer = BetterModel.modelOrNull(pet.model());
        if (renderer == null) {
            if (missing.add(pet.model())) {
                Pets.plugin().getLogger().warning("BetterModel model not found for pet " + pet.id()
                        + ": " + pet.model());
            }
            return null;
        }
        Location p = owner.getLocation();
        double yaw = Math.toRadians(p.getYaw());
        // derrière le joueur (premier) ou derrière le dernier de la file
        Follower last = before.isEmpty() ? null : before.get(before.size() - 1);
        double bx = last == null ? p.getX() : last.x;
        double bz = last == null ? p.getZ() : last.z;
        double radius = last == null ? (minDistance + maxDistance) / 2 + reach(pet)
                : (gapMin + gapMax) / 2 + reach(pet) + reach(last.pet);
        Location start = new Location(p.getWorld(), bx + Math.sin(yaw) * radius, p.getY(), bz - Math.cos(yaw) * radius);
        start.setY(targetY(pet, p, start.getX(), start.getZ()));
        start.setYaw(p.getYaw());
        ArmorStand carrier = stand(start);
        EntityTracker tracker = renderer.getOrCreate(BukkitAdapter.adapt(carrier));
        Follower f = new Follower(owner, pet, carrier, tracker, renderer.animation(pet.walkAnimation()).isPresent());
        f.x = start.getX();
        f.y = start.getY();
        f.z = start.getZ();
        f.yaw = start.getYaw();
        f.lastPX = bx;
        f.lastPZ = bz;
        updateLabel(f);
        updateSeat(f);
        return f;
    }

    /** How much further than a pet-sized one this pet keeps: from its centre to its edge, past half a block. */
    private static double reach(Pet pet) {
        return Math.max(0, pet.size() / 2 - 0.5);
    }

    /** Porte-armure invisible, sans hitbox (marker), jamais sauvegardé. */
    static ArmorStand stand(Location at) {
        return at.getWorld().spawn(at, ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setMarker(true);
            stand.setSmall(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setCollidable(false);
            stand.setPersistent(false);
            stand.setSilent(true);
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                stand.addEquipmentLock(slot, ArmorStand.LockType.ADDING_OR_CHANGING);
            }
        });
    }

    /** Hauteur visée : au-dessus des pieds du joueur (vol), sinon le sol sous la position. */
    private double targetY(Pet pet, Location player, double x, double z) {
        if (pet.flying()) {
            return player.getY() + height;
        }
        World world = player.getWorld();
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int top = (int) Math.floor(player.getY()) + 2;
        for (int y = top; y >= top - 6; y--) {
            Block block = world.getBlockAt(bx, y, bz);
            if (!block.isPassable() && block.getRelative(0, 1, 0).isPassable()) {
                return block.getBoundingBox().getMaxY();
            }
        }
        return player.getY();
    }

    /**
     * Déplacement d'un familier par rapport à son meneur (lx, lz) : le joueur pour le premier, le familier
     * précédent pour les suivants (lead), avec ses bornes minD / maxD. Regarde toujours le joueur à l'arrêt.
     */
    private void move(Follower f, double lx, double lz, double minDistance, double maxDistance, Follower lead) {
        Location p = f.owner.getLocation();
        World world = p.getWorld();
        double dx = f.x - lx;
        double dz = f.z - lz;
        double dist = Math.hypot(dx, dz);
        boolean snap = f.carrier.getWorld() != world || Math.hypot(f.x - p.getX(), f.z - p.getZ()) > snapDistance
                || Math.abs(f.y - p.getY()) > snapDistance || (lead != null && lead.carrier.getWorld() != world);
        double ox = f.x;
        double oz = f.z;
        if (snap) {
            // téléportation, autre monde, chute : il réapparaît derrière son meneur, au milieu de sa zone
            double yaw = Math.toRadians(p.getYaw());
            double r = (minDistance + maxDistance) / 2;
            f.x = lx + Math.sin(yaw) * r;
            f.z = lz - Math.cos(yaw) * r;
            f.y = targetY(f.pet, p, f.x, f.z);
            f.moving = false;
        } else {
            // Zone de confort entre distance-min et distance-max : il ne bouge pas (il se contente de regarder
            // son maître, même si celui-ci tourne autour de lui). Il ne se déplace que s'il en sort : trop loin,
            // il revient jusqu'à être bien dans la zone ; trop près, il recule juste ce qu'il faut. Il s'arrête
            // une fois ce but atteint (pas de va-et-vient au bord de la zone).
            if (!f.moving && (dist > maxDistance || dist < minDistance)) {
                f.moving = true;
                double margin = Math.min(1.5, (maxDistance - minDistance) / 2);
                f.goal = dist > maxDistance ? maxDistance - margin : minDistance + Math.min(0.5, margin);
            }
            if (f.moving) {
                double ux = dist > 1.0E-3 ? dx / dist : Math.sin(Math.toRadians(p.getYaw()));
                double uz = dist > 1.0E-3 ? dz / dist : -Math.cos(Math.toRadians(p.getYaw()));
                double remaining = dist - f.goal;
                // plus il est loin du but, plus il va vite, sans dépasser la vitesse du joueur (x1,3)
                double pdx = lx - f.lastPX;
                double pdz = lz - f.lastPZ;
                double cap = Math.max(minSpeed, Math.hypot(pdx, pdz) * 1.3);
                double step = Math.min(cap, Math.max(0.04, Math.abs(remaining) * smoothing));
                if (Math.abs(remaining) <= step) {
                    step = Math.abs(remaining);
                    // but atteint : il s'arrête, sauf si le maître continue de s'éloigner de lui (il l'accompagne
                    // alors en continu, au lieu d'avancer par à-coups)
                    double away = -(pdx * ux + pdz * uz);
                    f.moving = remaining > -1.0E-3 && away > 0.02;
                }
                f.x -= ux * step * Math.signum(remaining);
                f.z -= uz * step * Math.signum(remaining);
            }
            // hauteur : suit le joueur en vol, ne réagit pas à un simple saut (écart de plus d'un bloc ou en
            // déplacement seulement) ; au sol, le sol sous le familier
            double ty = targetY(f.pet, p, f.x, f.z);
            if (f.moving || !f.pet.flying() || Math.abs(ty - f.y) > 1.0) {
                f.y += (ty - f.y) * (f.pet.flying() ? 0.15 : 0.4);
            }
            // familier volant : ne pas finir dans un bloc plein, on monte au besoin
            for (int i = 0; f.pet.flying() && i < 2 && world.getBlockAt((int) Math.floor(f.x), (int) Math.floor(f.y),
                    (int) Math.floor(f.z)).getType().isOccluding(); i++) {
                f.y += 1;
            }
        }
        f.lastPX = lx;
        f.lastPZ = lz;
        double moved = Math.hypot(f.x - ox, f.z - oz);
        boolean walking = !snap && moved > 0.02;
        // regarde où il va en se déplaçant, sinon son maître
        float targetYaw = walking
                ? (float) Math.toDegrees(Math.atan2(-(f.x - ox), f.z - oz))
                : (float) Math.toDegrees(Math.atan2(-(p.getX() - f.x), p.getZ() - f.z));
        float delta = ((targetYaw - f.yaw) % 360 + 540) % 360 - 180;
        f.yaw += snap ? delta : delta * 0.2f;
        animateWalk(f, walking);
        double bob = f.pet.flying() ? Math.sin(tick * 0.1) * bobAmplitude : 0;
        Location to = new Location(world, f.x, f.y + bob, f.z, f.yaw, 0);
        if (f.carrier.getWorld() != world) {
            // un porteur avec passager ne change pas de monde : le nom est retiré puis recréé
            if (f.label != null && f.label.isValid()) {
                f.label.remove();
            }
            f.label = null;
            if (f.seat != null && f.seat.isValid()) {
                f.seat.remove();
            }
            f.seat = null;
            f.carrier.teleport(to);
            updateLabel(f);
            updateSeat(f);
        } else {
            f.carrier.teleport(to, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
        if (tick % 20 == 0) {
            updateLabel(f);
            updateSeat(f);
        }
        if (tick % 5 == 0) {
            updateLight(f, world);
        }
        Pet.Trail trail = f.pet.trail();
        if (trail != null && tick % trail.gap() == 0) {
            world.spawnParticle(trail.particle(), f.x, f.y + trail.yOffset(), f.z, trail.amount(), 0.1, 0.1, 0.1, 0);
        }
    }

    private void animateWalk(Follower f, boolean walking) {
        if (walking != f.walking && f.hasWalk) {
            if (walking) {
                f.tracker.animate(f.pet.walkAnimation());
            } else {
                f.tracker.stopAnimation(f.pet.walkAnimation());
            }
        }
        f.walking = walking;
    }

    // ------------------------------------------------------------------ mounts

    /** Seat of a mountable pet: an Interaction as high as its back, riding the carrier (removed when not wanted). */
    private void updateSeat(Follower f) {
        Pet.Mount mount = f.pet.mount();
        boolean wanted = mount != null && Pets.mountsEnabled();
        if (!wanted) {
            if (f.seat != null && f.seat.isValid()) {
                f.seat.eject();
                f.seat.remove();
            }
            f.seat = null;
            return;
        }
        if (f.seat != null && f.seat.isValid()) return;
        f.seat = f.carrier.getWorld().spawn(f.carrier.getLocation(), org.bukkit.entity.Interaction.class, i -> {
            i.setPersistent(false);
            i.setInteractionWidth((float) Math.max(1.2, Math.min(4.0, f.pet.size() * 0.6)));
            i.setInteractionHeight((float) Math.max(0.3, mount.seat()));
            i.setResponsive(true);
        });
        f.carrier.addPassenger(f.seat);
    }

    private static boolean ridden(Follower f) {
        if (f.horse != null) return f.horse.isValid() && f.horse.getPassengers().contains(f.owner);
        return f.seat != null && f.seat.isValid() && f.seat.getPassengers().contains(f.owner);
    }

    // ------------------------------------------------------------------ horse

    /** Back of a horse at scale 1 (where its rider sits), in blocks. */
    private static final double HORSE_SEAT = 1.32;
    /** Horse speed attribute to blocks per tick (an attribute of 0.225 makes about 9.7 blocks a second). */
    private static final double HORSE_SPEED = 43.17 / 20;

    /** Vanilla horse jump height (blocks) for a jump strength. */
    private static double jumpHeight(double s) {
        return -0.1817584952 * s * s * s + 3.689713992 * s * s + 2.128599134 * s - 0.343930367;
    }

    /** Jump strength that clears that height (bisection on the vanilla curve). */
    private static double jumpStrength(double height) {
        double lo = 0.1, hi = 2.0;
        for (int i = 0; i < 30; i++) {
            double mid = (lo + hi) / 2;
            if (jumpHeight(mid) < height) lo = mid; else hi = mid;
        }
        return hi;
    }

    /** Horses of ridden pets (their inventory, leash, clicks refused). */
    private final Set<UUID> horses = new HashSet<>();

    /** The owner gets on a real horse carrying the pet's model. */
    private boolean mountHorse(Follower f) {
        Pet.Mount mount = f.pet.mount();
        Location at = new Location(f.carrier.getWorld(), f.x, f.y, f.z, f.yaw, 0);
        double scale = Math.max(0.5, Math.min(4.0, mount.seat() / HORSE_SEAT));
        org.bukkit.entity.Horse horse = at.getWorld().spawn(at, org.bukkit.entity.Horse.class, h -> {
            h.setPersistent(false);
            h.setInvisible(true);
            h.setSilent(true);
            h.setInvulnerable(true);
            h.setCollidable(false);
            h.setAdult();
            h.setAgeLock(true);
            h.setTamed(true);
            h.setOwner(f.owner);
            h.setRemoveWhenFarAway(false);
            h.getInventory().setSaddle(invisibleSaddle());
            attribute(h, org.bukkit.attribute.Attribute.SCALE, scale);
            attribute(h, org.bukkit.attribute.Attribute.MOVEMENT_SPEED, mount.speed() / HORSE_SPEED);
            attribute(h, org.bukkit.attribute.Attribute.JUMP_STRENGTH, jumpStrength(Math.max(1.2, mount.jump())));
            attribute(h, org.bukkit.attribute.Attribute.STEP_HEIGHT, 1.0);
        });
        if (!horse.addPassenger(f.owner)) {
            horse.remove();
            return false;
        }
        horses.add(horse.getUniqueId());
        fr.influencraft.flux.api.client.Drivers.drive(horse, f.owner);
        f.horse = horse;
        // the seat would catch the rider's own clicks: back when he gets off
        if (f.seat != null && f.seat.isValid()) f.seat.remove();
        f.seat = null;
        f.hx = Double.NaN;
        f.modelDrop = Double.NaN;
        f.rideTicks = 0;
        // second passenger, behind the rider: his game moves the model with the horse
        if (!horse.addPassenger(f.carrier)) {
            Pets.plugin().getLogger().warning("Mount of " + f.owner.getName() + ": the model's carrier could not ride the"
                    + " horse, the model follows it from the server instead.");
        }
        return true;
    }

    /** The horse goes, the model back on the carrier where the horse stood. */
    private void dismountHorse(Follower f) {
        org.bukkit.entity.AbstractHorse horse = f.horse;
        f.horse = null;
        if (horse == null) return;
        horses.remove(horse.getUniqueId());
        fr.influencraft.flux.api.client.Drivers.release(horse);
        if (horse.isValid()) {
            Location at = horse.getLocation();
            f.x = at.getX();
            f.y = at.getY();
            f.z = at.getZ();
            f.yaw = horse.getBodyYaw();
            f.vy = 0;
            horse.eject();
            horse.remove();
        }
        f.carrier.leaveVehicle();
        lowerModel(f, 0);
        f.modelDrop = Double.NaN;
        if (f.carrier.isValid()) {
            f.carrier.teleport(new Location(f.carrier.getWorld(), f.x, f.y, f.z, f.yaw, 0),
                    io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
            f.walking = false;
            f.tracker.stopAnimation(f.pet.walkAnimation());
            updateSeat(f);
        }
    }

    private static void attribute(org.bukkit.entity.LivingEntity e, org.bukkit.attribute.Attribute a, double value) {
        var instance = e.getAttribute(a);
        if (instance != null) instance.setBaseValue(value);
    }

    /** Every bone of the pet's model moved down by {@code drop} blocks (0: back in place). */
    private static void lowerModel(Follower f, double drop) {
        float d = (float) drop;
        f.tracker.getPipeline().defaultPosition(d == 0 ? v -> v : v -> new Vector3f(v).sub(0, d, 0));
    }

    /** Ticks of the horse's speed the model is put ahead by, so it keeps up with the horse on the rider's screen. */
    private static final double MODEL_LEAD = 2;

    /** A saddle drawn with an equipment asset that does not exist: nothing shows on the invisible horse. */
    private static org.bukkit.inventory.ItemStack invisibleSaddle() {
        org.bukkit.inventory.ItemStack saddle = new org.bukkit.inventory.ItemStack(org.bukkit.Material.SADDLE);
        saddle.setData(io.papermc.paper.datacomponent.DataComponentTypes.EQUIPPABLE,
                io.papermc.paper.datacomponent.item.Equippable.equippable(EquipmentSlot.SADDLE)
                        .assetId(net.kyori.adventure.key.Key.key("fluxpets", "none")).build());
        return saddle;
    }

    /** Each tick of a ridden horse: the carrier (name, state) where the horse is, walk animation, the mole's dig. */
    private void rideHorse(Follower f) {
        org.bukkit.entity.AbstractHorse horse = f.horse;
        Player rider = f.owner;
        Location at = horse.getLocation();
        double ox = f.x, oz = f.z;
        org.bukkit.Input input = rider.getCurrentInput();
        double forward = (input.isForward() ? 1 : 0) - (input.isBackward() ? 1 : 0);
        double side = (input.isLeft() ? 1 : 0) - (input.isRight() ? 1 : 0);
        double factor = 1;
        if (forward != 0 || side != 0) {
            double yawRad = Math.toRadians(rider.getYaw());
            double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
            org.bukkit.util.Vector dir = new org.bukkit.util.Vector(fx * forward + fz * side, 0, fz * forward - fx * side);
            fr.influencraft.fluxpets.pets.event.PetMountMoveEvent event =
                    new fr.influencraft.fluxpets.pets.event.PetMountMoveEvent(rider, f.pet, at, dir.normalize());
            Bukkit.getPluginManager().callEvent(event);
            factor = event.getSpeedFactor();
        }
        var speed = horse.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
        double wanted = f.pet.mount().speed() / HORSE_SPEED * factor;
        if (speed != null && Math.abs(speed.getBaseValue() - wanted) > 1.0E-6) speed.setBaseValue(wanted);
        f.x = at.getX();
        f.y = at.getY();
        f.z = at.getZ();
        f.yaw = horse.getBodyYaw();
        animateWalk(f, Math.hypot(f.x - ox, f.z - oz) > 0.02);
        f.lastPX = f.x;
        f.lastPZ = f.z;
        if (f.carrier.getVehicle() == horse) {
            // the carrier sits on the horse's back: the model goes down by that height to stand on the ground
            double drop = f.carrier.getLocation().getY() - at.getY();
            if (Double.isNaN(f.modelDrop) || Math.abs(drop - f.modelDrop) > 1.0E-3) {
                f.modelDrop = drop;
                lowerModel(f, drop);
            }
            if (tick % 5 == 0) updateLight(f, at.getWorld());
            return;
        }
        if (++f.rideTicks == 5) {
            Pets.plugin().getLogger().warning("Mount of " + f.owner.getName() + ": the model's carrier is not on the"
                    + " horse (vehicle: " + f.carrier.getVehicle() + "), the model follows it from the server instead.");
        }
        if (!Double.isNaN(f.modelDrop)) {
            lowerModel(f, 0);
            f.modelDrop = Double.NaN;
        }
        // the model where the horse is, ahead by its speed (the server hears of the horse a little late)
        double lead = Double.isNaN(f.hx) ? 0 : MODEL_LEAD;
        double vx = lead == 0 ? 0 : at.getX() - f.hx, vy = lead == 0 ? 0 : at.getY() - f.hy, vz = lead == 0 ? 0 : at.getZ() - f.hz;
        f.hx = at.getX();
        f.hy = at.getY();
        f.hz = at.getZ();
        if (f.carrier.getWorld() == at.getWorld()) {
            f.carrier.teleport(new Location(at.getWorld(), f.x + vx * lead, f.y + Math.max(0, vy) * lead, f.z + vz * lead,
                    f.yaw, 0), io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
        if (tick % 5 == 0) updateLight(f, at.getWorld());
    }

    @org.bukkit.event.EventHandler
    public void onHorseInventory(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (event.getInventory().getHolder() instanceof org.bukkit.entity.AbstractHorse h && horses.contains(h.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onHorseClick(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (horses.contains(event.getRightClicked().getUniqueId())) event.setCancelled(true);
    }

    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onHorseLeash(org.bukkit.event.entity.PlayerLeashEntityEvent event) {
        if (horses.contains(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    /** Right click on a pet's seat: its owner rides it from the pet's mount level. */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onSeat(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof org.bukkit.entity.Interaction seat)) return;
        Follower f = bySeat(seat);
        if (f == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.equals(f.owner)) {
            PetMessages.send(player, "mount-other");
            return;
        }
        if (player.isInsideVehicle() || !seat.getPassengers().isEmpty()) return;
        int level = PetData.level(player, f.pet);
        if (level < f.pet.mount().level()) {
            PetMessages.send(player, "mount-level", "%pet%", f.pet.name(), "%level%", String.valueOf(f.pet.mount().level()));
            return;
        }
        if (f.pet.flying() ? seat.addPassenger(player) : mountHorse(f)) {
            f.vy = 0;
            PetMessages.send(player, "mounted", "%pet%", f.pet.name());
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_HORSE_SADDLE, 0.8f, 1.2f);
        }
    }

    private Follower bySeat(org.bukkit.entity.Interaction seat) {
        for (List<Follower> list : followers.values()) {
            for (Follower f : list) if (seat.equals(f.seat)) return f;
        }
        return null;
    }

    /**
     * The rider's keys move the pet like a horse: it faces where he looks at once, forward along that, sides at half
     * speed, back at a quarter, sprint, jump; one block steps, gravity, water and lava walked on, room kept for the
     * rider's head. Flying mounts go where the rider looks, jump to climb.
     */
    private void ride(Follower f) {
        Player rider = f.owner;
        Pet.Mount mount = f.pet.mount();
        World world = f.carrier.getWorld();
        org.bukkit.Input input = rider.getCurrentInput();
        double forward = (input.isForward() ? 1 : 0) - (input.isBackward() ? 1 : 0);
        double side = (input.isLeft() ? 1 : 0) - (input.isRight() ? 1 : 0);
        double yawRad = Math.toRadians(rider.getYaw());
        double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
        // horse handling: sides at half speed, back at a quarter
        double fwd = forward < 0 ? forward * 0.25 : forward, sd = side * 0.5;
        double dx = fx * fwd + fz * sd, dz = fz * fwd - fx * sd;
        double len = Math.hypot(dx, dz);
        double ox = f.x, oz = f.z;
        boolean onGround = !f.pet.flying() && f.y <= ground(world, f.x, f.y, f.z) + 1.0E-3;
        // room above the floor: the pet's body, and the rider's head on top of its seat
        double room = Math.max(1.6, mount.seat() + 1.8);
        if (len > 1.0E-3) {
            // the slower directions stay slower: only a diagonal (length > 1) is brought back to 1
            if (len > 1) {
                dx /= len;
                dz /= len;
            }
            double speed = mount.speed() * (input.isSprint() ? 1.3 : 1);
            fr.influencraft.fluxpets.pets.event.PetMountMoveEvent event = new fr.influencraft.fluxpets.pets.event.PetMountMoveEvent(rider, f.pet, new Location(world, f.x, f.y, f.z),
                    new org.bukkit.util.Vector(dx, 0, dz).normalize());
            Bukkit.getPluginManager().callEvent(event);
            speed *= event.getSpeedFactor();
            // one axis at a time: the pet slides along a wall instead of stopping
            if (free(world, f.x + dx * speed, f.y, f.z, room)) f.x += dx * speed;
            if (free(world, f.x, f.y, f.z + dz * speed, room)) f.z += dz * speed;
        }
        if (f.pet.flying()) {
            double climb = input.isJump() ? mount.speed() * 0.8
                    : -Math.sin(Math.toRadians(rider.getPitch())) * mount.speed() * forward;
            double ny = f.y + climb;
            if (!world.getBlockAt((int) Math.floor(f.x), (int) Math.floor(ny + 0.1), (int) Math.floor(f.z)).isSolid()
                    && !world.getBlockAt((int) Math.floor(f.x), (int) Math.floor(ny + mount.seat()), (int) Math.floor(f.z)).isSolid()) {
                f.y = ny;
            }
        } else {
            double floor = ground(world, f.x, f.y, f.z);
            if (onGround && input.isJump()) f.vy = Math.sqrt(2 * 0.08 * Math.max(0.5, mount.jump())) + 0.02;
            if (floor > f.y && floor - f.y <= 1.05) {
                // step up (smoothly)
                f.y = Math.min(floor, f.y + 0.5);
                f.vy = Math.max(0, f.vy);
            } else {
                f.vy = (f.vy - 0.08) * 0.98;
                f.y += f.vy;
                if (f.y <= floor) {
                    f.y = floor;
                    f.vy = 0;
                }
            }
        }
        // like a horse: the body faces where the rider looks, at once, moving or not
        f.yaw = rider.getYaw();
        animateWalk(f, Math.hypot(f.x - ox, f.z - oz) > 0.02);
        f.lastPX = f.x;
        f.lastPZ = f.z;
        f.carrier.teleport(new Location(world, f.x, f.y, f.z, f.yaw, 0),
                io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        if (tick % 5 == 0) updateLight(f, world);
    }

    /** Top of the ground under this point (blocks up to one above, liquids count as ground), y - 8 when none. */
    private static double ground(World world, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return y;
        int top = (int) Math.floor(y + 1.05);
        for (int by = top; by >= top - 9; by--) {
            Block block = world.getBlockAt(bx, by, bz);
            if (block.isLiquid()) return by + 0.9;
            if (!block.isPassable()) {
                double max = block.getBoundingBox().getMaxY();
                return max > by ? max : by + 1;
            }
        }
        return y - 8;
    }

    /** The pet may stand there: at most one block higher than now, that much room above the floor. */
    private static boolean free(World world, double x, double y, double z, double room) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return false;
        double floor = ground(world, x, y, z);
        if (floor - y > 1.05) return false;
        double base = Math.max(y, floor);
        for (double h = 0.1; h < room; h += 0.75) {
            Block block = world.getBlockAt(bx, (int) Math.floor(base + h), bz);
            if (!block.isPassable() && !block.isLiquid()) return false;
        }
        return true;
    }

    /**
     * Lumière du modèle : les item displays de BetterModel prennent celle du bloc de leur origine, nulle quand
     * l'origine (les pieds) touche un bloc (dalle, terre labourée, tapis, bord d'un bloc plein), d'où un familier
     * tout noir. On lui impose la vraie lumière de l'endroit, mesurée au milieu du familier et un bloc au-dessus
     * (la plus forte des deux), envoyée seulement quand elle change.
     */
    private void updateLight(Follower f, World world) {
        int bx = (int) Math.floor(f.x);
        int bz = (int) Math.floor(f.z);
        Block middle = world.getBlockAt(bx, (int) Math.floor(f.y + 0.4), bz);
        Block above = middle.getRelative(0, 1, 0);
        int block = Math.max(middle.getLightFromBlocks(), above.getLightFromBlocks());
        int sky = Math.max(middle.getLightFromSky(), above.getLightFromSky());
        if (block != f.blockLight || sky != f.skyLight) {
            f.blockLight = block;
            f.skyLight = sky;
            f.tracker.update(TrackerUpdateAction.brightness(block, sky));
        }
    }

    private void updateLabel(Follower f) {
        Config s = Pets.settings();
        if (!s.getBool("pet-entity.show-hologram")) {
            if (f.label != null && f.label.isValid()) {
                f.label.remove();
            }
            f.label = null;
            return;
        }
        if (f.label == null || !f.label.isValid()) {
            f.label = f.carrier.getWorld().spawn(f.carrier.getLocation(), TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setTransformation(new Transformation(new Vector3f(0, (float) labelHeight, 0), new AxisAngle4f(),
                        new Vector3f(1, 1, 1), new AxisAngle4f()));
            });
            f.carrier.addPassenger(f.label);
        }
        f.label.text(Texts.of(s.getString("pet-entity.name").replace("%pet%", f.pet.name())
                .replace("%level%", String.valueOf(PetData.level(f.owner, f.pet)))
                .replace("%player%", f.owner.getName())));
    }

    private void remove(Follower f) {
        if (f.horse != null) {
            horses.remove(f.horse.getUniqueId());
            fr.influencraft.flux.api.client.Drivers.release(f.horse);
            if (f.horse.isValid()) {
                f.horse.eject();
                f.horse.remove();
            }
            f.horse = null;
        }
        if (!f.tracker.isClosed()) {
            f.tracker.close();
        }
        if (f.label != null && f.label.isValid()) {
            f.label.remove();
        }
        if (f.seat != null && f.seat.isValid()) {
            f.seat.eject();
            f.seat.remove();
        }
        if (f.carrier.isValid()) {
            f.carrier.remove();
        }
    }

    /** Arrêt du plugin ou rechargement : retire tous les modèles. */
    public void shutdown() {
        followers.values().forEach(list -> list.forEach(this::remove));
        followers.clear();
    }
}
