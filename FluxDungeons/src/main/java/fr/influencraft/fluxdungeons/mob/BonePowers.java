package fr.influencraft.fluxdungeons.mob;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Abilities of the Ossacorne, the skeleton of an ancestral four-legged predator with ram horns. Every attack comes
 * from its anatomy: the horns charge, the tail sweeps, the dorsal plates are thrown, the spine and the four legs make
 * the ground bristle with bones. Every timing follows its model's animations tick for tick
 * (docs/technique/boss/ossacorne.md, assets/monstres/ossacorne_anim.py):
 * <ul>
 *   <li>{@code bone_awaken} (awaken 190, the opening move): lying in the dust, it wakes up, unfolds, roars at 122 and
 *   takes its guard; invulnerable meanwhile;</li>
 *   <li>{@code bone_charge} (charge 50): skull lowered, the line of the rush on the ground (locked at 12), it rushes at
 *   20 for {@code distance} blocks, horns first: whoever the skull meets is hit, thrown and unbalanced; into a wall:
 *   stunned (charge_stun 40) and vulnerable;</li>
 *   <li>{@code bone_sweep} (tail_sweep 56): the pelvis turns, the tail sweeps an arc behind it and on its sides from
 *   its left to its right between ticks 22 and 32: whoever it passes is hit and thrown sideways, his action cut;</li>
 *   <li>{@code bone_volley} (bone_volley 70): three fragments tear off its hind plates (12, 16, 20), hover behind its
 *   skull, turn to the farthest player (24) and are thrown at 32, 37 and 42: aimed where he is, where he goes, where
 *   he is; never homing; an arrow breaks a fragment;</li>
 *   <li>{@code bone_ossuary} (ossuary 120, ultimate): legs spread, it trembles, its four feet strike the ground (40,
 *   42), bone spikes rise in three groups around it (44, 48, 52) and stay a while as obstacles, it roars (62) and a
 *   shockwave leaves at 64: a spike between it and a player shelters him.</li>
 * </ul>
 * Vulnerable windows (charge against a wall, end of the sweep, end of the ultimate): it stays still and its bones
 * glow. Nothing ever changes in the map: spikes and fragments are block displays.
 */
final class BonePowers {

    static final Set<String> TYPES = Set.of("bone_awaken", "bone_charge", "bone_sweep", "bone_volley", "bone_ossuary");
    private static final String TAG = "fluxdungeons_fx";
    private static final BlockData BONE = Material.BONE_BLOCK.createBlockData();
    private static final Color DUST = Color.fromRGB(0xD8C3A0), WHITE = Color.fromRGB(0xFFF1D9), WARN = Color.fromRGB(0xC5A986),
            RED = Color.fromRGB(0xFF5C5C);
    /** Outline of its bones while it is open to a counter-attack. */
    private static final int EXPOSED = 0xE9D8B8;
    private static final Material[] CRACKED = {Material.GRAVEL, Material.COARSE_DIRT, Material.COBBLESTONE};

    /** A bone spike of the ultimate, standing for a while. */
    private record Spike(Location at, double radius, List<BlockDisplay> parts) {
    }

    private final Plugin plugin;
    private final Fx fx;
    private final Set<BlockDisplay> alive = new HashSet<>();
    private final List<Spike> spikes = new ArrayList<>();
    private final Map<UUID, LivingEntity> bones = new HashMap<>();
    private final Map<UUID, Location> lastPos = new HashMap<>();
    private final Map<UUID, Double> walked = new HashMap<>();
    /** Recent places of each player (for the volley's aim ahead of a moving archer). */
    private final Map<UUID, Deque<Vector>> tracks = new HashMap<>();
    private int ticks;

    BonePowers(Plugin plugin, Fx fx) {
        this.plugin = plugin;
        this.fx = fx;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    boolean cast(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        if (!TYPES.contains(ab.type())) return false;
        bones.put(c.entity().getUniqueId(), c.entity());
        switch (ab.type()) {
            case "bone_awaken" -> awaken(ab, c, target);
            case "bone_charge" -> charge(ab, c, target);
            case "bone_sweep" -> sweep(ab, c);
            case "bone_volley" -> volley(ab, c);
            case "bone_ossuary" -> ossuary(ab, c);
            default -> {
            }
        }
        return true;
    }

    /**
     * Whether casting now makes sense: the charge wants someone in front (not too close, not too far), the sweep
     * someone behind it or on its sides (or two players around it), the volley someone keeping away in sight, the
     * ultimate someone around it.
     */
    boolean worth(MobTemplate.Ability ab, LivingEntity e) {
        if (!TYPES.contains(ab.type())) return true;
        bones.put(e.getUniqueId(), e);
        return switch (ab.type()) {
            case "bone_charge" -> {
                double min = ab.number("target-min", 3), max = ab.number("target-max", 12);
                for (Player p : fighters(e.getLocation(), max)) {
                    double d = p.getLocation().distance(e.getLocation());
                    if (d >= min && Math.abs(side(e, p.getLocation())) <= 40 && e.hasLineOfSight(p)) yield true;
                }
                yield false;
            }
            case "bone_sweep" -> {
                double r = ab.number("radius", 4.8);
                List<Player> near = fighters(e.getLocation(), r);
                boolean behind = near.stream().anyMatch(p -> Math.abs(side(e, p.getLocation())) >= 70);
                yield behind || near.size() >= 2;
            }
            case "bone_volley" -> farthest(e, ab.number("min-range", 8), ab.number("max-range", 30)) != null;
            case "bone_ossuary" -> !fighters(e.getLocation(), ab.number("wave-radius", 9)).isEmpty();
            default -> true;
        };
    }

    void clear() {
        for (BlockDisplay d : alive) if (d.isValid()) d.remove();
        alive.clear();
        spikes.clear();
        bones.clear();
    }

    // ---------------------------------------------------------------- every tick: steps, spikes, archers

    private void tick() {
        ticks++;
        bones.values().removeIf(e -> !e.isValid());
        // dust and a bone sound at the front feet while it walks (one foot every half stride)
        for (LivingEntity e : bones.values()) {
            Location at = e.getLocation(), last = lastPos.put(e.getUniqueId(), at.clone());
            if (last == null || last.getWorld() != at.getWorld()) continue;
            double moved = Math.hypot(at.getX() - last.getX(), at.getZ() - last.getZ());
            if (moved < 0.02 || moved > 1.5) continue;
            double w = walked.merge(e.getUniqueId(), moved, Double::sum);
            if (w < 0.66) continue;
            walked.put(e.getUniqueId(), 0.0);
            Vector f = forward(e), r = right(f);
            int s = (ticks / 11) % 2 == 0 ? 1 : -1;
            Location paw = at.clone().add(f.clone().multiply(0.6)).add(r.clone().multiply(1.1 * s)).add(0, 0.1, 0);
            w(e).spawnParticle(Particle.BLOCK, paw, 6, 0.25, 0.05, 0.25, 0, blockUnder(paw));
        }
        // spikes are obstacles: whoever walks into one is pushed out of it
        spikes.removeIf(s -> s.parts().stream().noneMatch(BlockDisplay::isValid));
        for (Spike s : spikes) {
            for (Player p : fighters(s.at(), s.radius() + 0.4)) {
                Vector out = p.getLocation().toVector().subtract(s.at().toVector()).setY(0);
                if (out.lengthSquared() < 1e-4) out = new Vector(1, 0, 0);
                if (Math.abs(p.getLocation().getY() - s.at().getY()) > 2.5) continue;
                Vector v = p.getVelocity();
                p.setVelocity(new Vector(out.normalize().getX() * 0.32, Math.max(v.getY(), 0.05), out.getZ() * 0.32));
            }
        }
        // recent places of the players near an Ossacorne (aim of the second fragment)
        if (ticks % 2 == 0 && !bones.isEmpty()) {
            for (LivingEntity e : bones.values()) {
                for (Player p : fighters(e.getLocation(), 40)) {
                    Deque<Vector> q = tracks.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
                    q.addLast(p.getLocation().toVector());
                    while (q.size() > 6) q.removeFirst();
                }
            }
        }
    }

    // ---------------------------------------------------------------- shared

    private static World w(LivingEntity e) {
        return e.getWorld();
    }

    private static void snd(Location at, String key, float volume, float pitch) {
        at.getWorld().playSound(at, key, SoundCategory.HOSTILE, volume, pitch);
    }

    private static BlockData blockUnder(Location at) {
        Block b = Fx.ground(at.getWorld(), at.getBlockX(), at.getY(), at.getBlockZ());
        return b == null ? Material.STONE.createBlockData() : b.getBlockData();
    }

    private static List<Player> fighters(Location at, double within) {
        List<Player> out = new ArrayList<>();
        for (Player p : at.getWorld().getPlayers()) {
            if (Mobs.fighting(p) && p.getLocation().distanceSquared(at) <= within * within) out.add(p);
        }
        return out;
    }

    private static Vector forward(LivingEntity e) {
        Vector f = e.getLocation().getDirection().setY(0);
        return f.lengthSquared() < 1e-6 ? new Vector(0, 0, 1) : f.normalize();
    }

    /** Its right hand side. */
    private static Vector right(Vector f) {
        return new Vector(-f.getZ(), 0, f.getX());
    }

    private static Vector flat(Location from, Location to) {
        Vector v = to.toVector().subtract(from.toVector()).setY(0);
        return v.lengthSquared() < 1e-6 ? from.getDirection().setY(0).normalize() : v.normalize();
    }

    private static float yawTo(Vector dir) {
        return (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
    }

    /** Signed angle of a place from its front, in degrees: > 0 on its right, < 0 on its left. */
    private static double side(LivingEntity e, Location at) {
        Vector f = forward(e), to = flat(e.getLocation(), at);
        return Math.toDegrees(Math.atan2(right(f).dot(to), f.dot(to)));
    }

    /** A point of its body: {@code x} blocks to its right, {@code y} up, {@code back} behind its origin. */
    private static Location body(LivingEntity e, double x, double y, double back) {
        Vector f = forward(e);
        return e.getLocation().clone().add(right(f).multiply(x)).add(0, y, 0).add(f.multiply(-back));
    }

    private static double groundY(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int by = (int) Math.floor(y + 1.2); by >= (int) Math.floor(y) - 4; by--) {
            Block b = w.getBlockAt(bx, by, bz);
            if (b.isCollidable()) return by + b.getBoundingBox().getHeight();
        }
        return y;
    }

    private static Location onGround(Location l) {
        Location g = l.clone();
        g.setY(groundY(l.getWorld(), l.getX(), l.getY(), l.getZ()));
        return g;
    }

    private static Block wallAhead(LivingEntity e, Vector dir, double ahead) {
        Location front = e.getLocation().clone().add(dir.clone().multiply(ahead));
        for (double h : new double[]{0.6, 1.5}) {
            Block b = front.clone().add(0, h, 0).getBlock();
            if (b.isCollidable()) return b;
        }
        return null;
    }

    private static Location step(LivingEntity e, Vector dir, double speed) {
        Location at = e.getLocation().clone().add(dir.clone().multiply(speed));
        at.setY(groundY(e.getWorld(), at.getX(), at.getY(), at.getZ()));
        at.setYaw(yawTo(dir));
        at.setPitch(0);
        Mobs.move(e, at);
        return at;
    }

    private static void face(LivingEntity e, Location to) {
        e.setRotation(yawTo(flat(e.getLocation(), to)), 0);
    }

    /** The screens of nearby players shake (a hurt shake without damage). */
    private static void shake(Location at, double within) {
        for (Player p : fighters(at, within)) p.sendHurtAnimation(0);
    }

    private static void title(Location at, MobTemplate.Ability ab, int ticks) {
        String title = ab.text("title", ""), subtitle = ab.text("subtitle", "");
        if (title.isBlank() && subtitle.isBlank()) return;
        for (Player p : fighters(at, 48)) {
            p.showTitle(net.kyori.adventure.title.Title.title(fr.influencraft.flux.api.text.Texts.parse(title),
                    fr.influencraft.flux.api.text.Texts.parse(subtitle), net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(Math.max(500, ticks * 50L)),
                            java.time.Duration.ofMillis(400))));
        }
    }

    private static void bonesDust(Location at, int count, double spread) {
        at.getWorld().spawnParticle(Particle.FALLING_DUST, at, count, spread, spread * 0.5, spread, 0, BONE);
        at.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, at, Math.max(1, count / 2), spread, spread * 0.5, spread, 0,
                new Particle.DustTransition(DUST, WHITE, 1.1f));
    }

    private static void unbalance(Player p, int ticks) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 1, false, true));
        p.clearActiveItem();
    }

    private void timeline(LivingEntity e, int length, java.util.function.IntConsumer body) {
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (!e.isValid() || e.isDead() || t > length) {
                    cancel();
                    return;
                }
                body.accept(t);
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Still and glowing for {@code ticks}: the window to hit it back. */
    private void exposed(RockPowers.Caster c, int ticks) {
        c.glow(EXPOSED);
        Bukkit.getScheduler().runTaskLater(plugin, () -> c.glow(-1), ticks);
    }

    private static Player farthest(LivingEntity e, double min, double max) {
        Player best = null;
        double bestD = -1;
        for (Player p : fighters(e.getLocation(), max)) {
            double d = p.getLocation().distance(e.getLocation());
            if (d >= min && d > bestD && e.hasLineOfSight(p)) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- awakening (cinematic opening)

    private void awaken(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        int length = 190;
        c.hold(length + 2);
        c.animate("awaken");
        e.setInvulnerable(true);
        if (target != null) face(e, target.getLocation());
        title(e.getLocation(), ab, 70);
        timeline(e, length, t -> {
            Location at = e.getLocation();
            // 0-30 dust over the bones, a deep rumble, stone and bone
            if (t == 0) {
                snd(at, "entity.ravager.ambient", 1.6f, 0.4f);
                snd(at, "block.stone.break", 1.2f, 0.5f);
                fx.crater(at, 3.5, 60, 0.15, CRACKED);
            }
            if (t < 30 && t % 4 == 0) bonesDust(body(e, 0, 1.0, 0.8), 6, 1.3);
            if (t == 12 || t == 24) snd(at, "block.bone_block.break", 1.0f, 0.5f);
            if (t == 18) snd(at, "block.bone_block.hit", 1.0f, 0.6f);
            // 30-60 the skull rises: bone cracks one after the other, a guttural growl
            if (t >= 30 && t <= 60 && t % 6 == 0) snd(at, "block.bone_block.break", 1.2f, 0.55f + (t - 30) * 0.01f);
            if (t == 40) snd(at, "entity.ravager.ambient", 1.6f, 0.5f);
            if (t == 46 && target != null && target.isValid()) face(e, target.getLocation());
            // 60-110 the legs unfold, the tail rises
            if (t >= 60 && t <= 110 && t % 5 == 0) snd(at, "block.bone_block.step", 1.2f, 0.5f + ThreadLocalRandom.current().nextFloat() * 0.2f);
            if (t == 70 || t == 92) w(e).spawnParticle(Particle.BLOCK, at, 30, 1.6, 0.1, 1.6, 0, blockUnder(at));
            if (t == 100) snd(at, "entity.skeleton.ambient", 1.2f, 0.4f);
            // 122 the roar: bone dust, stone, the ground shakes
            if (t == 122) {
                snd(at, "entity.ravager.roar", 2.2f, 0.55f);
                snd(at, "block.bone_block.break", 1.8f, 0.4f);
                snd(at, "block.stone.break", 1.4f, 0.5f);
                bonesDust(body(e, 0, 1.6, -1.6), 30, 1.0);
                fx.debris(at, 10, Material.STONE.createBlockData(), 0.6);
                fx.shockwave(at, 4, 8, 0.25);
                shake(at, 20);
            }
            // 150-190 it fixes its target and takes its guard
            if (t == 156) snd(at, "block.bone_block.hit", 1.2f, 0.6f);
            if (t == 176 && target != null && target.isValid()) face(e, target.getLocation());
            if (t == length) {
                e.setInvulnerable(false);
                c.release();
            }
        });
    }

    // ---------------------------------------------------------------- Charge funeraire

    private void charge(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = w(e);
        double power = ab.number("charge-power", 9), speed = ab.number("speed", 0.4), distance = ab.number("distance", 4.8),
                width = ab.number("width", 1.6);
        int launch = 20, lock = 12, runTicks = (int) Math.ceil(distance / speed);
        Vector[] dir = {flat(e.getLocation(), target.getLocation())};
        Set<UUID> struck = new HashSet<>();
        boolean[] stopped = {false};
        c.hold(52);
        c.animate("charge");
        snd(e.getLocation(), "entity.ravager.ambient", 1.6f, 0.45f);
        timeline(e, 50, t -> {
            Location at = e.getLocation();
            if (t <= lock && target.isValid() && Mobs.fighting(target)) {
                dir[0] = flat(at, target.getLocation());
                e.setRotation(yawTo(dir[0]), 0);
            }
            Vector f = dir[0], r = right(f);
            if (t == 6 || t == 10) snd(at, "block.bone_block.break", 1.4f, 0.5f);
            if (t >= 4 && t < 14 && t % 3 == 0) w.spawnParticle(Particle.BLOCK, body(e, 0, 0.1, 2.0), 6, 0.6, 0.05, 0.4, 0, blockUnder(at));
            // the line of the rush (beige while it can still turn, red once locked)
            if (t >= 2 && t < launch && t % 2 == 0) {
                Color col = t > lock ? RED : WARN;
                for (double k = 2.8; k <= distance + 3.2; k += 0.7) {
                    Location p = at.clone().add(f.clone().multiply(k));
                    p.setY(groundY(w, p.getX(), p.getY(), p.getZ()) + 0.15);
                    w.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.3f));
                }
            }
            if (t == 14) {
                snd(at, "entity.ravager.step", 1.6f, 0.5f);
                w.spawnParticle(Particle.BLOCK, body(e, 0, 0.2, 2.2), 24, 0.8, 0.1, 0.8, 0, blockUnder(at));
                bonesDust(body(e, 0, 0.3, 2.2), 8, 0.8);
            }
            if (t == launch) {
                snd(at, "entity.ravager.roar", 1.6f, 0.6f);
                snd(at, "entity.breeze.charge", 1.2f, 0.5f);
            }
            if (t < launch || stopped[0]) {
                if (t == 38 && !stopped[0]) exposed(c, 12);
                return;
            }
            if (t >= launch + runTicks) {
                stopped[0] = true;
                snd(at, "block.bone_block.step", 1.6f, 0.5f);
                w.spawnParticle(Particle.BLOCK, at.clone().add(f.clone().multiply(1.6)), 20, 0.8, 0.1, 0.8, 0, blockUnder(at));
                return;
            }
            // rushing in a straight line; a wall ends it (stunned)
            Block wall = wallAhead(e, f, 3.0 + speed);
            if (wall != null) {
                stopped[0] = true;
                crash(c, wall);
                return;
            }
            Location now = step(e, f, speed);
            if (t % 2 == 0) {
                w.spawnParticle(Particle.BLOCK, now.clone().add(r.clone().multiply(1.1)), 4, 0.2, 0.05, 0.2, 0, blockUnder(now));
                w.spawnParticle(Particle.BLOCK, now.clone().add(r.clone().multiply(-1.1)), 4, 0.2, 0.05, 0.2, 0, blockUnder(now));
                snd(now, "block.bone_block.step", 1.4f, 0.6f);
            }
            // skull and horns: 2.6 blocks in front of its body, a little above the ground
            Location skull = now.clone().add(f.clone().multiply(2.6));
            for (Player p : fighters(now, 6)) {
                if (struck.contains(p.getUniqueId())) continue;
                Location pl = p.getLocation();
                if (Math.hypot(pl.getX() - skull.getX(), pl.getZ() - skull.getZ()) > width || Math.abs(pl.getY() - now.getY()) > 2.5) continue;
                struck.add(p.getUniqueId());
                c.hit(p, power);
                p.setVelocity(f.clone().multiply(1.5).setY(0.55));
                unbalance(p, 30);
                snd(pl, "entity.ravager.attack", 1.6f, 0.6f);
                snd(pl, "block.bone_block.break", 1.4f, 0.5f);
                fx.debris(pl.clone().add(0, 0.6, 0), 6, BONE, 0.9);
            }
        });
    }

    /** Rammed a wall: horns in the stone, dust, stunned and open to a counter-attack (charge_stun, 40 ticks). */
    private void crash(RockPowers.Caster c, Block wall) {
        LivingEntity e = c.entity();
        World w = w(e);
        c.hold(42);
        c.animate("charge_stun");
        exposed(c, 40);
        Location hit = wall.getLocation().add(0.5, 0.5, 0.5);
        snd(hit, "entity.generic.explode", 1.0f, 0.7f);
        snd(hit, "block.stone.break", 1.8f, 0.5f);
        snd(hit, "block.bone_block.break", 1.8f, 0.6f);
        fx.debris(hit, 12, wall.getBlockData(), 1.1);
        bonesDust(hit, 16, 0.6);
        shake(hit, 12);
        timeline(e, 40, t -> {
            if (t >= 6 && t <= 30 && t % 4 == 0) {
                Location head = body(e, 0, 2.2, -2.2);
                for (int i = 0; i < 3; i++) {
                    double b = t * 0.35 + i * Math.PI * 2 / 3;
                    w.spawnParticle(Particle.DUST, head.clone().add(Math.cos(b) * 0.6, 0, Math.sin(b) * 0.6), 1, 0, 0, 0, 0,
                            new Particle.DustOptions(WHITE, 1f));
                }
            }
            if (t == 30) snd(e.getLocation(), "entity.ravager.ambient", 1.2f, 0.55f);
            if (t == 40) c.release();
        });
    }

    // ---------------------------------------------------------------- Balayage des vertebres

    private void sweep(MobTemplate.Ability ab, RockPowers.Caster c) {
        LivingEntity e = c.entity();
        World w = w(e);
        double power = ab.number("sweep-power", 7), radius = ab.number("radius", 4.8), near = ab.number("near", 3.0);
        int from = 22, to = 32;
        // angle of the tail from straight behind it (degrees, > 0 to its right), linear over the sweep
        double start = -100, end = 100;
        Set<UUID> struck = new HashSet<>();
        c.hold(58);
        c.animate("tail_sweep");
        timeline(e, 56, t -> {
            Location at = e.getLocation();
            if (t <= 12 && t % 4 == 0) snd(at, "block.bone_block.break", 1.2f, 0.5f + t * 0.02f);
            if (t == 6) snd(at, "entity.ravager.ambient", 1.4f, 0.45f);
            if (t == 20) snd(at, "item.trident.throw", 1.8f, 0.45f);
            if (t == 24) snd(at, "entity.player.attack.sweep", 1.8f, 0.5f);
            if (t >= 28 && t <= 34 && t % 2 == 0) {
                Location back = body(e, 1.5, 0.15, 3.2);
                w.spawnParticle(Particle.BLOCK, back, 14, 1.4, 0.05, 0.8, 0, blockUnder(back));
            }
            if (t == 40) exposed(c, 16);
            if (t < from || t > to) return;
            double a0 = start + (end - start) * (t - 1 - from) / (to - from), a1 = start + (end - start) * (t - from) / (to - from);
            Vector f = forward(e), back = f.clone().multiply(-1), r = right(f);
            // the tail tip, drawn where the sweep is now
            double rad = Math.toRadians(a1);
            Vector dirNow = back.clone().multiply(Math.cos(rad)).add(r.clone().multiply(Math.sin(rad)));
            for (double k = 2.4; k <= radius; k += 0.8)
                w.spawnParticle(Particle.SWEEP_ATTACK, at.clone().add(dirNow.clone().multiply(k)).add(0, 0.9, 0), 1, 0, 0, 0, 0);
            for (Player p : fighters(at, radius)) {
                if (struck.contains(p.getUniqueId())) continue;
                double d = p.getLocation().distance(at);
                // angle from straight behind it, > 0 to its right
                double phi = 180 - Math.abs(side(e, p.getLocation()));
                phi *= side(e, p.getLocation()) >= 0 ? 1 : -1;
                boolean reached = d <= near ? Math.abs(phi) <= 110 : Math.abs(phi) <= 75;
                if (!reached || phi <= Math.min(a0, a1) - 1e-6 || phi > Math.max(a0, a1)) continue;
                struck.add(p.getUniqueId());
                c.hit(p, power);
                p.setVelocity(r.clone().multiply(1.4).add(dirNow.clone().multiply(0.4)).setY(0.45));
                unbalance(p, 20);
                snd(p.getLocation(), "entity.player.attack.knockback", 1.6f, 0.6f);
                snd(p.getLocation(), "block.bone_block.hit", 1.6f, 0.5f);
            }
        });
    }

    // ---------------------------------------------------------------- Traque des ossements

    private void volley(MobTemplate.Ability ab, RockPowers.Caster c) {
        LivingEntity e = c.entity();
        World w = w(e);
        Player target = farthest(e, ab.number("min-range", 8), ab.number("max-range", 30));
        if (target == null) return;
        double power = ab.number("shard-power", 4), speed = ab.number("shard-speed", 1.15), range = ab.number("shard-range", 34);
        int[] tear = {12, 16, 20}, throwAt = {32, 37, 42};
        // where each fragment tears off (plates 8, 7, 6: blocks behind its origin, height of their tips)
        double[][] plate = {{0, 2.4, 2.3}, {0, 2.5, 1.9}, {0, 2.65, 1.55}};
        // fan behind and above its skull (x to its right, up, behind)
        double[][] fan = {{-1.3, 3.2, 0.1}, {0, 3.7, 0.4}, {1.3, 3.2, 0.1}};
        BlockDisplay[] shards = new BlockDisplay[3];
        c.hold(72);
        c.animate("bone_volley");
        face(e, target.getLocation());
        snd(e.getLocation(), "entity.ravager.ambient", 1.4f, 0.4f);
        timeline(e, 70, t -> {
            Location at = e.getLocation();
            if (t <= 32 && target.isValid()) face(e, target.getLocation());
            if (t > 0 && t < 12 && t % 3 == 0) snd(at, "block.bone_block.step", 0.9f, 1.2f);
            for (int i = 0; i < 3; i++) {
                if (t == tear[i]) {
                    Location from = body(e, plate[i][0], plate[i][1], plate[i][2]);
                    shards[i] = shard(from);
                    bonesDust(from, 10, 0.25);
                    snd(from, "block.bone_block.break", 1.4f, 0.8f + i * 0.1f);
                }
                BlockDisplay s = shards[i];
                if (s == null || !s.isValid() || t >= throwAt[i]) continue;
                // rising to its place in the fan (8 ticks), turning slowly; pointing at the target from 24
                Location goal = body(e, fan[i][0], fan[i][1], fan[i][2]);
                Location now = s.getLocation();
                double k = Math.min(1, (t - tear[i] + 1) / 8.0);
                Location pos = now.clone().add(goal.toVector().subtract(now.toVector()).multiply(k * 0.6));
                Vector look = t >= 24 && target.isValid() ? target.getEyeLocation().toVector().subtract(pos.toVector())
                        : new Vector(Math.cos(t * 0.3 + i), 0.6, Math.sin(t * 0.3 + i));
                pos.setDirection(look);
                s.teleport(pos);
                spin(s, t * (t >= 24 ? 10 : 25));
                if (t % 3 == 0) w.spawnParticle(Particle.FALLING_DUST, pos, 1, 0.05, 0.05, 0.05, 0, BONE);
            }
            if (t == 24) snd(at, "block.amethyst_block.resonate", 1.4f, 0.5f);
            for (int i = 0; i < 3; i++) {
                if (t != throwAt[i] || shards[i] == null || !shards[i].isValid()) continue;
                Location from = shards[i].getLocation();
                Vector aim = i == 1 ? lead(target, from, speed) : target.getEyeLocation().add(0, -0.4, 0).toVector();
                Vector dir = aim.subtract(from.toVector());
                if (dir.lengthSquared() < 1e-4) dir = forward(e);
                fly(c, shards[i], dir.normalize(), speed, range, power);
                shards[i] = null;
                snd(from, "entity.arrow.shoot", 1.4f, 0.5f);
                snd(from, "block.bone_block.hit", 1.2f, 1.2f);
            }
            if (t == 46) for (BlockDisplay s : shards) if (s != null) forget(s);
        });
    }

    /** Where the target will be when a fragment thrown now reaches him (his last few ticks of movement, 90 %). */
    private Vector lead(Player p, Location from, double speed) {
        Vector now = p.getEyeLocation().add(0, -0.4, 0).toVector();
        Deque<Vector> q = tracks.get(p.getUniqueId());
        if (q == null || q.size() < 2) return now;
        Vector v = q.peekLast().clone().subtract(q.peekFirst()).multiply(1.0 / ((q.size() - 1) * 2)).setY(0);
        double travel = now.distance(from.toVector()) / speed;
        return now.add(v.multiply(travel * 0.9));
    }

    private BlockDisplay shard(Location at) {
        BlockDisplay d = at.getWorld().spawn(at, BlockDisplay.class, b -> {
            b.setBlock(BONE);
            b.setPersistent(false);
            b.setBrightness(new Display.Brightness(12, 15));
            b.setTeleportDuration(1);
            b.addScoreboardTag(TAG);
        });
        spin(d, 0);
        alive.add(d);
        return d;
    }

    /** A fragment: a long irregular point of bone (0.22 x 0.26 x 0.8 blocks) turned about its own length. */
    private static void spin(BlockDisplay d, float degrees) {
        Quaternionf q = new Quaternionf().rotateZ((float) Math.toRadians(degrees));
        Vector3f size = new Vector3f(0.22f, 0.26f, 0.8f);
        Vector3f half = q.transform(new Vector3f(-size.x / 2, -size.y / 2, -size.z / 2));
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(1);
        d.setTransformation(new Transformation(half, q, size, new Quaternionf()));
    }

    /** A thrown fragment: straight line, fast, spinning, a thin trail; an arrow breaks it, a block stops it. */
    private void fly(RockPowers.Caster c, BlockDisplay s, Vector dir, double speed, double range, double power) {
        World w = s.getWorld();
        Location[] pos = {s.getLocation().clone()};
        pos[0].setDirection(dir);
        new BukkitRunnable() {
            double gone;
            int t;

            @Override
            public void run() {
                if (!s.isValid()) {
                    cancel();
                    return;
                }
                t++;
                Location prev = pos[0].clone();
                pos[0].add(dir.clone().multiply(speed));
                gone += speed;
                s.teleport(pos[0]);
                spin(s, t * 40);
                w.spawnParticle(Particle.DUST_COLOR_TRANSITION, pos[0], 1, 0.03, 0.03, 0.03, 0,
                        new Particle.DustTransition(DUST, WHITE, 0.8f));
                // an arrow through it breaks it
                for (AbstractArrow arrow : w.getNearbyEntitiesByType(AbstractArrow.class, pos[0], 0.9)) {
                    if (arrow.isInBlock()) continue;
                    arrow.remove();
                    burst(pos[0], false);
                    forget(s);
                    cancel();
                    return;
                }
                for (Player p : fighters(pos[0], 3)) {
                    Vector body = p.getLocation().toVector().add(new Vector(0, 1.0, 0));
                    if (distToSegment(body, prev.toVector(), pos[0].toVector()) > 0.75) continue;
                    c.hit(p, power);
                    p.setVelocity(p.getVelocity().add(dir.clone().multiply(0.45).setY(0.15)));
                    snd(pos[0], "block.bone_block.break", 1.4f, 1.1f);
                    burst(pos[0], true);
                    forget(s);
                    cancel();
                    return;
                }
                if (!pos[0].getBlock().isPassable() || gone >= range) {
                    burst(pos[0], true);
                    forget(s);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void burst(Location at, boolean loud) {
        at.getWorld().spawnParticle(Particle.BLOCK, at, 14, 0.2, 0.2, 0.2, 0, BONE);
        bonesDust(at, 6, 0.2);
        snd(at, "block.bone_block.break", loud ? 1.2f : 1.0f, loud ? 0.9f : 1.4f);
    }

    private static double distToSegment(Vector p, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double len = ab.lengthSquared();
        double k = len < 1e-9 ? 0 : Math.max(0, Math.min(1, p.clone().subtract(a).dot(ab) / len));
        return p.distance(a.clone().add(ab.multiply(k)));
    }

    // ---------------------------------------------------------------- Regne du fossoyeur (ultimate)

    private void ossuary(MobTemplate.Ability ab, RockPowers.Caster c) {
        LivingEntity e = c.entity();
        World w = w(e);
        double slamPower = ab.number("slam-power", 3), spikePower = ab.number("spike-power", 4), wavePower = ab.number("wave-power", 10),
                waveRadius = ab.number("wave-radius", 9), shelter = ab.number("shelter", 0.4);
        int spikeLife = (int) ab.number("spike-life", 100), count = (int) ab.number("spikes", 10);
        Location center = e.getLocation().clone();
        List<double[]> plan = spikePlan(count, ab.number("ring-min", 3), ab.number("ring-max", 5));
        List<Spike> mine = new ArrayList<>();
        Set<UUID> waved = new HashSet<>();
        c.hold(122);
        c.animate("ossuary");
        title(center, ab, 60);
        timeline(e, 120, t -> {
            Location at = e.getLocation();
            // 0-24 it spreads its legs and lowers: dust under its feet, a deep growl
            if (t == 0) {
                snd(at, "entity.ravager.ambient", 1.8f, 0.4f);
                snd(at, "block.stone.break", 1.2f, 0.4f);
            }
            if (t < 40 && t % 4 == 0) w.spawnParticle(Particle.BLOCK, at, 10 + t / 2, 1.6, 0.05, 1.6, 0, blockUnder(at));
            // the reach of the wave, on the ground: beige, then red once it is too late
            if (t % 3 == 0 && t <= 64) Fx.ring(at, waveRadius, t >= 40 ? RED : WARN, 1.4f, t * 0.05);
            // 24-40 harder and harder bone cracks, the ground cracks
            if (t >= 24 && t < 40 && t % 3 == 0) snd(at, "block.bone_block.break", 1.4f, 0.5f + (t - 24) * 0.02f);
            if (t == 28) fx.crater(at, 5.5, 100, 0.2, CRACKED);
            if (t >= 24 && t < 40 && t % 2 == 0) w.spawnParticle(Particle.BLOCK, at, 6, 3, 0.05, 3, 0, Material.STONE.createBlockData());
            // 40 and 42: the four feet strike the ground
            if (t == 40 || t == 42) {
                snd(at, "entity.generic.explode", 1.0f, 0.5f);
                snd(at, "block.bone_block.break", 2.0f, 0.45f);
                fx.shockwave(at, 2.5, 4, 0.3);
                bonesDust(at.clone().add(0, 0.3, 0), 16, 1.6);
                shake(at, 14);
                if (t == 40) {
                    for (Player p : fighters(at, 2.4)) {
                        c.hit(p, slamPower);
                        p.setVelocity(p.getVelocity().setY(0.45));
                    }
                }
            }
            // 44, 48, 52: the spikes burst out of the ground, group after group
            for (int g = 0; g < 3; g++) {
                if (t != 44 + 4 * g) continue;
                for (double[] s : plan) {
                    if ((int) s[2] != g) continue;
                    Spike sp = spike(center, s, spikeLife, spikePower, c);
                    if (sp != null) mine.add(sp);
                }
                snd(at, "block.bone_block.break", 1.8f, 0.6f + g * 0.1f);
                snd(at, "block.stone.break", 1.4f, 0.6f);
            }
            // 62 the roar, 64 the shockwave runs out to waveRadius in 12 ticks
            if (t == 62) {
                snd(at, "entity.ravager.roar", 2.4f, 0.5f);
                snd(at, "entity.skeleton.hurt", 1.2f, 0.3f);
                shake(at, 24);
            }
            if (t >= 64 && t <= 76) {
                double r = waveRadius * (t - 64) / 12.0, r0 = waveRadius * Math.max(0, t - 65) / 12.0;
                if (t == 64) {
                    fx.shockwave(at, waveRadius, 12, 0.5);
                    snd(at, "entity.generic.explode", 1.6f, 0.45f);
                }
                for (int i = 0; i < 28; i++) {
                    double b = i * Math.PI * 2 / 28;
                    Location p = at.clone().add(Math.cos(b) * r, 0.3, Math.sin(b) * r);
                    w.spawnParticle(Particle.CLOUD, p, 1, 0.1, 0.05, 0.1, 0.01);
                    if (i % 2 == 0) w.spawnParticle(Particle.FALLING_DUST, p, 1, 0.1, 0.1, 0.1, 0, BONE);
                }
                for (Player p : fighters(at, waveRadius)) {
                    if (waved.contains(p.getUniqueId())) continue;
                    double d = Math.hypot(p.getLocation().getX() - at.getX(), p.getLocation().getZ() - at.getZ());
                    if (d > r || d < r0 - 1.5 || Math.abs(p.getLocation().getY() - at.getY()) > 3) continue;
                    waved.add(p.getUniqueId());
                    boolean safe = sheltered(at, p.getLocation(), mine);
                    if (safe) {
                        c.hitShare(p, wavePower, shelter);
                        p.setVelocity(flat(at, p.getLocation()).multiply(0.4).setY(0.25));
                        snd(p.getLocation(), "block.bone_block.hit", 1.4f, 0.8f);
                    } else {
                        c.hit(p, wavePower);
                        p.setVelocity(flat(at, p.getLocation()).multiply(1.6).setY(0.55));
                        unbalance(p, 40);
                        snd(p.getLocation(), "entity.player.attack.knockback", 1.6f, 0.5f);
                    }
                }
            }
            // small bone fragments falling back
            if (t == 66 || t == 72) fx.debris(at, 10, BONE, 1.2);
            // 80-120 it stays low: the window to hit it
            if (t == 80) exposed(c, 40);
            if (t == 118) snd(at, "block.bone_block.step", 1.2f, 0.5f);
        });
    }

    /**
     * Places of the spikes: {@code count} slots evenly around it, each turned a little and at a random distance in
     * [min, max]; two slots far apart stay empty (a way out); {x, z, group, height}.
     */
    private static List<double[]> spikePlan(int count, double min, double max) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int slots = count + 2;
        int gap = r.nextInt(slots), gap2 = (gap + slots / 2 + r.nextInt(-1, 2) + slots) % slots;
        List<double[]> out = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < slots; i++) {
            if (i == gap || i == gap2) continue;
            double a = (i + r.nextDouble(-0.25, 0.25)) * Math.PI * 2 / slots, d = r.nextDouble(min, max);
            out.add(new double[]{Math.cos(a) * d, Math.sin(a) * d, n % 3, r.nextDouble(1.4, 2.6)});
            n++;
        }
        return out;
    }

    /** A spike: three blade pieces of bone, leaning outwards, rising out of the ground in 5 ticks. */
    private Spike spike(Location center, double[] s, int life, double power, RockPowers.Caster c) {
        World w = center.getWorld();
        Location base = onGround(center.clone().add(s[0], 0, s[1]));
        if (Math.abs(base.getY() - center.getY()) > 3) return null;
        Vector out = new Vector(s[0], 0, s[1]).normalize();
        base.setDirection(out);
        base.setPitch(0);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double height = s[3];
        float lean = (float) Math.toRadians(r.nextDouble(8, 20));
        float[][] pieces = {{0.75f, 0.45f, 0.48f, 0}, {0.52f, 0.34f, 0.36f, 7}, {0.3f, 0.22f, 0.3f, 16}};   // width, depth, height share, extra lean
        List<BlockDisplay> parts = new ArrayList<>();
        Vector3f p = new Vector3f(0, 0, 0);
        List<Transformation> finals = new ArrayList<>();
        for (float[] pc : pieces) {
            float h = (float) (height * pc[2]);
            Quaternionf q = new Quaternionf().rotateY((float) Math.toRadians(r.nextDouble(-12, 12))).rotateX(lean + (float) Math.toRadians(pc[3]));
            Vector3f corner = q.transform(new Vector3f(-pc[0] / 2, 0, -pc[1] / 2)).add(p);
            finals.add(new Transformation(corner, q, new Vector3f(pc[0], h, pc[1]), new Quaternionf()));
            p.add(q.transform(new Vector3f(0, h * 0.85f, 0)));
        }
        float sink = (float) height + 0.2f;
        for (Transformation f : finals) {
            Vector3f low = new Vector3f(f.getTranslation()).sub(0, sink, 0);
            BlockDisplay d = w.spawn(base, BlockDisplay.class, b -> {
                b.setBlock(BONE);
                b.setPersistent(false);
                b.setBrightness(new Display.Brightness(12, 15));
                b.setTransformation(new Transformation(low, f.getLeftRotation(), f.getScale(), f.getRightRotation()));
                b.addScoreboardTag(TAG);
            });
            alive.add(d);
            parts.add(d);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay d = parts.get(i);
                if (!d.isValid()) continue;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(5);
                d.setTransformation(finals.get(i));
            }
        }, 1L);
        w.spawnParticle(Particle.BLOCK, base, 16, 0.4, 0.1, 0.4, 0, blockUnder(base));
        bonesDust(base.clone().add(0, 0.5, 0), 6, 0.3);
        Spike spike = new Spike(base, 0.55, parts);
        spikes.add(spike);
        // a spike rising under a player throws him up
        for (Player pl : fighters(base, 1.0)) {
            c.hit(pl, power);
            pl.setVelocity(out.clone().multiply(0.5).setY(0.7));
        }
        // it sinks back and crumbles
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay d = parts.get(i);
                if (!d.isValid()) continue;
                Transformation f = finals.get(i);
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(12);
                d.setTransformation(new Transformation(new Vector3f(f.getTranslation()).sub(0, sink, 0), f.getLeftRotation(), f.getScale(), f.getRightRotation()));
            }
            w.spawnParticle(Particle.FALLING_DUST, base.clone().add(0, 1, 0), 14, 0.3, 0.6, 0.3, 0, BONE);
            w.spawnParticle(Particle.BLOCK, base.clone().add(0, 0.8, 0), 14, 0.3, 0.6, 0.3, 0, BONE);
            snd(base, "block.bone_block.break", 0.8f, 0.7f);
        }, life);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            parts.forEach(this::forget);
            spikes.remove(spike);
        }, life + 13L);
        return spike;
    }

    /** True when a spike stands between the centre of the wave and the player (within its width of the line). */
    private static boolean sheltered(Location from, Location player, List<Spike> spikes) {
        Vector a = from.toVector().setY(0), b = player.toVector().setY(0);
        double dist = a.distance(b);
        for (Spike s : spikes) {
            if (s.parts().stream().noneMatch(BlockDisplay::isValid)) continue;
            Vector c = s.at().toVector().setY(0);
            if (c.distance(a) >= dist) continue;
            if (distToSegment(c, a, b) <= s.radius() + 0.45) return true;
        }
        return false;
    }

    private void forget(BlockDisplay d) {
        alive.remove(d);
        if (d.isValid()) d.remove();
    }
}
