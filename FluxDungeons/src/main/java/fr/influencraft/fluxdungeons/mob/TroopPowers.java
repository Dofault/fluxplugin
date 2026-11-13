package fr.influencraft.fluxdungeons.mob;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntPredicate;

/**
 * Abilities that give a troop monster its way of fighting (its role), whatever its theme. Every one is telegraphed
 * (a mark on the ground, a line, a sound that rises) and drawn with particles and moving items, nothing is left in
 * the map:
 * <ul>
 *   <li>{@code pounce}: crouches while a ring shows where it will land, then leaps in an arc onto a player, or right
 *   behind his back ({@code behind}: chance), and bounces again ({@code bounces}); a shock on landing, maybe a
 *   burning patch ({@code burn}). Slimes and magma cubes;</li>
 *   <li>{@code bolt}: a projectile (an item seen in flight), straight, homing ({@code homing}) or lobbed
 *   ({@code lob}), several at once ({@code count}, {@code spread}) or in bursts ({@code volleys}); on a hit an effect
 *   ({@code effect}: poison, slowness...) and a push; a lobbed one may leave a pool that hurts ({@code pool});</li>
 *   <li>{@code windup}: a slow, heavy blow: the area it will hit fills up on the ground, then a crushing impact;</li>
 *   <li>{@code aimed_shot}: a sniper: a thin line follows a player, turns red and stops, then a shot along it that
 *   goes through everybody on it;</li>
 *   <li>{@code blink}: vanishes in smoke and comes out behind a player (the one furthest away with {@code far-min}),
 *   strikes a moment later;</li>
 *   <li>{@code fuse}: close to a player, swells and flashes, then blows up, and dies of it;</li>
 *   <li>{@code rally}: a support: heals the hurt monsters around it and hardens them for a while (kill it first).</li>
 * </ul>
 * Shared: {@code power} (damage, in units of the monster's {@code damage}: power 4 on a monster of damage 2 is two
 * of its hits), {@code impact} (ticks of warning), {@code far-min}, {@code effect} / {@code effect-ticks} /
 * {@code effect-level} (a potion effect on whoever is hit). Lingering zones (burning patch, pool) hurt by {@code share}
 * of the hit per tick.
 */
final class TroopPowers {

    static final Set<String> TYPES = Set.of("pounce", "bolt", "windup", "aimed_shot", "blink", "fuse", "rally");

    private final Plugin plugin;
    private final Fx fx;
    private final Set<Entity> alive = new HashSet<>();

    TroopPowers(Plugin plugin, Fx fx) {
        this.plugin = plugin;
        this.fx = fx;
    }

    /** Whether there is somebody to hit (abilities that would hit nobody wait). */
    boolean worth(MobTemplate.Ability ab, LivingEntity e) {
        if (!TYPES.contains(ab.type())) return true;
        return switch (ab.type()) {
            case "pounce" -> seen(e, ab.number("range-min", 2.5), ab.number("range", 14)) != null;
            case "bolt", "aimed_shot" -> seen(e, ab.number("range-min", 0), ab.number("range", 22)) != null;
            case "windup" -> seen(e, 0, ab.number("radius", 3.5) + 1.2) != null;
            case "blink" -> seen(e, ab.number("range-min", 3), ab.number("range", 18)) != null;
            case "fuse" -> seen(e, 0, ab.number("trigger", 2.8)) != null;
            case "rally" -> !hurtAllies(e, ab.number("radius", 10), ab.number("below", 85) / 100).isEmpty();
            default -> true;
        };
    }

    boolean cast(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        if (!TYPES.contains(ab.type())) return false;
        Player aim = target;
        if (ab.number("far-min", 0) > 0) {
            Player far = TidePowers.farthest(c.entity(), ab.number("far-min", 0));
            if (far != null) aim = far;
        }
        String animation = ab.text("animation", "");
        if (!animation.isBlank()) c.animate(animation);
        switch (ab.type()) {
            case "pounce" -> pounce(ab, c, aim, (int) ab.number("bounces", 1), (int) ab.number("impact", 14));
            case "bolt" -> bolt(ab, c, aim);
            case "windup" -> windup(ab, c, aim);
            case "aimed_shot" -> aimedShot(ab, c, aim);
            case "blink" -> blink(ab, c, aim);
            case "fuse" -> fuse(ab, c);
            case "rally" -> rally(ab, c);
            default -> {
            }
        }
        return true;
    }

    void clear() {
        for (Entity e : alive) if (e.isValid()) e.remove();
        alive.clear();
    }

    // ---------------------------------------------------------------- helpers

    /** The nearest fighting player between {@code min} and {@code max} blocks that the monster sees. */
    private static Player seen(LivingEntity e, double min, double max) {
        Player best = null;
        double bd = max * max;
        for (Player p : e.getWorld().getPlayers()) {
            if (!Mobs.fighting(p)) continue;
            double d = p.getLocation().distanceSquared(e.getLocation());
            if (d <= bd && d >= min * min && e.hasLineOfSight(p)) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private static List<Player> within(Location c, double radius, double height) {
        List<Player> out = new ArrayList<>();
        for (Player p : c.getWorld().getPlayers()) {
            if (!Mobs.fighting(p)) continue;
            Location l = p.getLocation();
            if (Math.abs(l.getY() - c.getY()) <= height && Math.hypot(l.getX() - c.getX(), l.getZ() - c.getZ()) <= radius) out.add(p);
        }
        return out;
    }

    private static Color color(MobTemplate.Ability ab, String key, int fallback) {
        String s = ab.text(key, "");
        try {
            return Color.fromRGB(s.isBlank() ? fallback : Integer.parseInt(s.replace("#", ""), 16));
        } catch (NumberFormatException e) {
            return Color.fromRGB(fallback);
        }
    }

    private static Material material(MobTemplate.Ability ab, String key, Material fallback) {
        Material m = Material.matchMaterial(ab.text(key, ""));
        return m == null ? fallback : m;
    }

    private static Particle particle(MobTemplate.Ability ab, String key, Particle fallback) {
        String s = ab.text(key, "");
        if (s.isBlank()) return fallback;
        try {
            Particle p = Particle.valueOf(s.toUpperCase(Locale.ROOT));
            // particles that need data (dust, blocks, items) are drawn by their own code
            return p.getDataType() == Void.class ? p : fallback;
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    /** The effect of a hit ({@code effect}: a potion effect id, {@code effect-ticks}, {@code effect-level}). */
    private static void afflict(MobTemplate.Ability ab, Player p) {
        String name = ab.text("effect", "");
        if (name.isBlank()) return;
        PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        if (type == null) return;
        p.addPotionEffect(new PotionEffect(type, (int) ab.number("effect-ticks", 60), (int) ab.number("effect-level", 0), false, true));
    }

    private ItemDisplay item(Location at, Material m, float size) {
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, it -> {
            it.setItemStack(new ItemStack(m));
            it.setPersistent(false);
            it.addScoreboardTag("fluxdungeons_fx");
            it.setTeleportDuration(1);
            it.setBrightness(new Display.Brightness(15, 15));
            it.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(size, size, size), new AxisAngle4f()));
        });
        alive.add(d);
        return d;
    }

    private void drop(Entity e) {
        alive.remove(e);
        if (e.isValid()) e.remove();
    }

    /** Runs {@code step} every tick from 0 until it returns false or {@code max} ticks passed, then {@code end}. */
    private void every(int max, IntPredicate step, Runnable end) {
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                boolean go = t <= max && step.test(t);
                t++;
                if (!go) {
                    cancel();
                    if (end != null) end.run();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Whether a body about two blocks high cannot stand at this spot (a solid block at its feet or head). */
    private static boolean blocked(Location at) {
        Block feet = at.getBlock(), head = feet.getRelative(BlockFace.UP);
        return (feet.getType().isSolid() && !feet.isPassable()) || (head.getType().isSolid() && !head.isPassable());
    }

    /** The floor under a spot (up to 4 blocks down), or null when there is none: a leap never ends in the void. */
    private static Location floor(Location at) {
        Location l = at.clone();
        l.setY(Math.floor(l.getY()) + 1);
        for (int i = 0; i < 6; i++) {
            Block below = l.getBlock().getRelative(BlockFace.DOWN);
            if (!blocked(l) && below.getType().isSolid() && !below.isPassable()) return l;
            l.add(0, -1, 0);
        }
        return null;
    }

    private static Vector flat(Vector v) {
        Vector f = v.clone().setY(0);
        return f.lengthSquared() < 1e-4 ? new Vector(0, 0, 1) : f.normalize();
    }

    // ---------------------------------------------------------------- pounce

    /**
     * Crouches {@code crouch} ticks while a ring follows the player where it will land (locked once it jumps), leaps
     * in an arc (stopped by a wall), hits and pushes everyone in {@code radius} on landing, then may bounce again on
     * a player nearby.
     */
    private void pounce(MobTemplate.Ability ab, RockPowers.Caster c, Player target, int bounces, int crouch) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double radius = ab.number("radius", 2.2), knock = ab.number("knock", 0.8), behindChance = ab.number("behind", 0.4);
        Material look = material(ab, "item", Material.SLIME_BLOCK);
        Color warn = color(ab, "color", 0x7BD05A);
        boolean behind = ThreadLocalRandom.current().nextDouble() < behindChance;
        c.hold(crouch + 30);
        w.playSound(e.getLocation(), Sound.ENTITY_SLIME_SQUISH, 1.4f, 0.6f);
        Location[] dest = new Location[1];
        every(crouch, t -> {
            if (!e.isValid() || !target.isOnline() || target.getWorld() != w) return false;
            dest[0] = landing(target, behind, ab.number("behind-distance", 2.6));
            // squashing down: particles pressed out at its feet, a pitch that rises
            if (t % 2 == 0) {
                Fx.ring(dest[0], radius, behind ? Color.fromRGB(0xFF5C5C) : warn, 1.2f, t * 0.2);
                w.spawnParticle(Particle.BLOCK, e.getLocation().add(0, 0.1, 0), 4, 0.4, 0.05, 0.4, 0, look.createBlockData());
            }
            if (t % 4 == 0) w.playSound(e.getLocation(), Sound.BLOCK_SLIME_BLOCK_STEP, 1f, 0.6f + (float) t / crouch);
            return true;
        }, () -> {
            if (!e.isValid() || dest[0] == null) {
                c.release();
                return;
            }
            leap(ab, c, dest[0], radius, knock, look, () -> {
                if (bounces <= 1 || !e.isValid()) return;
                Player next = seen(e, 0, ab.number("range", 14));
                if (next == null) return;
                // the next bounce comes quicker: hard to read when two in a row
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (e.isValid()) pounce(ab, c, next, bounces - 1, (int) ab.number("rebound", 8));
                }, 4L);
            });
        });
    }

    /** Where a pounce lands: on the player, or just behind his back (where he does not look), on solid ground. */
    private static Location landing(Player p, boolean behind, double distance) {
        Location base = p.getLocation();
        if (behind) {
            Location back = base.clone().add(flat(base.getDirection()).multiply(-distance));
            Location ground = floor(back);
            if (ground != null && Math.abs(ground.getY() - base.getY()) < 2.5) return ground;
        }
        return base.clone();
    }

    private void leap(MobTemplate.Ability ab, RockPowers.Caster c, Location dest, double radius, double knock, Material look, Runnable after) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Location from = e.getLocation();
        double dist = from.distance(dest);
        int flight = (int) Math.max(8, Math.min(20, dist * 1.1));
        double height = Math.min(5.5, 1.6 + dist * 0.3);
        Vector face = flat(dest.toVector().subtract(from.toVector()));
        c.hold(flight + 12);
        e.setGravity(false);
        w.playSound(from, Sound.ENTITY_SLIME_JUMP, 1.6f, 0.7f);
        Particle trail = particle(ab, "trail", Particle.ITEM_SLIME);
        Location[] at = {from};
        every(flight, t -> {
            if (!e.isValid()) return false;
            double k = (double) (t + 1) / flight;
            Location next = from.clone().add(dest.toVector().subtract(from.toVector()).multiply(k)).add(0, Math.sin(Math.PI * k) * height, 0);
            next.setDirection(face);
            if (blocked(next)) return false;
            Mobs.move(e, next);
            at[0] = next;
            w.spawnParticle(trail, next.clone().add(0, 0.4, 0), 3, 0.2, 0.2, 0.2, 0.01);
            return k < 1;
        }, () -> {
            if (!e.isValid()) return;
            e.setGravity(true);
            Location land = at[0];
            w.playSound(land, Sound.ENTITY_SLIME_ATTACK, 1.6f, 0.5f);
            w.playSound(land, Sound.BLOCK_SLIME_BLOCK_FALL, 1.6f, 0.6f);
            w.spawnParticle(Particle.BLOCK, land.clone().add(0, 0.2, 0), 40, radius / 2, 0.2, radius / 2, 0.1, look.createBlockData());
            fx.shockwave(land, radius, 6, 0.35);
            for (Player p : within(land, radius, 2.2)) {
                c.hit(p, 1);
                afflict(ab, p);
                Vector out = flat(p.getLocation().toVector().subtract(land.toVector()));
                p.setVelocity(out.multiply(knock).setY(0.4));
            }
            if (ab.params().get("burn") instanceof java.util.Map<?, ?> burn) burn(c, land, burn);
            c.release();
            after.run();
        });
    }

    /** A burning patch left where a magma cube landed: hurts whoever stays in it ({@code burn: {radius, duration, tick}}). */
    private void burn(RockPowers.Caster c, Location at, java.util.Map<?, ?> burn) {
        double radius = burn.get("radius") instanceof Number n ? n.doubleValue() : 2;
        int duration = burn.get("duration") instanceof Number n ? n.intValue() : 60;
        int tick = burn.get("tick") instanceof Number n ? Math.max(5, n.intValue()) : 10;
        // each tick of the patch takes this share of the ability's hit
        double share = burn.get("share") instanceof Number n ? n.doubleValue() : 0.15;
        World w = at.getWorld();
        w.playSound(at, Sound.BLOCK_FIRE_AMBIENT, 1.4f, 0.8f);
        every(duration, t -> {
            if (t % 2 == 0) {
                Fx.ring(at, radius, Color.fromRGB(0xFF7A1A), 1.1f, t * 0.15);
                w.spawnParticle(Particle.FLAME, at.clone().add(0, 0.15, 0), 5, radius / 2, 0.05, radius / 2, 0.01);
                if (t % 6 == 0) w.spawnParticle(Particle.LAVA, at, 1, radius / 3, 0, radius / 3, 0);
            }
            if (t % tick == 0) for (Player p : within(at, radius, 1.5)) c.hitShare(p, 0.25, share);
            return true;
        }, null);
    }

    // ---------------------------------------------------------------- bolt

    /** One shot or a burst ({@code volleys}, {@code gap} ticks apart), each one re-aimed at its target. */
    private void bolt(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int charge = (int) ab.number("charge", 8), volleys = (int) Math.max(1, ab.number("volleys", 1)), gap = (int) Math.max(2, ab.number("gap", 6));
        Color tint = color(ab, "color", 0x8BD346);
        c.hold(charge + gap * (volleys - 1) + 4);
        // gathering in front of it: the shot is coming
        w.playSound(e.getLocation(), Sound.ENTITY_LLAMA_SPIT, 0.8f, 0.6f);
        every(charge, t -> {
            if (!e.isValid()) return false;
            if (t % 2 == 0) {
                Location hand = e.getEyeLocation().add(flat(target.getLocation().toVector().subtract(e.getLocation().toVector())).multiply(0.6));
                w.spawnParticle(Particle.DUST, hand, 4, 0.25, 0.25, 0.25, 0, new Particle.DustOptions(tint, 1f + (float) t / Math.max(1, charge)));
            }
            return true;
        }, () -> {
            for (int v = 0; v < volleys; v++) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!e.isValid() || !target.isOnline() || target.getWorld() != w || !Mobs.fighting(target)) return;
                    if (e instanceof org.bukkit.entity.Mob m) m.lookAt(target);
                    shoot(ab, c, target);
                }, (long) v * gap);
            }
        });
    }

    private void shoot(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int count = (int) Math.max(1, ab.number("count", 1));
        double spread = ab.number("spread", 18), speed = ab.number("speed", 0.9), homing = Math.toRadians(ab.number("homing", 0));
        double range = ab.number("range", 22) + 4;
        Material m = material(ab, "item", Material.SLIME_BALL);
        float size = (float) ab.number("size", 0.55);
        Particle trail = particle(ab, "trail", null);
        Location eye = e.getEyeLocation();
        // predict: aims where the player is going (0 = where he is)
        Location aimAt = target.getLocation().add(0, 1, 0).add(target.getVelocity().clone().setY(0).multiply(ab.number("predict", 0) * 10));
        w.playSound(eye, soundOf(ab), 1.4f, (float) ab.number("pitch", 1.1));
        if (ab.flag("lob", false)) {
            for (int i = 0; i < count; i++) {
                Location spot = aimAt.clone().add(0, -1, 0);
                if (i > 0) spot.add(ThreadLocalRandom.current().nextDouble(-2.5, 2.5), 0, ThreadLocalRandom.current().nextDouble(-2.5, 2.5));
                lob(ab, c, eye, spot, m, size, trail);
            }
            return;
        }
        Vector base = aimAt.toVector().subtract(eye.toVector()).normalize();
        for (int i = 0; i < count; i++) {
            double a = count == 1 ? 0 : Math.toRadians(-spread / 2 + spread * i / (count - 1));
            Vector[] dir = {base.clone().rotateAroundY(a)};
            ItemDisplay shot = item(eye.clone(), m, size);
            double[] flown = {0};
            every((int) (range / speed) + 2, t -> {
                if (!shot.isValid()) return false;
                if (homing > 0 && target.isOnline() && target.getWorld() == w && Mobs.fighting(target)) {
                    Vector want = target.getLocation().add(0, 1, 0).toVector().subtract(shot.getLocation().toVector()).normalize();
                    double angle = dir[0].angle(want);
                    if (angle > 1e-3) {
                        double k = Math.min(1, homing / angle);
                        dir[0] = dir[0].clone().multiply(1 - k).add(want.multiply(k)).normalize();
                    }
                }
                Location next = shot.getLocation().add(dir[0].clone().multiply(speed));
                flown[0] += speed;
                if (flown[0] > range || next.getBlock().getType().isSolid()) {
                    w.spawnParticle(Particle.ITEM, next, 6, 0.1, 0.1, 0.1, 0.05, new ItemStack(m));
                    return false;
                }
                next.setDirection(dir[0]);
                shot.teleport(next);
                if (trail != null) w.spawnParticle(trail, next, 1, 0, 0, 0, 0);
                else if (t % 2 == 0) w.spawnParticle(Particle.ITEM, next, 1, 0, 0, 0, 0, new ItemStack(m));
                for (Player p : w.getPlayers()) {
                    if (Mobs.fighting(p) && p.getBoundingBox().expand(0.35).contains(next.toVector())) {
                        c.hit(p, 0.7);
                        afflict(ab, p);
                        double knock = ab.number("knock", 0);
                        if (knock > 0) p.setVelocity(flat(dir[0]).multiply(knock).setY(0.25));
                        w.playSound(next, Sound.ENTITY_SLIME_SQUISH_SMALL, 1.4f, 1.2f);
                        w.spawnParticle(Particle.ITEM, next, 10, 0.2, 0.2, 0.2, 0.08, new ItemStack(m));
                        return false;
                    }
                }
                return true;
            }, () -> drop(shot));
        }
    }

    private static Sound soundOf(MobTemplate.Ability ab) {
        String s = ab.text("sound", "");
        if (!s.isBlank()) {
            Sound found = Registry.SOUNDS.get(NamespacedKey.minecraft(s.toLowerCase(Locale.ROOT)));
            if (found != null) return found;
        }
        return Sound.ENTITY_LLAMA_SPIT;
    }

    /** A lobbed shot: its landing spot marked all the way, a splash there, maybe a pool that keeps hurting. */
    private void lob(MobTemplate.Ability ab, RockPowers.Caster c, Location from, Location spot, Material m, float size, Particle trail) {
        World w = from.getWorld();
        Location ground = floor(spot.clone().add(0, 1, 0));
        Location land = ground == null ? spot : ground;
        int flight = (int) Math.max(12, Math.min(28, from.distance(land) * 1.3));
        double radius = ab.number("radius", 1.8);
        Color warn = color(ab, "color", 0x8BD346);
        ItemDisplay ball = item(from.clone(), m, size);
        every(flight, t -> {
            double k = (double) t / flight;
            ball.teleport(from.clone().add(land.toVector().subtract(from.toVector()).multiply(k)).add(0, Math.sin(Math.PI * k) * 3.2, 0));
            if (trail != null) w.spawnParticle(trail, ball.getLocation(), 1, 0, 0, 0, 0);
            if (t % 2 == 0) Fx.ring(land, radius, warn, 1.1f, t * 0.15);
            return t < flight;
        }, () -> {
            drop(ball);
            w.playSound(land, Sound.ENTITY_SPLASH_POTION_BREAK, 1.4f, 0.8f);
            w.spawnParticle(Particle.ITEM, land.clone().add(0, 0.3, 0), 20, radius / 3, 0.2, radius / 3, 0.1, new ItemStack(m));
            for (Player p : within(land, radius, 2)) {
                c.hit(p, 0.8);
                afflict(ab, p);
            }
            if (ab.params().get("pool") instanceof java.util.Map<?, ?> pool) pool(ab, c, land, pool, warn);
        });
    }

    /** A pool left by a lobbed shot ({@code pool: {radius, duration, tick}}): hurts and afflicts whoever stays in it. */
    private void pool(MobTemplate.Ability ab, RockPowers.Caster c, Location at, java.util.Map<?, ?> pool, Color tint) {
        double radius = pool.get("radius") instanceof Number n ? n.doubleValue() : 2.4;
        int duration = pool.get("duration") instanceof Number n ? n.intValue() : 80;
        int tick = pool.get("tick") instanceof Number n ? Math.max(5, n.intValue()) : 10;
        double share = pool.get("share") instanceof Number n ? n.doubleValue() : 0.15;
        World w = at.getWorld();
        every(duration, t -> {
            if (t % 3 == 0) {
                Fx.ring(at, radius, tint, 1f, -t * 0.08);
                w.spawnParticle(Particle.DUST, at.clone().add(0, 0.15, 0), 8, radius / 2, 0.05, radius / 2, 0, new Particle.DustOptions(tint, 1.3f));
                if (t % 9 == 0) w.spawnParticle(Particle.BUBBLE_POP, at.clone().add(0, 0.2, 0), 4, radius / 3, 0, radius / 3, 0.02);
            }
            if (t % tick == 0) {
                for (Player p : within(at, radius, 1.5)) {
                    c.hitShare(p, 0.2, share);
                    afflict(ab, p);
                }
            }
            return true;
        }, null);
    }

    // ---------------------------------------------------------------- windup

    /**
     * A slow, heavy blow: the monster stops, the zone it will hit (a circle around it, or a cone towards the player
     * with {@code shape: cone}) fills up from the middle as the blow comes, then a crushing impact: big damage, a
     * crater, players thrown away ({@code knock}, {@code launch}) and dazed ({@code daze}: ticks of heavy slowness).
     */
    private void windup(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int impact = (int) ab.number("impact", 26);
        double radius = ab.number("radius", 3.5), angle = ab.number("angle", 110);
        boolean cone = "cone".equalsIgnoreCase(ab.text("shape", "circle"));
        Color edge = color(ab, "color", 0xFF5C5C);
        Material debris = material(ab, "item", Material.COBBLESTONE);
        Location from = e.getLocation();
        Vector dir = flat(target.getLocation().toVector().subtract(from.toVector()));
        if (e instanceof org.bukkit.entity.Mob m) m.lookAt(target);
        c.hold(impact + 10);
        w.playSound(from, Sound.ENTITY_RAVAGER_ROAR, 0.7f, 1.4f);
        every(impact + 1, t -> {
            if (!e.isValid()) return false;
            if (t < impact) {
                double k = (double) t / impact;
                if (t % 2 == 0) {
                    if (cone) {
                        Fx.cone(from, dir, angle, radius, edge, Color.fromRGB(0x5A1A1A));
                    } else {
                        Fx.ring(from, radius, edge, 1.2f, 0);
                        // the filling: a second ring grows from the middle to the edge
                        Fx.ring(from, Math.max(0.3, radius * k), Color.fromRGB(0xFFB02E), 1.4f, t * 0.1);
                    }
                }
                if (t % 5 == 0) w.playSound(from, Sound.BLOCK_ANVIL_LAND, 0.25f + (float) k * 0.4f, 0.5f + (float) k * 0.6f);
                return true;
            }
            if (e instanceof org.bukkit.entity.Mob m) m.swingMainHand();
            w.playSound(from, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.6f);
            w.playSound(from, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.6f, 0.5f);
            fx.crater(cone ? from.clone().add(dir.clone().multiply(radius / 2)) : from, cone ? radius * 0.6 : radius, 40, 0.3, debris);
            fx.shockwave(from, radius, 8, 0.45);
            w.spawnParticle(Particle.BLOCK, from.clone().add(0, 0.3, 0), 50, radius / 2, 0.3, radius / 2, 0.15, debris.createBlockData());
            for (Player p : within(from, radius + 0.4, 2.5)) {
                Vector to = p.getLocation().toVector().subtract(from.toVector()).setY(0);
                if (cone && to.lengthSquared() > 0.5 && Math.toDegrees(to.angle(dir)) > angle / 2) continue;
                c.hit(p, 2.5);
                afflict(ab, p);
                p.setVelocity(flat(to).multiply(ab.number("knock", 1.1)).setY(ab.number("launch", 0.5)));
                int daze = (int) ab.number("daze", 0);
                if (daze > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, daze, 3, false, false));
            }
            return false;
        }, null);
    }

    // ---------------------------------------------------------------- aimed shot

    /**
     * A sniper: a thin line follows the player for {@code aim} ticks, turns red and stops for {@code lock} ticks (the
     * time to step aside), then the shot flies along it at once, through everybody on it.
     */
    private void aimedShot(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int aim = (int) ab.number("aim", 30), lock = (int) ab.number("lock", 10);
        double range = ab.number("range", 26), width = ab.number("width", 0.7);
        Color line = color(ab, "color", 0xFFD94D);
        Material m = material(ab, "item", Material.ARROW);
        c.hold(aim + lock + 6);
        w.playSound(e.getLocation(), Sound.ITEM_CROSSBOW_LOADING_START, 1.4f, 0.7f);
        Vector[] dir = new Vector[1];
        double[] length = {range};
        every(aim + lock, t -> {
            if (!e.isValid() || !target.isOnline() || target.getWorld() != w) return false;
            Location eye = e.getEyeLocation();
            if (t < aim) {
                if (e instanceof org.bukkit.entity.Mob mob) mob.lookAt(target);
                dir[0] = target.getLocation().add(0, 1.1, 0).toVector().subtract(eye.toVector()).normalize();
                if (t == aim / 2) w.playSound(eye, Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 1.4f, 0.8f);
            } else if (t == aim) {
                w.playSound(eye, Sound.ITEM_CROSSBOW_LOADING_END, 1.6f, 1.2f);
                w.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
            }
            RayTraceResult wall = w.rayTraceBlocks(eye, dir[0], range, org.bukkit.FluidCollisionMode.NEVER, true);
            length[0] = wall == null ? range : wall.getHitPosition().distance(eye.toVector());
            if (t % 2 == 0) {
                Color now = t < aim ? line : Color.fromRGB(0xFF2020);
                float thick = t < aim ? 0.5f : 0.9f;
                for (double s = 1; s < length[0]; s += 0.5) {
                    w.spawnParticle(Particle.DUST, eye.clone().add(dir[0].clone().multiply(s)), 1, 0, 0, 0, 0, new Particle.DustOptions(now, thick));
                }
            }
            return true;
        }, () -> {
            if (!e.isValid() || dir[0] == null) return;
            Location eye = e.getEyeLocation();
            w.playSound(eye, Sound.ITEM_CROSSBOW_SHOOT, 1.8f, 0.7f);
            w.playSound(eye, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.2f, 1.4f);
            ItemDisplay bolt = item(eye.clone(), m, 0.8f);
            Set<UUID> hit = new HashSet<>();
            double[] s = {0};
            every((int) (length[0] / 2.5) + 1, t -> {
                s[0] = Math.min(length[0], s[0] + 2.5);
                Location at = eye.clone().add(dir[0].clone().multiply(s[0]));
                at.setDirection(dir[0]);
                bolt.teleport(at);
                w.spawnParticle(Particle.CRIT, at, 4, 0.1, 0.1, 0.1, 0.05);
                for (Player p : w.getPlayers()) {
                    if (!Mobs.fighting(p) || hit.contains(p.getUniqueId())) continue;
                    Vector rel = p.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector());
                    double along = rel.dot(dir[0]);
                    if (along < 0 || along > s[0]) continue;
                    if (rel.clone().subtract(dir[0].clone().multiply(along)).length() > width + 0.4) continue;
                    hit.add(p.getUniqueId());
                    c.hit(p, 2.2);
                    afflict(ab, p);
                    p.setVelocity(flat(dir[0]).multiply(ab.number("knock", 0.6)).setY(0.3));
                    w.playSound(p.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 1.2f, 0.8f);
                }
                return s[0] < length[0];
            }, () -> drop(bolt));
        });
    }

    // ---------------------------------------------------------------- blink

    /**
     * Gathers smoke for {@code impact} ticks, vanishes and comes out right behind the player (or beside him when his
     * back is against a wall), then strikes {@code strike} ticks later if he is still within reach.
     */
    private void blink(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int impact = (int) ab.number("impact", 12), strike = (int) ab.number("strike", 7);
        Color tint = color(ab, "color", 0x5B2D9E);
        c.hold(impact + strike + 6);
        w.playSound(e.getLocation(), Sound.ENTITY_ENDERMAN_STARE, 0.6f, 1.6f);
        every(impact, t -> {
            if (!e.isValid()) return false;
            if (t % 2 == 0) {
                w.spawnParticle(Particle.DUST, e.getLocation().add(0, 1, 0), 8, 0.4, 0.7, 0.4, 0, new Particle.DustOptions(tint, 1.2f));
                w.spawnParticle(Particle.PORTAL, e.getLocation().add(0, 1, 0), 6, 0.3, 0.5, 0.3, 0.4);
            }
            return true;
        }, () -> {
            if (!e.isValid() || !target.isOnline() || target.getWorld() != w || !Mobs.fighting(target)) {
                c.release();
                return;
            }
            Location base = target.getLocation();
            Vector back = flat(base.getDirection()).multiply(-ab.number("behind", 1.8));
            Location spot = null;
            for (Vector v : List.of(back, back.clone().rotateAroundY(Math.PI / 2), back.clone().rotateAroundY(-Math.PI / 2), back.clone().multiply(-1))) {
                Location l = floor(base.clone().add(v));
                if (l != null && Math.abs(l.getY() - base.getY()) < 2) {
                    spot = l;
                    break;
                }
            }
            if (spot == null) {
                c.release();
                return;
            }
            Location gone = e.getLocation();
            w.spawnParticle(Particle.LARGE_SMOKE, gone.clone().add(0, 1, 0), 25, 0.3, 0.6, 0.3, 0.02);
            w.playSound(gone, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 0.8f);
            spot.setDirection(base.toVector().subtract(spot.toVector()));
            Mobs.move(e, spot);
            w.spawnParticle(Particle.LARGE_SMOKE, spot.clone().add(0, 1, 0), 25, 0.3, 0.6, 0.3, 0.02);
            w.spawnParticle(Particle.DUST, spot.clone().add(0, 1, 0), 20, 0.4, 0.7, 0.4, 0, new Particle.DustOptions(tint, 1.4f));
            w.playSound(spot, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 1.2f);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!e.isValid()) return;
                c.release();
                if (e instanceof org.bukkit.entity.Mob m) m.swingMainHand();
                w.playSound(e.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.4f, 0.8f);
                w.spawnParticle(Particle.SWEEP_ATTACK, e.getLocation().add(flat(e.getLocation().getDirection())).add(0, 1, 0), 1, 0, 0, 0, 0);
                for (Player p : within(e.getLocation(), ab.number("reach", 2.6), 2)) {
                    c.hit(p, 1.6);
                    afflict(ab, p);
                }
            }, strike);
        });
    }

    // ---------------------------------------------------------------- fuse

    /** Swells and flashes for {@code fuse} ticks (its blast zone shown), then blows up and dies of it. */
    private void fuse(MobTemplate.Ability ab, RockPowers.Caster c) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int fuse = (int) ab.number("fuse", 24);
        double radius = ab.number("radius", 3.5);
        Color warn = color(ab, "color", 0xFF5C5C);
        c.hold(fuse + 10);
        w.playSound(e.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 1.6f, 0.8f);
        AttributeInstance scale = e.getAttribute(Attribute.SCALE);
        double size = scale == null ? 1 : scale.getBaseValue();
        every(fuse, t -> {
            if (!e.isValid()) return false;
            double k = (double) t / fuse;
            Location at = e.getLocation();
            // swells and flashes faster and faster
            if (scale != null) scale.setBaseValue(size * (1 + 0.35 * k + 0.05 * Math.sin(t * (0.6 + k))));
            e.setGlowing((t / Math.max(1, (int) (6 - 4 * k))) % 2 == 0);
            if (t % 2 == 0) Fx.ring(at, radius, warn, 1.3f, t * 0.25);
            w.spawnParticle(Particle.SMOKE, at.clone().add(0, e.getHeight(), 0), 2, 0.1, 0.1, 0.1, 0.02);
            if (t % 3 == 0) w.spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0, e.getHeight() / 2, 0), 4, 0.3, 0.3, 0.3, 0.05);
            return true;
        }, () -> {
            if (!e.isValid()) return;
            Location at = e.getLocation();
            e.setGlowing(false);
            if (scale != null) scale.setBaseValue(size);
            w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.9f);
            w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1, 0, 0, 0, 0);
            w.spawnParticle(Particle.FLAME, at.clone().add(0, 0.5, 0), 40, radius / 3, 0.4, radius / 3, 0.08);
            fx.shockwave(at, radius, 6, 0.5);
            for (Player p : within(at, radius, 2.5)) {
                double d = p.getLocation().distance(at);
                // full in the middle, half at the edge
                c.hitShare(p, 3, 1 - 0.5 * Math.min(1, d / radius));
                afflict(ab, p);
                p.setVelocity(flat(p.getLocation().toVector().subtract(at.toVector())).multiply(1.2).setY(0.55));
            }
            e.setHealth(0);
        });
    }

    // ---------------------------------------------------------------- rally

    /** Dungeon monsters (not bosses) around it whose life is under that share. */
    private static List<LivingEntity> hurtAllies(LivingEntity e, double radius, double below) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity n : e.getNearbyEntities(radius, 4, radius)) {
            if (!(n instanceof LivingEntity le) || n instanceof Player || !le.getScoreboardTags().contains(Mobs.TAG)
                    || le.getScoreboardTags().contains(Mobs.BOSS_TAG) || le.isDead()) continue;
            AttributeInstance max = le.getAttribute(Attribute.MAX_HEALTH);
            if (max != null && le.getHealth() < max.getValue() * below) out.add(le);
        }
        return out;
    }

    /**
     * A support: a beam to each hurt monster around it, which gets back {@code heal} percent of its life and is
     * hardened ({@code shield} ticks of Resistance) and hastened ({@code haste} ticks of Speed). Kill it first.
     */
    private void rally(MobTemplate.Ability ab, RockPowers.Caster c) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int impact = (int) ab.number("impact", 16);
        double radius = ab.number("radius", 10);
        Color tint = color(ab, "color", 0x2ECF7B);
        List<LivingEntity> allies = hurtAllies(e, radius, ab.number("below", 85) / 100);
        if (allies.isEmpty()) return;
        c.hold(impact + 6);
        w.playSound(e.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 1.4f);
        every(impact, t -> {
            if (!e.isValid()) return false;
            if (t % 2 == 0) {
                Fx.ring(e.getLocation(), 1.4, tint, 1.2f, t * 0.3);
                w.spawnParticle(Particle.ENCHANT, e.getLocation().add(0, 2, 0), 6, 0.4, 0.3, 0.4, 0.5);
            }
            return true;
        }, () -> {
            if (!e.isValid()) return;
            Location from = e.getEyeLocation();
            w.playSound(from, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.6f, 1.2f);
            for (LivingEntity ally : allies) {
                if (!ally.isValid() || ally.isDead()) continue;
                Location to = ally.getLocation().add(0, ally.getHeight() / 2, 0);
                Vector line = to.toVector().subtract(from.toVector());
                for (double s = 0; s < line.length(); s += 0.4) {
                    w.spawnParticle(Particle.DUST, from.clone().add(line.clone().normalize().multiply(s)), 1, 0, 0, 0, 0, new Particle.DustOptions(tint, 1f));
                }
                AttributeInstance max = ally.getAttribute(Attribute.MAX_HEALTH);
                if (max != null) ally.setHealth(Math.min(max.getValue(), ally.getHealth() + max.getValue() * ab.number("heal", 15) / 100));
                int shield = (int) ab.number("shield", 60), haste = (int) ab.number("haste", 0);
                if (shield > 0) ally.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, shield, 0, false, false));
                if (haste > 0) ally.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, haste, 1, false, false));
                w.spawnParticle(Particle.HEART, to.clone().add(0, 0.6, 0), 3, 0.3, 0.3, 0.3, 0);
                w.spawnParticle(Particle.HAPPY_VILLAGER, to, 10, 0.4, 0.5, 0.4, 0);
            }
        });
    }
}
