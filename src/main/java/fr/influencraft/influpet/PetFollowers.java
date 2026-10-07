package fr.influencraft.influpet;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.EntityTracker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Familiers BetterModel qui suivent vraiment leur maître, au lieu d'être accrochés à sa caméra.
 *
 * EcoPets téléporte à chaque tick l'entité du familier sur un point fixe calculé depuis le regard du joueur
 * (PetDisplay.tickPlayer, décompilé : hauteur des yeux, location-*-offset tournés avec le yaw) : tourner la
 * tête fait tourner le familier autour de soi. Aucune option ne change ça. On laisse donc à EcoPets une ANCRE
 * invisible sans modèle (ModelPet.spawn) qu'il déplace à sa guise, et le modèle est porté par un autre
 * porte-armure que l'on déplace nous-mêmes :
 * - joueur à l'arrêt : le familier reste où il est (tourner la tête ne le bouge plus), revient s'il est trop
 *   loin, s'écarte s'il est collé au joueur, regarde le joueur, animation idle ;
 * - joueur en marche : il le rejoint avec un peu de retard, sur le côté et en arrière selon la DIRECTION DE
 *   MARCHE (pas le regard), regarde où il va, animation walk ;
 * - trop loin (téléportation, chute) ou autre monde : réapparaît à côté du joueur.
 * Le nom (hologramme) et la traînée d'EcoPets seraient restés sur l'ancre : show-hologram est coupé côté
 * EcoPets et le nom est un TextDisplay passager du porteur ; la traînée des légendaires vient de familiers.yml.
 * L'ancre disparaît (familier rangé, joueur invisible ou mort, déconnexion) : le porteur aussi.
 * Porte-armures « marker » : aucune hitbox, ils n'interceptent ni clic ni coup.
 */
final class PetFollowers {

    private static final class Follower {
        final Player owner;
        final String petId;
        ArmorStand anchor;
        final ArmorStand carrier;
        final EntityTracker tracker;
        TextDisplay label;
        double x, y, z;
        float yaw;
        double dirX, dirZ;
        double lastPX, lastPZ;
        boolean walking;
        boolean hasWalk;

        Follower(Player owner, String petId, ArmorStand anchor, ArmorStand carrier, EntityTracker tracker) {
            this.owner = owner;
            this.petId = petId;
            this.anchor = anchor;
            this.carrier = carrier;
            this.tracker = tracker;
        }
    }

    private record Trail(Particle particle, int amount, int gap, double yOffset) {
    }

    private final InfluPet plugin;
    private final Map<UUID, Follower> followers = new HashMap<>();
    private final Map<String, Trail> trails = new HashMap<>();
    private long tick;

    // réglages (section familier-suivi de config.yml, valeurs par défaut ci-dessous)
    private double side, back, height, minDistance, maxDistance, snapDistance, smoothing, minSpeed, bobAmplitude,
            labelHeight;
    private String nameFormat = "%pet%&f (Niv. %level%)";

    PetFollowers(InfluPet plugin) {
        this.plugin = plugin;
        reload();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    void reload() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("familier-suivi");
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
        // nom au-dessus du familier : même format que l'hologramme d'EcoPets (pet-entity.name)
        File eco = new File(plugin.getDataFolder().getParentFile(), "EcoPets/config.yml");
        nameFormat = YamlConfiguration.loadConfiguration(eco).getString("pet-entity.name", nameFormat);
        // traînées des légendaires (familiers.yml, généré par appliquer_effets.py)
        trails.clear();
        ConfigurationSection t = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "familiers.yml"))
                .getConfigurationSection("trainees");
        if (t != null) {
            for (String id : t.getKeys(false)) {
                try {
                    trails.put(id, new Trail(Particle.valueOf(t.getString(id + ".particule", "flame").toUpperCase()),
                            t.getInt(id + ".nombre", 2), Math.max(1, t.getInt(id + ".intervalle", 2)),
                            t.getDouble(id + ".hauteur", 0)));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Traînée du familier " + id + " : particule inconnue.");
                }
            }
        }
    }

    private static double get(ConfigurationSection c, String path, double def) {
        return c == null ? def : c.getDouble(path, def);
    }

    /** Ancre posée par EcoPets pour ce joueur : le modèle suit le joueur sur son propre porteur. */
    void attach(Player owner, String petId, String model, ArmorStand anchor) {
        Follower old = followers.get(owner.getUniqueId());
        if (old != null && old.petId.equals(petId) && old.carrier.isValid()
                && old.carrier.getWorld() == owner.getWorld()) {
            // EcoPets a recréé son ancre (téléportation, rechargement) : on garde le même porteur
            old.anchor = anchor;
            return;
        }
        if (old != null) {
            remove(old);
            followers.remove(owner.getUniqueId());
        }
        var renderer = BetterModel.modelOrNull(model);
        if (renderer == null) {
            plugin.getLogger().warning("Modèle BetterModel introuvable pour le familier " + petId + " : " + model);
            return;
        }
        Location p = owner.getLocation();
        double fx = -Math.sin(Math.toRadians(p.getYaw()));
        double fz = Math.cos(Math.toRadians(p.getYaw()));
        Location start = p.clone().add(-fx * back + fz * side, height, -fz * back - fx * side);
        start.setYaw(p.getYaw());
        ArmorStand carrier = stand(start);
        EntityTracker tracker = renderer.getOrCreate(BukkitAdapter.adapt(carrier));
        Follower f = new Follower(owner, petId, anchor, carrier, tracker);
        f.x = start.getX();
        f.y = start.getY();
        f.z = start.getZ();
        f.yaw = start.getYaw();
        f.dirX = fx;
        f.dirZ = fz;
        f.lastPX = p.getX();
        f.lastPZ = p.getZ();
        f.hasWalk = renderer.animation("walk").isPresent();
        followers.put(owner.getUniqueId(), f);
        updateLabel(f);
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

    private TextDisplay label(ArmorStand carrier) {
        TextDisplay label = carrier.getWorld().spawn(carrier.getLocation(), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTransformation(new Transformation(new Vector3f(0, (float) labelHeight, 0), new AxisAngle4f(),
                    new Vector3f(1, 1, 1), new AxisAngle4f()));
        });
        carrier.addPassenger(label);
        return label;
    }

    private void tickAll() {
        tick++;
        for (Iterator<Follower> it = followers.values().iterator(); it.hasNext(); ) {
            Follower f = it.next();
            if (!f.owner.isOnline() || f.anchor == null || !f.anchor.isValid() || !f.carrier.isValid()) {
                remove(f);
                it.remove();
                continue;
            }
            move(f);
        }
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
        double ty = p.getY() + height;
        double dx = f.x - p.getX();
        double dz = f.z - p.getZ();
        double dist = Math.hypot(dx, dz);
        boolean snap = f.carrier.getWorld() != world || dist > snapDistance || Math.abs(f.y - ty) > snapDistance;
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
        // ne pas viser l'intérieur d'un bloc plein : on monte d'un ou deux blocs au besoin
        for (int i = 0; i < 2 && world.getBlockAt((int) Math.floor(tx), (int) Math.floor(ty), (int) Math.floor(tz))
                .getType().isOccluding(); i++) {
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
            f.y += (ty - f.y) * 0.15;
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
        double bob = Math.sin(tick * 0.1) * bobAmplitude;
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
        Trail trail = trails.get(f.petId);
        if (trail != null && tick % trail.gap() == 0) {
            world.spawnParticle(trail.particle(), f.x, f.y + trail.yOffset(), f.z, trail.amount(), 0.1, 0.1, 0.1, 0);
        }
    }

    private void updateLabel(Follower f) {
        if (f.label == null || !f.label.isValid()) {
            f.label = label(f.carrier);
        }
        var info = PetHook.pet(f.petId);
        int level = PetHook.activeLevel(f.owner, f.petId);
        String text = nameFormat.replace("%pet%", info == null ? f.petId : info.name())
                .replace("%level%", String.valueOf(level))
                .replace("%player%", f.owner.getName());
        f.label.text(Texts.of(text));
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

    /** Arrêt du plugin : retire tous les porteurs. */
    void shutdown() {
        followers.values().forEach(this::remove);
        followers.clear();
    }

    /** Joueur propriétaire de l'ancre qu'EcoPets vient de créer : le plus proche avec ce familier actif. */
    static Player ownerOf(Location anchor, String petId) {
        Player best = null;
        double bestDist = 6 * 6;
        for (Player player : anchor.getWorld().getPlayers()) {
            if (!petId.equalsIgnoreCase(PetHook.activeId(player))) {
                continue;
            }
            double d = player.getLocation().distanceSquared(anchor);
            if (d < bestDist) {
                bestDist = d;
                best = player;
            }
        }
        return best;
    }
}
