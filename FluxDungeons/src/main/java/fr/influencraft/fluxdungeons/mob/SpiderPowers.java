package fr.influencraft.fluxdungeons.mob;

import fr.influencraft.flux.api.text.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Sylvan Spider (L'Araignée Sylvestre, the Weaver of the Old Woods), boss of the forester dungeon: a giant spider
 * grown out of an old tree. Heavy, sudden, and a hunter: it weighs its prey, walks around it, charges when it gets
 * away. Every timing follows its model's animations tick for tick (docs/technique/boss/araignee-sylvestre.md,
 * assets/monstres/araignee_sylvestre_anim.py, table EVENTS):
 * <ul>
 *   <li>before its entrance it is a heap of dead wood in the middle of its clearing (animation {@code dormant}): seen,
 *   but still and out of reach;</li>
 *   <li>{@code tisseuse_awaken} (awaken 110, opening): legs unfold one after the other, its eyes light up (38), it
 *   rises, threatens and screams (86); invulnerable until 110;</li>
 *   <li>{@code tisseuse_bite} (bite 34): wind-up, a lunge of its whole body, the chelicerae snap at 18 on whoever
 *   stands in front of its mouth;</li>
 *   <li>{@code tisseuse_volley} (volley 56): its abdomen rears up, bark plates part, three wooden needles leave at
 *   22, 28 and 34 for the player keeping furthest away: straight at him, ahead of his run, then further ahead (or
 *   beside a player standing still); they bend a little towards him, stop on walls, slow briefly;</li>
 *   <li>{@code tisseuse_snare} (snare 60): zones marked from 6 (red from 20) under the players, fibres flung at 28,
 *   webs of roots on the ground at 32 for a few seconds: slow inside, a short hold on the knot in the middle;</li>
 *   <li>{@code tisseuse_grove} (grove 140, ultimate): it roots itself, a crown of roots rises around it, a network of
 *   roots runs through the arena (50-75), then three strikes (80, 92, 104): the knots of the network marked in red
 *   burst into roots; after it the spider is spent (exhausted 60): still, no attack, an opening for the players.</li>
 * </ul>
 * It moves itself (its file says {@code stationary: true}): always forwards along its heading, which turns at a limited
 * rate, at the exact speeds its walk and run are drawn for (no sliding feet); a short crouch before it bolts (pounce),
 * a braking step when it stops (halt), small steps when it turns on the spot (shuffle). Its bite is its only melee hit
 * ({@code attack-speed: 0}). Nothing ever changes in the map: roots, webs and needles are displays, never saved.
 */
final class SpiderPowers {

    static final Set<String> TYPES = Set.of("tisseuse_awaken", "tisseuse_bite", "tisseuse_volley", "tisseuse_snare", "tisseuse_grove");
    static final String MODEL = "araignee_sylvestre";
    private static final String TAG = "fluxdungeons_fx";
    /** Scoreboard tag of a spider whose entrance is over. */
    private static final String AWAKE = "fluxdungeons_spider_awake";
    private static final List<String> EYES = List.of("glow_Eyes_empty");
    /** Blocks a second its walk and its run are drawn for (araignee_sylvestre_anim.py: WALK_SPEED, RUN_SPEED). */
    private static final double WALK = 1.4, RUN = 3.05;
    /** Blocks between two plants of a group of feet, in both gaits. */
    private static final double STRIDE = 0.84;
    /** Its feet at rest: blocks to its right, blocks ahead of its origin (front pair first). */
    private static final double[][] FEET = {{1.32, 1.72}, {-1.32, 1.72}, {1.81, 0.64}, {-1.81, 0.64},
            {1.74, -0.5}, {-1.74, -0.5}, {1.43, -1.75}, {-1.43, -1.75}};
    /** Feet of the group that plants first (alternating tetrapod: left 1, right 2, left 3, right 4). */
    private static final int[] GROUP_A = {1, 2, 5, 6}, GROUP_B = {0, 3, 4, 7};
    /** Order and tick each leg lands on during the entrance. */
    private static final int[][] WAKE_STEPS = {{0, 38}, {7, 42}, {2, 54}, {5, 58}, {1, 62}, {3, 68}, {4, 72}, {6, 76}};
    private static final Color AMBER = Color.fromRGB(0xE2B75D), OLIVE = Color.fromRGB(0x84936A), RED = Color.fromRGB(0xFF5C5C),
            BARK = Color.fromRGB(0x62452F), MOSS_C = Color.fromRGB(0x617A4B);
    private static final Color[] LEAVES = {Color.fromRGB(0x425B3C), Color.fromRGB(0x617A4B), Color.fromRGB(0x84936A),
            Color.fromRGB(0x99704A), Color.fromRGB(0x795239)};
    private static final BlockData ROOT = Material.MANGROVE_ROOTS.createBlockData(), DIRT = Material.ROOTED_DIRT.createBlockData(),
            WOOD = Material.DARK_OAK_WOOD.createBlockData(), LOG = Material.DARK_OAK_LOG.createBlockData(),
            KNOT = Material.SPRUCE_WOOD.createBlockData(), KNOT_LIT = Material.OCHRE_FROGLIGHT.createBlockData();
    private static final Material[] CRACKED = {Material.ROOTED_DIRT, Material.COARSE_DIRT, Material.PODZOL};

    private enum Mode {CHASE, APPROACH, CIRCLE, PAUSE}

    private enum Gait {STILL, WALK, RUN, SHUFFLE}

    /** A spider and what it is doing. */
    private static final class Brain {
        final LivingEntity e;
        boolean awakened, waking, dormant, sprinting, yawKnown;
        float yaw;
        Mode mode = Mode.APPROACH;
        Gait gait = Gait.STILL;
        int modeAge, modeTicks, side = 1, busyUntil, stillTicks, nextMood;
        double orbit = 6, walked;
        boolean groupA = true;
        Vector lastPrey;

        Brain(LivingEntity e) {
            this.e = e;
            // a spider found again (its chunk came back) has already made its entrance
            this.awakened = e.getScoreboardTags().contains(AWAKE);
        }
    }

    /** A web of roots on the ground: slows whoever walks in it, holds once whoever steps on its knot. */
    private record Web(UUID owner, Location center, double radius, double knot, int until, int holdTicks, String heldText,
                       List<BlockDisplay> strands, Set<UUID> held) {
    }

    private final Plugin plugin;
    private final Fx fx;
    private final Mobs mobs;
    private final Map<UUID, Brain> brains = new HashMap<>();
    private final Set<Entity> alive = new HashSet<>();
    private final List<Web> webs = new ArrayList<>();
    private int ticks;

    SpiderPowers(Plugin plugin, Fx fx, Mobs mobs) {
        this.plugin = plugin;
        this.fx = fx;
        this.mobs = mobs;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    boolean cast(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        if (!TYPES.contains(ab.type())) return false;
        Brain b = brain(c.entity());
        setGait(b, Gait.STILL);
        if (b.sprinting) {
            b.sprinting = false;
            mobs.loop(b.e, "run", false);
        }
        switch (ab.type()) {
            case "tisseuse_awaken" -> awaken(ab, c, b, target);
            case "tisseuse_bite" -> bite(ab, c, b, target);
            case "tisseuse_volley" -> volley(ab, c, b);
            case "tisseuse_snare" -> snare(ab, c, b);
            case "tisseuse_grove" -> grove(ab, c, b);
            default -> {
            }
        }
        return true;
    }

    /**
     * Whether casting now makes sense. Nothing before the entrance, which waits for a player in its clearing; the bite
     * wants someone in front of its mouth, the needles someone keeping away in sight, the web and the grove someone
     * around it.
     */
    boolean worth(MobTemplate.Ability ab, LivingEntity e) {
        if (!TYPES.contains(ab.type())) return true;
        Brain b = brain(e);
        if (!b.awakened) {
            return "tisseuse_awaken".equals(ab.type()) && !b.waking && !fighters(e.getLocation(), ab.number("wake-range", 17)).isEmpty();
        }
        if (Bukkit.getCurrentTick() < b.busyUntil) return false;
        return switch (ab.type()) {
            case "tisseuse_awaken" -> false;
            case "tisseuse_bite" -> {
                for (Player p : fighters(e.getLocation(), ab.number("reach", 4.1) + 0.3))
                    if (Math.abs(side(b, p.getLocation())) <= 65 && e.hasLineOfSight(p)) yield true;
                yield false;
            }
            case "tisseuse_volley" -> farthest(e, ab.number("min-range", 6), ab.number("max-range", 30)) != null;
            case "tisseuse_snare" -> !fighters(e.getLocation(), ab.number("reach", 18)).isEmpty();
            case "tisseuse_grove" -> !fighters(e.getLocation(), ab.number("reach", 22)).isEmpty();
            default -> true;
        };
    }

    /** Whether this monster steers itself (its heading is ours: the pilot must not turn it to its target). */
    boolean steers(LivingEntity e) {
        return brains.containsKey(e.getUniqueId());
    }

    void clear() {
        for (Entity d : alive) if (d.isValid()) d.remove();
        alive.clear();
        webs.clear();
        brains.clear();
    }

    /** A spider just appeared (or came back with its chunk): a heap of dead wood until its entrance. */
    void spawned(LivingEntity e) {
        Brain b = brain(e);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!e.isValid()) return;
            if (b.awakened) mobs.parts(e, true, EYES);
            else sleep(b);
        }, 2L);
    }

    /**
     * The spider died: the fight is over at once (victory, rewards), and a stand-in of its model collapses where it
     * stood (animation death 70): legs curl in, bark plates fly off, its eyes go out (40), it lies down (50).
     */
    void died(LivingEntity e) {
        Brain b = brains.remove(e.getUniqueId());
        webs.removeIf(web -> {
            if (!web.owner().equals(e.getUniqueId())) return false;
            for (BlockDisplay d : web.strands()) forget(d);
            return true;
        });
        Location at = e.getLocation();
        if (b != null && b.yawKnown) at.setYaw(b.yaw);
        at.setPitch(0);
        World w = at.getWorld();
        ModelBridge.Handle corpse;
        try {
            corpse = ModelBridge.stand(at, MODEL, fighters(at, 64));
        } catch (LinkageError | RuntimeException ex) {
            corpse = null;
        }
        if (corpse != null) {
            corpse.parts(true, EYES);
            corpse.play("death");
        }
        ModelBridge.Handle body = corpse;
        Vector f = at.getDirection().setY(0).normalize();
        Location back = at.clone().add(f.clone().multiply(-0.7)).add(0, 1.8, 0);
        snd(at, "entity.creaking.death", 2.0f, 0.5f);
        snd(at, "entity.spider.death", 1.4f, 0.5f);
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                t++;
                if (t == 20) snd(at, "entity.evoker_fangs.attack", 1.2f, 0.6f);
                if (t == 26) snd(at, "entity.creaking.sway", 1.6f, 0.5f);
                if (t == 34 || t == 37) {
                    fx.debris(back, 3, WOOD, 0.5);
                    leaves(back, 10, 0.6);
                    snd(back, "block.wood.break", 1.4f, 0.6f);
                }
                if (t == 40 && body != null) body.parts(false, EYES);
                if (t == 50) {
                    snd(at, "entity.ravager.step", 1.6f, 0.5f);
                    snd(at, "block.wood.fall", 1.6f, 0.5f);
                    w.spawnParticle(Particle.BLOCK, at, 40, 1.4, 0.1, 1.4, 0, blockUnder(at));
                    leaves(at.clone().add(0, 0.6, 0), 14, 1.2);
                }
                if (t >= 110) {
                    if (body != null) body.close();
                    w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.8, 0), 60, 1.2, 0.6, 1.2, 0, WOOD);
                    leaves(at.clone().add(0, 1.0, 0), 24, 1.2);
                    snd(at, "block.wood.break", 1.4f, 0.5f);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private Brain brain(LivingEntity e) {
        return brains.computeIfAbsent(e.getUniqueId(), k -> new Brain(e));
    }

    // ---------------------------------------------------------------- every tick

    private void tick() {
        ticks++;
        if (ticks % 10 == 0) {
            // a spider is known from its model, among the dungeon monsters of loaded worlds
            for (World w : Bukkit.getWorlds()) {
                for (LivingEntity le : w.getLivingEntities()) {
                    if (brains.containsKey(le.getUniqueId()) || mobs.templateOf(le) == null) continue;
                    MobTemplate t = mobs.templateOfEntity(le);
                    if (t != null && MODEL.equals(t.model())) spawned(le);
                }
            }
        }
        for (Iterator<Brain> it = brains.values().iterator(); it.hasNext(); ) {
            Brain b = it.next();
            if (!b.e.isValid() || b.e.isDead()) {
                it.remove();
                continue;
            }
            if (!b.awakened) {
                if (!b.waking) asleep(b);
                continue;
            }
            prowl(b);
        }
        webTick();
    }

    /** Dead wood: still, out of reach, folded on itself; dust and spores hang around it now and then. */
    private void sleep(Brain b) {
        if (b.dormant) return;
        b.dormant = true;
        b.e.setInvulnerable(true);
        mobs.parts(b.e, false, EYES);
        mobs.loop(b.e, "dormant", true);
    }

    private void asleep(Brain b) {
        if (!b.dormant) sleep(b);
        if (ticks % 12 != 0) return;
        Location at = b.e.getLocation();
        World w = at.getWorld();
        w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, at.clone().add(0, 1.2, 0), 2, 1.4, 0.6, 1.4, 0);
        if (ticks % 36 == 0) leaves(at.clone().add(0, 2.2, 0), 1, 1.2);
    }

    // ---------------------------------------------------------------- the hunt: it moves itself

    private static Player target(LivingEntity e) {
        if (e instanceof Mob m && m.getTarget() instanceof Player p && p.getWorld() == e.getWorld() && Mobs.fighting(p)) return p;
        return null;
    }

    private void prowl(Brain b) {
        LivingEntity e = b.e;
        if (!(e instanceof Mob m)) return;
        if (!b.yawKnown) {
            b.yaw = e.getLocation().getYaw();
            b.yawKnown = true;
        }
        int now = Bukkit.getCurrentTick();
        if (!m.hasAI()) {
            // an ability holds it and plays its own animations
            if (b.gait != Gait.STILL) setGait(b, Gait.STILL);
            return;
        }
        if (now < b.busyUntil) return;
        Player target = target(e);
        if (target == null) {
            setGait(b, Gait.STILL);
            stopSprint(b, false);
            return;
        }
        Location at = e.getLocation();
        double d = flatDistance(at, target.getLocation());
        boolean enraged = e.getHealth() < 0.4 * maxHealth(e);
        // whether its prey stands still (it then feints towards it)
        Vector here = target.getLocation().toVector();
        if (b.lastPrey != null && here.distanceSquared(b.lastPrey) < 0.01) b.stillTicks++;
        else b.stillTicks = 0;
        b.lastPrey = here;

        b.modeAge++;
        if (b.modeAge >= b.modeTicks) choose(b, d, enraged);
        // a prey that gets away is run down whatever it was doing; one that comes close ends the charge
        if (b.mode != Mode.CHASE && d > (enraged ? 9 : 11)) setMode(b, Mode.CHASE, 40);
        if (b.mode == Mode.CHASE && d < 5) setMode(b, Mode.APPROACH, 14);

        Vector to = flat(at, target.getLocation()), r = right(to);
        Vector want = null;
        boolean run = false;
        switch (b.mode) {
            case CHASE -> {
                want = to;
                run = true;
            }
            case APPROACH -> {
                if (d > 3.0) want = to;
            }
            case CIRCLE -> {
                double radial = Math.max(-0.7, Math.min(0.7, (d - b.orbit) * 0.4));
                want = r.clone().multiply(b.side).add(to.clone().multiply(radial)).normalize();
            }
            case PAUSE -> {
                // a prey standing still within reach of a feint: its front legs reach for it, without striking
                if (b.modeAge == 6 && b.stillTicks > 25 && d > 3.5 && d < 8 && now >= b.nextMood) mood(b, "probe", 36);
            }
        }
        if (run && !b.sprinting) {
            // the spring: it crouches on its legs (5 ticks) before it bolts
            b.sprinting = true;
            setGait(b, Gait.STILL);
            mobs.play(e, "pounce");
            b.busyUntil = now + 5;
            snd(at, "entity.creaking.twitch", 1.4f, 0.7f);
            snd(at, "block.wood.hit", 1.2f, 0.5f);
            e.getWorld().spawnParticle(Particle.BLOCK, at, 16, 1.2, 0.05, 1.2, 0, blockUnder(at));
            return;
        }
        if (!run && b.sprinting) stopSprint(b, true);
        if (want == null) {
            // standing: it turns to its prey, with small steps when the turn is wide
            float gap = turn(b, yawTo(to), 6);
            setGait(b, Math.abs(gap) > 4 ? Gait.SHUFFLE : Gait.STILL);
            e.setRotation(b.yaw, 0);
            e.setBodyYaw(b.yaw);
            if (b.gait == Gait.SHUFFLE && ticks % 6 == 0) creak(at, 0.7f);
            return;
        }
        float gap = turn(b, yawTo(want), run ? 7 : 10);
        double speed = (run ? RUN : WALK) / 20.0;
        // a wide turn is taken slowly: it pivots before it goes
        if (Math.abs(gap) > 50) speed *= 0.35;
        Vector heading = dir(b.yaw);
        Location next = step(e, heading.multiply(speed), b.yaw);
        if (next == null) {
            // a wall or a ledge: the other way round, or straight to its prey
            if (b.mode == Mode.CIRCLE) b.side = -b.side;
            else setMode(b, Mode.CIRCLE, 16);
            setGait(b, Gait.STILL);
            return;
        }
        setGait(b, run ? Gait.RUN : Gait.WALK);
        if (Math.abs(gap) > 20 && ticks % 8 == 0) creak(at, run ? 0.9f : 0.7f);
        footfalls(b, speed, run);
    }

    /** Picks what it does next from the distance to its prey. */
    private void choose(Brain b, double d, boolean enraged) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double roll = r.nextDouble();
        if (d > 10) {
            setMode(b, Mode.CHASE, 40);
        } else if (d > 4.5) {
            if (roll < 0.40) {
                setMode(b, Mode.CIRCLE, 26 + r.nextInt(24));
                b.orbit = Math.max(4.5, Math.min(8, d));
                if (r.nextDouble() < 0.4) b.side = -b.side;
            } else if (roll < (enraged ? 0.92 : 0.75)) {
                setMode(b, Mode.APPROACH, 20 + r.nextInt(16));
            } else {
                // it stops dead and weighs its prey, or rears up at it
                setMode(b, Mode.PAUSE, 30 + r.nextInt(12));
                if (Bukkit.getCurrentTick() >= b.nextMood) mood(b, r.nextBoolean() ? "watch" : "threat", r.nextBoolean() ? 30 : 40);
            }
        } else {
            if (roll < 0.70) setMode(b, Mode.APPROACH, 14 + r.nextInt(10));
            else {
                setMode(b, Mode.CIRCLE, 16 + r.nextInt(12));
                b.orbit = 4.5;
            }
        }
    }

    private static void setMode(Brain b, Mode mode, int ticks) {
        b.mode = mode;
        b.modeTicks = Math.max(4, ticks);
        b.modeAge = 0;
    }

    /** A moment of its own while it stands: an animation, its sound, and no other one for a while. */
    private void mood(Brain b, String animation, int length) {
        LivingEntity e = b.e;
        setGait(b, Gait.STILL);
        mobs.play(e, animation);
        b.nextMood = Bukkit.getCurrentTick() + 120 + ThreadLocalRandom.current().nextInt(80);
        Location at = e.getLocation();
        switch (animation) {
            case "threat" -> {
                b.modeTicks = Math.max(b.modeTicks, b.modeAge + length);
                snd(at, "entity.creaking.activate", 1.4f, 0.7f);
                snd(at, "entity.spider.ambient", 1.4f, 0.5f);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!e.isValid()) return;
                    Location l = e.getLocation();
                    snd(l, "entity.ravager.step", 1.2f, 0.6f);
                    for (int i = 0; i < 2; i++) dust(foot(b, i), 8);
                }, 34L);
            }
            case "probe" -> {
                b.modeTicks = Math.max(b.modeTicks, b.modeAge + length);
                snd(at, "entity.creaking.sway", 1.0f, 0.9f);
                for (int[] tap : new int[][]{{0, 12}, {1, 16}}) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!e.isValid()) return;
                        Location l = body(b, FEET[tap[0]][0], 0.05, FEET[tap[0]][1] + 0.4);
                        snd(l, "block.wood.step", 1.0f, 0.7f);
                        dust(l, 5);
                    }, tap[1]);
                }
            }
            default -> snd(at, "entity.spider.ambient", 0.8f, 0.4f);
        }
    }

    private void stopSprint(Brain b, boolean brake) {
        if (!b.sprinting) return;
        b.sprinting = false;
        mobs.loop(b.e, "run", false);
        b.gait = Gait.STILL;
        if (!brake) return;
        // it brakes on its front legs, the abdomen swinging on
        mobs.play(b.e, "halt");
        b.busyUntil = Bukkit.getCurrentTick() + 6;
        Location at = b.e.getLocation();
        snd(at, "block.wood.hit", 1.4f, 0.5f);
        snd(at, "block.rooted_dirt.break", 1.2f, 0.7f);
        for (int i = 0; i < 2; i++) dust(foot(b, i), 10);
    }

    private void setGait(Brain b, Gait gait) {
        if (b.gait == gait) return;
        switch (b.gait) {
            case WALK -> mobs.loop(b.e, "walk", false);
            case RUN -> mobs.loop(b.e, "run", false);
            case SHUFFLE -> mobs.loop(b.e, "shuffle", false);
            default -> {
            }
        }
        b.gait = gait;
        switch (gait) {
            case WALK -> mobs.loop(b.e, "walk", true);
            case RUN -> mobs.loop(b.e, "run", true);
            case SHUFFLE -> mobs.loop(b.e, "shuffle", true);
            default -> {
            }
        }
    }

    /** Turns its heading towards {@code wanted} by {@code max} degrees at most; returns what is left to turn. */
    private static float turn(Brain b, float wanted, float max) {
        float gap = wrap(wanted - b.yaw);
        b.yaw = wrap(b.yaw + Math.max(-max, Math.min(max, gap)));
        return wrap(wanted - b.yaw);
    }

    private static float wrap(float a) {
        a %= 360;
        if (a > 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }

    private static Vector dir(float yaw) {
        double a = Math.toRadians(yaw);
        return new Vector(-Math.sin(a), 0, Math.cos(a));
    }

    /**
     * Wood on the ground: one knock each time a group of four feet plants (every {@link #STRIDE} blocks, the pace of
     * both gaits), dust under those feet; heavier and scratching in the run.
     */
    private void footfalls(Brain b, double moved, boolean run) {
        b.walked += moved;
        if (b.walked < STRIDE) return;
        b.walked -= STRIDE;
        b.groupA = !b.groupA;
        Location at = b.e.getLocation();
        snd(at, "block.wood.step", run ? 1.5f : 1.1f, run ? 0.55f : 0.65f);
        snd(at, run ? "block.rooted_dirt.break" : "block.rooted_dirt.step", run ? 0.9f : 0.8f, 0.7f);
        if (ThreadLocalRandom.current().nextInt(3) == 0) creak(at, 1.0f);
        for (int i : b.groupA ? GROUP_A : GROUP_B) {
            Location f = foot(b, i);
            f.getWorld().spawnParticle(Particle.BLOCK, f, run ? 5 : 3, 0.12, 0.03, 0.12, 0, blockUnder(f));
            if (run && i < 2) leaves(f.clone().add(0, 0.2, 0), 1, 0.2);
        }
    }

    private static void creak(Location at, float pitch) {
        snd(at, "entity.creaking.step", 0.8f, pitch);
    }

    /** One step of its body along {@code move}, on the ground, if it fits there (else along one axis, else null). */
    private static Location step(LivingEntity e, Vector move, float yaw) {
        Location at = e.getLocation();
        for (Vector m : new Vector[]{move, new Vector(move.getX(), 0, 0), new Vector(0, 0, move.getZ())}) {
            if (m.lengthSquared() < 1e-6) continue;
            Location next = at.clone().add(m);
            Location ground = landing(next);
            if (ground == null || Math.abs(ground.getY() - at.getY()) > 1.1) continue;
            ground.setYaw(yaw);
            ground.setPitch(0);
            Mobs.move(e, ground);
            e.setBodyYaw(yaw);
            return ground;
        }
        return null;
    }

    /** The ground under a place if its body fits there (free above, floor at most 3 below), else null. */
    private static Location landing(Location at) {
        World w = at.getWorld();
        double y = Double.NaN;
        int bx = at.getBlockX(), bz = at.getBlockZ();
        for (int by = (int) Math.floor(at.getY() + 1.0); by >= (int) Math.floor(at.getY()) - 3; by--) {
            Block b = w.getBlockAt(bx, by, bz);
            if (b.isCollidable()) {
                y = by + b.getBoundingBox().getHeight();
                break;
            }
        }
        if (Double.isNaN(y)) return null;
        for (double dx : new double[]{-0.9, 0, 0.9}) {
            for (double dz : new double[]{-0.9, 0, 0.9}) {
                for (double h : new double[]{0.3, 1.3}) {
                    if (w.getBlockAt((int) Math.floor(at.getX() + dx), (int) Math.floor(y + h), (int) Math.floor(at.getZ() + dz)).isCollidable()) return null;
                }
            }
        }
        Location out = at.clone();
        out.setY(y);
        return out;
    }

    // ---------------------------------------------------------------- shared

    private static void snd(Location at, String key, float volume, float pitch) {
        at.getWorld().playSound(at, key, SoundCategory.HOSTILE, volume, pitch);
    }

    private static BlockData blockUnder(Location at) {
        Block b = Fx.ground(at.getWorld(), at.getBlockX(), at.getY(), at.getBlockZ());
        return b == null ? Material.PODZOL.createBlockData() : b.getBlockData();
    }

    private static List<Player> fighters(Location at, double within) {
        List<Player> out = new ArrayList<>();
        for (Player p : at.getWorld().getPlayers()) {
            if (Mobs.fighting(p) && p.getLocation().distanceSquared(at) <= within * within) out.add(p);
        }
        return out;
    }

    private static double flatDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
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

    private Vector forward(Brain b) {
        return b.yawKnown ? dir(b.yaw) : b.e.getLocation().getDirection().setY(0).normalize();
    }

    /** Signed angle of a place from its front, in degrees. */
    private double side(Brain b, Location at) {
        Vector f = forward(b), to = flat(b.e.getLocation(), at);
        return Math.toDegrees(Math.atan2(right(f).dot(to), f.dot(to)));
    }

    /** A point of its body: {@code x} blocks to its right, {@code y} up, {@code ahead} in front of its origin. */
    private Location body(Brain b, double x, double y, double ahead) {
        Vector f = forward(b);
        return b.e.getLocation().clone().add(right(f).multiply(x)).add(0, y, 0).add(f.multiply(ahead));
    }

    private Location foot(Brain b, int i) {
        return body(b, FEET[i][0], 0.05, FEET[i][1]);
    }

    /** Its back, where the bark plates are. */
    private Location back(Brain b) {
        return body(b, 0, 2.05, -0.8);
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
        g.setYaw(0);
        g.setPitch(0);
        return g;
    }

    /** Turns it towards a place, {@code max} degrees a tick at most (never a snap). */
    private void face(Brain b, Location to, float max) {
        if (!b.yawKnown) {
            b.yaw = b.e.getLocation().getYaw();
            b.yawKnown = true;
        }
        turn(b, yawTo(flat(b.e.getLocation(), to)), max);
        b.e.setRotation(b.yaw, 0);
        b.e.setBodyYaw(b.yaw);
    }

    private static void shake(Location at, double within) {
        for (Player p : fighters(at, within)) p.sendHurtAnimation(0);
    }

    private static double maxHealth(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    private static void title(Location at, MobTemplate.Ability ab, int ticks) {
        String title = ab.text("title", ""), subtitle = ab.text("subtitle", "");
        if (title.isBlank() && subtitle.isBlank()) return;
        for (Player p : fighters(at, 48)) {
            p.showTitle(net.kyori.adventure.title.Title.title(Texts.parse(title), Texts.parse(subtitle),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(200),
                            java.time.Duration.ofMillis(Math.max(500, ticks * 50L)), java.time.Duration.ofMillis(400))));
        }
    }

    private static void bar(Player p, String text) {
        if (text != null && !text.isBlank()) p.sendActionBar(Texts.parse(text));
    }

    /** Dead leaves and moss, in the browns and greens of its bark. */
    private static void leaves(Location at, int count, double spread) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            at.getWorld().spawnParticle(Particle.TINTED_LEAVES, at, 1, spread, spread * 0.6, spread, 0, LEAVES[r.nextInt(LEAVES.length)]);
        }
    }

    private static void dust(Location at, int count) {
        at.getWorld().spawnParticle(Particle.BLOCK, at, count, 0.2, 0.04, 0.2, 0, blockUnder(at));
    }

    private static void mote(Location at, Color color, float size, int count, double spread) {
        at.getWorld().spawnParticle(Particle.DUST, at, count, spread, spread, spread, 0, new Particle.DustOptions(color, size));
    }

    /** Amber glints before its eyes. */
    private void eyeGlow(Brain b, int count) {
        mote(body(b, 0, 0.95, 1.4), AMBER, 0.6f, count, 0.22);
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

    private void later(LivingEntity e, long ticks, Runnable run) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (e == null || (e.isValid() && !e.isDead())) run.run();
        }, ticks);
    }

    /** Held in place for {@code ticks}: slowed hard, no jump. */
    private static void hold(Player p, int ticks, String text) {
        if (ticks <= 0) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 5, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, ticks, 128, false, false));
        bar(p, text);
    }

    private BlockDisplay display(Location at, BlockData data, Transformation tr, int light) {
        BlockDisplay d = at.getWorld().spawn(at, BlockDisplay.class, bd -> {
            bd.setBlock(data);
            bd.setPersistent(false);
            bd.setTransformation(tr);
            if (light >= 0) bd.setBrightness(new Display.Brightness(light, 15));
            bd.addScoreboardTag(TAG);
        });
        alive.add(d);
        return d;
    }

    private void forget(Entity d) {
        alive.remove(d);
        if (d.isValid()) d.remove();
    }

    private static void moveTo(Display d, Transformation tr, int ticks) {
        if (!d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(tr);
    }

    /**
     * A root out of the ground at {@code base}: a column {@code height} tall leaning {@code lean} degrees towards
     * {@code dir} (any way when null), rising in {@code rise} ticks and sinking back after {@code life}.
     */
    private void root(Location base, double height, double width, double lean, Vector dir, int rise, int life) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = base.clone();
        at.setYaw(0);
        at.setPitch(0);
        float yaw = dir == null ? (float) r.nextDouble(0, Math.PI * 2) : (float) Math.atan2(dir.getX(), dir.getZ());
        float[][] pieces = {{(float) width, (float) (height * 0.6), 0}, {(float) (width * 0.6), (float) (height * 0.55), 12}};
        Vector3f p = new Vector3f();
        for (float[] pc : pieces) {
            Quaternionf q = new Quaternionf().rotateY(yaw).rotateX((float) Math.toRadians(lean + pc[2]));
            Vector3f corner = q.transform(new Vector3f(-pc[0] / 2, 0, -pc[0] / 2)).add(p);
            Transformation up = new Transformation(corner, q, new Vector3f(pc[0], pc[1], pc[0]), new Quaternionf());
            Transformation down = new Transformation(new Vector3f(corner).sub(0, (float) height + 0.3f, 0), q,
                    new Vector3f(pc[0], pc[1], pc[0]), new Quaternionf());
            BlockDisplay d = display(at, ROOT, down, -1);
            Bukkit.getScheduler().runTaskLater(plugin, () -> moveTo(d, up, rise), 1L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> moveTo(d, down, 10), rise + (long) life);
            Bukkit.getScheduler().runTaskLater(plugin, () -> forget(d), rise + life + 11L);
            p.add(q.transform(new Vector3f(0, pc[1] * 0.85f, 0)));
        }
        at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 0.1, 0), 6, 0.25, 0.05, 0.25, 0, DIRT);
    }

    private static Transformation strand(Vector3f offset, float yaw, float width, float height, float length) {
        Quaternionf q = new Quaternionf().rotateY(yaw);
        Vector3f corner = q.transform(new Vector3f(-width / 2, 0, 0)).add(offset);
        return new Transformation(corner, q, new Vector3f(width, height, length), new Quaternionf());
    }

    /**
     * A root lying on the ground from {@code from} towards {@code to}, growing from its start in {@code grow} ticks.
     * {@link #retract} draws it back.
     */
    private BlockDisplay strand(Location from, Location to, float width, int grow) {
        Location at = from.clone().add(0, 0.02, 0);
        at.setYaw(0);
        at.setPitch(0);
        Vector d = to.toVector().subtract(from.toVector()).setY(0);
        float yaw = (float) Math.atan2(d.getX(), d.getZ()), len = (float) d.length();
        BlockDisplay s = display(at, ROOT, strand(new Vector3f(), yaw, width, width * 0.7f, 0.01f), -1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> moveTo(s, strand(new Vector3f(), yaw, width, width * 0.7f, len), grow), 1L);
        return s;
    }

    private void retract(BlockDisplay s, int ticks) {
        if (!s.isValid()) return;
        Transformation tr = s.getTransformation();
        moveTo(s, new Transformation(tr.getTranslation(), tr.getLeftRotation(),
                new Vector3f(tr.getScale().x, tr.getScale().y, 0.01f), tr.getRightRotation()), ticks);
        Bukkit.getScheduler().runTaskLater(plugin, () -> forget(s), ticks + 1L);
    }

    /** A wooden knot on the ground (a cube turned on itself), where roots cross. */
    private BlockDisplay knot(Location at, float size, BlockData data) {
        Location l = at.clone();
        l.setYaw(0);
        l.setPitch(0);
        Quaternionf q = new Quaternionf().rotateY((float) ThreadLocalRandom.current().nextDouble(Math.PI));
        Vector3f corner = q.transform(new Vector3f(-size / 2, 0, -size / 2));
        Transformation small = new Transformation(new Vector3f(0, -size, 0).add(corner), q, new Vector3f(size, size, size), new Quaternionf());
        BlockDisplay k = display(l, data, small, -1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> moveTo(k, new Transformation(new Vector3f(corner).add(0, -size * 0.35f, 0), q,
                new Vector3f(size, size, size), new Quaternionf()), 4), 1L);
        return k;
    }

    // ---------------------------------------------------------------- entrance (awaken 110)

    private void awaken(MobTemplate.Ability ab, RockPowers.Caster c, Brain b, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int length = 110;
        b.waking = true;
        b.dormant = false;
        b.yaw = e.getLocation().getYaw();
        b.yawKnown = true;
        c.hold(length + 2);
        e.setInvulnerable(true);
        mobs.loop(e, "dormant", false);
        c.animate("awaken");
        timeline(e, length, t -> {
            Location at = e.getLocation();
            // 0-20 dead wood: dust and spores hang in the air, something creaks inside
            if (t == 0) snd(at, "block.creaking_heart.idle", 1.8f, 0.5f);
            if (t < 40 && t % 4 == 0) {
                w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, at.clone().add(0, 1.4, 0), 3, 1.2, 0.5, 1.2, 0);
                leaves(at.clone().add(0, 2.0, 0), 1, 1.0);
            }
            // 20 a first leg unfolds, slowly; 28 a second one answers; 30 the abdomen contracts
            if (t == 20) {
                snd(at, "entity.creaking.twitch", 1.6f, 0.6f);
                dust(foot(b, 0), 10);
            }
            if (t == 28) {
                snd(at, "entity.creaking.sway", 1.4f, 0.7f);
                dust(foot(b, 7), 8);
            }
            if (t == 30) {
                snd(at, "block.wood.hit", 1.4f, 0.5f);
                leaves(back(b), 6, 0.5);
            }
            // 38 its eyes light up
            if (t == 38) {
                c.parts(true, EYES);
                snd(at, "block.amethyst_block.resonate", 1.2f, 0.5f);
                snd(at, "entity.spider.ambient", 1.0f, 0.4f);
            }
            if (t >= 38 && t < 50 && t % 2 == 0) eyeGlow(b, 2);
            // each leg lands in its own time: wood on the ground
            for (int[] s : WAKE_STEPS) {
                if (t != s[1]) continue;
                Location f = foot(b, s[0]);
                snd(f, "block.wood.step", 1.4f, 0.55f + s[0] * 0.03f);
                snd(f, "entity.creaking.step", 1.0f, 0.7f);
                dust(f, 8);
            }
            if (t == 44) snd(at, "entity.creaking.activate", 1.2f, 0.6f);
            // 66 the chelicerae open and shut a first time; 70-80 the plates of its back shiver
            if (t == 66) snd(at, "entity.spider.step", 1.4f, 0.5f);
            if (t == 70) snd(at, "entity.evoker_fangs.attack", 1.0f, 1.2f);
            if (t >= 70 && t < 82 && t % 3 == 0) {
                leaves(back(b), 4, 0.6);
                snd(at, "block.bamboo_wood.hit", 0.8f, 0.6f + (t - 70) * 0.03f);
            }
            // 80-92 the threat: front legs thrown forwards, scream at 86, they slam down at 88
            if (t == 80) snd(at, "entity.player.attack.sweep", 1.4f, 0.6f);
            if (t == 86) {
                Player p = target != null && target.isValid() ? target : farthest(e, 0, 40);
                snd(at, "entity.creaking.activate", 2.4f, 0.5f);
                snd(at, "entity.ravager.roar", 1.6f, 0.6f);
                snd(at, "entity.spider.ambient", 2.0f, 0.4f);
                title(at, ab, 50);
                shake(at, 24);
                for (int i = 0; i < 16; i++) {
                    double a = i * Math.PI / 8;
                    leaves(at.clone().add(Math.cos(a) * 2.6, 0.6, Math.sin(a) * 2.6), 2, 0.3);
                }
                if (p != null) eyeGlow(b, 10);
            }
            if (t == 88) {
                snd(at, "entity.ravager.step", 1.6f, 0.5f);
                for (int i = 0; i < 2; i++) {
                    Location f = body(b, FEET[i][0], 0.05, FEET[i][1] + 0.55);
                    dust(f, 14);
                    fx.crater(f, 0.9, 40, 0.2, CRACKED);
                }
            }
            if (t == 104) for (int i : new int[]{0, 1, 6, 7}) dust(foot(b, i), 5);
            if (t == length) {
                e.setInvulnerable(false);
                b.awakened = true;
                b.waking = false;
                e.addScoreboardTag(AWAKE);
                setMode(b, Mode.PAUSE, 8);
                c.release();
            }
        });
    }

    // ---------------------------------------------------------------- Mandibules du vieux bois (bite 34)

    private void bite(MobTemplate.Ability ab, RockPowers.Caster c, Brain b, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double power = ab.number("bite-power", 9), reach = ab.number("reach", 4.1), half = ab.number("half-angle", 42);
        Player prey = nearestInFront(b, reach + 1.5, target);
        c.hold(36);
        c.animate("bite");
        timeline(e, 34, t -> {
            Location at = e.getLocation();
            // 0-12 it follows its prey with its whole body, then commits (a side step after 12 gets out of it)
            if (t <= 12 && prey != null && prey.isValid() && Mobs.fighting(prey)) face(b, prey.getLocation(), 9);
            if (t == 2) {
                snd(at, "entity.creaking.twitch", 1.4f, 0.8f);
                snd(at, "entity.spider.ambient", 1.2f, 0.6f);
            }
            if (t == 8) snd(at, "entity.spider.step", 1.4f, 0.5f);
            // the cone it is about to snap in, drawn on the ground while the chelicerae open
            if (t >= 4 && t < 16 && t % 3 == 0) {
                Fx.cone(at.clone().add(0, 0.1, 0), forward(b), half * 2, reach, t >= 10 ? RED : OLIVE, t >= 10 ? RED : OLIVE);
                mote(body(b, 0, 0.7, 1.3), BARK, 0.7f, 2, 0.15);
            }
            if (t == 15) {
                snd(at, "entity.player.attack.sweep", 1.4f, 0.6f);
                snd(at, "entity.creaking.attack", 1.4f, 0.7f);
                for (int i = 0; i < 2; i++) dust(foot(b, i), 6);
            }
            // 18 the chelicerae shut
            if (t == 18) {
                Location mouth = body(b, 0, 0.6, 2.05);
                snd(mouth, "entity.evoker_fangs.attack", 1.8f, 0.6f);
                snd(mouth, "block.wood.break", 1.4f, 0.6f);
                w.spawnParticle(Particle.BLOCK, mouth, 26, 0.35, 0.25, 0.35, 0, LOG);
                w.spawnParticle(Particle.CRIT, mouth, 8, 0.3, 0.2, 0.3, 0.1);
                for (int i = 0; i < 2; i++) dust(body(b, FEET[i][0], 0.05, FEET[i][1] + 0.55), 8);
                Vector f = forward(b);
                boolean any = false;
                for (Player p : fighters(at, reach)) {
                    if (Math.abs(side(b, p.getLocation())) > half || Math.abs(p.getLocation().getY() - at.getY()) > 2.2
                            || !e.hasLineOfSight(p)) continue;
                    c.hit(p, power);
                    p.setVelocity(f.clone().multiply(0.55).setY(0.28));
                    any = true;
                }
                if (any) {
                    snd(mouth, "entity.spider.hurt", 1.0f, 0.5f);
                    shake(at, 6);
                }
            }
            // 20-30 the chelicerae quiver, it draws back
            if (t > 20 && t < 30 && t % 4 == 0) snd(at, "block.wood.hit", 0.6f, 1.2f);
            if (t == 30) for (int i = 0; i < 2; i++) dust(foot(b, i), 4);
        });
    }

    private Player nearestInFront(Brain b, double within, Player fallback) {
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : fighters(b.e.getLocation(), within)) {
            double d = p.getLocation().distanceSquared(b.e.getLocation());
            if (Math.abs(side(b, p.getLocation())) <= 80 && d < bestD) {
                best = p;
                bestD = d;
            }
        }
        return best != null ? best : fallback;
    }

    // ---------------------------------------------------------------- Aiguilles de la forêt (volley 56)

    private void volley(MobTemplate.Ability ab, RockPowers.Caster c, Brain b) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Player prey = farthest(e, ab.number("min-range", 6), ab.number("max-range", 30));
        if (prey == null) return;
        List<BlockDisplay> thorns = new ArrayList<>();
        Deque<Vector> trail = new ArrayDeque<>();
        int[] shots = {22, 28, 34};
        c.hold(58);
        c.animate("volley");
        timeline(e, 56, t -> {
            Location at = e.getLocation();
            boolean there = prey.isValid() && Mobs.fighting(prey) && prey.getWorld() == w;
            if (t <= 34 && there) face(b, prey.getLocation(), 8);
            if (there) {
                trail.addLast(prey.getLocation().toVector());
                if (trail.size() > 6) trail.removeFirst();
            }
            Location back = back(b);
            // 0-22 the wood strains: plates part, leaves and sparks of sap turn around its back, thorns come out
            if (t == 2) {
                snd(at, "item.crossbow.loading_start", 1.6f, 0.5f);
                snd(at, "entity.creaking.sway", 1.4f, 0.6f);
            }
            if (t == 12) snd(at, "item.crossbow.loading_middle", 1.6f, 0.5f);
            if (t >= 4 && t < 22 && t % 2 == 0) {
                double a = t * 0.6;
                leaves(back.clone().add(Math.cos(a) * 0.9, 0.2, Math.sin(a) * 0.9), 1, 0.05);
                mote(back.clone().add(Math.cos(a + Math.PI) * 0.9, 0.3, Math.sin(a + Math.PI) * 0.9), MOSS_C, 0.6f, 1, 0.02);
            }
            if (t == 10) {
                for (int i = 0; i < 5; i++) thorns.add(thorn(body(b, (i - 2) * 0.22, 2.0, -0.55 - (i % 2) * 0.35), forward(b)));
                snd(back, "block.pointed_dripstone.place", 1.0f, 0.6f);
            }
            if (t >= 16 && t < 22) eyeGlow(b, 2);
            if (t == 20) snd(at, "item.crossbow.loading_end", 1.6f, 0.5f);
            // 22, 28, 34 a needle leaves: straight at its prey, then ahead of its run, then further ahead (or beside it)
            for (int i = 0; i < shots.length; i++) {
                if (t != shots[i] || !there) continue;
                if (!thorns.isEmpty()) forget(thorns.remove(thorns.size() - 1));
                Vector vel = trail.size() < 2 ? new Vector() : trail.peekLast().clone().subtract(trail.peekFirst()).multiply(1.0 / (trail.size() - 1));
                vel.setY(0);
                Location aim = prey.getLocation().add(0, 0.9, 0);
                double speed = ab.number("needle-speed", 1.1);
                double flight = back.distance(aim) / speed;
                if (i == 1) aim.add(vel.clone().multiply(flight));
                if (i == 2) {
                    if (vel.lengthSquared() > 0.0025) aim.add(vel.clone().multiply(flight * 1.7));
                    else aim.add(right(flat(at, aim)).multiply(ThreadLocalRandom.current().nextBoolean() ? 1.6 : -1.6));
                }
                snd(back, "entity.arrow.shoot", 1.6f, 0.6f);
                snd(back, "item.crossbow.shoot", 1.2f, 0.7f);
                snd(back, "block.wood.hit", 1.2f, 0.7f);
                leaves(back, 6, 0.3);
                needle(c, back, aim, prey, ab);
            }
            // 34-56 plates close, the thorns left go back in
            if (t == 40) {
                for (BlockDisplay d : thorns) forget(d);
                thorns.clear();
                snd(at, "block.wood.place", 1.0f, 0.6f);
            }
            if (t == 46) for (int i = 0; i < 2; i++) dust(foot(b, i), 5);
            if (t == 56) for (BlockDisplay d : thorns) forget(d);
        });
        // whatever happens to it, no thorn stays behind
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (BlockDisplay d : thorns) forget(d);
        }, 60L);
    }

    /** A thorn standing between two plates of its back. */
    private BlockDisplay thorn(Location at, Vector forward) {
        Location l = at.clone();
        l.setYaw(0);
        l.setPitch(0);
        Quaternionf q = new Quaternionf().rotateY((float) Math.atan2(forward.getX(), forward.getZ())).rotateX((float) Math.toRadians(-25));
        Vector3f corner = q.transform(new Vector3f(-0.04f, 0, -0.04f));
        BlockDisplay d = display(l, LOG, new Transformation(corner, q, new Vector3f(0.08f, 0.02f, 0.08f), new Quaternionf()), -1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> moveTo(d, new Transformation(corner, q, new Vector3f(0.08f, 0.55f, 0.08f), new Quaternionf()), 8), 1L);
        return d;
    }

    /**
     * A wooden needle in flight: a dark thorn with a trail of leaves, bending a little towards its prey (never a
     * pursuit), stopped by the first wall, gone on impact or after {@code needle-range} blocks. Hits the first player
     * on its way and slows him a moment.
     */
    private void needle(RockPowers.Caster c, Location from, Location aim, Player prey, MobTemplate.Ability ab) {
        World w = from.getWorld();
        double speed = ab.number("needle-speed", 1.1), range = ab.number("needle-range", 34), power = ab.number("needle-power", 5);
        double bend = Math.toRadians(ab.number("needle-turn", 1.2));
        int slow = (int) ab.number("slow-ticks", 20);
        Location start = from.clone();
        start.setYaw(0);
        start.setPitch(0);
        Vector first = aim.toVector().subtract(start.toVector());
        if (first.lengthSquared() < 1e-6) return;
        first.normalize();
        BlockDisplay d = display(start, LOG, needleShape(first), 12);
        d.setTeleportDuration(1);
        new BukkitRunnable() {
            final Vector dir = first.clone();
            Location pos = start.clone();
            double flown;

            @Override
            public void run() {
                if (!d.isValid()) {
                    cancel();
                    return;
                }
                // it leans towards its prey, a degree a tick: a late dodge still works
                if (prey.isValid() && prey.getWorld() == w && flown > 2) {
                    Vector want = prey.getLocation().add(0, 0.9, 0).toVector().subtract(pos.toVector());
                    if (want.lengthSquared() > 1e-6 && want.normalize().dot(dir) > 0.3) {
                        double angle = dir.angle(want);
                        if (angle > 1e-4) {
                            double k = Math.min(1, bend / angle);
                            dir.multiply(1 - k).add(want.multiply(k)).normalize();
                        }
                    }
                }
                RayTraceResult wall = w.rayTraceBlocks(pos, dir, speed, FluidCollisionMode.NEVER, true);
                double reach = wall == null ? speed : wall.getHitPosition().distance(pos.toVector());
                Player struck = null;
                double best = reach;
                for (Player p : fighters(pos, speed + 2.5)) {
                    RayTraceResult hit = p.getBoundingBox().expand(0.2).rayTrace(pos.toVector(), dir, reach);
                    if (hit == null) continue;
                    double at = hit.getHitPosition().distance(pos.toVector());
                    if (at <= best) {
                        best = at;
                        struck = p;
                    }
                }
                Location next = pos.clone().add(dir.clone().multiply(struck != null ? best : reach));
                leaves(next, 1, 0.04);
                mote(next, MOSS_C, 0.5f, 1, 0.02);
                if (struck != null) {
                    c.hit(struck, power);
                    if (slow > 0) struck.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, 1, false, true));
                    snd(next, "entity.arrow.hit", 1.2f, 0.6f);
                    snd(next, "block.wood.break", 1.0f, 1.2f);
                    w.spawnParticle(Particle.BLOCK, next, 12, 0.2, 0.2, 0.2, 0, LOG);
                    forget(d);
                    cancel();
                    return;
                }
                flown += reach;
                if (wall != null || flown >= range) {
                    if (wall != null) {
                        snd(next, "block.wood.hit", 1.4f, 0.8f);
                        snd(next, "entity.arrow.hit", 0.8f, 0.5f);
                        w.spawnParticle(Particle.BLOCK, next, 10, 0.15, 0.15, 0.15, 0, LOG);
                    }
                    forget(d);
                    cancel();
                    return;
                }
                pos = next;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(1);
                d.setTransformation(needleShape(dir));
                d.teleport(pos);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static Transformation needleShape(Vector dir) {
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ()));
        return new Transformation(q.transform(new Vector3f(-0.05f, -0.05f, -0.5f)), q, new Vector3f(0.1f, 0.1f, 1.0f), new Quaternionf());
    }

    // ---------------------------------------------------------------- Toile des racines (snare 60)

    private void snare(MobTemplate.Ability ab, RockPowers.Caster c, Brain b) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double radius = ab.number("web-radius", 2.4), knot = ab.number("knot-radius", 1.0), power = ab.number("snare-power", 4);
        int count = (int) ab.number("webs", 4), life = (int) ab.number("web-ticks", 140), holdTicks = (int) ab.number("root-ticks", 20);
        double reach = ab.number("reach", 18);
        String held = ab.text("held-text", "");
        List<Location> zones = new ArrayList<>();
        c.hold(62);
        c.animate("snare");
        timeline(e, 60, t -> {
            Location at = e.getLocation();
            Location back = back(b);
            // 0-26 fibres stretch: the ground strains under the zones to come
            if (t == 0) {
                snd(at, "item.crossbow.loading_start", 1.4f, 0.4f);
                snd(at, "block.hanging_roots.place", 1.6f, 0.5f);
            }
            if (t == 6) {
                zones.addAll(snareZones(b, count, reach, radius));
                snd(at, "block.roots.break", 1.4f, 0.6f);
            }
            if (t >= 6 && t < 32 && t % 2 == 0) {
                for (Location z : zones) {
                    Fx.ring(z.clone().add(0, 0.08, 0), radius, t >= 20 ? RED : OLIVE, 1.2f, t * 0.05);
                    Fx.ring(z.clone().add(0, 0.08, 0), knot, t >= 20 ? RED : OLIVE, 0.9f, -t * 0.08);
                    if (t % 4 == 0) {
                        w.spawnParticle(Particle.BLOCK, z.clone().add(0, 0.1, 0), 5, radius * 0.4, 0.02, radius * 0.4, 0, DIRT);
                        leaves(z.clone().add(0, 0.3, 0), 1, radius * 0.4);
                    }
                }
                mote(back, BARK, 0.6f, 2, 0.5);
                if (t % 6 == 0) snd(at, "block.vine.step", 1.4f, 0.5f + t * 0.02f);
            }
            if (t == 20) snd(at, "entity.creaking.sway", 1.4f, 0.8f);
            // 28 the cast: fibres fly from its abdomen to each zone
            if (t == 28) {
                snd(at, "item.crossbow.shoot", 1.6f, 0.5f);
                snd(at, "block.vine.break", 1.6f, 0.6f);
                leaves(back, 10, 0.5);
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for (Location z : zones) {
                    for (int i = 0; i < 6; i++) {
                        w.spawnParticle(Particle.TRAIL, back, 1, 0.2, 0.2, 0.2, 0,
                                new Particle.Trail(z.clone().add(r.nextDouble(-1, 1), 0.2, r.nextDouble(-1, 1)), LEAVES[3 + r.nextInt(2)], 4));
                    }
                }
            }
            // 32 the webs lie on the ground
            if (t == 32) {
                for (Location z : zones) {
                    List<BlockDisplay> strands = weave(z, radius);
                    Set<UUID> caught = new HashSet<>();
                    snd(z, "block.mangrove_roots.place", 1.6f, 0.6f);
                    snd(z, "block.cobweb.place", 1.4f, 0.5f);
                    w.spawnParticle(Particle.BLOCK, z.clone().add(0, 0.2, 0), 18, radius * 0.5, 0.1, radius * 0.5, 0, DIRT);
                    for (Player p : fighters(z, radius + 0.3)) {
                        if (Math.abs(p.getLocation().getY() - z.getY()) > 2) continue;
                        c.hit(p, power);
                        if (flatDistance(p.getLocation(), z) <= knot) {
                            hold(p, holdTicks, held);
                            caught.add(p.getUniqueId());
                        }
                    }
                    webs.add(new Web(e.getUniqueId(), z, radius, knot, Bukkit.getCurrentTick() + life, Math.max(0, holdTicks - 4), held, strands, caught));
                }
            }
            if (t == 46) for (int i = 0; i < 2; i++) dust(foot(b, i), 5);
        });
    }

    /**
     * Where the webs go: on a group of players first (two of them close together), then under each player, then on
     * the ground between the spider and them, never two on the same spot.
     */
    private List<Location> snareZones(Brain b, int count, double reach, double radius) {
        Location at = b.e.getLocation();
        List<Player> players = fighters(at, reach);
        List<Location> picks = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            for (int j = i + 1; j < players.size(); j++) {
                Location a = players.get(i).getLocation(), c2 = players.get(j).getLocation();
                if (a.distanceSquared(c2) <= 16) picks.add(a.clone().add(c2).multiply(0.5));
            }
        }
        for (Player p : players) picks.add(p.getLocation());
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Player p : players) {
            // where he would run to: beside him and on his way back from the spider
            Vector away = flat(at, p.getLocation());
            picks.add(p.getLocation().add(right(away).multiply(r.nextBoolean() ? 4.2 : -4.2)));
            picks.add(p.getLocation().add(away.clone().multiply(4.5)));
        }
        for (int i = 0; i < 8; i++) {
            double a = r.nextDouble(Math.PI * 2), d = r.nextDouble(4, Math.max(5, reach - 6));
            picks.add(at.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
        }
        List<Location> out = new ArrayList<>();
        for (Location pick : picks) {
            if (out.size() >= count) break;
            Location g = onGround(pick);
            if (Math.abs(g.getY() - at.getY()) > 2.5 || !g.clone().add(0, 0.5, 0).getBlock().isPassable()) continue;
            boolean free = true;
            for (Location o : out) if (flatDistance(o, g) < radius * 1.5) free = false;
            if (free) out.add(g);
        }
        return out;
    }

    /** A web of roots: three roots crossing in its middle, a ring of six joining their ends, knots where they meet. */
    private List<BlockDisplay> weave(Location center, double radius) {
        List<BlockDisplay> out = new ArrayList<>();
        double turn = ThreadLocalRandom.current().nextDouble(Math.PI);
        Location[] rim = new Location[6];
        for (int i = 0; i < 6; i++) {
            double a = turn + i * Math.PI / 3;
            rim[i] = center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
        }
        for (int i = 0; i < 3; i++) out.add(strand(rim[i], rim[i + 3], 0.16f, 4));
        for (int i = 0; i < 6; i++) out.add(strand(rim[i], rim[(i + 1) % 6], 0.11f, 6));
        out.add(knot(center, 0.42f, KNOT));
        for (int i = 0; i < 6; i += 2) out.add(knot(rim[i], 0.26f, KNOT));
        return out;
    }

    private void webTick() {
        if (webs.isEmpty()) return;
        int now = Bukkit.getCurrentTick();
        for (Iterator<Web> it = webs.iterator(); it.hasNext(); ) {
            Web web = it.next();
            if (now >= web.until()) {
                // it comes apart: roots draw back into the ground
                for (BlockDisplay d : web.strands()) retract(d, 12);
                web.center().getWorld().spawnParticle(Particle.BLOCK, web.center().clone().add(0, 0.2, 0), 14,
                        web.radius() * 0.5, 0.05, web.radius() * 0.5, 0, ROOT);
                snd(web.center(), "block.hanging_roots.break", 1.0f, 0.7f);
                it.remove();
                continue;
            }
            if (now % 4 != 0) continue;
            for (Player p : fighters(web.center(), web.radius() + 0.2)) {
                if (Math.abs(p.getLocation().getY() - web.center().getY()) > 1.6) continue;
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 14, 1, false, true));
                if (flatDistance(p.getLocation(), web.center()) <= web.knot() && web.held().add(p.getUniqueId())) {
                    hold(p, web.holdTicks(), web.heldText());
                    snd(web.center(), "block.mangrove_roots.place", 1.2f, 0.8f);
                }
            }
            if (now % 20 == 0) leaves(web.center().clone().add(0, 0.25, 0), 1, web.radius() * 0.4);
        }
    }

    // ---------------------------------------------------------------- Domaine des anciens bois (grove 140 + exhausted 60)

    /** A knot of the network: where it is, its display, the ray it belongs to and its rank on it. */
    private record Node(Location at, BlockDisplay knot, int ray, int rank) {
    }

    private void grove(MobTemplate.Ability ab, RockPowers.Caster c, Brain b) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Location home = c.home().clone();
        double arena = ab.number("arena-radius", 16), half = ab.number("arena-half", 17), gap = ab.number("knot-gap", 3.5),
                zone = ab.number("zone-radius", 1.7), power = ab.number("root-power", 10);
        int rays = (int) ab.number("rays", 8), slow = (int) ab.number("slow-ticks", 50), rest = (int) ab.number("exhausted-ticks", 60);
        boolean shield = ab.flag("shielded", true);
        String spent = ab.text("exhausted-text", "");
        int[] strikes = {80, 92, 104};
        int lead = 14;
        List<BlockDisplay> lines = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        List<Location> pillars = new ArrayList<>();
        List<List<Node>> waves = new ArrayList<>();
        Location[] centre = {null};
        c.hold(140 + rest + 2);
        c.animate("grove");
        if (shield) e.setInvulnerable(true);
        b.busyUntil = Bukkit.getCurrentTick() + 140 + rest;
        title(e.getLocation(), ab, 60);
        timeline(e, 140 + rest, t -> {
            Location at = e.getLocation();
            ThreadLocalRandom r = ThreadLocalRandom.current();
            // ---- 0-30 rooting: each foot stabs into the ground and takes root, the forest wakes
            if (t == 0) {
                snd(at, "block.creaking_heart.idle", 2.4f, 0.4f);
                snd(at, "entity.ravager.stunned", 1.4f, 0.4f);
                centre[0] = onGround(at);
            }
            if (t < 16 && t % 2 == 1) {
                int i = t / 2;
                Location f = onGround(foot(b, i));
                snd(f, "block.wood.step", 1.4f, 0.5f);
                snd(f, "block.mangrove_roots.place", 1.0f, 0.6f);
                dust(f, 8);
                Vector out = flat(at, f);
                root(f.clone().add(out.clone().multiply(0.35)), 0.6, 0.22, 35, out, 5, 108 - t);
            }
            if (t < 50 && t % 2 == 0) {
                double a = t * 0.3, rr = 3.4 - t * 0.02;
                for (int i = 0; i < 3; i++) {
                    Location p = at.clone().add(Math.cos(a + i * 2.1) * rr, 0.3 + (t % 20) * 0.08, Math.sin(a + i * 2.1) * rr);
                    leaves(p, 1, 0.1);
                    if (t % 4 == 0) w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, p, 1, 0.2, 0.2, 0.2, 0);
                }
                dust(at.clone().add(r.nextDouble(-2, 2), 0, r.nextDouble(-2, 2)), 2);
            }
            if (t == 10 || t == 20 || t == 30 || t == 40) {
                shake(at, 20);
                snd(at, "entity.warden.heartbeat", 2.0f, 0.5f);
            }
            if (t >= 12 && t < 50 && t % 3 == 0) eyeGlow(b, 2);
            // ---- 30-50 the crown of wood: roots rise in a ring around it, its back opens on a tangle of fibres
            if (t == 30) {
                snd(at, "entity.creaking.activate", 2.0f, 0.5f);
                snd(at, "block.mangrove_roots.break", 1.6f, 0.5f);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4 + 0.4;
                    Vector out = new Vector(Math.cos(a), 0, Math.sin(a));
                    root(onGround(at.clone().add(out.clone().multiply(2.9))), 1.5, 0.34, 28, out, 8, 80);
                }
                if (shield) later(e, 20, () -> e.setInvulnerable(false));
            }
            if (t >= 34 && t < 76 && t % 2 == 0) {
                Location back = back(b).add(0, 0.5, 0);
                mote(back, BARK, 0.7f, 2, 0.3);
                mote(back, MOSS_C, 0.6f, 1, 0.4);
            }
            // ---- 50-75 the weaving: roots run from it to the edges of the arena, knots along them, rings joining them
            if (t == 50) {
                snd(at, "item.crossbow.shoot", 2.0f, 0.4f);
                snd(at, "block.roots.break", 2.0f, 0.5f);
                snd(at, "entity.ravager.roar", 1.4f, 0.5f);
                shake(at, 24);
                leaves(back(b), 20, 0.8);
                Location o = centre[0];
                double turn = r.nextDouble(Math.PI * 2);
                Location[][] spots = new Location[rays][];
                for (int k = 0; k < rays; k++) {
                    double a = turn + k * Math.PI * 2 / rays;
                    Vector d = new Vector(Math.cos(a), 0, Math.sin(a));
                    // as far as the arena goes that way (a square clearing around its home)
                    double len = arena;
                    for (double s = gap; s <= arena; s += 0.5) {
                        Location p = o.clone().add(d.clone().multiply(s));
                        if (Math.abs(p.getX() - home.getX()) > half || Math.abs(p.getZ() - home.getZ()) > half) {
                            len = s - 0.5;
                            break;
                        }
                    }
                    lines.add(strand(o.clone().add(d.clone().multiply(1.6)), o.clone().add(d.clone().multiply(len)), 0.2f, 14));
                    int n = (int) Math.floor(len / gap);
                    spots[k] = new Location[n];
                    for (int j = 0; j < n; j++) {
                        Location p = onGround(o.clone().add(d.clone().multiply(gap * (j + 1))));
                        spots[k][j] = p;
                        int ray = k, rank = j;
                        later(e, 2 + (long) (12 * gap * (j + 1) / len), () -> {
                            nodes.add(new Node(p, knot(p, 0.4f, KNOT), ray, rank));
                            snd(p, "block.wood.place", 0.8f, 0.6f + rank * 0.1f);
                            dust(p, 4);
                        });
                    }
                }
                // rings: the second and the fourth knots of neighbouring rays joined
                for (int j : new int[]{1, 3}) {
                    for (int k = 0; k < rays; k++) {
                        Location[] a = spots[k], n = spots[(k + 1) % rays];
                        if (a.length <= j || n.length <= j) continue;
                        Location from = a[j], to = n[j];
                        later(e, 16 + j * 3L, () -> lines.add(strand(from, to, 0.13f, 8)));
                    }
                }
            }
            if (t > 50 && t < 74 && t % 3 == 0) snd(at, "block.roots.place", 1.6f, 0.5f + (t - 50) * 0.02f);
            // branches burst out of the ground here and there: obstacles of a moment, away from the players
            if (t == 62) {
                for (int i = 0; i < 24 && pillars.size() < 5; i++) {
                    double a = r.nextDouble(Math.PI * 2), d = r.nextDouble(5, arena - 2);
                    Location p = onGround(centre[0].clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
                    if (Math.abs(p.getX() - home.getX()) > half - 1 || Math.abs(p.getZ() - home.getZ()) > half - 1) continue;
                    boolean free = fighters(p, 2.5).isEmpty();
                    for (Location o : pillars) if (flatDistance(o, p) < 4) free = false;
                    if (!free) continue;
                    pillars.add(p);
                    root(p, 3.0, 0.7, r.nextDouble(-8, 8), null, 6, 50);
                    snd(p, "block.mangrove_roots.break", 1.4f, 0.6f);
                    w.spawnParticle(Particle.BLOCK, p.clone().add(0, 0.3, 0), 20, 0.5, 0.3, 0.5, 0, DIRT);
                }
            }
            if (t >= 62 && t < 118 && t % 2 == 0) {
                // nobody walks through a branch
                for (Location p : pillars) {
                    for (Player pl : fighters(p, 0.9)) pl.setVelocity(flat(p, pl.getLocation()).multiply(0.3).setY(0.05));
                }
            }
            // ---- 75-115 the predator's strike: three times, knots light up, fibres tighten, roots burst out
            if (t == 66) {
                // which knots burst on each strike: one in two, the others, then those around each player
                List<Node> first = new ArrayList<>(), second = new ArrayList<>(), third = new ArrayList<>();
                for (Node n : nodes) ((n.ray() + n.rank()) % 2 == 0 ? first : second).add(n);
                for (Player p : fighters(centre[0], arena + 4)) {
                    nodes.stream().sorted(java.util.Comparator.comparingDouble(n -> n.at().distanceSquared(p.getLocation())))
                            .limit(3).filter(n -> !third.contains(n)).forEach(third::add);
                }
                for (Node n : first) if (third.size() < nodes.size() / 2 && n.rank() % 2 == 0 && !third.contains(n)) third.add(n);
                waves.add(first);
                waves.add(second);
                waves.add(third);
            }
            for (int i = 0; i < strikes.length && i < waves.size(); i++) {
                int s = strikes[i];
                List<Node> wave = waves.get(i);
                if (t == s - lead) {
                    for (Node n : wave) if (n.knot().isValid()) {
                        n.knot().setBlock(KNOT_LIT);
                        n.knot().setBrightness(new Display.Brightness(15, 15));
                    }
                    snd(at, "block.vine.step", 1.8f, 0.5f);
                }
                if (t >= s - lead && t < s && t % 2 == 0) {
                    for (Node n : wave) {
                        Fx.ring(n.at().clone().add(0, 0.08, 0), zone, t >= s - 7 ? RED : AMBER, 1.2f, t * 0.07);
                        if (t % 4 == 0) w.spawnParticle(Particle.BLOCK, n.at().clone().add(0, 0.1, 0), 4, zone * 0.4, 0.02, zone * 0.4, 0, DIRT);
                    }
                }
                if (t == s - 5) snd(at, "entity.creaking.attack", 1.8f, 0.6f);
                if (t == s) {
                    snd(at, "entity.evoker_fangs.attack", 1.6f, 0.5f);
                    snd(at, "entity.ravager.step", 1.6f, 0.5f);
                    for (int k = 0; k < 2; k++) dust(foot(b, k), 10);
                    shake(at, 24);
                    Set<UUID> struck = new HashSet<>();
                    for (Node n : wave) {
                        for (int k = 0; k < 3; k++) {
                            double a = k * Math.PI * 2 / 3 + r.nextDouble(0.6);
                            Vector out = new Vector(Math.cos(a), 0, Math.sin(a));
                            root(n.at().clone().add(out.clone().multiply(zone * 0.45)), 2.0, 0.42, -16, out, 3, 9);
                        }
                        w.spawnParticle(Particle.BLOCK, n.at().clone().add(0, 0.4, 0), 16, zone * 0.4, 0.3, zone * 0.4, 0, DIRT);
                        leaves(n.at().clone().add(0, 0.8, 0), 4, zone * 0.4);
                        snd(n.at(), "block.mangrove_roots.break", 1.2f, 0.6f);
                        if (n.knot().isValid()) {
                            n.knot().setBlock(KNOT);
                            n.knot().setBrightness(null);
                        }
                        for (Player p : fighters(n.at(), zone + 0.2)) {
                            if (Math.abs(p.getLocation().getY() - n.at().getY()) > 2.2 || !struck.add(p.getUniqueId())) continue;
                            c.hit(p, power);
                            if (slow > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, 1, false, true));
                            p.setVelocity(flat(n.at(), p.getLocation()).multiply(0.25).setY(0.5));
                        }
                    }
                }
            }
            // ---- 115-140 it lets go: roots draw back, knots sink, its eyes go out
            if (t == 115) {
                for (BlockDisplay d : lines) retract(d, 16);
                lines.clear();
                for (Node n : nodes) {
                    BlockDisplay k = n.knot();
                    later(null, 8, () -> forget(k));
                    dust(n.at(), 3);
                }
                nodes.clear();
                snd(at, "block.hanging_roots.break", 1.8f, 0.5f);
                snd(at, "entity.creaking.sway", 1.6f, 0.5f);
            }
            if (t == 120) {
                c.parts(false, EYES);
                snd(at, "block.amethyst_block.resonate", 0.8f, 0.4f);
            }
            // ---- 140 spent: it sags on its legs, still and open to every blow
            if (t == 140) {
                e.setInvulnerable(false);
                c.animate("exhausted");
                snd(at, "entity.ravager.stunned", 1.6f, 0.5f);
                snd(at, "block.wood.fall", 1.4f, 0.5f);
                for (int i = 0; i < 8; i++) dust(foot(b, i), 5);
                for (Player p : fighters(at, 40)) bar(p, spent);
            }
            if (t > 140 && t < 140 + rest - 20 && t % 28 == 0) {
                snd(at, "entity.creaking.freeze", 1.2f, 0.5f);
                leaves(back(b), 4, 0.6);
                w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, at.clone().add(0, 1.4, 0), 6, 1.0, 0.4, 1.0, 0);
            }
            if (t == 140 + rest - 4) {
                c.parts(true, EYES);
                snd(at, "entity.creaking.activate", 1.4f, 0.7f);
                eyeGlow(b, 8);
            }
            if (t == 140 + rest) {
                setMode(b, Mode.PAUSE, 4);
                c.release();
            }
        });
        // whatever happens to it, the network never outlives the ultimate
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (BlockDisplay d : lines) forget(d);
            for (Node n : nodes) forget(n.knot());
            if (e.isValid() && shield && !e.isDead() && brains.containsKey(e.getUniqueId())) e.setInvulnerable(false);
        }, 150L);
    }
}
