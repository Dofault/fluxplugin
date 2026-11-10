package fr.influencraft.fluxdungeons.mob;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Abilities of farm creatures (the Harvest Hamster first), built around the crops of the arena: what it eats becomes
 * its ammunition, what the players uproot first is lost to it. Each one follows its model's animation tick for tick
 * (timings in docs/technique/boss/hamster-moissonneur.md):
 * <ul>
 *   <li>{@code harvest}: runs to the thickest patch of crops, eats them bite by bite (they disappear), cheeks
 *   swelling, then turns to the player and spits everything in up to three salvoes; the more it ate, the more
 *   projectiles ({@code per-crop}, {@code max-shots}) and the harder they hit. Starts with its cheeks already full
 *   ({@code start-reserve}). With no crop left it sniffs around once, annoyed, and gives this ability up;</li>
 *   <li>{@code rolling_charge}: curls into a ball and rolls at the player along a line locked when the roll starts
 *   (dodge sideways); bowls over whoever it meets, crushes crops on its way; into a wall it is stunned for a while;</li>
 *   <li>{@code crush_leap}: a ring marks where it will land, it leaps there and lands in a shockwave
 *   ({@code radius});</li>
 *   <li>{@code root_eruption}: eyes shut, gathers its magic, slams the ground: cracks run under the players and
 *   roots burst out of each crack (damage, slowness, a lingering hazard while they stand);</li>
 *   <li>{@code great_feast}: the ultimate. Draws the energy of every crop left (they wither away), swells, and spits
 *   three widening waves of glowing carrots; weaker without crops, it then burns its own life instead
 *   ({@code self-cost}). Exhausted afterwards: the window to hit it;</li>
 *   <li>{@code phase_shift}: shakes itself, a text on screen, a little faster from then on ({@code speed-bonus});</li>
 *   <li>{@code entrance}: its first move ({@code opening: true}): dashes to its crops, sniffs, sits up at the player;</li>
 *   <li>{@code scurry}: runs at a player who keeps away.</li>
 * </ul>
 * The cheeks of the model come in two sets of bones (Cheek_* full, Cheek_*_empty): this class shows the right one.
 */
final class HarvestPowers {

    static final Set<String> TYPES = Set.of("harvest", "rolling_charge", "crush_leap", "root_eruption", "great_feast",
            "phase_shift", "entrance", "scurry");
    static final Set<Material> CROPS = Set.of(Material.CARROTS, Material.POTATOES, Material.WHEAT, Material.BEETROOTS);
    private static final List<String> FULL = List.of("Cheek_right", "Cheek_left");
    private static final List<String> EMPTY = List.of("Cheek_right_empty", "Cheek_left_empty");
    private static final String TAG = "fluxdungeons_fx";
    private static final Color GOLD = Color.fromRGB(0xFFD94D), HARVEST = Color.fromRGB(0xF0A800),
            EARTH = Color.fromRGB(0x8A5A2B);

    /** What a monster has in its cheeks, and what it knows of its field. */
    private static final class Larder {
        boolean full = true;
        int reserve;
        boolean mourned;
        final List<Material> eaten = new ArrayList<>();
        List<Block> crops = new ArrayList<>();
        long scanned = -1;
        Location home;
    }

    private final Plugin plugin;
    private final Fx fx;
    private final Map<UUID, Larder> larders = new HashMap<>();
    private final Set<Entity> alive = new HashSet<>();

    HarvestPowers(Plugin plugin, Fx fx) {
        this.plugin = plugin;
        this.fx = fx;
    }

    boolean cast(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        if (!TYPES.contains(ab.type())) return false;
        Larder own = larder(c.entity(), ab);
        if (own.home == null) own.home = c.home();
        switch (ab.type()) {
            case "harvest" -> harvest(ab, c, target);
            case "rolling_charge" -> roll(ab, c, target);
            case "crush_leap" -> leap(ab, c, target);
            case "root_eruption" -> roots(ab, c);
            case "great_feast" -> feast(ab, c, target);
            case "phase_shift" -> phaseShift(ab, c);
            case "entrance" -> entrance(ab, c, target);
            case "scurry" -> scurry(ab, c, target);
            default -> {
            }
        }
        return true;
    }

    /** False when the ability has nothing to work with (no crop, nothing in the cheeks, already sulked once). */
    boolean worth(MobTemplate.Ability ab, LivingEntity e, Location home) {
        if (!"harvest".equals(ab.type())) return true;
        Larder l = larder(e, ab);
        if (l.home == null) l.home = home;
        return (l.full && l.reserve > 0) || !crops(e, ab, l).isEmpty() || !l.mourned;
    }

    /** Every moving piece left (plugin stopping). */
    void clear() {
        for (Entity d : alive) if (d.isValid()) d.remove();
        alive.clear();
        larders.clear();
    }

    // ---------------------------------------------------------------- shared

    private Larder larder(LivingEntity e, MobTemplate.Ability ab) {
        larders.keySet().removeIf(id -> {
            Entity x = Bukkit.getEntity(id);
            return x == null || !x.isValid();
        });
        return larders.computeIfAbsent(e.getUniqueId(), k -> {
            Larder l = new Larder();
            l.reserve = (int) ab.number("start-reserve", 4);
            for (int i = 0; i < l.reserve; i++) l.eaten.add(Material.CARROTS);
            return l;
        });
    }

    /**
     * Its field: the crops joined to the place where it appeared (gaps of one block allowed, water channels of a
     * farm), found once; crops never grow back during a run, so afterwards only what is still a crop is kept.
     */
    private List<Block> crops(LivingEntity e, MobTemplate.Ability ab, Larder l) {
        if (l.scanned < 0) {
            l.scanned = Bukkit.getCurrentTick();
            l.crops = field(home(e, l), (int) ab.number("field-radius", 40), (int) ab.number("field-seed", 6));
        }
        l.crops.removeIf(b -> !CROPS.contains(b.getType()));
        return l.crops;
    }

    private Location home(LivingEntity e, Larder l) {
        return l.home != null ? l.home : e.getLocation();
    }

    /** Crops joined to those within {@code seed} blocks of a place, at most {@code radius} blocks from it. */
    static List<Block> field(Location from, int radius, int seed) {
        World w = from.getWorld();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        Set<Block> seen = new HashSet<>();
        for (int x = -seed; x <= seed; x++) {
            for (int z = -seed; z <= seed; z++) {
                for (int y = -2; y <= 2; y++) {
                    Block b = w.getBlockAt(from.getBlockX() + x, from.getBlockY() + y, from.getBlockZ() + z);
                    if (CROPS.contains(b.getType()) && seen.add(b)) queue.add(b);
                }
            }
        }
        List<Block> out = new ArrayList<>();
        while (!queue.isEmpty() && out.size() < 4000) {
            Block b = queue.poll();
            out.add(b);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dz == 0 && dy == 0) continue;
                        Block n = b.getRelative(dx, dy, dz);
                        if (!CROPS.contains(n.getType()) || seen.contains(n)) continue;
                        if (Math.abs(n.getX() - from.getBlockX()) > radius || Math.abs(n.getZ() - from.getBlockZ()) > radius) continue;
                        seen.add(n);
                        queue.add(n);
                    }
                }
            }
        }
        return out;
    }

    /** A flash of light (coloured where the game allows it). */
    private static void flash(Location at) {
        if (Particle.FLASH.getDataType() == Color.class) at.getWorld().spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0, GOLD);
        else if (Particle.FLASH.getDataType() == Void.class) at.getWorld().spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0);
    }

    private static List<Player> fighters(LivingEntity e, double within) {
        List<Player> out = new ArrayList<>();
        for (Player p : e.getWorld().getPlayers()) {
            if (Mobs.fighting(p) && p.getLocation().distanceSquared(e.getLocation()) <= within * within) out.add(p);
        }
        return out;
    }

    private static float yawTo(Vector dir) {
        return (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
    }

    private static Vector flat(Location from, Location to) {
        Vector v = to.toVector().subtract(from.toVector()).setY(0);
        return v.lengthSquared() < 1e-6 ? from.getDirection().setY(0).normalize() : v.normalize();
    }

    /** Turns the monster (the model follows) towards a place. */
    private static void face(LivingEntity e, Location at) {
        e.setRotation(yawTo(flat(e.getLocation(), at)), 0);
    }

    /** Height of the ground under (x, z), searched from a little above y down to 4 blocks below. */
    private static double groundY(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int by = (int) Math.floor(y + 1.2); by >= (int) Math.floor(y) - 4; by--) {
            Block b = w.getBlockAt(bx, by, bz);
            if (b.isCollidable()) return by + b.getBoundingBox().getHeight();
        }
        return y;
    }

    /** Moves the monster one step towards a place on the ground (teleport, facing where it goes). */
    private static Location step(LivingEntity e, Vector dir, double speed) {
        Location at = e.getLocation().clone().add(dir.clone().multiply(speed));
        at.setY(groundY(e.getWorld(), at.getX(), at.getY(), at.getZ()));
        at.setYaw(yawTo(dir));
        at.setPitch(0);
        e.teleport(at);
        return at;
    }

    /** True when a wall stands right in front, at the height of its body. */
    private static boolean wallAhead(LivingEntity e, Vector dir, double ahead) {
        Location front = e.getLocation().clone().add(dir.clone().multiply(ahead));
        for (double h : new double[]{0.6, 1.4}) {
            Block b = front.clone().add(0, h, 0).getBlock();
            if (b.isCollidable() && !CROPS.contains(b.getType())) return true;
        }
        return false;
    }

    /** Crops around a place are crushed (gone, not eaten). */
    private static void crush(Location at, double radius) {
        World w = at.getWorld();
        int r = (int) Math.ceil(radius);
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z > radius * radius) continue;
                for (int y = -1; y <= 1; y++) {
                    Block b = w.getBlockAt(at.getBlockX() + x, at.getBlockY() + y, at.getBlockZ() + z);
                    if (!CROPS.contains(b.getType())) continue;
                    w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.3, 0.5), 8, 0.25, 0.15, 0.25, 0, b.getBlockData());
                    b.setType(Material.AIR);
                }
            }
        }
    }

    private static void title(LivingEntity e, MobTemplate.Ability ab, int ticks) {
        String title = ab.text("title", ""), subtitle = ab.text("subtitle", "");
        if (title.isBlank() && subtitle.isBlank()) return;
        for (Player p : fighters(e, 48)) {
            p.showTitle(net.kyori.adventure.title.Title.title(fr.influencraft.flux.api.text.Texts.parse(title),
                    fr.influencraft.flux.api.text.Texts.parse(subtitle), net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(Math.max(500, ticks * 50L)),
                            java.time.Duration.ofMillis(400))));
        }
    }

    /** Where the mouth of the model is ({@code mouth: [forwards, height]} in blocks from the entity). */
    private static Location mouth(LivingEntity e, MobTemplate.Ability ab) {
        Vector dir = e.getLocation().getDirection().setY(0).normalize();
        return e.getLocation().clone().add(dir.multiply(number(ab, "mouth", 0, 1.9))).add(0, number(ab, "mouth", 1, 1.4), 0);
    }

    private static double number(MobTemplate.Ability ab, String key, int index, double fallback) {
        Object v = ab.params().get(key);
        if (v instanceof List<?> list && list.size() > index && list.get(index) instanceof Number n) return n.doubleValue();
        return fallback;
    }

    /** Runs {@code body(tick)} every tick for {@code ticks} ticks, while the monster lives. */
    private void timeline(LivingEntity e, int ticks, java.util.function.IntConsumer body) {
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (!e.isValid() || e.isDead() || t > ticks) {
                    cancel();
                    return;
                }
                body.accept(t);
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ---------------------------------------------------------------- projectiles

    /**
     * A crop spat out: a flying item (carrot, potato...) pointed along its path, slightly falling. Hits the first
     * player it meets (each player once per salvo), breaks on walls.
     */
    private void shoot(RockPowers.Caster c, Location from, Vector vel, Material item, boolean glowing, double fallback,
                       double share, Set<UUID> hitBySalvo) {
        World w = from.getWorld();
        ItemDisplay d = w.spawn(from, ItemDisplay.class, it -> {
            it.setItemStack(new ItemStack(item));
            it.setPersistent(false);
            it.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            it.setTeleportDuration(1);
            it.addScoreboardTag(TAG);
            if (glowing) {
                it.setBrightness(new Display.Brightness(15, 15));
                it.setGlowing(true);
                it.setGlowColorOverride(GOLD);
            } else {
                it.setBrightness(new Display.Brightness(13, 15));
            }
        });
        alive.add(d);
        orient(d, vel);
        new BukkitRunnable() {
            int t;
            final Location pos = from.clone();

            @Override
            public void run() {
                if (!d.isValid() || ++t > 40) {
                    cancel();
                    forget(d);
                    return;
                }
                vel.setY(vel.getY() - 0.012);
                pos.add(vel);
                if (!pos.getBlock().isPassable()) {
                    w.spawnParticle(Particle.ITEM, pos, 8, 0.1, 0.1, 0.1, 0.08, new ItemStack(item));
                    w.playSound(pos, Sound.BLOCK_CROP_BREAK, 0.6f, 1.3f);
                    if (glowing) {
                        w.spawnParticle(Particle.DUST, pos, 12, 0.35, 0.2, 0.35, 0, new Particle.DustOptions(GOLD, 1.5f));
                        flash(pos);
                        fx.crater(pos, 1.2, 30, 0);
                    }
                    cancel();
                    forget(d);
                    return;
                }
                d.teleport(pos);
                orient(d, vel);
                if (glowing && t % 2 == 0) w.spawnParticle(Particle.DUST, pos, 1, 0, 0, 0, 0, new Particle.DustOptions(GOLD, 1.0f));
                for (Player p : w.getPlayers()) {
                    if (!Mobs.fighting(p) || hitBySalvo.contains(p.getUniqueId())) continue;
                    if (!p.getBoundingBox().expand(0.3).contains(pos.toVector())) continue;
                    hitBySalvo.add(p.getUniqueId());
                    if (share >= 1) c.hit(p, fallback);
                    else c.hitShare(p, fallback, share);
                    p.setVelocity(p.getVelocity().add(vel.clone().setY(0).normalize().multiply(0.35)).setY(0.25));
                    w.spawnParticle(Particle.ITEM, pos, 10, 0.15, 0.15, 0.15, 0.1, new ItemStack(item));
                    w.playSound(pos, Sound.ENTITY_SLIME_SQUISH_SMALL, 1f, 1.2f);
                    cancel();
                    forget(d);
                    return;
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Points an item display along its path (the tip of a carrot sprite is its bottom left corner). */
    private static void orient(ItemDisplay d, Vector vel) {
        Vector3f dir = new Vector3f((float) vel.getX(), (float) vel.getY(), (float) vel.getZ());
        if (dir.lengthSquared() < 1e-6) return;
        dir.normalize();
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(-1, -1, 0).normalize(), dir);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(1);
        d.setTransformation(new Transformation(new Vector3f(), q, new Vector3f(0.75f, 0.75f, 0.75f), new Quaternionf()));
    }

    private void forget(Entity d) {
        if (d.isValid()) d.remove();
        alive.remove(d);
    }

    /** A fan of {@code count} shots towards a place, spread over {@code spread} degrees around the aim. */
    private void fan(RockPowers.Caster c, MobTemplate.Ability ab, Location aim, int count, double spread, double speed,
                     List<Material> items, boolean glowing, double fallback, double share) {
        LivingEntity e = c.entity();
        Location from = mouth(e, ab);
        Vector base = aim.clone().add(0, 0.9, 0).toVector().subtract(from.toVector());
        double dist = Math.max(1, base.length());
        base.normalize();
        // aim a little above the target: the shots fall a bit on the way
        base.setY(base.getY() + Math.min(0.25, dist * 0.012)).normalize();
        Set<UUID> salvo = new HashSet<>();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            double k = count == 1 ? 0 : (i / (double) (count - 1)) - 0.5;
            double yaw = Math.toRadians(k * spread + r.nextDouble(-2.5, 2.5));
            Vector v = base.clone().rotateAroundY(yaw);
            v.setY(v.getY() + r.nextDouble(-0.05, 0.07));
            Material item = items.isEmpty() ? Material.CARROT : produce(items.get(r.nextInt(items.size())));
            shoot(c, from.clone().add(r.nextDouble(-0.15, 0.15), r.nextDouble(-0.1, 0.1), r.nextDouble(-0.15, 0.15)),
                    v.multiply(speed * r.nextDouble(0.9, 1.1)), item, glowing, fallback, share, salvo);
        }
    }

    private static Material produce(Material crop) {
        return switch (crop) {
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT;
            case WHEAT -> Material.WHEAT;
            default -> Material.CARROT;
        };
    }

    // ---------------------------------------------------------------- harvest

    private void harvest(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        Larder l = larder(e, ab);
        if (l.full && l.reserve > 0) {
            spit(ab, c, target, l);
            return;
        }
        List<Block> crops = crops(e, ab, l);
        if (crops.isEmpty()) {
            nothingLeft(ab, c, l);
            return;
        }
        // the thickest patch, the nearest one when two are as thick
        Block best = null;
        int bestCount = -1;
        double bestDist = 0;
        for (Block b : crops) {
            int n = 0;
            for (Block o : crops) if (o.getLocation().distanceSquared(b.getLocation()) <= 6.5) n++;
            double d = b.getLocation().distanceSquared(e.getLocation());
            if (n > bestCount || (n == bestCount && d < bestDist)) {
                best = b;
                bestCount = n;
                bestDist = d;
            }
        }
        Location patch = best.getLocation().add(0.5, 0, 0.5);
        Vector toPatch = flat(e.getLocation(), patch);
        // stands just before the patch, its mouth over it
        Location stand = patch.clone().subtract(toPatch.clone().multiply(1.6));
        World w = e.getWorld();
        int seek = (int) ab.number("seek-ticks", 10), runMax = (int) ab.number("run-max", 90);
        double speed = ab.number("run-speed", 5.0) / 20;
        c.hold(seek + runMax + 45 + 46 + 10);
        c.animate("harvest_seek");
        face(e, patch);
        w.playSound(e.getLocation(), Sound.ENTITY_FOX_SNIFF, 1.2f, 1.3f);
        new BukkitRunnable() {
            int t;
            boolean running;

            @Override
            public void run() {
                if (!e.isValid() || e.isDead()) {
                    cancel();
                    return;
                }
                t++;
                if (t < seek) return;
                if (!running) {
                    running = true;
                    c.loop("run");
                }
                Vector to = stand.toVector().subtract(e.getLocation().toVector()).setY(0);
                boolean arrived = to.length() <= speed * 1.2;
                if (!arrived && t < seek + runMax && !wallAhead(e, to.clone().normalize(), 0.9)) {
                    step(e, to.normalize(), Math.min(speed, to.length()));
                    if (t % 4 == 0) w.spawnParticle(Particle.BLOCK, e.getLocation(), 3, 0.3, 0.05, 0.3, 0, Material.FARMLAND.createBlockData());
                    return;
                }
                cancel();
                c.stopLoop("run");
                face(e, patch);
                eat(ab, c, target, l, patch);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Bites at the listed ticks of its eating animation: the crops near its mouth go into its cheeks. */
    private void eat(MobTemplate.Ability ab, RockPowers.Caster c, Player target, Larder l, Location patch) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        List<Block> near = new ArrayList<>();
        for (Block b : crops(e, ab, l)) if (b.getLocation().add(0.5, 0, 0.5).distanceSquared(patch) <= 7) near.add(b);
        if (near.isEmpty()) {
            nothingLeft(ab, c, l);
            return;
        }
        boolean shortMeal = near.size() <= 3;
        int[] bites = shortMeal ? new int[]{6, 14} : new int[]{6, 14, 22, 30, 38};
        int length = shortMeal ? 22 : 45;
        int perBite = (int) Math.min(ab.number("per-bite", 3), Math.ceil(near.size() / (double) bites.length));
        c.hold(length + 46 + 4);
        c.animate(shortMeal ? "harvest_eat_short" : "harvest_eat");
        timeline(e, length, t -> {
            for (int b : bites) {
                if (t != b) continue;
                Location mouth = e.getLocation().add(e.getLocation().getDirection().setY(0).normalize().multiply(1.6));
                near.removeIf(x -> !CROPS.contains(x.getType()));
                near.sort(Comparator.comparingDouble(x -> x.getLocation().add(0.5, 0, 0.5).distanceSquared(mouth)));
                int n = 0;
                for (Block x : new ArrayList<>(near)) {
                    if (n >= perBite) break;
                    w.spawnParticle(Particle.BLOCK, x.getLocation().add(0.5, 0.3, 0.5), 12, 0.25, 0.2, 0.25, 0, x.getBlockData());
                    l.eaten.add(x.getType());
                    x.setType(Material.AIR);
                    near.remove(x);
                    n++;
                }
                l.reserve += n;
                if (n > 0) {
                    w.playSound(mouth, Sound.ENTITY_GENERIC_EAT, 1.2f, 1.3f);
                    w.playSound(mouth, Sound.BLOCK_CROP_BREAK, 0.8f, 1f);
                }
            }
            if (t == length - 3 && l.reserve > 0) w.playSound(e.getLocation(), Sound.ENTITY_PUFFER_FISH_BLOW_UP, 1.6f, 1.2f);
            if (t == length) {
                if (l.reserve <= 0) {
                    c.release();
                    return;
                }
                l.full = true;
                c.parts(true, FULL);
                c.parts(false, EMPTY);
                spit(ab, c, target, l);
            }
        });
    }

    /**
     * Turns to the player, swells, then three thrusts: salvoes at ticks 14, 18 and 22 of harvest_spit; the empty
     * cheeks are shown at tick 24.
     */
    private void spit(MobTemplate.Ability ab, RockPowers.Caster c, Player target, Larder l) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int reserve = Math.max(1, l.reserve);
        List<Material> items = new ArrayList<>(l.eaten);
        int shots = (int) Math.min(ab.number("max-shots", 30), Math.max(4, reserve * ab.number("per-crop", 2)));
        // the third salvo only for a big meal
        int[] split = reserve >= 6 ? new int[]{(int) Math.round(shots * 0.4), (int) Math.round(shots * 0.35), 0}
                : new int[]{(int) Math.round(shots * 0.55), 0, 0};
        split[2] = reserve >= 6 ? shots - split[0] - split[1] : 0;
        split[1] = reserve >= 6 ? split[1] : shots - split[0];
        // a carrot hits for shot-power, less after a small meal
        double fallback = ab.number("shot-power", 3) * (0.7 + 0.3 * Math.min(1, reserve / 12.0));
        double speed = ab.number("speed", 0.9);
        c.hold(46);
        c.animate("harvest_spit");
        w.playSound(e.getLocation(), Sound.ENTITY_PLAYER_BREATH, 1.5f, 1.4f);
        timeline(e, 46, t -> {
            Player p = target.isValid() && Mobs.fighting(target) ? target : null;
            if (t <= 12 && p != null) face(e, p.getLocation());
            if (t == 2) w.playSound(e.getLocation(), Sound.ENTITY_PUFFER_FISH_BLOW_UP, 1.8f, 0.8f);
            if (t == 12) w.playSound(e.getLocation(), Sound.ENTITY_LLAMA_SPIT, 1.6f, 0.6f);
            if (t == 24) w.playSound(e.getLocation(), Sound.ENTITY_PUFFER_FISH_BLOW_OUT, 1.8f, 0.9f);
            if (t == 36 || t == 40) w.playSound(e.getLocation(), Sound.ENTITY_PLAYER_BREATH, 1.2f, 1.6f);
            int salvo = t == 14 ? 0 : t == 18 ? 1 : t == 22 ? 2 : -1;
            if (salvo >= 0 && split[salvo] > 0) {
                Location aim = p != null ? p.getLocation() : e.getLocation().add(e.getLocation().getDirection().multiply(8));
                double spread = new double[]{24, 40, 70}[salvo];
                fan(c, ab, aim, split[salvo], spread, speed, items, false, fallback, 1);
                w.playSound(e.getLocation(), Sound.ENTITY_BREEZE_SHOOT, 1.4f, 1.3f);
                w.playSound(e.getLocation(), Sound.ENTITY_LLAMA_SPIT, 1.4f, 0.8f + salvo * 0.15f);
                w.spawnParticle(Particle.CLOUD, mouth(e, ab), 6, 0.2, 0.1, 0.2, 0.05);
                w.spawnParticle(Particle.SWEEP_ATTACK, mouth(e, ab), 1, 0, 0, 0, 0);
            }
            if (t == 24) {
                l.full = false;
                l.reserve = 0;
                l.eaten.clear();
                c.parts(true, EMPTY);
                c.parts(false, FULL);
            }
            if (t == 46) c.release();
        });
    }

    /** Nothing left to eat: sniffs around, looks at the player, stamps, gives up harvesting (once). */
    private void nothingLeft(MobTemplate.Ability ab, RockPowers.Caster c, Larder l) {
        LivingEntity e = c.entity();
        l.mourned = true;
        c.hold(28);
        c.animate("harvest_none");
        World w = e.getWorld();
        timeline(e, 28, t -> {
            if (t == 2 || t == 8) w.playSound(e.getLocation(), Sound.ENTITY_FOX_SNIFF, 1.2f, 1.2f);
            if (t == 21 || t == 24) {
                Location paw = e.getLocation().add(e.getLocation().getDirection().setY(0).normalize().multiply(1.1));
                w.spawnParticle(Particle.BLOCK, paw, 10, 0.2, 0.05, 0.2, 0, Material.FARMLAND.createBlockData());
                w.playSound(paw, Sound.BLOCK_ROOTED_DIRT_STEP, 1.2f, 0.7f);
            }
            if (t == 22) w.spawnParticle(Particle.ANGRY_VILLAGER, e.getLocation().add(0, 2.7, 0), 1, 0.2, 0.1, 0.2, 0);
            if (t == 28) c.release();
        });
    }

    // ---------------------------------------------------------------- rolling charge

    /**
     * Curls up (0-18), then rolls faster and faster until tick 43 (0.21 to 0.89 block a tick, about 14 blocks),
     * homing on its target ({@code homing} degrees a tick) until tick {@code lock} (36): only the last steps go
     * straight, a quick side step at the end still dodges it. Unrolls with a jolt; a wall stuns it.
     */
    private void roll(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double v0 = ab.number("start-speed", 0.21), v1 = ab.number("end-speed", 0.89);
        double width = ab.number("width", 2.0), homing = Math.toRadians(ab.number("homing", 6));
        int lock = (int) ab.number("lock", 36);
        Vector[] dir = {flat(e.getLocation(), target.getLocation())};
        Set<UUID> struck = new HashSet<>();
        Map<UUID, Integer> touched = new HashMap<>();
        ThreadLocalRandom r3 = ThreadLocalRandom.current();
        boolean[] crashed = {false};
        c.hold(64);
        c.animate("roll");
        w.playSound(e.getLocation(), Sound.ENTITY_ARMADILLO_ROLL, 1.5f, 0.8f);
        timeline(e, 64, t -> {
            if (crashed[0]) return;
            if (t < 18) {
                if (target.isValid()) {
                    face(e, target.getLocation());
                    dir[0] = flat(e.getLocation(), target.getLocation());
                }
                if (t % 3 == 0) w.playSound(e.getLocation(), Sound.ENTITY_ARMADILLO_SCUTE_DROP, 1.2f, 0.6f + t * 0.06f);
                if (t >= 10) w.spawnParticle(Particle.BLOCK, e.getLocation(), 6, 0.6, 0.05, 0.6, 0, Material.FARMLAND.createBlockData());
                if (t == 17) w.playSound(e.getLocation(), Sound.ENTITY_BREEZE_CHARGE, 1.6f, 0.7f);
                return;
            }
            if (t < 43) {
                double u = (t - 18) / 25.0;
                double v = v0 + (v1 - v0) * u;
                if (t < lock && target.isValid() && Mobs.fighting(target)) {
                    // turns towards its target, at most `homing` a tick
                    Vector want = flat(e.getLocation(), target.getLocation());
                    double angle = Math.atan2(dir[0].getX() * want.getZ() - dir[0].getZ() * want.getX(), dir[0].dot(want));
                    dir[0].rotateAroundY(-Math.max(-homing, Math.min(homing, angle))).normalize();
                }
                if (wallAhead(e, dir[0], 1.2 + v)) {
                    crashed[0] = true;
                    crash(ab, c, dir[0]);
                    return;
                }
                Location at = step(e, dir[0], v);
                crush(at, 1.0);
                w.spawnParticle(Particle.BLOCK, at, 8, 0.5, 0.05, 0.5, 0, Material.FARMLAND.createBlockData());
                if (t % 3 == 0) fx.crater(at, 1.3, 40, 0.3, Fx.BROKEN_EARTH);
                if (t % 4 == 0) w.playSound(at, Sound.ENTITY_ARMADILLO_ROLL, 1.2f, 1f + (float) u * 0.7f);
                if (t % 6 == 0) w.playSound(at, Sound.BLOCK_ROOTED_DIRT_BREAK, 1f, 0.7f);
                // run over: a small hit every 3 ticks while the player stays under the ball, pushed ahead of it (he
                // keeps being rolled over until he gets out of its way)
                for (Player p : fighters(e, 5)) {
                    Location pl = p.getLocation();
                    if (Math.hypot(pl.getX() - at.getX(), pl.getZ() - at.getZ()) > width || Math.abs(pl.getY() - at.getY()) > 2) continue;
                    struck.add(p.getUniqueId());
                    Integer last = touched.get(p.getUniqueId());
                    if (last != null && t - last < 3) continue;
                    touched.put(p.getUniqueId(), t);
                    c.hit(p, ab.number("tick-power", 2));
                    Vector side = new Vector(-dir[0].getZ(), 0, dir[0].getX());
                    if (side.dot(pl.toVector().subtract(at.toVector())) < 0) side.multiply(-1);
                    p.setVelocity(dir[0].clone().multiply(v * 1.6 + 0.2).add(side.multiply(0.12)).setY(0.22));
                    w.playSound(pl, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1f, 0.8f + r3.nextFloat() * 0.3f);
                    w.spawnParticle(Particle.BLOCK, pl.clone().add(0, 0.6, 0), 8, 0.3, 0.3, 0.3, 0, Material.FARMLAND.createBlockData());
                }
                return;
            }
            if (t == 43) {
                // unrolls with a jolt: a small shockwave
                Location at = e.getLocation();
                w.playSound(at, Sound.ENTITY_GENERIC_SMALL_FALL, 1.4f, 0.6f);
                w.playSound(at, Sound.ENTITY_RAVAGER_STEP, 1.2f, 1.3f);
                fx.shockwave(at, 3, 5, 0.4);
                fx.crater(at, 2.5, 50, 0.4, Fx.BROKEN_EARTH);
                for (Player p : fighters(e, 2.5)) {
                    if (struck.add(p.getUniqueId())) c.hit(p, ab.number("jolt-power", 2.5));
                }
            }
            if (t == 64) c.release();
        });
    }

    /** Rolled into a wall: unrolls with a jolt, dizzy for two seconds (stars), shakes it off. */
    private void crash(MobTemplate.Ability ab, RockPowers.Caster c, Vector dir) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int stun = 62;
        c.hold(stun);
        c.animate("roll_crash");
        Location wall = e.getLocation().add(dir.clone().multiply(1.6)).add(0, 0.8, 0);
        Block hit = wall.getBlock();
        BlockData data = hit.getType().isAir() ? Material.HAY_BLOCK.createBlockData() : hit.getBlockData();
        w.playSound(e.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.7f, 1.6f);
        w.playSound(e.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.4f, 0.6f);
        w.playSound(e.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 1.2f, 0.8f);
        w.spawnParticle(Particle.EXPLOSION, wall, 2, 0.3, 0.3, 0.3, 0);
        fx.debris(wall, 10, data, 1.0);
        fx.crater(e.getLocation(), 2, 60, 0.4, Fx.BROKEN_EARTH);
        timeline(e, stun, t -> {
            if (t >= 6 && t <= 46 && t % 2 == 0) {
                double a = t * 0.35;
                Location head = e.getLocation().add(e.getLocation().getDirection().setY(0).normalize().multiply(1.0)).add(0, 2.6, 0);
                for (int i = 0; i < 3; i++) {
                    double b = a + i * Math.PI * 2 / 3;
                    w.spawnParticle(Particle.WAX_ON, head.clone().add(Math.cos(b) * 0.7, 0, Math.sin(b) * 0.7), 1, 0, 0, 0, 0);
                }
            }
            if (t % 12 == 6 && t <= 46) w.playSound(e.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.6f + (t % 24 == 6 ? 0.2f : 0f));
            if (t == 46) w.playSound(e.getLocation(), Sound.ENTITY_HORSE_BREATHE, 1f, 1.5f);
            if (t == stun) c.release();
        });
    }

    // ---------------------------------------------------------------- crush leap

    /**
     * A ring marks the landing and follows the target until the leap (tick 9), then stays. Taking off hurts whoever
     * is next to it; landing (tick 24) hurts the whole ring (more in the middle) with a shockwave, cracked ground and
     * debris; the crater then keeps hurting and slowing whoever stands in its middle for 1.5 seconds.
     */
    private void leap(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double radius = ab.number("radius", 8), max = ab.number("max-distance", 12);
        Location start = e.getLocation().clone();
        Location[] spot = {aimLeap(start, target.getLocation(), max, w)};
        int launch = 9, land = 24, after = (int) ab.number("aftershock", 30);
        c.hold(42);
        c.animate("leap");
        face(e, spot[0]);
        w.playSound(start, Sound.ENTITY_RAVAGER_ROAR, 0.6f, 1.8f);
        timeline(e, land + after, t -> {
            if (t <= launch && target.isValid() && Mobs.fighting(target)) {
                // the landing follows the target until the leap
                Location want = aimLeap(start, target.getLocation(), max, w);
                Vector move = want.toVector().subtract(spot[0].toVector()).setY(0);
                if (move.length() > 0.7) move.normalize().multiply(0.7);
                spot[0] = spot[0].clone().add(move);
                spot[0].setY(groundY(w, spot[0].getX(), want.getY(), spot[0].getZ()));
                face(e, spot[0]);
            }
            Location sp = spot[0];
            if (t <= land && t % 2 == 0) {
                double k = t / (double) land;
                Fx.ring(sp, radius, HARVEST, 1.6f, t * 0.05);
                Fx.ring(sp, radius * (1 - k), Color.fromRGB(0xFF5C5C), 1.1f, -t * 0.08);
                Fx.ring(sp, radius * 0.5, Color.fromRGB(0xFF5C5C), 1.2f, 0);
                if (t % 6 == 0) w.playSound(sp, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.5f, 0.6f + (float) k);
            }
            if (t == launch) {
                w.playSound(start, Sound.ENTITY_RABBIT_JUMP, 2f, 0.5f);
                w.playSound(start, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.2f, 0.8f);
                fx.crater(start, 2.5, 40, 0.5, Fx.BROKEN_EARTH);
                fx.shockwave(start, 3, 4, 0.35);
                w.spawnParticle(Particle.CLOUD, start, 16, 0.8, 0.1, 0.8, 0.05);
                for (Player p : fighters(e, 3.5)) {
                    c.hit(p, ab.number("takeoff-power", 3));
                    Vector out = p.getLocation().toVector().subtract(start.toVector()).setY(0);
                    if (out.lengthSquared() < 1e-4) out = new Vector(1, 0, 0);
                    p.setVelocity(out.normalize().multiply(0.6).setY(0.4));
                }
            }
            if (t >= launch && t <= land) {
                double k = (t - launch) / (double) (land - launch);
                Location at = start.clone().add(sp.clone().subtract(start).toVector().multiply(k));
                at.setY(start.getY() + (sp.getY() - start.getY()) * k + 4 * (2.6 + start.distance(sp) * 0.15) * k * (1 - k));
                Vector flat = sp.toVector().subtract(start.toVector()).setY(0);
                at.setYaw(yawTo(flat.lengthSquared() < 1e-6 ? start.getDirection() : flat.normalize()));
                at.setPitch(0);
                e.teleport(at);
                w.spawnParticle(Particle.DUST, at.clone().add(0, 1, 0), 2, 0.4, 0.4, 0.4, 0, new Particle.DustOptions(HARVEST, 1.1f));
            }
            if (t == land) {
                w.playSound(sp, Sound.ENTITY_GENERIC_BIG_FALL, 2f, 0.5f);
                w.playSound(sp, Sound.ENTITY_RAVAGER_STEP, 2f, 0.5f);
                w.playSound(sp, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.3f);
                w.playSound(sp, Sound.BLOCK_ROOTED_DIRT_BREAK, 2f, 0.6f);
                w.spawnParticle(Particle.EXPLOSION_EMITTER, sp, 1);
                w.spawnParticle(Particle.CLOUD, sp.clone().add(0, 0.3, 0), 50, radius * 0.4, 0.2, radius * 0.4, 0.08);
                Block under = Fx.ground(w, sp.getBlockX(), sp.getY(), sp.getBlockZ());
                BlockData data = under == null ? Material.DIRT.createBlockData() : under.getBlockData();
                fx.crater(sp, radius * 0.8, 90, 0.5, Fx.BROKEN_EARTH);
                fx.shockwave(sp, radius, 8, 0.55);
                fx.debris(sp.clone().add(0, 0.4, 0), 18, data, 1.4);
                crush(sp, 2.2);
                for (Player p : fighters(e, radius + 2)) {
                    Location pl = p.getLocation();
                    double d = Math.hypot(pl.getX() - sp.getX(), pl.getZ() - sp.getZ());
                    if (d > radius || Math.abs(pl.getY() - sp.getY()) > 2.5) continue;
                    // full blow near the middle, less at the edge of the wave
                    c.hit(p, d < radius / 2 ? ab.number("power-center", 7) : ab.number("power-edge", 4));
                    Vector out = pl.toVector().subtract(sp.toVector()).setY(0);
                    if (out.lengthSquared() < 1e-4) out = new Vector(1, 0, 0);
                    p.setVelocity(out.normalize().multiply(d < radius / 2 ? 1.0 : 0.6).setY(0.5));
                }
            }
            if (t > land && (t - land) % 5 == 0) {
                // aftershock: the middle of the crater keeps shaking
                for (Player p : fighters(e, radius)) {
                    Location pl = p.getLocation();
                    if (Math.hypot(pl.getX() - sp.getX(), pl.getZ() - sp.getZ()) > radius / 2 || Math.abs(pl.getY() - sp.getY()) > 2) continue;
                    c.hit(p, ab.number("aftershock-power", 1));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 15, 1));
                }
                w.spawnParticle(Particle.BLOCK, sp.clone().add(0, 0.1, 0), 12, radius * 0.25, 0.05, radius * 0.25, 0,
                        Material.COARSE_DIRT.createBlockData());
                if ((t - land) % 10 == 0) w.playSound(sp, Sound.BLOCK_GRAVEL_BREAK, 1.2f, 0.6f);
            }
            if (t == 42) c.release();
        });
    }

    /** Where a leap aimed at a place lands: that place, no further than {@code max} blocks, on the ground. */
    private static Location aimLeap(Location start, Location at, double max, World w) {
        Vector to = at.toVector().subtract(start.toVector()).setY(0);
        if (to.length() > max) to.normalize().multiply(max);
        Location spot = start.clone().add(to);
        spot.setY(groundY(w, spot.getX(), at.getY(), spot.getZ()));
        return spot;
    }

    // ---------------------------------------------------------------- root eruption

    /** Gathers (0-34), slams at tick 38: cracks run to each spot, roots burst out one after the other. */
    private void roots(MobTemplate.Ability ab, RockPowers.Caster c) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int life = (int) ab.number("life", 30);
        c.hold(45);
        c.animate("roots");
        w.playSound(e.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 1.5f, 1.4f);
        List<Location> spots = new ArrayList<>();
        timeline(e, 45, t -> {
            Location at = e.getLocation();
            Vector fwd = at.getDirection().setY(0).normalize();
            Vector side = new Vector(-fwd.getZ(), 0, fwd.getX());
            if (t < 20 && t % 2 == 0) {
                // golden light around the cheeks, then rising from the feet
                for (int s = -1; s <= 1; s += 2) {
                    Location cheek = at.clone().add(fwd.clone().multiply(1.3)).add(side.clone().multiply(0.8 * s)).add(0, 1.5, 0);
                    w.spawnParticle(Particle.DUST, cheek, 2, 0.2, 0.2, 0.2, 0, new Particle.DustOptions(GOLD, 1.0f));
                }
                if (t >= 10) w.spawnParticle(Particle.WAX_ON, at.clone().add(0, 0.2, 0), 4, 1.0, 0.1, 1.0, 0.02);
            }
            if (t == 22) {
                Location paws = at.clone().add(fwd.clone().multiply(1.2));
                w.spawnParticle(Particle.BLOCK, paws, 30, 0.8, 0.1, 0.8, 0.1, Material.ROOTED_DIRT.createBlockData());
                w.playSound(paws, Sound.BLOCK_ROOTED_DIRT_BREAK, 2f, 0.6f);
                w.playSound(paws, Sound.ITEM_HOE_TILL, 2f, 0.6f);
                w.playSound(paws, Sound.ENTITY_WARDEN_DIG, 1f, 1.4f);
                fx.crater(paws, 2.8, 70, 0.5, Fx.BROKEN_EARTH);
                fx.shockwave(paws, 3.5, 5, 0.35);
                for (Player p : fighters(e, ab.number("range", 20))) spots.add(p.getLocation().clone());
                // a crack runs from the boss to each player (1.6 block a tick); where it arrives, a wide zone is
                // shown, then a forest of roots bursts out of all of it
                for (Location s : spots) {
                    s.setY(groundY(w, s.getX(), s.getY(), s.getZ()));
                    int travel = (int) Math.ceil(s.distance(paws) / 1.6);
                    crack(paws, s, travel);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> zone(ab, c, s, life), travel);
                }
            }
            if (t == 45) c.release();
        });
    }

    /**
     * A wide zone of roots ({@code zone} blocks of radius) where a crack arrived: shown for {@code warn} ticks (red
     * circle, a golden one closing in, the ground cracking), then roots burst out all over it one after the other;
     * whoever is in it is hurt and slowed, and keeps taking small hits while the roots stand.
     */
    private void zone(MobTemplate.Ability ab, RockPowers.Caster c, Location center, int life) {
        World w = center.getWorld();
        double radius = ab.number("zone", 4);
        int warn = (int) ab.number("warn", 8);
        fx.crater(center, radius, warn + life + 20, 0.35, Material.ROOTED_DIRT, Material.COARSE_DIRT);
        w.playSound(center, Sound.BLOCK_ROOTED_DIRT_BREAK, 1.6f, 0.5f);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Location> spots = new ArrayList<>();
        spots.add(center.clone());
        double a0 = r.nextDouble(Math.PI * 2);
        for (int i = 0; i < 5; i++) {
            double a = a0 + Math.PI * 2 * i / 5;
            spots.add(center.clone().add(Math.cos(a) * radius * 0.5, 0, Math.sin(a) * radius * 0.5));
        }
        for (int i = 0; i < 8; i++) {
            double a = a0 + Math.PI * 2 * (i + 0.5) / 8;
            spots.add(center.clone().add(Math.cos(a) * radius * 0.88, 0, Math.sin(a) * radius * 0.88));
        }
        for (Location s : spots) s.setY(groundY(w, s.getX(), s.getY(), s.getZ()));
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                t++;
                if (t <= warn) {
                    if (t % 2 == 0) {
                        Fx.ring(center, radius, Color.fromRGB(0xFF5C5C), 1.4f, t * 0.1);
                        Fx.ring(center, radius * (1 - t / (double) warn), GOLD, 1.0f, 0);
                        w.spawnParticle(Particle.BLOCK, center.clone().add(0, 0.1, 0), 10, radius * 0.4, 0.02, radius * 0.4, 0,
                                Material.ROOTED_DIRT.createBlockData());
                    }
                    if (t == warn) {
                        for (int i = 0; i < spots.size(); i++) {
                            Location s = spots.get(i);
                            Bukkit.getScheduler().runTaskLater(plugin, () -> root(ab, c, s, life, false), 1L + i / 3);
                        }
                        w.playSound(center, Sound.BLOCK_ROOTS_BREAK, 2f, 0.5f);
                        w.playSound(center, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1.6f, 0.6f);
                    }
                    return;
                }
                int since = t - warn;
                for (Player p : w.getPlayers()) {
                    if (!Mobs.fighting(p)) continue;
                    Location pl = p.getLocation();
                    if (Math.hypot(pl.getX() - center.getX(), pl.getZ() - center.getZ()) > radius || Math.abs(pl.getY() - center.getY()) > 2.5) continue;
                    if (since == 3) {
                        c.hit(p, ab.number("burst-power", 4));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2));
                        p.setVelocity(p.getVelocity().setY(0.45));
                    } else if (since > 3 && since < life && since % 6 == 0) {
                        c.hit(p, ab.number("linger-power", 1));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 25, 1));
                    }
                }
                if (since >= life) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** A line of earth particles running underground from a place to another, then a crack marking the spot. */
    private void crack(Location from, Location to, int travel) {
        fx.crater(to, 0.9, travel + 14, 0);
        World w = from.getWorld();
        BlockData dirt = Material.ROOTED_DIRT.createBlockData();
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (++t > travel + 10) {
                    cancel();
                    return;
                }
                if (t <= travel) {
                    Location p = from.clone().add(to.clone().subtract(from).toVector().multiply(t / (double) travel));
                    w.spawnParticle(Particle.BLOCK, p.add(0, 0.1, 0), 3, 0.15, 0.02, 0.15, 0, dirt);
                } else {
                    w.spawnParticle(Particle.BLOCK, to.clone().add(0, 0.1, 0), 6, 0.4, 0.02, 0.4, 0, dirt);
                    w.spawnParticle(Particle.DUST, to.clone().add(0, 0.12, 0), 3, 0.35, 0.02, 0.35, 0, new Particle.DustOptions(GOLD, 0.9f));
                    if (t == travel + 1) w.playSound(to, Sound.BLOCK_ROOTED_DIRT_PLACE, 1f, 0.6f);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * A root bursting out: four bent segments (mangrove roots, then a glowing tip), rising in 4 ticks; hurts and slows
     * whoever stands on it, slows whoever touches it while it stands, sinks back after {@code life} ticks.
     */
    private void root(MobTemplate.Ability ab, RockPowers.Caster c, Location at, int life, boolean hurts) {
        World w = at.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double bend = r.nextDouble(Math.PI * 2);
        float[] sizes = {0.6f, 0.48f, 0.36f, 0.24f};
        Material[] blocks = {Material.MUDDY_MANGROVE_ROOTS, Material.MANGROVE_ROOTS, Material.MANGROVE_ROOTS, Material.SHROOMLIGHT};
        List<BlockDisplay> parts = new ArrayList<>();
        float y = 0;
        float ox = 0, oz = 0;
        for (int i = 0; i < sizes.length; i++) {
            float s = sizes[i];
            float lean = 0.12f * i;
            ox += (float) (Math.cos(bend) * lean);
            oz += (float) (Math.sin(bend) * lean);
            final float fx = ox, fz = oz, fy = y;
            final float tilt = (float) Math.toRadians(14 * i);
            final Material m = blocks[i];
            BlockDisplay d = w.spawn(at, BlockDisplay.class, b -> {
                b.setBlock(m.createBlockData());
                b.setPersistent(false);
                b.setBrightness(new Display.Brightness(m == Material.SHROOMLIGHT ? 15 : 11, 15));
                b.addScoreboardTag(TAG);
                b.setTransformation(new Transformation(new Vector3f(fx - s / 2, fy - 2.4f, fz - s / 2),
                        new AxisAngle4f(tilt, (float) -Math.sin(bend), 0, (float) Math.cos(bend)), new Vector3f(s, 0.62f, s), new AxisAngle4f()));
            });
            alive.add(d);
            parts.add(d);
            y += 0.55f;
        }
        w.playSound(at, Sound.BLOCK_ROOTS_BREAK, 1.6f, 0.6f);
        w.playSound(at, Sound.BLOCK_MANGROVE_ROOTS_BREAK, 1.6f, 0.7f);
        w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.2, 0), 18, 0.35, 0.1, 0.35, 0.05, Material.ROOTED_DIRT.createBlockData());
        fx.crater(at, 1.4, life + 20, 0.6, Material.ROOTED_DIRT, Material.COARSE_DIRT);
        fx.debris(at.clone().add(0, 0.3, 0), 5, Material.ROOTED_DIRT.createBlockData(), 0.8);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (BlockDisplay d : parts) {
                if (!d.isValid()) continue;
                Transformation tr = d.getTransformation();
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(4);
                d.setTransformation(new Transformation(new Vector3f(tr.getTranslation()).add(0, 2.4f, 0), tr.getLeftRotation(),
                        tr.getScale(), tr.getRightRotation()));
            }
        }, 1L);
        // the burst (a lone root; roots of a zone leave the damage to their zone)
        if (hurts) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player p : w.getPlayers()) {
                if (!Mobs.fighting(p)) continue;
                Location pl = p.getLocation();
                if (Math.hypot(pl.getX() - at.getX(), pl.getZ() - at.getZ()) > 1.1 || Math.abs(pl.getY() - at.getY()) > 2) continue;
                c.hit(p, ab.number("burst-power", 4));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2));
                p.setVelocity(p.getVelocity().setY(0.45));
            }
        }, 3L);
        // standing: a hazard, then it sinks
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                t++;
                if (hurts && t < life && t % 10 == 0) {
                    for (Player p : w.getPlayers()) {
                        if (!Mobs.fighting(p)) continue;
                        Location pl = p.getLocation();
                        if (Math.hypot(pl.getX() - at.getX(), pl.getZ() - at.getZ()) > 0.8 || Math.abs(pl.getY() - at.getY()) > 2) continue;
                        c.hit(p, ab.number("linger-power", 1));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 25, 1));
                    }
                }
                if (t == life) {
                    for (BlockDisplay d : parts) {
                        if (!d.isValid()) continue;
                        Transformation tr = d.getTransformation();
                        d.setInterpolationDelay(0);
                        d.setInterpolationDuration(6);
                        d.setTransformation(new Transformation(new Vector3f(tr.getTranslation()).add(0, -2.4f, 0), tr.getLeftRotation(),
                                tr.getScale(), tr.getRightRotation()));
                    }
                    w.playSound(at, Sound.BLOCK_ROOTED_DIRT_BREAK, 1f, 0.8f);
                }
                if (t >= life + 7) {
                    cancel();
                    for (BlockDisplay d : parts) forget(d);
                }
            }
        }.runTaskTimer(plugin, 4L, 1L);
    }

    // ---------------------------------------------------------------- the Great Feast

    /**
     * 0-25 announced (glows, text and warning, a column of light, golden runes on the ground until 85), 25-58 the
     * crops of the field fly into its mouth as golden carrots one after the other (none left at 58), 60 full cheeks,
     * 60-85 overload (sits up, ground cracking around it, heartbeat), then three waves at 88, 96 and 104: each one
     * aimed and drawn on the ground as a cone 12 ticks before (move out of it); the last one also throws a ring
     * around it. Empty cheeks at 108, glow off at 110, exhausted until 150.
     */
    private void feast(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Larder l = larder(e, ab);
        List<Block> field = new ArrayList<>(crops(e, ab, l));
        Collections.shuffle(field);
        int total = field.size();
        int[] absorbed = {0};
        double[] power = {1};
        int[] waves = {88, 96, 104};
        int[] counts = {9, 13, 19};
        double[] spreads = {28, 55, 85};
        Vector[] aims = new Vector[3];
        double ringRadius = ab.number("ring", 2.5), wavePower = ab.number("wave-power", 4),
                ringPower = ab.number("ring-power", 40), rainPower = ab.number("rain-power", 4), far = ab.number("far", 6);
        List<Map<UUID, Location>> rains = List.of(new HashMap<>(), new HashMap<>(), new HashMap<>());
        double lead = RockPowers.leadIn(ab);
        c.hold(150);
        c.animate("great_feast");
        c.glow(0xF0A800);
        title(e, ab, 85);
        w.playSound(e.getLocation(), Sound.BLOCK_TRIAL_SPAWNER_OMINOUS_ACTIVATE, 2f, 0.8f);
        w.playSound(e.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 2f, 0.6f);
        w.playSound(e.getLocation(), Sound.ENTITY_FOX_SNIFF, 1.6f, 0.7f);
        timeline(e, 150, t -> {
            Location at = e.getLocation();
            Location mouth = mouth(e, ab);
            Player p = target.isValid() && Mobs.fighting(target) ? target : null;
            if (t <= 25 && p != null && t % 2 == 0) face(e, p.getLocation());
            if (t < 86 && t % 2 == 0) {
                // golden runes turning on the ground, a column of light
                Fx.ring(at, 6.5, GOLD, 1.5f, t * 0.04);
                Fx.ring(at, 3.5, HARVEST, 1.2f, -t * 0.07);
                if (t % 4 == 0) Fx.column(at, 9, Particle.END_ROD, 1);
            }
            if (t >= 25 && t < 58 && !field.isEmpty()) {
                // the crops leave the field one after the other, flying into its mouth as golden carrots
                int due = (int) Math.ceil(total * (t - 24) / 33.0);
                while (absorbed[0] < due && !field.isEmpty()) {
                    Block b = field.remove(field.size() - 1);
                    if (!CROPS.contains(b.getType())) continue;
                    Location from = b.getLocation().add(0.5, 0.4, 0.5);
                    w.spawnParticle(Particle.BLOCK, from, 6, 0.2, 0.2, 0.2, 0, b.getBlockData());
                    b.setType(Material.AIR);
                    absorbed[0]++;
                    if (absorbed[0] % 3 == 1) fly(from, e, ab);
                }
                if (t % 3 == 0) w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.4f, 0.6f + (t - 25) / 40f);
            }
            if (t == 58) {
                power[0] = 0.6 + 0.4 * Math.min(1, absorbed[0] / ab.number("full-at", 20));
                l.crops = new ArrayList<>();
                if (absorbed[0] == 0) {
                    // nothing left in the field: it burns its own energy
                    double cost = ab.number("self-cost", 0.06);
                    AttributeInstance max = e.getAttribute(Attribute.MAX_HEALTH);
                    double hp = max == null ? 20 : max.getValue();
                    e.setHealth(Math.max(1, e.getHealth() - hp * cost));
                    w.spawnParticle(Particle.DAMAGE_INDICATOR, at.clone().add(0, 1.5, 0), 16, 0.6, 0.6, 0.6, 0.1);
                    w.playSound(at, Sound.ENTITY_PLAYER_HURT_ON_FIRE, 1.4f, 0.7f);
                }
                w.playSound(at, Sound.ENTITY_GENERIC_DRINK, 1.6f, 0.6f);
            }
            if (t == 60) {
                l.full = true;
                c.parts(true, FULL);
                c.parts(false, EMPTY);
                w.playSound(at, Sound.ENTITY_PUFFER_FISH_BLOW_UP, 2f, 0.6f);
            }
            if (t > 60 && t < 86) {
                if (t % 2 == 0) {
                    w.spawnParticle(Particle.DUST, at.clone().add(0, 1.3, 0), 8, 1.3, 0.9, 1.3, 0, new Particle.DustOptions(GOLD, 1.4f));
                    w.spawnParticle(Particle.ELECTRIC_SPARK, mouth, 3, 0.4, 0.3, 0.4, 0.05);
                }
                int beat = t < 74 ? 8 : 4;
                if (t % beat == 0) w.playSound(at, Sound.ENTITY_WARDEN_HEARTBEAT, 2f, 1.6f);
                if (t == 62) fx.crater(at, 3, 90, 0.4, Fx.BROKEN_EARTH);
                if (t == 74) fx.crater(at, 5, 80, 0.4, Fx.BROKEN_EARTH);
                if (t == 66 || t == 78) fx.shockwave(at, 3, 4, 0.3);
            }
            for (int i = 0; i < 3; i++) {
                int wt = waves[i];
                if (t == wt - 12) {
                    // aim locked and shown 12 ticks before the wave
                    Location aim = p != null ? p.getLocation() : at.clone().add(at.getDirection().multiply(8));
                    aims[i] = flat(at, aim);
                    face(e, aim);
                    // who keeps away gets a rain of golden carrots on his head (waves 2 and 3), shown where he stands
                    if (i >= 1) {
                        for (Player q : fighters(e, 40)) {
                            if (q.getLocation().distance(at) > far) rains.get(i).put(q.getUniqueId(), q.getLocation().clone());
                        }
                    }
                    w.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 2f, 0.7f + i * 0.25f);
                }
                if (aims[i] != null && t >= wt - 12 && t < wt && t % 2 == 0) {
                    Fx.cone(at, aims[i], spreads[i], 15, Color.fromRGB(0xFF5C5C), HARVEST);
                    if (i == 2) Fx.ring(at, ringRadius, Color.fromRGB(0xFF5C5C), 1.4f, t * 0.1);
                    for (Location spot : rains.get(i).values()) {
                        Fx.ring(spot, 2, Color.fromRGB(0xFF5C5C), 1.3f, t * 0.12);
                        Fx.ring(spot, 2 * (wt - t) / 12.0, GOLD, 1.0f, 0);
                    }
                }
                // whoever stays in the red cone (or ring) already loses life: time to get out before the wave
                if (aims[i] != null && lead > 0 && t >= wt - 12 && t < wt && (wt - t) % 4 == 0) {
                    double hit = wavePower * (i == 2 ? 1.4 : 1.0) * power[0];
                    Vector aimDir = aims[i];
                    double half = Math.toRadians(spreads[i] / 2);
                    for (Player q : fighters(e, 16)) {
                        Vector to = q.getLocation().toVector().subtract(at.toVector()).setY(0);
                        boolean inCone = to.length() <= 15 && to.length() > 0.1 && to.clone().normalize().angle(aimDir) <= half;
                        boolean inRing = i == 2 && to.length() <= ringRadius;
                        if (inCone) c.drain(q, hit, lead / 3);
                        if (inRing) c.drain(q, ringPower * power[0], lead / 3);
                        Location spot = rains.get(i).get(q.getUniqueId());
                        if (spot != null && Math.hypot(q.getLocation().getX() - spot.getX(), q.getLocation().getZ() - spot.getZ()) <= 2)
                            c.drain(q, rainPower * power[0], lead / 3);
                    }
                }
                if (t == wt && aims[i] != null) {
                    Location aim = at.clone().add(aims[i].clone().multiply(10));
                    int n = Math.max(6, (int) Math.round(counts[i] * power[0]));
                    fan(c, ab, aim, n, spreads[i], ab.number("speed", 1.25), List.of(Material.GOLDEN_CARROT), true,
                            wavePower * (i == 2 ? 1.4 : 1.0) * power[0], 1 - lead);
                    for (Location spot : rains.get(i).values()) rain(c, spot, rainPower * power[0], 1 - lead);
                    w.playSound(at, Sound.ENTITY_BREEZE_SHOOT, 2f, 0.7f);
                    w.playSound(at, Sound.ENTITY_BLAZE_SHOOT, 1.6f, 1.3f);
                    w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.6f);
                    flash(mouth);
                    w.spawnParticle(Particle.EXPLOSION, mouth, 2, 0.3, 0.2, 0.3, 0);
                    fx.shockwave(at, 2.5 + i, 4, 0.35);
                    if (i == 2) {
                        // the last one: a ring blasts out around it too
                        fx.shockwave(at, ringRadius, 6, 0.6);
                        fx.crater(at, ringRadius, 100, 0.5, Fx.BROKEN_EARTH);
                        for (Player q : fighters(e, ringRadius + 1)) {
                            Location ql = q.getLocation();
                            if (Math.hypot(ql.getX() - at.getX(), ql.getZ() - at.getZ()) > ringRadius) continue;
                            c.hitShare(q, ringPower * power[0], 1 - lead);
                            q.setVelocity(ql.toVector().subtract(at.toVector()).setY(0).normalize().multiply(1.1).setY(0.6));
                        }
                    }
                }
            }
            if (t == 108) {
                l.full = false;
                l.reserve = 0;
                l.eaten.clear();
                c.parts(true, EMPTY);
                c.parts(false, FULL);
                w.playSound(at, Sound.ENTITY_PUFFER_FISH_BLOW_OUT, 2f, 0.6f);
            }
            if (t == 110) {
                c.glow(-1);
                w.playSound(at, Sound.ENTITY_PLAYER_BREATH, 1.6f, 0.7f);
            }
            if (t > 110 && t < 145 && t % 6 == 0) {
                // exhausted: puffs of breath, a sleepy sound now and then
                w.spawnParticle(Particle.CLOUD, mouth, 3, 0.15, 0.1, 0.15, 0.01);
                if (t % 18 == 0) w.playSound(at, Sound.ENTITY_PLAYER_BREATH, 1.2f, 0.6f);
            }
            if (t == 150) c.release();
        });
    }

    /**
     * Golden carrots falling from the sky into a circle of 2 blocks (8 ticks), then a small golden blast: hurts who is
     * still in the circle (for players who keep away from the Great Feast).
     */
    private void rain(RockPowers.Caster c, Location spot, double fallback, double share) {
        World w = spot.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int k = 0; k < 5; k++) {
            Location top = spot.clone().add(r.nextDouble(-1.5, 1.5), 9 + r.nextDouble(0, 2), r.nextDouble(-1.5, 1.5));
            ItemDisplay d = w.spawn(top, ItemDisplay.class, it -> {
                it.setItemStack(new ItemStack(Material.GOLDEN_CARROT));
                it.setPersistent(false);
                it.setTeleportDuration(1);
                it.setBrightness(new Display.Brightness(15, 15));
                it.setGlowing(true);
                it.setGlowColorOverride(GOLD);
                it.addScoreboardTag(TAG);
            });
            alive.add(d);
            orient(d, new Vector(0, -1, 0));
            new BukkitRunnable() {
                int t;

                @Override
                public void run() {
                    if (!d.isValid() || ++t > 8) {
                        cancel();
                        forget(d);
                        return;
                    }
                    Location pos = top.clone().add(0, -(top.getY() - spot.getY()) * t / 8.0, 0);
                    d.teleport(pos);
                    w.spawnParticle(Particle.DUST, pos, 1, 0, 0, 0, 0, new Particle.DustOptions(GOLD, 1.0f));
                }
            }.runTaskTimer(plugin, 1L, 1L);
        }
        w.playSound(spot, Sound.ENTITY_ARROW_SHOOT, 1.4f, 0.6f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            flash(spot.clone().add(0, 0.5, 0));
            w.spawnParticle(Particle.EXPLOSION, spot.clone().add(0, 0.4, 0), 2, 0.6, 0.1, 0.6, 0);
            w.spawnParticle(Particle.DUST, spot.clone().add(0, 0.3, 0), 30, 1.2, 0.2, 1.2, 0, new Particle.DustOptions(GOLD, 1.5f));
            w.playSound(spot, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.5f);
            fx.crater(spot, 2, 60, 0.4, Fx.BROKEN_EARTH);
            for (Player q : w.getPlayers()) {
                if (!Mobs.fighting(q)) continue;
                Location ql = q.getLocation();
                if (Math.hypot(ql.getX() - spot.getX(), ql.getZ() - spot.getZ()) > 2 || Math.abs(ql.getY() - spot.getY()) > 3) continue;
                c.hitShare(q, fallback, share);
            }
        }, 9L);
    }

    /** A golden carrot flying from a crop of the field into the mouth of the monster (14 ticks). */
    private void fly(Location from, LivingEntity e, MobTemplate.Ability ab) {
        World w = from.getWorld();
        ItemDisplay d = w.spawn(from, ItemDisplay.class, it -> {
            it.setItemStack(new ItemStack(Material.GOLDEN_CARROT));
            it.setPersistent(false);
            it.setTeleportDuration(1);
            it.setBrightness(new Display.Brightness(15, 15));
            it.setGlowing(true);
            it.setGlowColorOverride(GOLD);
            it.addScoreboardTag(TAG);
        });
        alive.add(d);
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (!d.isValid() || !e.isValid() || ++t > 14) {
                    cancel();
                    forget(d);
                    return;
                }
                double k = t / 14.0;
                Location to = mouth(e, ab);
                Location pos = from.clone().add(to.clone().subtract(from).toVector().multiply(k)).add(0, 2.5 * k * (1 - k) * 4, 0);
                d.teleport(pos);
                orient(d, to.toVector().subtract(pos.toVector()));
                w.spawnParticle(Particle.DUST, pos, 1, 0, 0, 0, 0, new Particle.DustOptions(GOLD, 1.0f));
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ---------------------------------------------------------------- phase shift, entrance, scurry

    private void phaseShift(MobTemplate.Ability ab, RockPowers.Caster c) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        c.hold(30);
        c.animate("phase_shift");
        title(e, ab, 40);
        w.playSound(e.getLocation(), Sound.ENTITY_WOLF_SHAKE, 2f, 0.7f);
        w.playSound(e.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.2f, 0.6f);
        fx.shockwave(e.getLocation(), 4, 6, 0.4);
        fx.crater(e.getLocation(), 3, 60, 0.4, Fx.BROKEN_EARTH);
        w.spawnParticle(Particle.DUST, e.getLocation().add(0, 1.4, 0), 40, 1.2, 0.8, 1.2, 0, new Particle.DustOptions(GOLD, 1.4f));
        double bonus = ab.number("speed-bonus", 0);
        AttributeInstance speed = e.getAttribute(Attribute.MOVEMENT_SPEED);
        if (bonus > 0 && speed != null) {
            NamespacedKey key = new NamespacedKey(plugin, "phase_" + ab.text("spell", "shift"));
            speed.removeModifier(key);
            speed.addModifier(new AttributeModifier(key, bonus, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
        Bukkit.getScheduler().runTaskLater(plugin, c::release, 30L);
    }

    /** First move: runs to its field (0-18), sniffs, notices the crops, turns to the player at 38, sits up. */
    private void entrance(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Larder l = larder(e, ab);
        List<Block> field = crops(e, ab, l);
        Location goal;
        if (!field.isEmpty()) {
            double x = 0, z = 0;
            for (Block b : field) {
                x += b.getX() + 0.5;
                z += b.getZ() + 0.5;
            }
            goal = new Location(w, x / field.size(), e.getLocation().getY(), z / field.size());
        } else {
            goal = target.getLocation();
        }
        Vector dir = flat(e.getLocation(), goal);
        double run = Math.min(ab.number("run", 5), Math.max(0, e.getLocation().distance(goal) - 2));
        c.hold(60);
        c.animate("spawn");
        timeline(e, 60, t -> {
            if (t < 18 && run > 0 && !wallAhead(e, dir, 1)) {
                step(e, dir, run / 18);
                if (t % 2 == 0) w.spawnParticle(Particle.BLOCK, e.getLocation(), 5, 0.4, 0.05, 0.4, 0, Material.FARMLAND.createBlockData());
            }
            if (t == 26 || t == 30) w.playSound(e.getLocation(), Sound.ENTITY_FOX_SNIFF, 1.4f, 1.3f);
            if (t == 35) w.playSound(e.getLocation(), Sound.ENTITY_RABBIT_AMBIENT, 2f, 0.7f);
            if (t == 38 && target.isValid()) face(e, target.getLocation());
            if (t == 50) w.playSound(e.getLocation(), Sound.ENTITY_RABBIT_AMBIENT, 2f, 1.1f);
            if (t == 60) c.release();
        });
    }

    /** Runs at a player who keeps away, until it is close (or {@code ticks} ticks). */
    private void scurry(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        int max = (int) ab.number("ticks", 40);
        double speed = ab.number("run-speed", 5.0) / 20, stop = ab.number("stop", 4);
        c.hold(max + 2);
        c.loop("run");
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (!e.isValid() || e.isDead() || !target.isValid()) {
                    cancel();
                    return;
                }
                Vector to = target.getLocation().toVector().subtract(e.getLocation().toVector()).setY(0);
                if (++t > max || to.length() <= stop || wallAhead(e, to.clone().normalize(), 0.9)) {
                    cancel();
                    c.stopLoop("run");
                    c.release();
                    return;
                }
                step(e, to.normalize(), speed);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
}
