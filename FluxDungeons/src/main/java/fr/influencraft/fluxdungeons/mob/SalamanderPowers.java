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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Emerald Salamander (model {@code salamandrex}): a fast amphibian predator. Two halves:
 * <ul>
 *   <li>a <b>prowl</b> that moves it whenever no ability holds it (its file says {@code stationary: true}, so the
 *   pilot only turns it to its target): it runs in (zigzag from afar), circles its prey at close or middle range,
 *   backs off after a bite, hops sideways to change its angle, pauses now and then with a small idle gesture. Every
 *   move is a step checked against walls and ledges, and the model plays the locomotion that matches the real move
 *   relative to its facing (run, strafe_right, strafe_left, retreat, hop_*). Below 40 % life it is faster and more
 *   nervous. Before its first fight it sleeps curled up ({@code sleep}).</li>
 *   <li>abilities, timed tick for tick on its animations (assets/monstres/salamandrex_anim.py, EVENTS;
 *   docs/technique/boss/salamandrex.md):
 *   {@code sala_awaken} (awaken 180, opening cinematic), {@code sala_rush} (Rush Emeraude, rush 34),
 *   {@code sala_feint} (Feinte du Predateur, feint_right / feint_left 44), {@code sala_spit} (Eclat Venimeux,
 *   spit 30), {@code sala_fury} (Furie du Predateur, ultimate, fury 100) and {@code sala_frenzy} (phase change at
 *   40 %, frenzy 40).</li>
 * </ul>
 * Never a teleport across the room: every displacement is animated, step by step. Nothing changes in the map.
 */
final class SalamanderPowers implements Listener {

    static final Set<String> TYPES = Set.of("sala_awaken", "sala_rush", "sala_feint", "sala_spit", "sala_fury", "sala_frenzy");
    static final String MODEL = "salamandrex";
    private static final Color JADE = Color.fromRGB(0x91DDB0), EMERALD = Color.fromRGB(0x2E9667), DEEP = Color.fromRGB(0x176044),
            LIGHT = Color.fromRGB(0x70CC98), WARN = Color.fromRGB(0xFF5C5C);
    private static final BlockData EMERALD_BLOCK = Material.EMERALD_BLOCK.createBlockData();
    private static final Material[] RUBBLE = {Material.COBBLED_DEEPSLATE, Material.DEEPSLATE, Material.TUFF, Material.GRAVEL};
    private static final String[] LOCOMOTION = {"run", "strafe_right", "strafe_left", "retreat"};
    private static final String[] IDLES = {"idle_snap", "idle_tail", "idle_jaw", "idle_shift", "idle_crouch"};
    /** Distance from its origin to the front of its muzzle (blocks): what its bite reaches. */
    private static final double MUZZLE = 2.4;

    private enum Mode { APPROACH, STRAFE, RETREAT, HOP, PAUSE }

    /** Prowl state of one salamander. */
    private static final class Brain {
        final LivingEntity e;
        boolean awakened, enraged, sleeping;
        int seenTarget;
        Mode mode = Mode.PAUSE;
        int modeTicks, modeAge, side = 1, hopTick;
        double orbit;
        Location hopFrom, hopTo;
        String looping;
        int loopSince;
        long lastBite;
        int nextIdle = 120;
        Vector lastStill;
        int stillTicks;
        // tuning (sala_awaken can change them: run-speed, strafe-speed, retreat-speed, hop-distance)
        double run = 0.23, strafe = 0.18, retreat = 0.16, hop = 3.6;

        Brain(LivingEntity e) {
            this.e = e;
        }

        double k() {
            return enraged ? 1.15 : 1.0;
        }
    }

    private final Plugin plugin;
    private final Fx fx;
    private final Mobs mobs;
    private final Map<UUID, Brain> brains = new HashMap<>();
    private final Set<Entity> shots = new HashSet<>();
    private int ticks;

    SalamanderPowers(Plugin plugin, Fx fx, Mobs mobs) {
        this.plugin = plugin;
        this.fx = fx;
        this.mobs = mobs;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    // ---------------------------------------------------------------- casting

    boolean cast(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        if (!TYPES.contains(ab.type())) return false;
        Brain b = brain(c.entity());
        stopLoops(b);
        switch (ab.type()) {
            case "sala_awaken" -> awaken(ab, c, b, target);
            case "sala_rush" -> rush(ab, c, b, target);
            case "sala_feint" -> feint(ab, c, b, target);
            case "sala_spit" -> spit(ab, c, b, target);
            case "sala_fury" -> fury(ab, c, b, target);
            case "sala_frenzy" -> frenzy(ab, c, b);
            default -> {
            }
        }
        return true;
    }

    /**
     * Whether casting now makes sense. Rush: its target between {@code target-min} and {@code target-max} blocks, in
     * sight, nothing in the way. Feint: someone within {@code target-max} (a player standing still is always worth
     * it, a moving one half the time). Spit: the farthest player in sight beyond {@code min-range} (archers).
     * Ultimate: someone within its reach. Never an attack while it is still asleep (the cinematic comes first).
     */
    boolean worth(MobTemplate.Ability ab, LivingEntity e) {
        if (!TYPES.contains(ab.type())) return true;
        Brain b = brain(e);
        if (!b.awakened && !"sala_awaken".equals(ab.type())) return false;
        Player target = target(e);
        if (target == null) return "sala_frenzy".equals(ab.type());
        double d = flatDistance(e.getLocation(), target.getLocation());
        return switch (ab.type()) {
            case "sala_rush" -> d >= ab.number("target-min", 4) && d <= ab.number("target-max", 13) && e.hasLineOfSight(target)
                    && clearLine(e, target.getLocation());
            case "sala_feint" -> d >= ab.number("target-min", 2) && d <= ab.number("target-max", 8) && e.hasLineOfSight(target)
                    && (still(b) || ThreadLocalRandom.current().nextBoolean());
            case "sala_spit" -> farthest(e, ab.number("min-range", 7), ab.number("max-range", 22)) != null;
            case "sala_fury" -> d <= ab.number("target-max", 16) && e.hasLineOfSight(target);
            default -> true;
        };
    }

    void clear() {
        for (Entity s : shots) if (s.isValid()) s.remove();
        shots.clear();
        brains.clear();
    }

    private Brain brain(LivingEntity e) {
        return brains.computeIfAbsent(e.getUniqueId(), k -> new Brain(e));
    }

    // ---------------------------------------------------------------- every tick: find salamanders, prowl

    private void tick() {
        ticks++;
        if (ticks % 20 == 0) {
            // a salamander is known from its model: found among the dungeon monsters of loaded worlds
            for (World w : Bukkit.getWorlds()) {
                for (LivingEntity le : w.getLivingEntities()) {
                    if (brains.containsKey(le.getUniqueId()) || mobs.templateOf(le) == null) continue;
                    MobTemplate t = mobs.templateOfEntity(le);
                    if (t != null && MODEL.equals(t.model())) brain(le);
                }
            }
        }
        for (Iterator<Brain> it = brains.values().iterator(); it.hasNext(); ) {
            Brain b = it.next();
            if (!b.e.isValid() || b.e.isDead()) {
                it.remove();
                continue;
            }
            prowl(b);
        }
        shots.removeIf(s -> !s.isValid());
    }

    private static Player target(LivingEntity e) {
        if (e instanceof Mob m && m.getTarget() instanceof Player p && p.getWorld() == e.getWorld() && Mobs.fighting(p)) return p;
        return null;
    }

    private void prowl(Brain b) {
        LivingEntity e = b.e;
        if (!(e instanceof Mob m)) return;
        b.enraged = e.getHealth() < 0.4 * maxHealth(e);
        Player target = target(e);
        if (!b.awakened) {
            if (target == null) {
                if (!b.sleeping) {
                    mobs.loop(e, "sleep", true);
                    b.sleeping = true;
                }
                return;
            }
            // the cinematic (sala_awaken) wakes it up; without abilities (easy level) it wakes up on its own
            if (++b.seenTarget > 40 && m.hasAI()) {
                b.awakened = true;
                wake(b);
            }
            return;
        }
        if (!m.hasAI()) {
            // an ability holds it and plays its own animations
            b.looping = null;
            return;
        }
        if (target == null) {
            setLoop(b, null);
            return;
        }
        Location at = e.getLocation();
        double d = flatDistance(at, target.getLocation());
        // whether its prey stands still (the feint punishes campers)
        Vector here = target.getLocation().toVector();
        if (b.lastStill != null && here.distanceSquared(b.lastStill) < 0.04) b.stillTicks++;
        else b.stillTicks = 0;
        b.lastStill = here;

        b.modeAge++;
        if (b.mode != Mode.HOP && (b.modeAge >= b.modeTicks)) choose(b, d, target);
        Vector move = new Vector();
        double k = b.k();
        Vector to = flat(at, target.getLocation()), r = right(to);
        switch (b.mode) {
            case APPROACH -> {
                if (d > MUZZLE + 0.4) {
                    Vector zig = r.clone().multiply(d > 9 ? 0.45 * Math.sin(b.modeAge / 5.0) : 0.15 * Math.sin(b.modeAge / 4.0));
                    move = to.clone().add(zig).normalize().multiply(Math.min(b.run * k, d - MUZZLE));
                } else if (ticks % 3 == 0 && System.currentTimeMillis() - b.lastBite > 900) {
                    // in reach: the melee bite comes from FluxDungeons; it only shuffles a little meanwhile
                    move = r.clone().multiply(0.04 * b.side);
                }
            }
            case STRAFE -> {
                Vector radial = to.clone().multiply(Math.max(-0.6, Math.min(0.6, (d - b.orbit) * 0.35)));
                move = r.clone().multiply(b.side).add(radial).normalize().multiply(b.strafe * k);
            }
            case RETREAT -> move = to.clone().multiply(-1).add(r.clone().multiply(0.35 * b.side)).normalize().multiply(b.retreat * k);
            case HOP -> {
                hopStep(b, target);
                return;
            }
            case PAUSE -> {
                if (b.modeAge == 2 && b.modeTicks >= 14 && ticks >= b.nextIdle) {
                    String idle = IDLES[ThreadLocalRandom.current().nextInt(IDLES.length)];
                    setLoop(b, null);
                    mobs.play(e, idle);
                    b.nextIdle = ticks + 100 + ThreadLocalRandom.current().nextInt(80);
                }
            }
        }
        if (move.lengthSquared() < 1e-6) {
            face(e, target.getLocation());
            if (b.mode != Mode.APPROACH || ticks % 4 == 0) setLoop(b, null);
            return;
        }
        Location next = walk(e, move, target.getLocation());
        if (next == null) {
            // blocked by a wall or a ledge: change side, or hop away from it
            if (b.mode == Mode.STRAFE) b.side = -b.side;
            else if (!startHop(b, target, -b.side)) setMode(b, Mode.STRAFE, 12, d);
            setLoop(b, null);
            return;
        }
        Vector done = next.toVector().subtract(at.toVector()).setY(0);
        double fwd = done.dot(to), side = done.dot(r);
        String loop = Math.abs(fwd) >= Math.abs(side) ? (fwd >= 0 ? "run" : "retreat") : (side > 0 ? "strafe_right" : "strafe_left");
        setLoop(b, loop);
    }

    /** Picks what to do next from the distance to its prey (and its mood). */
    private void choose(Brain b, double d, Player target) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double k = b.enraged ? 0.8 : 1.0;
        double roll = r.nextDouble();
        if (System.currentTimeMillis() - b.lastBite < 600) {
            setMode(b, Mode.RETREAT, (int) ((10 + r.nextInt(8)) * k), d);
            return;
        }
        if (d > 12) {
            if (roll < 0.22 && startHop(b, target, r.nextBoolean() ? 1 : -1)) return;
            setMode(b, Mode.APPROACH, (int) ((22 + r.nextInt(14)) * k), d);
        } else if (d > 5) {
            if (roll < 0.45) setMode(b, Mode.APPROACH, (int) ((14 + r.nextInt(12)) * k), d);
            else if (roll < 0.80) setMode(b, Mode.STRAFE, (int) ((16 + r.nextInt(16)) * k), d);
            else if (!startHop(b, target, r.nextBoolean() ? 1 : -1)) setMode(b, Mode.STRAFE, 14, d);
        } else {
            if (roll < 0.40) setMode(b, Mode.STRAFE, (int) ((12 + r.nextInt(14)) * k), Math.max(3.2, d));
            else if (roll < 0.70) setMode(b, Mode.APPROACH, (int) ((14 + r.nextInt(10)) * k), d);
            else if (roll < (b.enraged ? 0.95 : 0.85)) {
                if (!startHop(b, target, r.nextBoolean() ? 1 : -1)) setMode(b, Mode.RETREAT, 10, d);
            } else setMode(b, Mode.PAUSE, 10 + r.nextInt(14), d);
        }
        if (b.mode == Mode.STRAFE && r.nextDouble() < 0.35) b.side = -b.side;
    }

    private static void setMode(Brain b, Mode mode, int ticks, double d) {
        b.mode = mode;
        b.modeTicks = Math.max(4, ticks);
        b.modeAge = 0;
        if (mode == Mode.STRAFE) b.orbit = Math.max(3.0, Math.min(9.0, d));
    }

    /** A quick hop to its right ({@code side} 1) or left, if the landing is free. */
    private boolean startHop(Brain b, Player target, int side) {
        LivingEntity e = b.e;
        Location at = e.getLocation();
        Vector to = flat(at, target.getLocation());
        for (int s : new int[]{side, -side}) {
            Vector dir = right(to).multiply(s).add(to.clone().multiply(0.2)).normalize();
            Location land = landing(e, at.clone().add(dir.multiply(b.hop * b.k())));
            if (land == null || !clearLine(e, land)) continue;
            b.hopFrom = at.clone();
            b.hopTo = land;
            b.hopTick = 0;
            b.side = s;
            b.mode = Mode.HOP;
            b.modeAge = 0;
            b.modeTicks = 12;
            setLoop(b, null);
            mobs.play(e, s > 0 ? "hop_right" : "hop_left");
            snd(at, "entity.frog.long_jump", 1.2f, 0.7f);
            return true;
        }
        return false;
    }

    /** One tick of a hop: crouch (0-3), airborne (3-9, the real move), landing (9-12). */
    private void hopStep(Brain b, Player target) {
        LivingEntity e = b.e;
        int t = b.hopTick++;
        if (t >= 3 && t <= 9) {
            double u = (t - 3) / 6.0;
            Location p = arc(b.hopFrom, b.hopTo, u, 0.9);
            face(p, target.getLocation());
            Mobs.move(e, p);
            if (t == 3) puff(b.hopFrom, 6);
        } else {
            face(e, target.getLocation());
        }
        if (t == 9) {
            puff(e.getLocation(), 8);
            snd(e.getLocation(), "entity.generic.big_fall", 0.7f, 1.3f);
        }
        if (t >= 12) {
            double d = flatDistance(e.getLocation(), target.getLocation());
            choose(b, d, target);
            if (b.mode == Mode.HOP) return;
            if (ThreadLocalRandom.current().nextDouble() < 0.5) setMode(b, Mode.STRAFE, 10, d);
        }
    }

    private void setLoop(Brain b, String loop) {
        if (loop != null && loop.equals(b.looping)) return;
        // a little hysteresis: no flicker between two loops
        if (loop != null && b.looping != null && ticks - b.loopSince < 4) return;
        if (b.looping != null) mobs.loop(b.e, b.looping, false);
        b.looping = loop;
        b.loopSince = ticks;
        if (loop != null) mobs.loop(b.e, loop, true);
    }

    private void stopLoops(Brain b) {
        for (String l : LOCOMOTION) mobs.loop(b.e, l, false);
        if (b.sleeping) {
            mobs.loop(b.e, "sleep", false);
            b.sleeping = false;
        }
        b.looping = null;
    }

    /** Woken without the cinematic (abilities off): eyes open, a short growl. */
    private void wake(Brain b) {
        stopLoops(b);
        mobs.play(b.e, "idle_jaw");
        snd(b.e.getLocation(), "entity.ravager.ambient", 1.4f, 0.8f);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBite(EntityDamageByEntityEvent event) {
        Brain b = brains.get(event.getDamager().getUniqueId());
        if (b == null || !(event.getEntity() instanceof Player)) return;
        b.lastBite = System.currentTimeMillis();
        // after a bite it backs off at once
        if (b.e instanceof Mob m && m.hasAI()) setMode(b, Mode.RETREAT, 12 + ThreadLocalRandom.current().nextInt(6), 2);
    }

    // ---------------------------------------------------------------- moving on the ground

    /**
     * One step along {@code move} (horizontal, blocks): on the ground, up a block at most, down three at most, never
     * into a wall (its body is about a block and a half wide). Slides along a wall when it can. Faces {@code look}.
     * Returns where it went, or null when nothing was possible.
     */
    private static Location walk(LivingEntity e, Vector move, Location look) {
        return walk(e, move, look, true);
    }

    /** {@code slide}: false = straight on or nothing (a rush stops against a wall instead of sliding along it). */
    private static Location walk(LivingEntity e, Vector move, Location look, boolean slide) {
        Location at = e.getLocation();
        Vector[] tries = slide ? new Vector[]{move, new Vector(move.getX(), 0, 0), new Vector(0, 0, move.getZ())} : new Vector[]{move};
        for (Vector m : tries) {
            if (m.lengthSquared() < 1e-6) continue;
            Location next = at.clone().add(m);
            Location ground = landing(e, next);
            if (ground == null || Math.abs(ground.getY() - at.getY()) > 1.1) continue;
            face(ground, look);
            Mobs.move(e, ground);
            return ground;
        }
        return null;
    }

    /** The ground under a place if its body fits there (free above, floor at most 3 below), else null. */
    private static Location landing(LivingEntity e, Location at) {
        World w = at.getWorld();
        double y = groundY(w, at.getX(), at.getY() + 1.0, at.getZ(), 4);
        if (Double.isNaN(y)) return null;
        double half = 0.7;
        for (double dx : new double[]{-half, 0, half}) {
            for (double dz : new double[]{-half, 0, half}) {
                for (double h : new double[]{0.2, 1.2}) {
                    if (w.getBlockAt((int) Math.floor(at.getX() + dx), (int) Math.floor(y + h), (int) Math.floor(at.getZ() + dz)).isCollidable()) return null;
                }
            }
        }
        Location out = at.clone();
        out.setY(y);
        return out;
    }

    /** Height of the floor under (x, z) searching down from y, NaN when none within {@code depth} blocks. */
    private static double groundY(World w, double x, double y, double z, int depth) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int by = (int) Math.floor(y); by >= (int) Math.floor(y) - depth; by--) {
            Block b = w.getBlockAt(bx, by, bz);
            if (b.isCollidable()) return by + b.getBoundingBox().getHeight();
        }
        return Double.NaN;
    }

    /** Whether its body can go straight to a place (no wall between, at knee and at head height). */
    private static boolean clearLine(LivingEntity e, Location to) {
        Location from = e.getLocation();
        Vector d = to.toVector().subtract(from.toVector()).setY(0);
        double len = d.length();
        if (len < 0.5) return true;
        d.multiply(1 / len);
        for (double s = 0.8; s < len; s += 0.6) {
            Location p = from.clone().add(d.clone().multiply(s));
            for (double h : new double[]{0.5, 1.3}) {
                if (p.clone().add(0, h, 0).getBlock().isCollidable()) return false;
            }
        }
        return true;
    }

    /** A point of a jump from a to b at share u (0..1), {@code height} blocks high in the middle. */
    private static Location arc(Location a, Location b, double u, double height) {
        Location p = a.clone().add(b.toVector().subtract(a.toVector()).multiply(u));
        p.setY(a.getY() + (b.getY() - a.getY()) * u + height * 4 * u * (1 - u));
        return p;
    }

    // ---------------------------------------------------------------- shared helpers

    private static double maxHealth(LivingEntity e) {
        var inst = e.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        return inst == null ? 20 : inst.getValue();
    }

    private static void snd(Location at, String key, float volume, float pitch) {
        at.getWorld().playSound(at, key, SoundCategory.HOSTILE, volume, pitch);
    }

    private static List<Player> fighters(Location at, double within) {
        List<Player> out = new java.util.ArrayList<>();
        for (Player p : at.getWorld().getPlayers()) {
            if (Mobs.fighting(p) && p.getLocation().distanceSquared(at) <= within * within) out.add(p);
        }
        return out;
    }

    private static double flatDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static Vector forward(LivingEntity e) {
        Vector f = e.getLocation().getDirection().setY(0);
        return f.lengthSquared() < 1e-6 ? new Vector(0, 0, 1) : f.normalize();
    }

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

    private static void face(LivingEntity e, Location to) {
        e.setRotation(yawTo(flat(e.getLocation(), to)), 0);
    }

    private static void face(Location l, Location to) {
        l.setYaw(yawTo(flat(l, to)));
        l.setPitch(0);
    }

    /** A point of its body: {@code x} to its right, {@code y} up, {@code ahead} in front of its origin. */
    private static Location body(LivingEntity e, double x, double y, double ahead) {
        Vector f = forward(e);
        return e.getLocation().clone().add(right(f).multiply(x)).add(0, y, 0).add(f.multiply(ahead));
    }

    private static boolean still(Brain b) {
        return b.stillTicks >= 30;
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

    private static void puff(Location at, int count) {
        World w = at.getWorld();
        w.spawnParticle(Particle.CLOUD, at.clone().add(0, 0.2, 0), count, 0.6, 0.05, 0.6, 0.02);
        Block under = Fx.ground(w, at.getBlockX(), at.getY(), at.getBlockZ());
        if (under != null) w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.1, 0), count, 0.6, 0.05, 0.6, 0, under.getBlockData());
    }

    private static void emeraldDust(Location at, int count, double spread, float size) {
        World w = at.getWorld();
        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, count, spread, spread * 0.6, spread, 0,
                new Particle.DustTransition(LIGHT, DEEP, size));
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

    private static void shake(Location at, double within) {
        for (Player p : fighters(at, within)) p.sendHurtAnimation(0);
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
        c.glow(0x91DDB0);
        Bukkit.getScheduler().runTaskLater(plugin, () -> c.glow(-1), ticks);
    }

    /** Ring of dust on the ground (an announced zone). */
    private static void ring(Location center, double radius, Color color, float size) {
        World w = center.getWorld();
        int n = Math.max(12, (int) (radius * 7));
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            Location p = center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
            double y = groundY(w, p.getX(), p.getY() + 1.5, p.getZ(), 4);
            if (!Double.isNaN(y)) p.setY(y + 0.12);
            w.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(color, size));
        }
    }

    // ---------------------------------------------------------------- awakening (cinematic opening, 180 ticks)

    private void awaken(MobTemplate.Ability ab, RockPowers.Caster c, Brain b, Player target) {
        LivingEntity e = c.entity();
        int length = 180;
        b.run = ab.number("run-speed", 4.6) / 20;
        b.strafe = ab.number("strafe-speed", 3.6) / 20;
        b.retreat = ab.number("retreat-speed", 3.2) / 20;
        b.hop = ab.number("hop-distance", 3.6);
        c.hold(length + 2);
        e.setInvulnerable(true);
        c.animate("awaken");
        Location[] leap = new Location[2];
        timeline(e, length, t -> {
            Location at = e.getLocation();
            boolean ok = target.isValid() && Mobs.fighting(target) && target.getWorld() == e.getWorld();
            if (t == 0) {
                snd(at, "entity.ravager.ambient", 1.5f, 0.45f);
                snd(at, "block.deepslate.step", 1.2f, 0.6f);
            }
            // phase 1: slow breath, a faint emerald shimmer over its back, then the eyes open on the player
            if (t < 46 && t % 10 == 0) emeraldDust(body(e, 0, 1.2, -0.6), 4, 0.9, 0.9f);
            if (t == 46) {
                snd(at, "entity.axolotl.idle_air", 1.4f, 0.5f);
                if (ok) face(e, target.getLocation());
            }
            // phase 2: it rises, settles its paws one after the other, the tail sweeps, a deep growl
            if (t == 64 || t == 72 || t == 80 || t == 88) snd(at, "block.deepslate.step", 1.2f, 0.7f);
            if (t == 95) {
                snd(at, "entity.ravager.ambient", 1.7f, 0.6f);
                snd(at, "entity.hoglin.angry", 0.8f, 0.5f);
            }
            // phase 3: a lightning hop to its side
            if (t == 106) {
                snd(at, "entity.frog.long_jump", 1.4f, 0.6f);
                Vector to = ok ? flat(at, target.getLocation()) : forward(e);
                for (int s : new int[]{1, -1}) {
                    Location land = landing(e, at.clone().add(right(to).multiply(4.0 * s)));
                    if (land != null && clearLine(e, land)) {
                        leap[0] = at.clone();
                        leap[1] = land;
                        break;
                    }
                }
            }
            if (leap[0] != null && t >= 116 && t <= 126) {
                Location p = arc(leap[0], leap[1], (t - 116) / 10.0, 1.1);
                p.setYaw(at.getYaw());
                Mobs.move(e, p);
                if (t == 116) {
                    puff(leap[0], 10);
                    snd(leap[0], "entity.goat.long_jump", 1.2f, 0.7f);
                }
                if (t == 126) {
                    puff(p, 16);
                    snd(p, "entity.generic.big_fall", 1.2f, 0.8f);
                    snd(p, "block.deepslate.step", 1.4f, 0.6f);
                }
            }
            if (t >= 128 && t <= 140 && ok) {
                Location l = e.getLocation();
                Vector want = flat(l, target.getLocation());
                float yaw = yawTo(want), cur = l.getYaw();
                float diff = ((yaw - cur) % 360 + 540) % 360 - 180;
                e.setRotation(cur + diff * 0.35f, 0);
            }
            // phase 4: guard, tail flick, the cry
            if (t == 156) {
                snd(at, "entity.ravager.roar", 1.8f, 0.85f);
                snd(at, "entity.axolotl.attack", 1.4f, 0.5f);
                emeraldDust(body(e, 0, 1.0, 2.2), 24, 0.6, 1.2f);
                shake(at, 20);
                title(at, ab, 60);
            }
            if (t == length) {
                e.setInvulnerable(false);
                b.awakened = true;
                setMode(b, Mode.PAUSE, 6, 0);
                c.release();
            }
        });
    }

    // ---------------------------------------------------------------- Rush Emeraude (34 ticks)

    private void rush(MobTemplate.Ability ab, RockPowers.Caster c, Brain b, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double power = ab.number("rush-power", 7), speed = ab.number("speed", 0.42) * b.k(), over = ab.number("overshoot", 3),
                width = ab.number("width", 1.5), max = ab.number("distance", 10);
        int lock = 8, launch = 10, runEnd = 26;
        Vector[] dir = {flat(e.getLocation(), target.getLocation())};
        double[] length = {max};
        Set<UUID> struck = new HashSet<>();
        boolean[] stopped = {false};
        c.hold(36);
        c.animate("rush");
        snd(e.getLocation(), "entity.axolotl.hurt", 1.2f, 0.5f);
        snd(e.getLocation(), "entity.ravager.step", 1.2f, 0.7f);
        timeline(e, 34, t -> {
            Location at = e.getLocation();
            if (t <= lock && target.isValid() && Mobs.fighting(target)) {
                dir[0] = flat(at, target.getLocation());
                length[0] = Math.min(max, flatDistance(at, target.getLocation()) + over);
                e.setRotation(yawTo(dir[0]), 0);
            }
            Vector f = dir[0];
            // the way of the rush on the ground: jade while it can still turn, red once locked
            if (t >= 2 && t < launch && t % 2 == 0) {
                Color col = t > lock ? WARN : JADE;
                for (double s = 2.4; s <= length[0] + 2.4; s += 0.8) {
                    Location p = at.clone().add(f.clone().multiply(s));
                    double y = groundY(w, p.getX(), p.getY() + 1.2, p.getZ(), 4);
                    if (!Double.isNaN(y)) p.setY(y + 0.12);
                    w.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.1f));
                }
            }
            if (t == lock) snd(at, "entity.creeper.primed", 0.9f, 1.4f);
            if (t == launch) {
                snd(at, "entity.camel.dash", 1.4f, 0.8f);
                snd(at, "entity.ravager.roar", 1.0f, 1.1f);
                puff(at, 10);
            }
            if (t < launch || stopped[0]) return;
            double gone = (t - launch) * speed;
            if (t > runEnd || gone >= length[0]) {
                stopped[0] = true;
                if (struck.isEmpty()) {
                    // missed: a skid, a shake of the head, open to a counter-attack
                    c.hold(26);
                    c.animate("rush_skid");
                    exposed(c, 24);
                    snd(at, "block.mud.step", 1.4f, 0.6f);
                    puff(at, 14);
                } else {
                    b.lastBite = System.currentTimeMillis();
                }
                return;
            }
            Location now = walk(e, f.clone().multiply(speed), at.clone().add(f.clone().multiply(10)), false);
            if (now == null) {
                // a wall: it stops dead, stunned for a moment (vulnerable)
                stopped[0] = true;
                c.hold(26);
                c.animate("rush_skid");
                exposed(c, 24);
                snd(at, "block.deepslate.break", 1.4f, 0.6f);
                snd(at, "entity.player.attack.strong", 1.2f, 0.6f);
                fx.debris(at.clone().add(f.clone().multiply(2.6)).add(0, 0.8, 0), 8, Material.COBBLED_DEEPSLATE.createBlockData(), 0.8);
                shake(at, 10);
                return;
            }
            if (t % 2 == 0) {
                puff(now.clone().add(f.clone().multiply(-1.5)), 3);
                emeraldDust(now.clone().add(0, 0.8, 0).add(f.clone().multiply(-2.5)), 2, 0.3, 0.8f);
                snd(now, "entity.frog.step", 1.2f, 0.8f);
            }
            Location mouth = now.clone().add(f.clone().multiply(MUZZLE - 0.2));
            for (Player p : fighters(now, 6)) {
                if (struck.contains(p.getUniqueId())) continue;
                Location pl = p.getLocation();
                if (Math.hypot(pl.getX() - mouth.getX(), pl.getZ() - mouth.getZ()) > width || Math.abs(pl.getY() - now.getY()) > 2.2) continue;
                struck.add(p.getUniqueId());
                c.hit(p, power);
                p.setVelocity(f.clone().multiply(0.9).add(right(f).multiply(0.25)).setY(0.35));
                snd(pl, "entity.player.attack.strong", 1.4f, 0.7f);
                snd(pl, "entity.ravager.attack", 1.2f, 0.9f);
                emeraldDust(pl.clone().add(0, 1, 0), 10, 0.4, 1.0f);
            }
        });
    }

    // ---------------------------------------------------------------- Feinte du Predateur (44 ticks)

    private void feint(MobTemplate.Ability ab, RockPowers.Caster c, Brain b, Player target) {
        LivingEntity e = c.entity();
        double power = ab.number("bite-power", 6), reach = ab.number("bite-reach", 3.2), speed = ab.number("speed", 0.26) * b.k();
        int side = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        Location[] jump = new Location[2];
        Location[] back = new Location[2];
        boolean[] bitten = {false};
        c.hold(46);
        c.animate(side > 0 ? "feint_right" : "feint_left");
        timeline(e, 44, t -> {
            Location at = e.getLocation();
            boolean ok = target.isValid() && Mobs.fighting(target) && target.getWorld() == e.getWorld();
            if (!ok) return;
            Location tl = target.getLocation();
            // 0-12: rushes in, eyes on its prey, stops at three blocks
            if (t < 12) {
                double d = flatDistance(at, tl);
                if (d > 3.2) walk(e, flat(at, tl).multiply(Math.min(speed, d - 3.2)), tl);
                else face(e, tl);
                if (t % 4 == 0) snd(at, "entity.frog.step", 1.0f, 0.9f);
            }
            if (t == 12) snd(at, "entity.frog.long_jump", 1.4f, 0.55f);
            // 15: picks the landing beside (or a little behind) its prey, on its chosen side when free
            if (t == 15) {
                Vector to = flat(at, tl);
                int[] order = {side, -side};
                for (int s : order) {
                    for (double behind : new double[]{1.0, 0.0, -1.0}) {
                        Location land = landing(e, tl.clone().add(right(to).multiply(3.0 * s)).add(to.clone().multiply(behind)));
                        if (land != null && clearLine(e, land)) {
                            jump[0] = at.clone();
                            jump[1] = land;
                            break;
                        }
                    }
                    if (jump[1] != null) break;
                }
                puff(at, 8);
            }
            // 16-26: the leap, turning in the air to land facing its prey
            if (jump[1] != null && t >= 16 && t <= 26) {
                double u = (t - 16) / 10.0;
                Location p = arc(jump[0], jump[1], u, 1.2);
                float y0 = jump[0].getYaw(), y1 = yawTo(flat(jump[1], tl));
                float diff = ((y1 - y0) % 360 + 540) % 360 - 180;
                p.setYaw(y0 + diff * (float) Math.min(1, u * 1.3));
                Mobs.move(e, p);
                if (t == 16) snd(jump[0], "entity.goat.long_jump", 1.2f, 0.8f);
                if (t == 26) {
                    puff(p, 12);
                    snd(p, "entity.generic.big_fall", 1.0f, 1.1f);
                }
            }
            // 26-32: turns on its prey, a hiss (the cue to step away), bites at 32
            if (t >= 26 && t <= 32) face(e, tl);
            if (t == 28) snd(at, "entity.axolotl.attack", 1.6f, 0.45f);
            if (t == 32 && !bitten[0]) {
                bitten[0] = true;
                Location mouth = body(e, 0, 0.6, MUZZLE - 0.3);
                for (Player p : fighters(at, reach + 2)) {
                    Location pl = p.getLocation();
                    Vector toP = flat(at, pl);
                    if (flatDistance(mouth, pl) > reach - 1.2 && (flatDistance(at, pl) > reach || toP.dot(forward(e)) < 0.45)) continue;
                    if (!e.hasLineOfSight(p)) continue;
                    c.hit(p, power);
                    p.setVelocity(toP.clone().multiply(0.6).setY(0.25));
                    snd(pl, "entity.ravager.attack", 1.4f, 1.0f);
                    snd(pl, "entity.player.attack.strong", 1.2f, 0.8f);
                    emeraldDust(pl.clone().add(0, 1, 0), 8, 0.35, 1.0f);
                }
                b.lastBite = System.currentTimeMillis();
            }
            // 36-44: hops back out of reach
            if (t == 36) {
                Location land = landing(e, at.clone().add(flat(tl, at).multiply(3.0)));
                if (land != null && clearLine(e, land)) {
                    back[0] = at.clone();
                    back[1] = land;
                }
            }
            if (back[1] != null && t >= 37 && t <= 42) {
                Location p = arc(back[0], back[1], (t - 37) / 5.0, 0.6);
                face(p, tl);
                Mobs.move(e, p);
                if (t == 42) puff(p, 6);
            }
            if (t == 44) {
                setMode(b, Mode.STRAFE, 14, flatDistance(e.getLocation(), tl));
                c.release();
            }
        });
    }

    // ---------------------------------------------------------------- Eclat Venimeux (30 ticks)

    private void spit(MobTemplate.Ability ab, RockPowers.Caster c, Brain b, Player cast) {
        LivingEntity e = c.entity();
        Player target = farthest(e, ab.number("min-range", 7), ab.number("max-range", 22));
        Player aim = target != null ? target : cast;
        double power = ab.number("spit-power", 5), speed = ab.number("shot-speed", 1.05), range = ab.number("shot-range", 26);
        int throwAt = 18;
        c.hold(32);
        c.animate("spit");
        timeline(e, 30, t -> {
            Location at = e.getLocation();
            if (t <= throwAt && aim.isValid()) face(e, aim.getLocation());
            Location mouth = body(e, 0, 1.15, MUZZLE + 0.2);
            if (t == 2) snd(at, "entity.creeper.primed", 1.0f, 0.6f);
            // charge: emerald gathers in its open mouth, drawn in from around its head
            if (t >= 6 && t < throwAt) {
                for (int i = 0; i < 2; i++) {
                    double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2), r = 0.6 + (throwAt - t) * 0.05;
                    Location from = mouth.clone().add(Math.cos(a) * r, Math.sin(a) * r * 0.6, Math.sin(a * 1.7) * r);
                    Vector v = mouth.toVector().subtract(from.toVector()).multiply(0.25);
                    e.getWorld().spawnParticle(Particle.DUST, from, 0, v.getX(), v.getY(), v.getZ(), 1,
                            new Particle.DustOptions(i == 0 ? LIGHT : EMERALD, 0.9f));
                }
                if (t % 4 == 0) e.getWorld().spawnParticle(Particle.WITCH, mouth, 2, 0.1, 0.1, 0.1, 0);
            }
            if (t == 12) snd(at, "block.amethyst_block.resonate", 1.0f, 1.4f);
            if (t == throwAt && aim.isValid() && Mobs.fighting(aim)) {
                snd(at, "entity.llama.spit", 1.6f, 0.6f);
                snd(at, "entity.slime.attack", 1.0f, 0.7f);
                Vector dir = aim.getLocation().add(0, 1.0, 0).toVector().subtract(mouth.toVector()).normalize();
                shoot(c, mouth, dir, speed, range, power, ab);
            }
            if (t == 30) c.release();
        });
    }

    /** The emerald shot: a small spinning gem, straight line, never homing; bursts on a wall or on a player. */
    private void shoot(RockPowers.Caster c, Location from, Vector dir, double speed, double range, double power, MobTemplate.Ability ab) {
        World w = from.getWorld();
        BlockDisplay gem = w.spawn(from.clone(), BlockDisplay.class, d -> {
            d.setBlock(EMERALD_BLOCK);
            d.setBrightness(new Display.Brightness(12, 12));
            d.setPersistent(false);
            d.addScoreboardTag("fluxdungeons_fx");
            d.setTeleportDuration(1);
            d.setTransformation(new Transformation(new Vector3f(-0.18f, -0.18f, -0.18f), new Quaternionf(), new Vector3f(0.36f, 0.36f, 0.36f), new Quaternionf()));
        });
        shots.add(gem);
        int slow = (int) ab.number("slow-ticks", 30), poison = (int) ab.number("poison-ticks", 40);
        new BukkitRunnable() {
            final Location pos = from.clone();
            double gone;
            int t;

            @Override
            public void run() {
                if (!gem.isValid()) {
                    cancel();
                    return;
                }
                t++;
                for (int step = 0; step < 2; step++) {
                    pos.add(dir.clone().multiply(speed / 2));
                    gone += speed / 2;
                    if (pos.getBlock().isCollidable() || gone > range) {
                        burst(pos, false);
                        return;
                    }
                    for (Player p : fighters(pos, 2.5)) {
                        if (!p.getBoundingBox().expand(0.45).contains(pos.toVector())) continue;
                        c.hit(p, power);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, 0, false, true));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, poison, 0, false, true));
                        burst(pos, true);
                        return;
                    }
                }
                Location l = pos.clone();
                l.setYaw(t * 25f);
                l.setPitch(t * 18f);
                gem.teleport(l);
                w.spawnParticle(Particle.DUST, pos, 2, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(t % 2 == 0 ? JADE : EMERALD, 0.9f));
                if (t % 3 == 0) w.spawnParticle(Particle.WITCH, pos, 1, 0, 0, 0, 0);
            }

            private void burst(Location at, boolean onPlayer) {
                gem.remove();
                cancel();
                w.spawnParticle(Particle.BLOCK, at, 14, 0.25, 0.25, 0.25, 0, EMERALD_BLOCK);
                w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 16, 0.4, 0.3, 0.4, 0, new Particle.DustTransition(JADE, DEEP, 1.1f));
                w.spawnParticle(Particle.HAPPY_VILLAGER, at, 3, 0.3, 0.3, 0.3, 0);
                snd(at, "entity.slime.squish", 1.2f, 0.7f);
                snd(at, "block.amethyst_block.break", 1.0f, onPlayer ? 0.7f : 1.0f);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ---------------------------------------------------------------- Furie du Predateur (ultimate, 100 ticks)

    private void fury(MobTemplate.Ability ab, RockPowers.Caster c, Brain b, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double power = ab.number("wave-power", 11), radius = ab.number("wave-radius", 5), maxLeap = ab.number("leap-range", 10);
        int[] hops = {24, 36, 48};
        double[] offsets = {110, -125, 165};
        Location[][] path = new Location[3][2];
        Location[] fin = new Location[2];
        Set<UUID> struck = new HashSet<>();
        c.hold(102);
        c.animate("fury");
        title(e.getLocation(), ab, 40);
        timeline(e, 100, t -> {
            Location at = e.getLocation();
            boolean ok = target.isValid() && Mobs.fighting(target) && target.getWorld() == w;
            Location tl = ok ? target.getLocation() : at.clone().add(forward(e).multiply(4));
            // 0-24: gathering: it sinks, its jaw wide open, emerald drawn towards it
            if (t == 0) {
                snd(at, "entity.ravager.roar", 1.6f, 0.55f);
                snd(at, "block.beacon.activate", 1.2f, 0.6f);
            }
            if (t == 10 || t == 18) snd(at, "entity.warden.heartbeat", 1.6f, 0.8f);
            if (t < 24) {
                face(e, tl);
                double r = 3.0 - t * 0.1;
                for (int i = 0; i < 3; i++) {
                    double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                    Location p = at.clone().add(Math.cos(a) * r, 0.4 + ThreadLocalRandom.current().nextDouble(1.2), Math.sin(a) * r);
                    Vector v = at.toVector().add(new Vector(0, 0.8, 0)).subtract(p.toVector()).multiply(0.15);
                    w.spawnParticle(Particle.DUST, p, 0, v.getX(), v.getY(), v.getZ(), 1, new Particle.DustOptions(i == 0 ? JADE : EMERALD, 1.0f));
                }
            }
            // three hops around its prey, each on another side: the landing shows a few ticks before
            for (int i = 0; i < 3; i++) {
                int h = hops[i];
                if (t == h - 6) {
                    double base = Math.toDegrees(Math.atan2(at.getZ() - tl.getZ(), at.getX() - tl.getX()));
                    for (double extra : new double[]{0, 35, -35, 70, -70}) {
                        double a = Math.toRadians(base + offsets[i] + extra);
                        double r = 6.0 + ThreadLocalRandom.current().nextDouble(1.0);
                        Location land = landing(e, tl.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r));
                        if (land != null && clearLine(e, land)) {
                            path[i][0] = at.clone();
                            path[i][1] = land;
                            break;
                        }
                    }
                }
                if (path[i][1] != null && t >= h - 6 && t < h + 9) ring(path[i][1], 1.2, JADE, 1.0f);
                if (path[i][1] != null && t >= h && t <= h + 9) {
                    double u = (t - h) / 9.0;
                    Location p = arc(path[i][0], path[i][1], u, 1.3);
                    p.setYaw(yawTo(flat(path[i][0], path[i][1])));
                    Mobs.move(e, p);
                    if (t == h) {
                        puff(path[i][0], 8);
                        snd(path[i][0], "entity.goat.long_jump", 1.3f, 0.7f + i * 0.1f);
                    }
                    if (t == h + 9) {
                        puff(p, 14);
                        emeraldDust(p.clone().add(0, 0.3, 0), 10, 0.6, 1.0f);
                        snd(p, "entity.generic.big_fall", 1.1f, 0.9f);
                    }
                }
            }
            // 58-70: it turns on its prey and contracts; at 64 the impact point is fixed and shown
            if (t >= 58 && t <= 70) face(e, fin[1] != null ? fin[1] : tl);
            if (t == 64) {
                Location aimAt = tl.clone();
                double d = flatDistance(at, aimAt);
                Vector to = flat(at, aimAt);
                Location land = null;
                for (double s = Math.min(maxLeap, Math.max(0, d - 1.0)); s >= 0; s -= 1.0) {
                    land = landing(e, at.clone().add(to.clone().multiply(s)));
                    if (land != null && clearLine(e, land)) break;
                    land = null;
                }
                fin[0] = at.clone();
                fin[1] = land != null ? land : at.clone();
                snd(at, "entity.creeper.primed", 1.2f, 0.5f);
                snd(at, "entity.warden.heartbeat", 1.8f, 1.1f);
            }
            if (fin[1] != null && t >= 64 && t < 78 && t % 2 == 0) {
                ring(fin[1], radius, WARN, 1.3f);
                ring(fin[1], radius * 0.5, JADE, 1.0f);
            }
            // 70-78: the pounce
            if (fin[1] != null && t >= 70 && t <= 78) {
                Location p = arc(fin[0], fin[1], (t - 70) / 8.0, 2.2);
                p.setYaw(yawTo(flat(fin[0], fin[1])));
                Mobs.move(e, p);
                if (t == 70) {
                    puff(fin[0], 10);
                    snd(fin[0], "entity.ravager.roar", 1.4f, 0.9f);
                }
            }
            // 78: the slam, a shockwave that runs along the ground for 8 ticks (jump over it, or be out of it)
            if (t == 78 && fin[1] != null) {
                Location p = fin[1];
                snd(p, "entity.generic.explode", 1.4f, 0.6f);
                snd(p, "block.deepslate.break", 1.8f, 0.5f);
                snd(p, "entity.ravager.attack", 1.4f, 0.6f);
                fx.shockwave(p, radius, 8, 0.3);
                fx.crater(p, 2.5, 50, 0.3, RUBBLE);
                fx.debris(p.clone().add(0, 0.4, 0), 10, Material.COBBLED_DEEPSLATE.createBlockData(), 0.9);
                fx.debris(p.clone().add(0, 0.4, 0), 6, EMERALD_BLOCK, 0.8);
                w.spawnParticle(Particle.CLOUD, p.clone().add(0, 0.3, 0), 30, 1.5, 0.2, 1.5, 0.05);
                emeraldDust(p.clone().add(0, 0.5, 0), 30, 1.6, 1.3f);
                shake(p, 18);
            }
            if (t >= 78 && t <= 86 && fin[1] != null) {
                double ringR = radius * (t - 77) / 9.0;
                Location center = fin[1];
                if (t == 82) snd(center, "block.sculk.break", 1.4f, 0.5f);
                for (Player p : fighters(center, radius + 1.5)) {
                    if (struck.contains(p.getUniqueId())) continue;
                    double d = flatDistance(center, p.getLocation());
                    if (d > ringR + 0.3 || d > radius) continue;
                    // in the air when the wave passes: it goes under him
                    double floor = groundY(w, p.getLocation().getX(), p.getLocation().getY() + 0.2, p.getLocation().getZ(), 3);
                    if (!Double.isNaN(floor) && p.getLocation().getY() - floor > 0.5) continue;
                    if (Math.abs(p.getLocation().getY() - center.getY()) > 2.0) continue;
                    // never through a wall
                    Location eye = center.clone().add(0, 0.8, 0);
                    var wall = w.rayTraceBlocks(eye, p.getEyeLocation().toVector().subtract(eye.toVector()).normalize(),
                            eye.distance(p.getEyeLocation()), org.bukkit.FluidCollisionMode.NEVER, true);
                    if (wall != null && wall.getHitBlock() != null) continue;
                    struck.add(p.getUniqueId());
                    c.hit(p, power);
                    Vector out = flat(center, p.getLocation());
                    p.setVelocity(out.multiply(1.1).setY(0.5));
                }
            }
            // 80-100: spent, low and panting: the window to strike
            if (t == 80) exposed(c, 20);
            if (t >= 82 && t % 6 == 0 && t < 100) snd(e.getLocation(), "entity.axolotl.idle_air", 0.8f, 0.6f);
            if (t == 100) {
                setMode(b, Mode.PAUSE, 4, 0);
                c.release();
            }
        });
    }

    // ---------------------------------------------------------------- Furie emeraude: phase change at 40 % (40 ticks)

    private void frenzy(MobTemplate.Ability ab, RockPowers.Caster c, Brain b) {
        LivingEntity e = c.entity();
        c.hold(42);
        e.setInvulnerable(true);
        c.animate("frenzy");
        title(e.getLocation(), ab, 40);
        timeline(e, 40, t -> {
            Location at = e.getLocation();
            if (t == 0) snd(at, "entity.axolotl.hurt", 1.4f, 0.4f);
            if (t == 14) {
                snd(at, "entity.ravager.roar", 2.0f, 0.7f);
                snd(at, "entity.warden.roar", 0.6f, 1.4f);
                shake(at, 20);
            }
            if (t == 18) {
                snd(at, "block.amethyst_block.resonate", 1.6f, 0.6f);
                for (int i = 0; i < 4; i++) ring(at.clone(), 1.5 + i, i % 2 == 0 ? JADE : EMERALD, 1.2f);
                emeraldDust(at.clone().add(0, 1, 0), 40, 1.6, 1.3f);
                w(e).spawnParticle(Particle.WITCH, at.clone().add(0, 1, 0), 20, 1.4, 0.6, 1.4, 0);
            }
            if (t > 18 && t < 34 && t % 3 == 0) emeraldDust(body(e, 0, 1.0, ThreadLocalRandom.current().nextDouble(-3, 2)), 3, 0.8, 0.9f);
            if (t == 40) {
                e.setInvulnerable(false);
                b.enraged = true;
                setMode(b, Mode.APPROACH, 10, 0);
                c.release();
            }
        });
    }

    private static World w(LivingEntity e) {
        return e.getWorld();
    }
}
