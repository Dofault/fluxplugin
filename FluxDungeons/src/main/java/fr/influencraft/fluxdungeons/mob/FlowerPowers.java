package fr.influencraft.fluxdungeons.mob;

import fr.influencraft.flux.api.text.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Abilities of the Devouring Flower, a carnivorous plant rooted in its bulb: it never walks, but the whole plant
 * turns on the spot to face its prey ({@value #TURN} degrees a tick at most; its head leads, bone {@code h_Tete}),
 * except while a strike is locked. Asleep and closed until its entrance. Every timing follows its model's animations tick for tick (docs/technique/boss/fleur-devoreuse.md,
 * assets/monstres/fleur_devoreuse_anim.py):
 * <ul>
 *   <li>{@code fleur_awaken} (awaken 100, opening): it stirs (24), opens (44-72), roars (76); cannot be hurt until 96;</li>
 *   <li>{@code fleur_bite} (bite 46): the head leans back, it stops turning at 14, the jaws slam at 23 on a cone in
 *   front of it: step aside or back off in time;</li>
 *   <li>{@code fleur_thorns} (thorns 60): against whoever keeps away: three thorns at 26, 34 and 42, the first at
 *   where the player stands, the second ahead of his run, the third aimed again; they bend a little towards him for
 *   half a second, then fly straight; a block stops them;</li>
 *   <li>{@code fleur_roots} (roots 70): its roots strike the ground at 30; two waves of marked zones (under the
 *   players and around them) break out at 42 and 52: hit and slowed hard for a moment;</li>
 *   <li>{@code fleur_bloom} (bloom 130, ultimate): it charges (0-28), turns monstrous (30-46) and bursts at 52: a cloud
 *   of spores runs out to {@code radius} (52-66), hurting and poisoning whoever it reaches; marked spore pods pop at
 *   76; then it is spent until 108 (glowing, unable to act): the players' reward.</li>
 * </ul>
 * It picks its move from what the players do: the bite for whoever comes close, thorns for whoever stays away,
 * roots for players who stand still or bunch up (and now and then all the same). Nothing ever changes in the map: seeds, thorns
 * and roots are displays, removed with the fight.
 */
final class FlowerPowers {

    static final Set<String> TYPES = Set.of("fleur_awaken", "fleur_bite", "fleur_thorns", "fleur_roots", "fleur_bloom");
    static final String MODEL = "fleur_devoreuse";
    /** How far its head turns ahead of its body to follow a player, each side. */
    static final float HEAD_RANGE = 40f;
    /** Degrees a tick the plant turns towards its prey at most. */
    static final float TURN = 7f;
    private static final String TAG = "fluxdungeons_fx";
    /** Scoreboard tag of a flower whose entrance is over. */
    private static final String AWAKE = "fluxdungeons_flower_awake";
    private static final Color POLLEN = Color.fromRGB(0xE8D57A), SPORE = Color.fromRGB(0x8FA283), MOSS = Color.fromRGB(0x5F7345),
            PINK = Color.fromRGB(0xF0B79A), RED = Color.fromRGB(0xFF5C5C), OLIVE = Color.fromRGB(0x9A9A62);
    private static final int EXPOSED = 0xE8D57A;
    private static final BlockData ROOT = Material.MANGROVE_ROOTS.createBlockData(), DIRT = Material.ROOTED_DIRT.createBlockData(),
            LEAF = Material.AZALEA_LEAVES.createBlockData(), POD = Material.SPORE_BLOSSOM.createBlockData();

    /** A flower and what it is doing. */
    private static final class Brain {
        final LivingEntity e;
        Location home;
        /** The way it faces now, and the player it turns to (the nearest one when null). */
        float yaw;
        Player focus;
        boolean awakened, waking, sleeping, locked;
        long busyUntil;

        Brain(LivingEntity e) {
            this.e = e;
            // a flower found again (its chunk came back) has already made its entrance
            this.awakened = e.getScoreboardTags().contains(AWAKE);
        }
    }

    private final Plugin plugin;
    private final Mobs mobs;
    private final NamespacedKey homeKey;
    private final Map<UUID, Brain> brains = new HashMap<>();
    private final Set<Entity> alive = new HashSet<>();
    /** Where each fighter stood one, two and three seconds ago (who stands still). */
    private final Map<UUID, Vector[]> trail = new HashMap<>();
    private int ticks;

    FlowerPowers(Plugin plugin, Mobs mobs) {
        this.plugin = plugin;
        this.mobs = mobs;
        this.homeKey = new NamespacedKey(plugin, "flower_home");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    boolean cast(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        if (!TYPES.contains(ab.type())) return false;
        Brain b = brain(c.entity());
        switch (ab.type()) {
            case "fleur_awaken" -> awaken(ab, c, b);
            case "fleur_bite" -> bite(ab, c, b, target);
            case "fleur_thorns" -> thorns(ab, c, b);
            case "fleur_roots" -> roots(ab, c, b);
            case "fleur_bloom" -> bloom(ab, c, b);
            default -> {
            }
        }
        return true;
    }

    /**
     * Whether casting now makes sense: nothing before its entrance nor during another move. The bite wants someone
     * within reach; the thorns someone keeping away in sight; the roots someone standing still or players bunched up
     * (one time in three otherwise); the bloom someone around it.
     */
    boolean worth(MobTemplate.Ability ab, LivingEntity e) {
        if (!TYPES.contains(ab.type())) return true;
        Brain b = brain(e);
        if (!b.awakened) return !b.waking && "fleur_awaken".equals(ab.type());
        if (System.currentTimeMillis() < b.busyUntil) return false;
        return switch (ab.type()) {
            case "fleur_bite" -> biteTarget(b, ab) != null;
            case "fleur_thorns" -> farthest(b, ab.number("min-range", 7), ab.number("max-range", 28)) != null;
            case "fleur_roots" -> rootsWanted(b, ab);
            case "fleur_bloom" -> !fighters(b.home, ab.number("radius", 9) + 6).isEmpty();
            default -> false;
        };
    }

    void clear() {
        for (Entity d : alive) if (d.isValid()) d.remove();
        alive.clear();
        brains.clear();
        trail.clear();
    }

    /** A flower just appeared (or came back with its chunk): asleep and closed until its entrance. */
    void spawned(LivingEntity e) {
        Brain b = brain(e);
        // once its model is on: its head range, and its sleep
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!e.isValid()) return;
            mobs.headRange(e, HEAD_RANGE);
            if (!b.awakened) sleep(b);
        }, 2L);
    }

    private Brain brain(LivingEntity e) {
        return brains.computeIfAbsent(e.getUniqueId(), k -> {
            Brain b = new Brain(e);
            // its place, kept on the entity: it must never drift
            String saved = e.getPersistentDataContainer().get(homeKey, PersistentDataType.STRING);
            if (saved != null) {
                String[] v = saved.split(";");
                b.home = new Location(e.getWorld(), Double.parseDouble(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2]),
                        Float.parseFloat(v[3]), 0);
            } else {
                b.home = e.getLocation().clone();
                b.home.setPitch(0);
                e.getPersistentDataContainer().set(homeKey, PersistentDataType.STRING,
                        b.home.getX() + ";" + b.home.getY() + ";" + b.home.getZ() + ";" + b.home.getYaw());
            }
            b.yaw = b.home.getYaw();
            return b;
        });
    }

    private void sleep(Brain b) {
        b.sleeping = true;
        b.e.setInvulnerable(true);
        mobs.loop(b.e, "sleep", true);
    }

    // ---------------------------------------------------------------- every 2 ticks

    private void tick() {
        ticks += 2;
        if (brains.isEmpty()) return;
        for (Iterator<Brain> it = brains.values().iterator(); it.hasNext(); ) {
            Brain b = it.next();
            LivingEntity e = b.e;
            if (!e.isValid() || e.isDead()) {
                it.remove();
                continue;
            }
            // rooted: whatever pushed it, it is back in its bulb
            if (ticks % 10 == 0 && (e.getWorld() != b.home.getWorld() || e.getLocation().distanceSquared(b.home) > 0.04)) {
                Location back = b.home.clone();
                back.setYaw(b.yaw);
                e.teleport(back);
            }
            if (b.sleeping) continue;
            if (!b.locked) turn(b);
            if (!b.awakened) continue;
            // it drools when a prey is near
            if (ticks % 36 == 0 && System.currentTimeMillis() >= b.busyUntil && !fighters(b.home, 9).isEmpty()) {
                Location mouth = mouth(b, front(b), 1.5, 2.0);
                e.getWorld().spawnParticle(Particle.DRIPPING_HONEY, mouth, 1, 0.25, 0.05, 0.25, 0);
            }
        }
        if (ticks % 20 == 0) {
            // where everybody stood: three samples, a second apart
            Set<UUID> seen = new HashSet<>();
            for (Brain b : brains.values()) {
                for (Player p : fighters(b.home, 40)) {
                    if (!seen.add(p.getUniqueId())) continue;
                    Vector[] t = trail.computeIfAbsent(p.getUniqueId(), k -> new Vector[3]);
                    t[2] = t[1];
                    t[1] = t[0];
                    t[0] = p.getLocation().toVector();
                }
            }
            trail.keySet().retainAll(seen);
        }
    }

    /** Turns the whole plant a step towards the player it follows (its prey of the moment, else the nearest one). */
    private void turn(Brain b) {
        Player to = b.focus != null && b.focus.isValid() && b.focus.getWorld() == b.home.getWorld() && Mobs.fighting(b.focus) ? b.focus : null;
        if (to == null) {
            double best = 30 * 30;
            for (Player p : fighters(b.home, 30)) {
                double d = p.getLocation().distanceSquared(b.home);
                if (d < best) {
                    best = d;
                    to = p;
                }
            }
        }
        if (to == null) return;
        Vector v = to.getLocation().toVector().subtract(b.home.toVector()).setY(0);
        if (v.lengthSquared() < 0.25) return;
        double d = (Math.toDegrees(Math.atan2(-v.getX(), v.getZ())) - b.yaw) % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        if (Math.abs(d) < 0.5) return;
        // the task runs every 2 ticks
        b.yaw += (float) Math.max(-2 * TURN, Math.min(2 * TURN, d));
        b.e.setRotation(b.yaw, 0);
    }

    /** Whether the player has hardly moved for three seconds. */
    private boolean still(Player p) {
        Vector[] t = trail.get(p.getUniqueId());
        return t != null && t[2] != null && t[2].distanceSquared(p.getLocation().toVector()) < 2.25;
    }

    // ---------------------------------------------------------------- who to hit

    /** The player its bite goes for: the nearest one within reach. */
    private Player biteTarget(Brain b, MobTemplate.Ability ab) {
        double reach = ab.number("reach", 5.2);
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : fighters(b.home, reach)) {
            double d = flatDistance(p.getLocation(), b.home);
            if (d < bestD && Math.abs(p.getLocation().getY() - b.home.getY()) < 3) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    /** The farthest player in sight between two distances (archers, casters keeping away). */
    private Player farthest(Brain b, double min, double max) {
        Player best = null;
        double bestD = -1;
        for (Player p : fighters(b.home, max)) {
            double d = p.getLocation().distance(b.home);
            if (d >= min && d > bestD && b.e.hasLineOfSight(p)) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    private boolean rootsWanted(Brain b, MobTemplate.Ability ab) {
        List<Player> around = fighters(b.home, ab.number("reach", 16));
        if (around.isEmpty()) return false;
        for (int i = 0; i < around.size(); i++) {
            Player p = around.get(i);
            if (still(p)) return true;
            for (int j = i + 1; j < around.size(); j++) {
                if (around.get(j).getLocation().distanceSquared(p.getLocation()) < 12.25) return true;
            }
        }
        // nobody stands still: now and then all the same, so that the ground is never safe
        return ThreadLocalRandom.current().nextInt(3) == 0;
    }

    // ---------------------------------------------------------------- entrance (awaken 100)

    private void awaken(MobTemplate.Ability ab, RockPowers.Caster c, Brain b) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        b.waking = true;
        b.sleeping = false;
        begin(c, b, 100, true);
        e.setInvulnerable(true);
        mobs.loop(e, "sleep", false);
        c.animate("awaken");
        timeline(e, 100, t -> {
            Location at = b.home;
            Vector f = front(b);
            // 0-24 asleep: a few spores, wood creaking very softly
            if (t == 0) snd(at, "block.mangrove_roots.step", 0.7f, 0.5f);
            if (t < 44 && t % 6 == 0) w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, at.clone().add(0, 2.2, 0), 2, 1.2, 0.8, 1.2, 0);
            // 24-44 first signs: a root tightens, the leaves tremble
            if (t == 24) {
                snd(at, "block.roots.break", 1.0f, 0.5f);
                w.spawnParticle(Particle.BLOCK, at.clone().add(f.clone().multiply(1.4)).add(0, 0.2, 0), 14, 0.5, 0.1, 0.5, 0, DIRT);
            }
            if (t >= 28 && t <= 44 && t % 4 == 0) snd(at, "block.azalea_leaves.hit", 0.8f, 0.7f + (t - 28) * 0.02f);
            // 44-76 the opening: deep creaking, the tongue slides out (60), a jolt at full gape (72)
            if (t == 44) {
                snd(at, "block.wood.break", 1.6f, 0.5f);
                snd(at, "entity.creaking.activate", 1.4f, 0.6f);
                title(at, ab, 70);
            }
            if (t == 60) snd(at, "block.honey_block.slide", 1.4f, 0.6f);
            if (t == 72) {
                snd(at, "block.mangrove_roots.break", 1.6f, 0.6f);
                shake(at, 16);
            }
            // 76-92 the roar: pollen and spores out of its mouth
            if (t == 76) {
                snd(at, "entity.ravager.roar", 2.2f, 0.55f);
                snd(at, "block.azalea_leaves.break", 1.6f, 0.5f);
                shake(at, 24);
            }
            if (t >= 76 && t <= 90 && t % 2 == 0) {
                Location mouth = mouth(b, f, 1.6, 3.0);
                breath(mouth, f, 14, 0.5);
            }
            if (t == 96) e.setInvulnerable(false);
            if (t == 100) {
                b.awakened = true;
                b.waking = false;
                e.addScoreboardTag(AWAKE);
                end(c, b);
            }
        });
    }

    // ---------------------------------------------------------------- Morsure du prédateur (bite 46)

    private void bite(MobTemplate.Ability ab, RockPowers.Caster c, Brain b, Player wanted) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double reach = ab.number("reach", 5.2), angle = ab.number("angle", 80), power = ab.number("bite-power", 8);
        Player near = biteTarget(b, ab);
        Player target = near != null ? near : wanted;
        b.focus = target;
        Vector[] aim = {front(b)};
        begin(c, b, 46, true);
        c.animate("bite");
        snd(b.home, "block.honey_block.break", 1.4f, 0.6f);
        snd(b.home, "block.mangrove_roots.step", 1.4f, 0.5f);
        timeline(e, 46, t -> {
            Location at = b.home;
            // 0-14 the head leans back and the plant still turns to its prey; the cone on the ground shows where it will snap
            if (t < 14) aim[0] = front(b);
            if (t == 14) {
                // locked: it stops turning, from now on stepping aside or back gets out of it
                lock(b);
                snd(at, "block.big_dripleaf.tilt_down", 1.6f, 0.5f);
            }
            if (t >= 4 && t < 23 && t % 2 == 0) Fx.cone(at.clone().add(0, 0.05, 0), aim[0], angle, reach, t >= 14 ? RED : OLIVE, t >= 14 ? RED : POLLEN);
            if (t == 18) snd(at, "entity.ravager.attack", 1.6f, 0.7f);
            // 23 the jaws slam shut
            if (t == 23) {
                Location snap = at.clone().add(aim[0].clone().multiply(reach * 0.72)).add(0, 1.4, 0);
                snd(snap, "entity.evoker_fangs.attack", 2.0f, 0.7f);
                snd(snap, "block.bamboo.break", 1.8f, 0.6f);
                snd(snap, "block.mangrove_roots.break", 1.4f, 0.8f);
                dust(snap, POLLEN, 1.2f, 18, 0.7);
                w.spawnParticle(Particle.BLOCK, snap, 14, 0.6, 0.4, 0.6, 0, LEAF);
                shake(at, 8);
                for (Player p : fighters(at, reach + 0.3)) {
                    Vector to = flat(at, p.getLocation());
                    if (Math.abs(p.getLocation().getY() - at.getY()) > 3) continue;
                    if (Math.toDegrees(to.angle(aim[0])) > angle / 2) continue;
                    c.hit(p, power);
                    p.setVelocity(to.multiply(0.5).setY(0.3));
                }
            }
            if (t == 30) snd(at, "block.honey_block.slide", 1.0f, 0.7f);
            if (t == 46) end(c, b);
        });
    }

    // ---------------------------------------------------------------- Épines traqueuses (thorns 60)

    private void thorns(MobTemplate.Ability ab, RockPowers.Caster c, Brain b) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Player target = farthest(b, ab.number("min-range", 7), ab.number("max-range", 28));
        if (target == null) return;
        ItemDisplay[] seed = {null};
        Vector[] before = {target.getLocation().toVector()};
        b.focus = target;
        begin(c, b, 60, true);
        c.animate("thorns");
        snd(b.home, "entity.cat.hiss", 1.6f, 0.5f);
        String warning = ab.text("warning", "");
        if (!warning.isBlank()) target.sendActionBar(Texts.parse(warning));
        timeline(e, 60, t -> {
            if (!target.isValid() || target.getWorld() != w) return;
            Vector f = front(b);
            Location mouth = mouth(b, f, 1.3, 2.9);
            // 0-24 the charge: green motes turning round its head, a seed glowing in its throat, the prey marked
            if (t <= 24 && t % 2 == 0) {
                double a = t * 0.45;
                for (int i = 0; i < 3; i++) {
                    double ang = a + i * Math.PI * 2 / 3;
                    dust(mouth.clone().add(Math.cos(ang) * 1.5, 0.3 * Math.sin(a + i), Math.sin(ang) * 1.5), SPORE, 1.0f, 1, 0);
                }
                Fx.ring(target.getLocation().add(0, 0.1, 0), 0.9, t >= 16 ? RED : OLIVE, 0.9f, t * 0.2);
            }
            if (t == 8) {
                seed[0] = seedDisplay(mouth);
                snd(mouth, "block.chorus_flower.grow", 1.0f, 1.4f);
            }
            if (seed[0] != null && seed[0].isValid() && t >= 8 && t < 44) spin(seed[0], mouth, t * 26, 0.25f + 0.02f * Math.min(12, t - 8));
            if (t == 24) before[0] = target.getLocation().toVector();
            // 26, 34, 42: where he stands, ahead of his run, aimed again
            if (t == 26 || t == 34 || t == 42) {
                double speed = ab.number("thorn-speed", 0.8);
                Vector now = target.getLocation().toVector();
                Vector to = now.clone().add(new Vector(0, 0.9, 0));
                if (t == 34) {
                    // his run over the last ten ticks, carried over the flight time
                    Vector run = now.clone().subtract(before[0]).multiply(1 / 10.0).setY(0);
                    to.add(run.multiply(Math.min(30, mouth.toVector().distance(to) / speed)));
                }
                before[0] = now;
                snd(mouth, "entity.llama.spit", 1.6f, 0.7f);
                snd(mouth, "entity.arrow.shoot", 1.2f, 0.6f);
                w.spawnParticle(Particle.BLOCK, mouth, 8, 0.2, 0.2, 0.2, 0, LEAF);
                shoot(c, ab, mouth, to, target, speed);
            }
            if (t == 44 && seed[0] != null) {
                forget(seed[0]);
                seed[0] = null;
            }
            if (t == 54) snd(b.home, "block.bamboo.hit", 1.4f, 0.6f);
        });
        // the end is on its own clock: the timeline above stops early if its prey leaves
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (seed[0] != null) forget(seed[0]);
            if (e.isValid()) end(c, b);
        }, 60L);
    }

    private ItemDisplay seedDisplay(Location at) {
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, it -> {
            it.setItemStack(new ItemStack(Material.MANGROVE_PROPAGULE));
            it.setPersistent(false);
            it.setBrightness(new Display.Brightness(13, 15));
            it.setTeleportDuration(1);
            it.addScoreboardTag(TAG);
        });
        alive.add(d);
        return d;
    }

    private static void spin(ItemDisplay d, Location at, float degrees, float size) {
        Quaternionf q = new Quaternionf().rotateY((float) Math.toRadians(degrees)).rotateX(0.5f);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(1);
        d.setTransformation(new Transformation(new Vector3f(), q, new Vector3f(size, size, size), new Quaternionf()));
        d.teleport(at);
    }

    /**
     * A thorn: flies at {@code speed} blocks a tick towards {@code to}, bends a little towards its prey for its first
     * ten ticks ({@code thorn-turn} degrees a tick at most), then straight; stopped by a block; hits the first player
     * on its way: small hit, brief slowness.
     */
    private void shoot(RockPowers.Caster c, MobTemplate.Ability ab, Location from, Vector to, Player prey, double speed) {
        World w = from.getWorld();
        double power = ab.number("thorn-power", 3.5), turn = Math.toRadians(ab.number("thorn-turn", 2.5));
        int slow = (int) ab.number("slow-ticks", 20), life = (int) ab.number("thorn-life", 50);
        ItemDisplay thorn = seedDisplay(from);
        Vector start = to.clone().subtract(from.toVector());
        if (start.lengthSquared() < 1e-6) start = new Vector(0, 0, 1);
        Vector[] dir = {start.normalize()};
        new BukkitRunnable() {
            int t;
            Location at = from.clone();

            @Override
            public void run() {
                if (!thorn.isValid() || t++ > life) {
                    forget(thorn);
                    cancel();
                    return;
                }
                if (t <= 10 && prey.isValid() && prey.getWorld() == w) {
                    Vector want = prey.getLocation().toVector().add(new Vector(0, 0.9, 0)).subtract(at.toVector());
                    if (want.lengthSquared() > 1e-6) {
                        want.normalize();
                        double angle = dir[0].angle(want);
                        if (angle > 1e-4) {
                            double k = Math.min(1, turn / angle);
                            dir[0] = dir[0].clone().multiply(1 - k).add(want.multiply(k)).normalize();
                        }
                    }
                }
                Location next = at.clone().add(dir[0].clone().multiply(speed));
                spin(thorn, next, t * 50, 0.55f);
                dust(next, MOSS, 0.8f, 1, 0.04);
                if (t % 2 == 0) w.spawnParticle(Particle.BLOCK, next, 1, 0.05, 0.05, 0.05, 0, LEAF);
                Player met = null;
                for (Player p : fighters(next, 2.5)) {
                    if (distToSegment(p.getLocation().toVector().add(new Vector(0, 0.9, 0)), at.toVector(), next.toVector()) < 0.85) met = p;
                }
                boolean wall = !next.getBlock().isPassable();
                if (met != null || wall) {
                    snd(next, "block.bamboo.break", 1.4f, 1.3f);
                    snd(next, "block.azalea_leaves.break", 1.2f, 1.2f);
                    w.spawnParticle(Particle.BLOCK, next, 12, 0.25, 0.25, 0.25, 0, LEAF);
                    if (met != null) {
                        c.hit(met, power);
                        met.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, 0, false, true));
                    }
                    forget(thorn);
                    cancel();
                    return;
                }
                at = next;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ---------------------------------------------------------------- Racines dévoreuses (roots 70)

    private void roots(MobTemplate.Ability ab, RockPowers.Caster c, Brain b) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double radius = ab.number("zone-radius", 1.8), reach = ab.number("reach", 16), power = ab.number("root-power", 6);
        int slow = (int) ab.number("slow-ticks", 30), extra = (int) ab.number("extra-zones", 3);
        List<Location> first = new ArrayList<>(), second = new ArrayList<>();
        begin(c, b, 70, false);
        b.locked = true;
        c.animate("roots");
        snd(b.home, "block.rooted_dirt.break", 1.6f, 0.5f);
        snd(b.home, "block.hanging_roots.step", 1.4f, 0.5f);
        timeline(e, 70, t -> {
            Location at = b.home;
            // 0-30 the ground wakes up round its bulb: dust, earth, small leaves
            if (t < 30 && t % 3 == 0) {
                w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.15, 0), 6, 1.6, 0.05, 1.6, 0, DIRT);
                w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.4, 0), 2, 1.4, 0.2, 1.4, 0, LEAF);
            }
            if (t == 12) snd(at, "block.roots.break", 1.4f, 0.5f);
            // two waves of zones: under each player and a few around them, marked 24 ticks before they break out
            if (t == 18) zones(b, first, reach, extra);
            if (t == 28) zones(b, second, reach, extra - 1);
            if (t == 30) {
                snd(at, "entity.ravager.step", 1.8f, 0.5f);
                snd(at, "block.mangrove_roots.break", 1.8f, 0.5f);
                w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.2, 0), 40, 2.0, 0.1, 2.0, 0, DIRT);
                shake(at, 14);
            }
            if (t % 2 == 0) {
                if (t >= 18 && t < 42) mark(first, radius, t - 18);
                if (t >= 28 && t < 52) mark(second, radius, t - 28);
            }
            if (t == 42) erupt(c, first, radius, power, slow, false);
            if (t == 52) erupt(c, second, radius, power, slow, true);
            if (t == 60) snd(at, "block.hanging_roots.break", 1.2f, 0.6f);
            if (t == 70) end(c, b);
        });
    }

    /** Zones of a wave: under each player around it, and {@code extra} more close to them. */
    private void zones(Brain b, List<Location> out, double reach, int extra) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Player> players = fighters(b.home, reach);
        for (Player p : players) {
            Location z = onGround(p.getLocation());
            if (z != null) out.add(z);
        }
        for (int i = 0; i < extra && !players.isEmpty(); i++) {
            Location near = players.get(r.nextInt(players.size())).getLocation();
            double a = r.nextDouble(Math.PI * 2), d = r.nextDouble(2.5, 5);
            Location z = onGround(near.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
            if (z != null && Math.abs(z.getY() - near.getY()) < 2 && z.distanceSquared(b.home) > 6) out.add(z);
        }
        for (Location z : out) snd(z, "block.rooted_dirt.hit", 1.0f, 0.6f);
    }

    /** A zone about to break out: a ring that turns red, earth shaking, root tips. */
    private void mark(List<Location> zones, double radius, int since) {
        for (Location z : zones) {
            Fx.ring(z.clone().add(0, 0.08, 0), radius, since >= 14 ? RED : OLIVE, 1.2f, since * 0.08);
            if (since % 4 == 0) z.getWorld().spawnParticle(Particle.BLOCK, z.clone().add(0, 0.1, 0), 5, radius * 0.4, 0.02, radius * 0.4, 0, DIRT);
            if (since == 8) for (int i = 0; i < 3; i++) {
                double a = i * Math.PI * 2 / 3;
                root(z.clone().add(Math.cos(a) * radius * 0.6, 0, Math.sin(a) * radius * 0.6), 0.3, 0.2, 8, null, 4, 12);
            }
        }
    }

    /** Roots break out of a zone: straight up, or hooks closing on its middle; whoever is inside is hit and slowed. */
    private void erupt(RockPowers.Caster c, List<Location> zones, double radius, double power, int slow, boolean hooks) {
        for (Location z : zones) {
            World w = z.getWorld();
            for (int i = 0; i < 6; i++) {
                double a = i * Math.PI / 3 + (hooks ? 0.5 : 0);
                Vector out = new Vector(Math.cos(a), 0, Math.sin(a));
                root(z.clone().add(out.clone().multiply(radius * 0.8)), hooks ? 2.2 : 1.8, 0.4, hooks ? -38 : -8, out, 3, 14);
            }
            w.spawnParticle(Particle.BLOCK, z.clone().add(0, 0.3, 0), 24, radius * 0.5, 0.3, radius * 0.5, 0, DIRT);
            w.spawnParticle(Particle.BLOCK, z.clone().add(0, 0.8, 0), 8, radius * 0.5, 0.4, radius * 0.5, 0, LEAF);
            snd(z, "block.mangrove_roots.place", 1.6f, 0.5f);
            snd(z, "entity.creaking.attack", 1.0f, 0.7f);
            for (Player p : fighters(z, radius + 0.3)) {
                if (Math.abs(p.getLocation().getY() - z.getY()) > 2) continue;
                c.hit(p, power);
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, 3, false, true));
            }
        }
    }

    /**
     * A root out of the ground at {@code base}: two pieces {@code height} tall together, leaning {@code lean} degrees
     * towards {@code dir} (any way when null; negative = towards the middle of its zone), up in {@code rise} ticks,
     * back under after {@code life}.
     */
    private void root(Location base, double height, double width, double lean, Vector dir, int rise, int life) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = base.clone();
        at.setYaw(0);
        at.setPitch(0);
        float yaw = dir == null ? (float) r.nextDouble(0, Math.PI * 2) : (float) Math.atan2(dir.getX(), dir.getZ());
        float[][] pieces = {{(float) width, (float) (height * 0.6), 0}, {(float) (width * 0.6), (float) (height * 0.55), 16}};
        Vector3f p = new Vector3f();
        for (float[] pc : pieces) {
            Quaternionf q = new Quaternionf().rotateY(yaw).rotateX((float) Math.toRadians(lean + Math.signum(lean) * pc[2]));
            Vector3f corner = q.transform(new Vector3f(-pc[0] / 2, 0, -pc[0] / 2)).add(p);
            Transformation up = new Transformation(corner, q, new Vector3f(pc[0], pc[1], pc[0]), new Quaternionf());
            Transformation down = new Transformation(new Vector3f(corner).sub(0, (float) height + 0.4f, 0), q,
                    new Vector3f(pc[0], pc[1], pc[0]), new Quaternionf());
            BlockDisplay d = at.getWorld().spawn(at, BlockDisplay.class, bd -> {
                bd.setBlock(ROOT);
                bd.setPersistent(false);
                bd.setTransformation(down);
                bd.addScoreboardTag(TAG);
            });
            alive.add(d);
            Bukkit.getScheduler().runTaskLater(plugin, () -> moveTo(d, up, rise), 1L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> moveTo(d, down, 10), rise + (long) life);
            Bukkit.getScheduler().runTaskLater(plugin, () -> forget(d), rise + life + 11L);
            p.add(q.transform(new Vector3f(0, pc[1] * 0.85f, 0)));
        }
    }

    // ---------------------------------------------------------------- Floraison carnivore (bloom 130, ultimate)

    private void bloom(MobTemplate.Ability ab, RockPowers.Caster c, Brain b) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double radius = ab.number("radius", 9), power = ab.number("bloom-power", 8), popPower = ab.number("pop-power", 4),
                popRadius = ab.number("pop-radius", 2.2);
        int poison = (int) ab.number("poison-ticks", 80), pods = (int) ab.number("pods", 4);
        Set<UUID> caught = new HashSet<>();
        List<Location> spots = new ArrayList<>();
        List<BlockDisplay> podLooks = new ArrayList<>();
        begin(c, b, 130, false);
        b.locked = true;
        c.animate("bloom");
        title(b.home, ab, 60);
        snd(b.home, "entity.warden.heartbeat", 2.0f, 0.6f);
        timeline(e, 130, t -> {
            Location at = b.home;
            Vector f = front(b);
            Location mouth = mouth(b, f, 1.4, 2.6);
            // 0-28 the charge: pollen drawn towards it, a heavy pulse, the plant trembling
            if (t < 30 && t % 10 == 0) snd(at, "entity.warden.heartbeat", 2.0f, 0.6f + t * 0.012f);
            if (t < 44 && t % 2 == 0) {
                double rr = radius * (1 - (t % 16) / 16.0) + 0.6;
                for (int i = 0; i < 6; i++) {
                    double a = i * Math.PI / 3 + t * 0.21;
                    dust(at.clone().add(Math.cos(a) * rr, 0.4 + 1.6 * (1 - rr / radius), Math.sin(a) * rr), i % 2 == 0 ? POLLEN : SPORE, 0.9f, 1, 0.05);
                }
            }
            // the danger zone on the ground from the first tick: pale, then red before the burst
            if (t < 52 && t % 2 == 0) Fx.ring(at.clone().add(0, 0.05, 0), radius, t >= 38 ? RED : POLLEN, 1.3f, t * 0.05);
            // 30-46 the transformation; the pods that will pop are marked from 30 on
            if (t == 30) {
                snd(at, "entity.ravager.roar", 1.8f, 0.5f);
                snd(at, "block.mangrove_roots.break", 1.8f, 0.5f);
                ThreadLocalRandom r = ThreadLocalRandom.current();
                for (int i = 0; i < pods; i++) {
                    double a = r.nextDouble(Math.PI * 2), d = radius + r.nextDouble(1.5, 6);
                    Location spot = onGround(at.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d));
                    if (spot == null || Math.abs(spot.getY() - at.getY()) > 2) continue;
                    spots.add(spot);
                    podLooks.add(pod(spot));
                }
            }
            if (t >= 30 && t < 76 && t % 2 == 0) for (Location s : spots) Fx.ring(s.clone().add(0, 0.08, 0), popRadius, t >= 62 ? RED : OLIVE, 1.1f, t * 0.1);
            if (t == 42) snd(mouth, "entity.slime.squish", 1.6f, 0.5f);
            // 52 the burst: the cloud runs out to the ring in 14 ticks and takes whoever it reaches
            if (t == 52) {
                snd(at, "entity.warden.sonic_boom", 1.8f, 0.7f);
                snd(at, "entity.ravager.roar", 2.2f, 0.75f);
                snd(at, "block.spore_blossom.break", 2.0f, 0.6f);
                shake(at, radius + 12);
                for (Color col : new Color[]{POLLEN, SPORE, PINK}) dust(mouth, col, 1.6f, 30, 1.2);
                w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, at.clone().add(0, 2, 0), 60, 2.5, 1.5, 2.5, 0);
            }
            if (t >= 52 && t <= 66) {
                double rr = 1.5 + (radius - 1.5) * (t - 52) / 14.0;
                Fx.ring(at.clone().add(0, 0.3, 0), rr, POLLEN, 1.5f, t * 0.3);
                Fx.ring(at.clone().add(0, 1.1, 0), rr, t % 2 == 0 ? SPORE : PINK, 1.3f, t * 0.5);
                Fx.ring(at.clone().add(0, 1.9, 0), rr * 0.92, SPORE, 1.1f, t * 0.7);
                for (Player p : fighters(at, rr + 0.4)) {
                    if (Math.abs(p.getLocation().getY() - at.getY()) > 4 || !caught.add(p.getUniqueId())) continue;
                    c.hit(p, power);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, poison, 0, false, true));
                    snd(p.getLocation(), "entity.puffer_fish.sting", 1.2f, 0.7f);
                }
            }
            // a thin haze stays a moment where the cloud passed (nothing more: the fight must stay readable)
            if (t > 66 && t <= 96 && t % 4 == 0) w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, at.clone().add(0, 1.2, 0), 10, radius * 0.5, 0.6, radius * 0.5, 0);
            // 58-108 spent: it glows and cannot act
            if (t == 58) c.glow(EXPOSED);
            if (t == 64) snd(mouth, "block.honey_block.fall", 1.6f, 0.5f);
            if (t == 76) {
                for (BlockDisplay look : podLooks) forget(look);
                for (Location s : spots) {
                    snd(s, "block.spore_blossom.break", 1.6f, 0.9f);
                    snd(s, "entity.puffer_fish.blow_up", 1.2f, 0.6f);
                    dust(s.clone().add(0, 0.8, 0), SPORE, 1.4f, 20, popRadius * 0.45);
                    dust(s.clone().add(0, 0.8, 0), POLLEN, 1.2f, 12, popRadius * 0.45);
                    for (Player p : fighters(s, popRadius + 0.3)) {
                        if (Math.abs(p.getLocation().getY() - s.getY()) > 2.5) continue;
                        c.hit(p, popPower);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, poison / 2, 0, false, true));
                    }
                }
            }
            if (t == 90) snd(at, "block.azalea_leaves.fall", 1.4f, 0.5f);
            if (t == 108) c.glow(-1);
            if (t == 130) end(c, b);
        });
    }

    /** A spore pod swelling on the ground where it will pop. */
    private BlockDisplay pod(Location at) {
        Transformation small = new Transformation(new Vector3f(-0.1f, 0, -0.1f), new Quaternionf(), new Vector3f(0.2f, 0.2f, 0.2f), new Quaternionf());
        Transformation full = new Transformation(new Vector3f(-0.6f, 0, -0.6f), new Quaternionf(), new Vector3f(1.2f, 1.2f, 1.2f), new Quaternionf());
        BlockDisplay d = at.getWorld().spawn(at, BlockDisplay.class, bd -> {
            bd.setBlock(POD);
            bd.setPersistent(false);
            bd.setTransformation(small);
            bd.addScoreboardTag(TAG);
        });
        alive.add(d);
        Bukkit.getScheduler().runTaskLater(plugin, () -> moveTo(d, full, 40), 1L);
        return d;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Starts a move: nothing else until it ends (no other ability, no idle gesture). {@code track}: its head keeps
     * following its prey meanwhile (until {@link #lock}).
     */
    private void begin(RockPowers.Caster c, Brain b, int ticks, boolean track) {
        c.hold(ticks + 2);
        b.busyUntil = System.currentTimeMillis() + (ticks + 2) * 50L;
        if (track && c.entity() instanceof Mob m) m.setAI(true);
    }

    /** It stops turning, its head stops following: the aim of a strike is set. */
    private static void lock(Brain b) {
        b.locked = true;
        if (b.e instanceof Mob m) m.setAI(false);
    }

    private void end(RockPowers.Caster c, Brain b) {
        b.busyUntil = 0;
        b.locked = false;
        b.focus = null;
        c.release();
    }

    /** The way the plant faces now. */
    private static Vector front(Brain b) {
        return fromYaw(b.yaw);
    }

    private static Vector fromYaw(float yaw) {
        double a = Math.toRadians(yaw);
        return new Vector(-Math.sin(a), 0, Math.cos(a));
    }

    /** Where its mouth is when its head looks along {@code dir}: {@code ahead} blocks from the bulb, {@code up} high. */
    private static Location mouth(Brain b, Vector dir, double ahead, double up) {
        return b.home.clone().add(dir.clone().multiply(ahead)).add(0, up, 0);
    }

    /** Pollen and spores blown out of its mouth. */
    private static void breath(Location mouth, Vector dir, int count, double speed) {
        World w = mouth.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            Vector v = dir.clone().multiply(r.nextDouble(0.5, 3.5)).add(new Vector(r.nextDouble(-0.6, 0.6), r.nextDouble(-0.4, 0.6), r.nextDouble(-0.6, 0.6)));
            dust(mouth.clone().add(v), i % 3 == 0 ? SPORE : POLLEN, 1.0f, 1, 0.05 * speed);
        }
        w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, mouth.clone().add(dir.clone().multiply(1.5)), 4, 0.6, 0.4, 0.6, 0);
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

    private static void dust(Location at, Color color, float size, int count, double spread) {
        at.getWorld().spawnParticle(Particle.DUST, at, count, spread, spread, spread, 0, new Particle.DustOptions(color, size));
    }

    private static void snd(Location at, String key, float volume, float pitch) {
        at.getWorld().playSound(at, key, SoundCategory.HOSTILE, volume, pitch);
    }

    private static void shake(Location at, double within) {
        for (Player p : fighters(at, within)) p.sendHurtAnimation(0);
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

    /** Unit vector on the ground from a place to another. */
    private static Vector flat(Location from, Location to) {
        Vector v = to.toVector().subtract(from.toVector()).setY(0);
        return v.lengthSquared() < 1e-6 ? new Vector(0, 0, 1) : v.normalize();
    }

    private static Location onGround(Location l) {
        Block g = Fx.ground(l.getWorld(), l.getBlockX(), l.getY() + 1, l.getBlockZ());
        if (g == null) return null;
        Location out = l.clone();
        out.setY(g.getY() + g.getBoundingBox().getHeight());
        return out;
    }

    private static double distToSegment(Vector p, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double len = ab.lengthSquared();
        double k = len < 1e-9 ? 0 : Math.max(0, Math.min(1, p.clone().subtract(a).dot(ab) / len));
        return p.distance(a.clone().add(ab.multiply(k)));
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
}
