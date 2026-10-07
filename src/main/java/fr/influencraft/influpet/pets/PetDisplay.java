package fr.influencraft.influpet.pets;

import com.willfp.eco.core.config.interfaces.Config;
import fr.influencraft.influpet.Texts;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.EntityTracker;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Modèle 3D (BetterModel) du familier actif, qui suit vraiment son maître (au lieu du point fixe collé à la
 * caméra d'EcoPets) :
 * - joueur à l'arrêt : le familier reste où il est, revient au-delà de distance-max, s'écarte s'il est collé,
 *   regarde le joueur, animation idle ;
 * - joueur en marche : il le rejoint avec un peu de retard, sur le côté et en arrière selon la direction de
 *   MARCHE, regarde où il va, animation walk ;
 * - au-delà de teleport-distance ou dans un autre monde : réapparaît à côté du joueur.
 * Familier « vol: true » : flotte à hauteur fixe au-dessus des pieds du joueur, avec un léger flottement ;
 * « vol: false » : posé sur le sol sous sa position.
 * Pas de modèle pour un familier caché (menu), un joueur mort, invisible ou spectateur. Nom au-dessus du modèle
 * (TextDisplay passager), traînée du familier (trail). Porte-armure marker : aucune hitbox, jamais sauvegardé.
 * Réglages de suivi : section familier-suivi de config.yml. Chargée seulement si BetterModel est actif.
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
        double dirX, dirZ;
        double lastPX, lastPZ;
        boolean walking;

        Follower(Player owner, Pet pet, ArmorStand carrier, EntityTracker tracker, boolean hasWalk) {
            this.owner = owner;
            this.pet = pet;
            this.carrier = carrier;
            this.tracker = tracker;
            this.hasWalk = hasWalk;
        }
    }

    private final Map<UUID, Follower> followers = new HashMap<>();
    /** Modèles introuvables déjà signalés (un avertissement par modèle, pas un par tick). */
    private final Set<String> missing = new HashSet<>();
    private long tick;

    private double side, back, height, minDistance, maxDistance, snapDistance, smoothing, minSpeed, bobAmplitude,
            labelHeight;

    public PetDisplay() {
        reload();
        Bukkit.getScheduler().runTaskTimer(Pets.plugin(), this::tickAll, 1L, 1L);
    }

    public void reload() {
        ConfigurationSection c = Pets.plugin().getConfig().getConfigurationSection("familier-suivi");
        side = get(c, "cote", 1.0);
        back = get(c, "arriere", 1.2);
        height = get(c, "hauteur", 0.9);
        minDistance = get(c, "distance-min", 1.0);
        maxDistance = get(c, "distance-max", 3.5);
        snapDistance = get(c, "teleport-distance", 16);
        smoothing = get(c, "lissage", 0.18);
        minSpeed = get(c, "vitesse-min", 0.45);
        bobAmplitude = get(c, "flottement", 0.06);
        labelHeight = get(c, "hauteur-nom", 1.3);
        missing.clear();
        // familiers rechargés : nouveaux objets Pet, les modèles sont recréés au tick suivant
        shutdown();
    }

    private static double get(ConfigurationSection c, String path, double def) {
        return c == null ? def : c.getDouble(path, def);
    }

    private Pet wanted(Player player) {
        if (!Pets.enabled() || !Pets.settings().getBool("pet-entity.enabled") || player.isDead()
                || player.isInvisible() || player.getGameMode() == GameMode.SPECTATOR || PetData.hidden(player)) {
            return null;
        }
        return PetData.active(player);
    }

    private void tickAll() {
        tick++;
        for (Iterator<Follower> it = followers.values().iterator(); it.hasNext(); ) {
            Follower f = it.next();
            if (!f.owner.isOnline() || !f.carrier.isValid() || !f.pet.equals(wanted(f.owner))) {
                remove(f);
                it.remove();
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            Follower f = followers.get(player.getUniqueId());
            if (f == null) {
                Pet pet = wanted(player);
                if (pet != null) {
                    f = spawn(player, pet);
                }
            }
            if (f != null) {
                move(f);
            }
        }
    }

    private Follower spawn(Player owner, Pet pet) {
        var renderer = BetterModel.modelOrNull(pet.model());
        if (renderer == null) {
            if (missing.add(pet.model())) {
                Pets.plugin().getLogger().warning("Modèle BetterModel introuvable pour le familier " + pet.id()
                        + " : " + pet.model());
            }
            return null;
        }
        Location p = owner.getLocation();
        double fx = -Math.sin(Math.toRadians(p.getYaw()));
        double fz = Math.cos(Math.toRadians(p.getYaw()));
        Location start = p.clone().add(-fx * back + fz * side, 0, -fz * back - fx * side);
        start.setY(targetY(pet, p, start.getX(), start.getZ()));
        start.setYaw(p.getYaw());
        ArmorStand carrier = stand(start);
        EntityTracker tracker = renderer.getOrCreate(BukkitAdapter.adapt(carrier));
        Follower f = new Follower(owner, pet, carrier, tracker, renderer.animation("walk").isPresent());
        f.x = start.getX();
        f.y = start.getY();
        f.z = start.getZ();
        f.yaw = start.getYaw();
        f.dirX = fx;
        f.dirZ = fz;
        f.lastPX = p.getX();
        f.lastPZ = p.getZ();
        followers.put(owner.getUniqueId(), f);
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

    private void move(Follower f) {
        Location p = f.owner.getLocation();
        World world = p.getWorld();
        // déplacement du joueur depuis le tick précédent : sa direction de marche, pas son regard
        double pdx = p.getX() - f.lastPX;
        double pdz = p.getZ() - f.lastPZ;
        f.lastPX = p.getX();
        f.lastPZ = p.getZ();
        double pSpeed = Math.hypot(pdx, pdz);
        boolean playerMoving = pSpeed > 0.03;
        if (playerMoving) {
            f.dirX += (pdx / pSpeed - f.dirX) * 0.3;
            f.dirZ += (pdz / pSpeed - f.dirZ) * 0.3;
            double n = Math.hypot(f.dirX, f.dirZ);
            if (n > 1.0E-4) {
                f.dirX /= n;
                f.dirZ /= n;
            }
        }
        double tx = f.x;
        double tz = f.z;
        double dx = f.x - p.getX();
        double dz = f.z - p.getZ();
        double dist = Math.hypot(dx, dz);
        boolean snap = f.carrier.getWorld() != world || dist > snapDistance || Math.abs(f.y - p.getY()) > snapDistance;
        if (playerMoving || snap) {
            // en arrière et sur le côté par rapport à la direction de marche
            tx = p.getX() - f.dirX * back + f.dirZ * side;
            tz = p.getZ() - f.dirZ * back - f.dirX * side;
        } else if (dist > maxDistance) {
            tx = p.getX() + dx / dist * (maxDistance - 1);
            tz = p.getZ() + dz / dist * (maxDistance - 1);
        } else if (dist < minDistance) {
            double ux = dist > 1.0E-3 ? dx / dist : f.dirZ;
            double uz = dist > 1.0E-3 ? dz / dist : -f.dirX;
            tx = p.getX() + ux * (minDistance + 0.3);
            tz = p.getZ() + uz * (minDistance + 0.3);
        }
        double ty = targetY(f.pet, p, tx, tz);
        // familier volant : ne pas viser l'intérieur d'un bloc plein, on monte d'un ou deux blocs au besoin
        for (int i = 0; f.pet.flying() && i < 2 && world.getBlockAt((int) Math.floor(tx), (int) Math.floor(ty),
                (int) Math.floor(tz)).getType().isOccluding(); i++) {
            ty += 1;
        }
        double ox = f.x;
        double oz = f.z;
        if (snap) {
            f.x = tx;
            f.y = ty;
            f.z = tz;
        } else {
            double sx = (tx - f.x) * smoothing;
            double sz = (tz - f.z) * smoothing;
            double step = Math.hypot(sx, sz);
            double cap = Math.max(minSpeed, pSpeed * 1.3);
            if (step > cap) {
                sx *= cap / step;
                sz *= cap / step;
            }
            f.x += sx;
            f.z += sz;
            f.y += (ty - f.y) * (f.pet.flying() ? 0.15 : 0.4);
        }
        double moved = Math.hypot(f.x - ox, f.z - oz);
        boolean walking = !snap && moved > 0.03;
        // regarde où il va en marchant, sinon son maître
        float targetYaw = walking
                ? (float) Math.toDegrees(Math.atan2(-(f.x - ox), f.z - oz))
                : (float) Math.toDegrees(Math.atan2(-(p.getX() - f.x), p.getZ() - f.z));
        float delta = ((targetYaw - f.yaw) % 360 + 540) % 360 - 180;
        f.yaw += snap ? delta : delta * 0.25f;
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
        Pet.Trail trail = f.pet.trail();
        if (trail != null && tick % trail.gap() == 0) {
            world.spawnParticle(trail.particle(), f.x, f.y + trail.yOffset(), f.z, trail.amount(), 0.1, 0.1, 0.1, 0);
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
        followers.values().forEach(this::remove);
        followers.clear();
    }
}
