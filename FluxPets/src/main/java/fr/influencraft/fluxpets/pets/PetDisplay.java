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
 */
public final class PetDisplay {

    private static final class Follower {
        final Player owner;
        final Pet pet;
        final ArmorStand carrier;
        final EntityTracker tracker;
        final boolean hasWalk;
        TextDisplay label;
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
                || player.isInvisible() || player.getGameMode() == GameMode.SPECTATOR || PetData.hidden(player)) {
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
                if (i == 0) {
                    // le premier suit le joueur, dans la zone de confort distance-min / distance-max
                    move(f, p.getX(), p.getZ(), minDistance, maxDistance, null);
                } else {
                    // les suivants marchent en file : chacun suit le précédent, entre ecart-file-min et -max
                    Follower lead = list.get(i - 1);
                    move(f, lead.x, lead.z, gapMin, gapMax, lead);
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
        double radius = last == null ? (minDistance + maxDistance) / 2 : (gapMin + gapMax) / 2;
        Location start = new Location(p.getWorld(), bx + Math.sin(yaw) * radius, p.getY(), bz - Math.cos(yaw) * radius);
        start.setY(targetY(pet, p, start.getX(), start.getZ()));
        start.setYaw(p.getYaw());
        ArmorStand carrier = stand(start);
        EntityTracker tracker = renderer.getOrCreate(BukkitAdapter.adapt(carrier));
        Follower f = new Follower(owner, pet, carrier, tracker, renderer.animation("walk").isPresent());
        f.x = start.getX();
        f.y = start.getY();
        f.z = start.getZ();
        f.yaw = start.getYaw();
        f.lastPX = bx;
        f.lastPZ = bz;
        updateLabel(f);
        return f;
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
        if (walking != f.walking && f.hasWalk) {
            if (walking) {
                f.tracker.animate("walk");
            } else {
                f.tracker.stopAnimation("walk");
            }
        }
        f.walking = walking;
        double bob = f.pet.flying() ? Math.sin(tick * 0.1) * bobAmplitude : 0;
        Location to = new Location(world, f.x, f.y + bob, f.z, f.yaw, 0);
        if (f.carrier.getWorld() != world) {
            // un porteur avec passager ne change pas de monde : le nom est retiré puis recréé
            if (f.label != null && f.label.isValid()) {
                f.label.remove();
            }
            f.label = null;
            f.carrier.teleport(to);
            updateLabel(f);
        } else {
            f.carrier.teleport(to, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
        if (tick % 20 == 0) {
            updateLabel(f);
        }
        if (tick % 5 == 0) {
            updateLight(f, world);
        }
        Pet.Trail trail = f.pet.trail();
        if (trail != null && tick % trail.gap() == 0) {
            world.spawnParticle(trail.particle(), f.x, f.y + trail.yOffset(), f.z, trail.amount(), 0.1, 0.1, 0.1, 0);
        }
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
        if (!f.tracker.isClosed()) {
            f.tracker.close();
        }
        if (f.label != null && f.label.isValid()) {
            f.label.remove();
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
