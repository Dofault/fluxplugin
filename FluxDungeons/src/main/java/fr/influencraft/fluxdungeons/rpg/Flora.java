package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.fluxdungeons.mob.Mobs;
import fr.influencraft.fluxdungeons.rpg.core.ClassDef;
import fr.influencraft.fluxdungeons.rpg.core.CombatMath;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The druid's plants (2026-11-14): his basic attack, his three skills and the mechanic they share.
 * <ul>
 *   <li><b>Seeds</b>: each of his hits plants seeds in the creature (up to {@code seed-max}, lost after
 *   {@code seed-seconds} without a new one).</li>
 *   <li><b>Bloom</b>: at {@code seed-max} seeds the creature <b>blooms</b>: roots hold it where it stands
 *   ({@code root} seconds; a boss is only slowed, half as long), a flower bursts out of it for {@code bloom} x magic
 *   power, and its pollen <b>heals</b> the druid and the allies within {@code bloom-heal-radius} of the flower
 *   ({@code bloom-heal} x magic power) and seeds the creatures around ({@code bloom-radius}). A creature blooms at most
 *   every {@code bloom-gap} seconds.</li>
 * </ul>
 * The druid is the class that keeps a party standing: his heals come from fighting, never from a heal key. Drawn with
 * block displays (never real blocks), every hit through {@link CombatEngine#strike}, every heal through
 * {@link CombatEngine#heal}. Numbers come from classes.yml ({@code passive} of the class, {@code extra} of each skill).
 */
final class Flora {

    /** The seeds a creature carries. */
    private static final class Seeds {
        final LivingEntity entity;
        int stacks;
        long until, nextBloom;

        Seeds(LivingEntity entity) {
            this.entity = entity;
        }
    }

    private static final Particle.DustOptions LEAF_DUST = new Particle.DustOptions(Color.fromRGB(0x6BC24A), 1.3f);
    private static final Particle.DustOptions POLLEN_DUST = new Particle.DustOptions(Color.fromRGB(0xF2DA6A), 1.0f);
    private static final Material[] LEAVES = {Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES, Material.MOSS_BLOCK};

    private final FluxPlugin plugin;
    private final CombatEngine combat;
    private final Skills skills;
    private final SpellFx fx;
    private final Map<UUID, Seeds> seeds = new HashMap<>();
    private BukkitTask ticker;
    private int blooms;

    Flora(FluxPlugin plugin, CombatEngine combat, Skills skills, SpellFx fx) {
        this.plugin = plugin;
        this.combat = combat;
        this.skills = skills;
        this.fx = fx;
    }

    /** Forgets every seed (plugin stopping; the displays are removed by {@link SpellFx#clear}). */
    void clear() {
        if (ticker != null) ticker.cancel();
        ticker = null;
        seeds.clear();
    }

    // ================================================================== the shared mechanic

    /** A hit of the druid: through the engine, then seeds ({@code stacks}). Null when it did not land. */
    CombatMath.Outcome hit(LivingEntity p, RpgPlayer r, LivingEntity target, CombatMath.Hit hit, String cast, int stacks) {
        LivingEntity body = skills.body(target);
        if (body == null) return null;
        CombatMath.Outcome o = combat.strike(p, body, hit, cast);
        if (o == null || o.dodged() || body.isDead() || body.getHealth() <= 0) return o;
        seed(p, r, body, stacks);
        return o;
    }

    /** Plants seeds in a creature; full of them (and not just bloomed), it blooms. */
    void seed(LivingEntity p, RpgPlayer r, LivingEntity e, int stacks) {
        if (stacks <= 0 || e.isDead()) return;
        ClassDef cls = r.heroClass();
        int max = (int) Math.max(1, cls.passive("seed-max", 3));
        long now = CombatEngine.now();
        Seeds c = seeds.computeIfAbsent(e.getUniqueId(), k -> new Seeds(e));
        c.stacks = Math.min(max, c.stacks + stacks);
        c.until = now + Math.round(20 * cls.passive("seed-seconds", 6));
        World w = e.getWorld();
        w.spawnParticle(Particle.COMPOSTER, e.getLocation().add(0, e.getHeight() * 0.6, 0), 3 * c.stacks, e.getWidth() / 2, e.getHeight() / 3, e.getWidth() / 2, 0);
        if (c.stacks >= max && now >= c.nextBloom) {
            c.stacks = 0;
            c.nextBloom = now + Math.round(20 * cls.passive("bloom-gap", 2));
            // the sprout is seen a moment before the flower opens
            w.playSound(e.getLocation(), Sound.BLOCK_BIG_DRIPLEAF_TILT_UP, SoundCategory.PLAYERS, 1.2f, 1.4f);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                RpgPlayer now2 = skills.player(p);
                if (Skills.here(p) && now2 != null && e.isValid() && !e.isDead()) bloom(p, now2, e);
            }, 4L);
        } else {
            w.playSound(e.getLocation(), Sound.BLOCK_MOSS_PLACE, SoundCategory.PLAYERS, 0.7f, 1.2f + 0.25f * c.stacks);
        }
        ensureTicker();
    }

    /** The creature blooms: rooted, hit, and its pollen heals the party and seeds its neighbours. */
    private void bloom(LivingEntity p, RpgPlayer r, LivingEntity e) {
        ClassDef cls = r.heroClass();
        boolean boss = boss(e);
        World w = e.getWorld();
        Location feet = e.getLocation();
        Location top = feet.clone().add(0, e.getHeight(), 0);
        double seconds = CombatMath.duration(cls.passive("root", 1.2) * (boss ? 0.5 : 1), r.stats(), combat.stats(e), true);
        int ticks = (int) Math.max(8, Math.round(seconds * 20));
        // a boss's fight is placed (the dungeons anchor it): slowed, never held
        e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, boss ? 1 : 9, false, false, false));
        // roots closing around its legs, a flower opening above it
        float width = (float) e.getWidth();
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2 * i / 6 + SpellFx.rnd(0.5f);
            Location base = SpellFx.ground(feet.clone().add(Math.cos(a) * (width * 0.5 + 0.35), 0.3, Math.sin(a) * (width * 0.5 + 0.35)));
            if (base == null) continue;
            fx.spike(base, Material.MANGROVE_ROOTS.createBlockData(), 0.26f, (float) (e.getHeight() * (0.45 + SpellFx.rnd(0.3f))),
                    SpellFx.lean(-Math.cos(a), -Math.sin(a)), 0.45f, ticks + 4);
        }
        float flower = Math.max(0.9f, width + 0.3f);
        BlockDisplay head = fx.display(top, Material.FLOWERING_AZALEA.createBlockData(), new Transformation(new Vector3f(-0.05f, 0, -0.05f),
                new Quaternionf(), new Vector3f(0.1f, 0.1f, 0.1f), new Quaternionf()));
        Bukkit.getScheduler().runTaskLater(plugin, () -> SpellFx.animate(head, new Transformation(new Vector3f(-flower / 2, -0.1f, -flower / 2),
                new Quaternionf(), new Vector3f(flower, flower, flower), new Quaternionf()), 4), 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> SpellFx.animate(head, new Transformation(new Vector3f(-0.05f, 0, -0.05f),
                new Quaternionf(), new Vector3f(0.1f, 0.1f, 0.1f), new Quaternionf()), 4), Math.max(8, ticks - 2L));
        fx.later(head, ticks + 4L);
        w.playSound(top, Sound.BLOCK_AZALEA_LEAVES_BREAK, SoundCategory.PLAYERS, 1.4f, 0.8f);
        w.playSound(top, Sound.BLOCK_ROOTS_BREAK, SoundCategory.PLAYERS, 1.2f, 0.7f);
        w.playSound(top, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.9f, 1.5f);
        w.spawnParticle(Particle.CHERRY_LEAVES, top, 30, 0.6, 0.4, 0.6, 0.05);
        w.spawnParticle(Particle.DUST, top, 20, 0.6, 0.4, 0.6, POLLEN_DUST);
        w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, top, 20, 1.2, 0.6, 1.2, 0);
        String cast = "bloom#" + e.getUniqueId() + "#" + (++blooms);
        combat.strike(p, e, CombatMath.Hit.magic(cls.passive("bloom", 1.0), "nature"), cast);
        // pollen: life for the party near the flower (and for the druid wherever he shoots from)
        double amount = CombatMath.heal(r.stats(), cls.passive("bloom-heal", 0.35), 0);
        double healRadius = cls.passive("bloom-heal-radius", 10);
        List<LivingEntity> healed = new ArrayList<>(skills.allies(top, healRadius));
        if (!healed.contains(p)) healed.add(p);
        for (LivingEntity ally : healed) {
            combat.heal(ally, amount, true);
            trail(top, ally.getLocation().add(0, 1.2, 0));
            ally.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, ally.getLocation().add(0, 1.1, 0), 6, 0.3, 0.4, 0.3, 0);
        }
        double radius = cls.passive("bloom-radius", 3);
        for (LivingEntity other : skills.enemies(feet, radius)) {
            LivingEntity body = skills.body(other);
            if (body != null && !body.equals(e)) seed(p, r, body, 1);
        }
    }

    /** Pollen drifting from a flower to someone it heals. */
    private static void trail(Location from, Location to) {
        if (from.getWorld() != to.getWorld()) return;
        Vector step = to.toVector().subtract(from.toVector());
        int parts = (int) Math.min(40, Math.max(3, step.length() / 0.5));
        step.multiply(1.0 / parts);
        Location at = from.clone();
        for (int i = 0; i < parts; i++) {
            at.add(step);
            at.getWorld().spawnParticle(Particle.DUST, at, 1, 0.03, 0.03, 0.03, POLLEN_DUST);
        }
    }

    /** Four times a second: seeds are seen on the creatures that carry them, and forgotten when they dry out. */
    private void ensureTicker() {
        if (ticker != null) return;
        ticker = new BukkitRunnable() {
            @Override
            public void run() {
                long now = CombatEngine.now();
                for (Iterator<Seeds> it = seeds.values().iterator(); it.hasNext(); ) {
                    Seeds c = it.next();
                    LivingEntity e = c.entity;
                    if (!e.isValid() || e.isDead() || (now >= c.until && now >= c.nextBloom)) {
                        it.remove();
                        continue;
                    }
                    if (now >= c.until) c.stacks = 0;
                    if (c.stacks > 0) {
                        e.getWorld().spawnParticle(Particle.DUST, e.getLocation().add(0, e.getHeight() + 0.2, 0), c.stacks, 0.15, 0.05, 0.15, LEAF_DUST);
                    }
                }
                if (seeds.isEmpty()) {
                    cancel();
                    ticker = null;
                }
            }
        }.runTaskTimer(plugin, 5L, 5L);
    }

    // ================================================================== basic attack: thorn

    /** A thorn flying where the player looks; one seed in the creature it hits. */
    void thorn(LivingEntity p, RpgPlayer r, CombatMath.Hit hit, String cast, double range, LivingEntity aimed) {
        Location eye = p.getEyeLocation();
        Vector dir = aimed == null ? eye.getDirection().normalize() : AimAssist.toward(eye, aimed);
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1e-4) right = new Vector(1, 0, 0);
        right.normalize();
        Location start = eye.clone().add(right.multiply(0.35)).add(0, -0.25, 0).add(dir.clone().multiply(0.6));
        start.setDirection(dir);
        BlockDisplay thorn = fx.display(start, Material.MOSS_BLOCK.createBlockData(),
                new Transformation(new Vector3f(-0.06f, -0.06f, -0.45f), new Quaternionf(), new Vector3f(0.12f, 0.12f, 0.8f), new Quaternionf()));
        BlockDisplay tip = fx.display(start, Material.FLOWERING_AZALEA_LEAVES.createBlockData(),
                new Transformation(new Vector3f(-0.11f, -0.11f, -0.6f), new Quaternionf(), new Vector3f(0.22f, 0.22f, 0.22f), new Quaternionf()));
        thorn.setTeleportDuration(1);
        tip.setTeleportDuration(1);
        World w = p.getWorld();
        w.playSound(start, Sound.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 0.5f, 1.7f);
        w.playSound(start, Sound.BLOCK_AZALEA_LEAVES_HIT, SoundCategory.PLAYERS, 1f, 1.5f);
        double speed = 1.6;
        new BukkitRunnable() {
            final Location at = start.clone();
            double gone;

            @Override
            public void run() {
                for (int sub = 0; sub < 3; sub++) {
                    at.add(dir.clone().multiply(speed / 3));
                    gone += speed / 3;
                    LivingEntity e = skills.struck(at, 0.3);
                    if (e != null) {
                        impact(e);
                        return;
                    }
                    if (!at.getBlock().isPassable() || gone >= range || !thorn.isValid()) {
                        impact(null);
                        return;
                    }
                }
                Location look = at.clone();
                look.setDirection(dir);
                thorn.teleport(look);
                tip.teleport(look);
                w.spawnParticle(Particle.COMPOSTER, at, 1, 0.03, 0.03, 0.03, 0);
            }

            private void impact(LivingEntity e) {
                cancel();
                fx.forget(thorn);
                fx.forget(tip);
                w.spawnParticle(Particle.BLOCK, at, 10, 0.12, 0.12, 0.12, 0.05, Material.AZALEA_LEAVES.createBlockData());
                w.playSound(at, Sound.BLOCK_AZALEA_LEAVES_BREAK, SoundCategory.PLAYERS, 0.6f, 1.6f);
                RpgPlayer now = skills.player(p);
                if (e != null && now != null) hit(p, now, e, hit, cast, 1);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ================================================================== right click: vine

    /**
     * A vine shoots to what the player looks at (a wall, the ground, a creature) and hauls him there; the creatures
     * he flies through are hit and seeded. Caught at the end of the vine, a creature is hit and held by roots.
     */
    boolean vine(LivingEntity p, RpgPlayer r, ClassDef.Skill s, String cast) {
        double range = s.number("range", 16);
        Location eye = p.getEyeLocation();
        LivingEntity caught = skills.lookedAt(p, range);
        Location anchor;
        if (caught != null) {
            anchor = caught.getLocation().add(0, caught.getHeight() / 2, 0);
        } else {
            RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
            if (hit == null) {
                skills.tell(p, "skill.no-anchor");
                return false;
            }
            anchor = hit.getHitPosition().toLocation(p.getWorld());
        }
        if (anchor.distanceSquared(p.getLocation()) < 9) {
            skills.tell(p, "skill.no-anchor");
            return false;
        }
        World w = p.getWorld();
        Vector line = anchor.toVector().subtract(eye.toVector());
        double length = line.length();
        Vector dir = line.clone().normalize();
        // the vine: segments of moss growing from his hand to the anchor, a leaf now and then
        int parts = (int) Math.ceil(length / 0.8);
        for (int i = 0; i < parts; i++) {
            int n = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Location at = eye.clone().add(dir.clone().multiply(n * 0.8)).add(0, -0.3, 0);
                at.setDirection(dir);
                fx.later(fx.display(at, Material.MOSS_BLOCK.createBlockData(), new Transformation(new Vector3f(-0.05f, -0.05f, 0),
                        new Quaternionf(), new Vector3f(0.1f, 0.1f, 0.85f), new Quaternionf())), 16);
                if (n % 3 == 1) {
                    fx.later(fx.display(at, Material.FLOWERING_AZALEA_LEAVES.createBlockData(), SpellFx.cube(0.26f,
                            new Quaternionf().rotateXYZ(SpellFx.rnd(3f), SpellFx.rnd(3f), 0))), 16);
                }
            }, i / 6);
        }
        w.playSound(eye, Sound.ENTITY_FISHING_BOBBER_THROW, SoundCategory.PLAYERS, 1f, 0.7f);
        w.playSound(eye, Sound.BLOCK_VINE_PLACE, SoundCategory.PLAYERS, 1.4f, 0.8f);
        w.playSound(anchor, Sound.BLOCK_ROOTS_PLACE, SoundCategory.PLAYERS, 1.2f, 0.9f);
        int stacks = (int) s.number("seed", 1);
        if (caught != null) {
            if (hit(p, r, caught, s.hit(), cast, stacks) != null && !caught.isDead()) {
                boolean boss = boss(caught);
                double seconds = CombatMath.duration(s.number("root", 1.0) * (boss ? 0.5 : 1), r.stats(), combat.stats(caught), true);
                caught.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) Math.max(6, Math.round(seconds * 20)), boss ? 1 : 9, false, false, false));
            }
        }
        double stop = caught != null ? 2.8 : 1.5;
        double speed = s.number("speed", 1.05);
        new BukkitRunnable() {
            int t, stuck;
            double last = Double.MAX_VALUE;

            @Override
            public void run() {
                if (!Skills.here(p) || p.isDead() || p.getWorld() != anchor.getWorld()) {
                    cancel();
                    return;
                }
                Vector to = anchor.toVector().subtract(p.getLocation().add(0, 1, 0).toVector());
                double dist = to.length();
                stuck = dist > last - 0.15 ? stuck + 1 : 0;
                last = dist;
                p.setFallDistance(0);
                if (dist <= stop || ++t > 30 || stuck > 3) {
                    cancel();
                    // a last hop: he lands on his feet beside what he aimed at, never flat against it
                    p.setVelocity(to.clone().setY(0).lengthSquared() < 1e-4 ? new Vector(0, 0.3, 0) : to.clone().setY(0).normalize().multiply(0.25).setY(0.35));
                    w.playSound(p.getLocation(), Sound.BLOCK_AZALEA_LEAVES_FALL, SoundCategory.PLAYERS, 1.2f, 1f);
                    return;
                }
                Vector v = to.normalize().multiply(speed);
                v.setY(v.getY() + 0.08);
                p.setVelocity(v);
                w.spawnParticle(Particle.COMPOSTER, p.getLocation().add(0, 1, 0), 2, 0.2, 0.3, 0.2, 0);
                w.spawnParticle(Particle.CHERRY_LEAVES, p.getLocation().add(0, 1, 0), 1, 0.2, 0.3, 0.2, 0);
                RpgPlayer now = skills.player(p);
                if (now == null) return;
                for (LivingEntity e : skills.enemies(p.getLocation(), 1.8)) hit(p, now, e, s.hit(), cast, stacks);
            }
        }.runTaskTimer(plugin, 3L, 1L);
        return true;
    }

    // ================================================================== A: bramble garden

    /**
     * Brambles grow where the player aims and stay a while: the creatures inside are torn again and again, slowed,
     * and seeded: they bloom in the middle of the thorns.
     */
    boolean brambles(LivingEntity p, RpgPlayer r, ClassDef.Skill s, String cast) {
        Location target = SpellFx.aimedGround(p, s.number("range", 18), skills);
        if (target == null) {
            skills.tell(p, "skill.blocked");
            return false;
        }
        double radius = s.number("radius", 4);
        int pulses = Math.max(1, s.hits());
        int period = (int) Math.max(4, Math.round(20 * Math.max(1, s.duration()) / pulses));
        int life = pulses * period + 6;
        int slow = (int) Math.max(0, s.number("slow", 2) - 1);
        int seedEvery = (int) Math.max(1, s.number("seed-every", 2));
        World w = p.getWorld();
        w.playSound(target, Sound.BLOCK_SWEET_BERRY_BUSH_PLACE, SoundCategory.PLAYERS, 1.6f, 0.6f);
        w.playSound(target, Sound.BLOCK_ROOTS_PLACE, SoundCategory.PLAYERS, 1.6f, 0.6f);
        w.playSound(p.getLocation(), Sound.BLOCK_AZALEA_PLACE, SoundCategory.PLAYERS, 1f, 0.8f);
        BlockData bush = Bukkit.createBlockData("minecraft:sweet_berry_bush[age=3]");
        BlockData young = Bukkit.createBlockData("minecraft:sweet_berry_bush[age=1]");
        int plants = (int) Math.round(radius * radius * 1.7);
        // the rim first: thorny roots leaning out, a fence everyone reads the zone by
        int rim = (int) Math.round(radius * 3.2);
        for (int k = 0; k < rim; k++) {
            double a = Math.PI * 2 * k / rim + SpellFx.rnd(0.2f);
            Location l = SpellFx.ground(target.clone().add(Math.cos(a) * radius, 0.4, Math.sin(a) * radius));
            if (l == null) continue;
            int n = k;
            Bukkit.getScheduler().runTaskLater(plugin, () -> fx.spike(l, Material.MANGROVE_ROOTS.createBlockData(), 0.3f, 1.3f + SpellFx.rnd(0.9f),
                    SpellFx.lean(Math.cos(a), Math.sin(a)), 0.4f + SpellFx.rnd(0.3f), life - n % 4), k % 4);
        }
        new BukkitRunnable() {
            int t, grown;

            @Override
            public void run() {
                t++;
                if (!Skills.here(p) || t > life) {
                    cancel();
                    w.spawnParticle(Particle.BLOCK, target.clone().add(0, 0.4, 0), 50, radius / 2, 0.2, radius / 2, 0, Material.AZALEA_LEAVES.createBlockData());
                    w.playSound(target, Sound.BLOCK_SWEET_BERRY_BUSH_BREAK, SoundCategory.PLAYERS, 1.2f, 0.7f);
                    return;
                }
                // the inside fills up in half a second
                for (int i = 0; i < 4 && grown < plants; i++, grown++) {
                    double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2), d = Math.sqrt(ThreadLocalRandom.current().nextDouble()) * (radius - 0.4);
                    Location l = SpellFx.ground(target.clone().add(Math.cos(a) * d, 0.4, Math.sin(a) * d));
                    if (l == null) continue;
                    int left = life - t;
                    switch (grown % 4) {
                        case 0 -> fx.spike(l, bush, 1.0f + SpellFx.rnd(0.5f), 1.0f + SpellFx.rnd(0.6f), 0, 0, left);
                        case 1 -> fx.spike(l, young, 0.9f + SpellFx.rnd(0.3f), 0.8f + SpellFx.rnd(0.3f), 0, 0, left);
                        case 2 -> fx.spike(l, Material.MANGROVE_ROOTS.createBlockData(), 0.28f, 0.9f + SpellFx.rnd(0.9f), SpellFx.rnd(6f), 0.5f + SpellFx.rnd(0.4f), left);
                        default -> fx.spike(l, LEAVES[grown % LEAVES.length].createBlockData(), 0.6f + SpellFx.rnd(0.3f), 0.35f + SpellFx.rnd(0.25f), SpellFx.rnd(6f), 0, left);
                    }
                }
                if (t % 4 == 0) {
                    SpellFx.ring(target, radius, 26, Particle.DUST, LEAF_DUST);
                    w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, target.clone().add(0, 0.6, 0), 6, radius / 2, 0.3, radius / 2, 0);
                }
                if (t % period != 0) return;
                int n = t / period;
                RpgPlayer now = skills.player(p);
                if (now == null) return;
                boolean any = false;
                for (LivingEntity e : skills.enemies(target, radius)) {
                    if (Math.abs(e.getLocation().getY() - target.getY()) > 3) continue;
                    if (hit(p, now, e, s.hit(), cast + "#" + n, n % seedEvery == 0 ? 1 : 0) == null) continue;
                    any = true;
                    if (!boss(e) && !e.hasPotionEffect(PotionEffectType.SLOWNESS)) {
                        e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, period + 6, slow, false, false, false));
                    }
                    w.spawnParticle(Particle.BLOCK, e.getLocation().add(0, 0.5, 0), 6, 0.2, 0.3, 0.2, 0, bush);
                }
                if (any) w.playSound(target, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, SoundCategory.PLAYERS, 0.9f, 0.9f + 0.05f * n);
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    // ================================================================== F: maneater

    /**
     * A giant carnivorous plant grows where the player aims and fights beside him: its head follows the nearest
     * creature, rears back, lunges and bites, again and again; when it withers, its pollen heals the party around it.
     */
    boolean maneater(LivingEntity p, RpgPlayer r, ClassDef.Skill s, String cast) {
        Location base = SpellFx.aimedGround(p, s.number("range", 16), skills);
        if (base == null) {
            skills.tell(p, "skill.blocked");
            return false;
        }
        base.setYaw(0);
        base.setPitch(0);
        double radius = s.number("radius", 6);
        double reach = s.number("reach", 5);
        int bites = Math.max(1, s.hits());
        int period = (int) Math.max(8, Math.round(20 * Math.max(1, s.duration()) / bites));
        int stacks = (int) s.number("seed", 1);
        int growth = 10, life = growth + bites * period;
        World w = p.getWorld();
        w.playSound(base, Sound.BLOCK_BIG_DRIPLEAF_PLACE, SoundCategory.PLAYERS, 1.6f, 0.5f);
        w.playSound(base, Sound.BLOCK_ROOTS_PLACE, SoundCategory.PLAYERS, 1.6f, 0.5f);
        w.playSound(base, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 0.35f, 1.9f);
        w.spawnParticle(Particle.BLOCK, base.clone().add(0, 0.3, 0), 40, 0.6, 0.2, 0.6, 0, Material.MOSS_BLOCK.createBlockData());
        // leaves spread at its foot
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2 * i / 6;
            Location l = SpellFx.ground(base.clone().add(Math.cos(a) * 0.9, 0.3, Math.sin(a) * 0.9));
            if (l != null) fx.spike(l, Material.FLOWERING_AZALEA_LEAVES.createBlockData(), 0.8f, 0.45f, SpellFx.lean(Math.cos(a), Math.sin(a)), 0.9f, life);
        }
        Transformation none = new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.01f, 0.01f, 0.01f), new Quaternionf());
        BlockDisplay stem = fx.display(base, Material.MOSS_BLOCK.createBlockData(), none);
        BlockDisplay upper = fx.display(base, Material.MOSS_BLOCK.createBlockData(), none);
        BlockDisplay lower = fx.display(base, Material.MOSS_BLOCK.createBlockData(), none);
        BlockDisplay upperMouth = fx.display(base, Material.NETHER_WART_BLOCK.createBlockData(), none);
        BlockDisplay lowerMouth = fx.display(base, Material.NETHER_WART_BLOCK.createBlockData(), none);
        BlockDisplay crest = fx.display(base, Material.FLOWERING_AZALEA.createBlockData(), none);
        List<BlockDisplay> all = List.of(stem, upper, lower, upperMouth, lowerMouth, crest);
        new BukkitRunnable() {
            int t;
            LivingEntity prey;
            Vector3f aim = new Vector3f(0, 0, 1);

            @Override
            public void run() {
                t++;
                if (!Skills.here(p) || t > life + 8) {
                    cancel();
                    for (BlockDisplay d : all) fx.forget(d);
                    if (Skills.here(p)) wither(p, base, s);
                    return;
                }
                float grow = t <= growth ? t / (float) growth : t > life ? Math.max(0.05f, 1 - (t - life) / 8f) : 1;
                int phase = t <= growth || t > life ? -1 : (t - growth - 1) % period;
                if (phase == 0) {
                    prey = nearest(base, radius);
                    if (prey != null) w.playSound(base, Sound.ENTITY_SNIFFER_SNIFFING, SoundCategory.PLAYERS, 0.9f, 0.6f);
                }
                if (prey != null && (prey.isDead() || !prey.isValid())) prey = null;
                // head: sways on its stem; rears back, lunges at its prey, snaps, comes back
                Vector3f rest = new Vector3f((float) Math.sin(t * 0.13) * 0.35f, 2.7f + (float) Math.sin(t * 0.21) * 0.12f, (float) Math.cos(t * 0.11) * 0.35f);
                Vector3f head = new Vector3f(rest);
                float open = 0.35f + 0.1f * (float) Math.sin(t * 0.3);
                if (prey != null && phase >= 0) {
                    Vector3f to = prey.getLocation().add(0, prey.getHeight() * 0.6, 0).toVector().subtract(base.toVector()).toVector3f();
                    aim = new Vector3f(to.x, 0, to.z);
                    if (aim.lengthSquared() < 1e-4f) aim.set(0, 0, 1);
                    aim.normalize();
                    if (to.length() > reach) to.normalize((float) reach);
                    if (to.y < 0.5f) to.y = 0.5f;
                    if (phase < 4) {
                        head.sub(new Vector3f(aim).mul(0.7f)).add(0, 0.3f, 0);
                        open = 0.35f + 0.2f * phase;
                    } else if (phase < 7) {
                        head.lerp(to, (phase - 3) / 3f);
                        open = 1.0f;
                    } else if (phase == 7) {
                        head.set(to);
                        open = 0.02f;
                        bite(to, phase);
                    } else {
                        head.set(to).lerp(rest, Math.min(1, (phase - 7) / 5f));
                        open = 0.1f;
                    }
                }
                place(head.mul(grow), open, grow);
            }

            private void bite(Vector3f to, int phase) {
                Location at = base.clone().add(to.x, to.y, to.z);
                w.playSound(at, Sound.ENTITY_EVOKER_FANGS_ATTACK, SoundCategory.PLAYERS, 1.1f, 0.8f);
                w.playSound(at, Sound.BLOCK_AZALEA_LEAVES_BREAK, SoundCategory.PLAYERS, 1.2f, 0.6f);
                w.spawnParticle(Particle.CRIT, at, 12, 0.3, 0.3, 0.3, 0.2);
                w.spawnParticle(Particle.BLOCK, at, 10, 0.3, 0.3, 0.3, 0, Material.NETHER_WART_BLOCK.createBlockData());
                RpgPlayer now = skills.player(p);
                if (now == null) return;
                String hitCast = cast + "#" + (t / period);
                for (LivingEntity e : skills.enemies(at, 1.8)) hit(p, now, e, s.hit(), hitCast, stacks);
                if (prey != null && !prey.isDead()) hit(p, now, prey, s.hit(), hitCast, stacks);
            }

            /** Puts every piece where the head is: stem from the ground to it, two jaws hinged at its back, a crest. */
            private void place(Vector3f head, float open, float grow) {
                // the stem: a thin box from the foot to the head
                float len = Math.max(0.05f, head.length());
                Quaternionf up = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), new Vector3f(head).normalize());
                float sw = 0.34f * grow;
                SpellFx.animate(stem, new Transformation(up.transform(new Vector3f(-sw / 2, 0, -sw / 2)), up, new Vector3f(sw, len, sw), new Quaternionf()), 1);
                // jaws: local z = forward (towards the prey), hinge at the back (z = -0.7)
                Quaternionf face = new Quaternionf().rotateY((float) Math.atan2(aim.x, aim.z));
                float jw = 1.3f * grow, jl = 1.5f * grow, jh = 0.42f * grow;
                Quaternionf upQ = new Quaternionf(face).rotateX(-open * 0.75f);
                Quaternionf lowQ = new Quaternionf(face).rotateX(open * 0.55f);
                jaw(upper, head, upQ, new Vector3f(-jw / 2, 0.04f, -jl / 2), new Vector3f(jw, jh, jl));
                jaw(upperMouth, head, upQ, new Vector3f(-jw * 0.42f, 0, -jl * 0.42f), new Vector3f(jw * 0.84f, 0.08f * grow, jl * 0.9f));
                jaw(lower, head, lowQ, new Vector3f(-jw / 2, -jh - 0.04f, -jl / 2), new Vector3f(jw, jh, jl));
                jaw(lowerMouth, head, lowQ, new Vector3f(-jw * 0.42f, -0.08f * grow, -jl * 0.42f), new Vector3f(jw * 0.84f, 0.08f * grow, jl * 0.9f));
                float cs = 0.8f * grow;
                jaw(crest, head, upQ, new Vector3f(-cs / 2, jh, -jl / 2 - cs * 0.2f), new Vector3f(cs, cs, cs));
            }

            private void jaw(BlockDisplay d, Vector3f head, Quaternionf q, Vector3f corner, Vector3f size) {
                // turned around the hinge at the back of the head
                Vector3f hinge = new Vector3f(0, 0, -0.7f * size.z / 1.5f);
                Vector3f offset = new Vector3f(corner).sub(hinge);
                Vector3f at = new Vector3f(head).add(new Quaternionf().rotateY((float) Math.atan2(aim.x, aim.z)).transform(new Vector3f(hinge))).add(q.transform(offset));
                SpellFx.animate(d, new Transformation(at, q, size, new Quaternionf()), 1);
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    private LivingEntity nearest(Location at, double radius) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : skills.enemies(at, radius)) {
            double d = e.getLocation().distanceSquared(at);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** The maneater withers: a cloud of pollen that heals the party around it. */
    private void wither(LivingEntity p, Location base, ClassDef.Skill s) {
        World w = base.getWorld();
        Location mid = base.clone().add(0, 1.4, 0);
        double radius = s.number("heal-radius", 8);
        w.playSound(mid, Sound.BLOCK_AZALEA_LEAVES_BREAK, SoundCategory.PLAYERS, 1.6f, 0.5f);
        w.playSound(mid, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.2f, 1.2f);
        w.spawnParticle(Particle.CHERRY_LEAVES, mid, 80, 1.2, 1.0, 1.2, 0.08);
        w.spawnParticle(Particle.DUST, mid, 60, radius / 3, 0.8, radius / 3, POLLEN_DUST);
        w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, mid, 60, radius / 2, 1, radius / 2, 0);
        fx.debris(mid, 10, LEAVES, 13);
        RpgPlayer r = skills.player(p);
        if (r == null) return;
        double amount = CombatMath.heal(r.stats(), s.number("heal", 1.5), 0);
        for (LivingEntity ally : skills.allies(base, radius)) {
            combat.heal(ally, amount, true);
            trail(mid, ally.getLocation().add(0, 1.2, 0));
            ally.getWorld().spawnParticle(Particle.HEART, ally.getLocation().add(0, 2, 0), 3, 0.3, 0.2, 0.3);
        }
    }

    private static boolean boss(LivingEntity e) {
        return e.getScoreboardTags().contains(Mobs.BOSS_TAG);
    }
}
