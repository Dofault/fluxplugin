package fr.influencraft.fluxdungeons.mob;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Abilities of the Copper T-Rex, built around its <b>Overcharge</b>: copper energy (0 to 100) that it gains by walking
 * (each step), by hitting players, by ramming a wall and by absorbing the copper veins of its mine, that makes its
 * blows heavier and that its ultimate needs in full. It shows on the boss: copper dust around its body (30+),
 * resonance and sparks (60+), an orange outline (85+), warning sounds when full. Timings follow its model's
 * animations tick for tick (docs/technique/boss/trex-cuivre.md):
 * <ul>
 *   <li>{@code copper_roar}: first move (detects its prey, roars); sets the Overcharge rules ({@code step-gain});</li>
 *   <li>{@code crushing_bite}: head drawn back, jaw open (0-9), snaps forwards: jaws shut at 12 in front of it;</li>
 *   <li>{@code tectonic_sweep}: twists, then whips its tail through an arc behind it (from its right to its left,
 *   15-22); hits who stands where the tail passes, at that moment; jumping over it avoids it;</li>
 *   <li>{@code drill_charge}: stares, crouches, the line of the charge shown on the ground (locked at 16), rams in a
 *   straight line; into a wall: stunned, Overcharge gained; else brakes after {@code distance} blocks;</li>
 *   <li>{@code cataclysmic_resonance}: the ultimate, at full Overcharge: gathers (0-50), stamps at 54 (a ground wave
 *   to jump over), three eruptions announced on the ground (disc, spokes, outer ring), Overcharge back to 0;</li>
 *   <li>{@code copper_absorb}: walks to a vein of raw copper and drains it (Overcharge); breaking the vein or hurting
 *   it enough meanwhile interrupts it (stunned);</li>
 *   <li>{@code copper_phase}: roar and stamp of a new phase: Overcharge gained faster, a little faster.</li>
 * </ul>
 */
final class CopperPowers {

    static final Set<String> TYPES = Set.of("copper_roar", "crushing_bite", "tectonic_sweep", "drill_charge",
            "cataclysmic_resonance", "copper_absorb", "copper_phase");
    static final Material VEIN = Material.RAW_COPPER_BLOCK;
    private static final Material DRAINED = Material.COBBLED_DEEPSLATE;
    private static final String TAG = "fluxdungeons_fx";
    private static final Color COPPER = Color.fromRGB(0xC87456), BRIGHT = Color.fromRGB(0xE3826C),
            DARK = Color.fromRGB(0x9A5038), RED = Color.fromRGB(0xFF5C5C);
    private static final int GLOW = 0xE3826C;

    /** Overcharge of one monster, and what it knows of its mine. */
    private static final class Charge {
        double value;
        double gain = 1;
        double stepGain = 1.2;
        Location last;
        Location home;
        RockPowers.Caster caster;
        boolean glowing;
        boolean scene;
        int ticks;
        List<Block> veins;
        int scanned = -1000;
    }

    private final Plugin plugin;
    private final Fx fx;
    private final Map<UUID, Charge> charges = new HashMap<>();
    private final Set<Entity> spawned = new HashSet<>();

    CopperPowers(Plugin plugin, Fx fx) {
        this.plugin = plugin;
        this.fx = fx;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    boolean cast(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        if (!TYPES.contains(ab.type())) return false;
        Charge ch = charge(c.entity());
        ch.caster = c;
        if (ch.home == null) ch.home = c.home();
        switch (ab.type()) {
            case "copper_roar" -> roar(ab, c, ch, target);
            case "crushing_bite" -> bite(ab, c, ch, target);
            case "tectonic_sweep" -> sweep(ab, c, ch, target);
            case "drill_charge" -> drill(ab, c, ch, target);
            case "cataclysmic_resonance" -> resonance(ab, c, ch, target);
            case "copper_absorb" -> absorb(ab, c, ch);
            case "copper_phase" -> phase(ab, c, ch);
            default -> {
            }
        }
        return true;
    }

    /** False when the ability has nobody to hit or nothing to work with (not full, no vein left...). */
    boolean worth(MobTemplate.Ability ab, LivingEntity e, Location home) {
        if (!TYPES.contains(ab.type())) return true;
        Charge ch = charge(e);
        if (ch.home == null) ch.home = home;
        return switch (ab.type()) {
            case "crushing_bite" -> inArc(e, ab.number("reach", 4.2), 0, ab.number("angle", 70));
            case "tectonic_sweep" -> inArc(e, ab.number("radius", 4.6), ab.number("min-angle", 60), 180);
            case "cataclysmic_resonance" -> ch.value >= ab.number("needs", 100);
            case "copper_absorb" -> ch.value < ab.number("below-charge", 70) && nearestVein(e, ch, ab) != null;
            default -> true;
        };
    }

    /** Overcharge of a monster (0-100), for the debug view. */
    double overcharge(LivingEntity e) {
        Charge ch = charges.get(e.getUniqueId());
        return ch == null ? 0 : ch.value;
    }

    void clear() {
        for (Entity d : spawned) if (d.isValid()) d.remove();
        spawned.clear();
        charges.clear();
    }

    // ---------------------------------------------------------------- Overcharge

    private Charge charge(LivingEntity e) {
        return charges.computeIfAbsent(e.getUniqueId(), k -> new Charge());
    }

    private static void gain(Charge ch, double amount) {
        ch.value = Math.max(0, Math.min(100, ch.value + amount * (amount > 0 ? ch.gain : 1)));
    }

    /** Damage factor of its blows: up to {@code overcharge-bonus} more at full Overcharge. */
    private static double boost(Charge ch, MobTemplate.Ability ab) {
        return 1 + ab.number("overcharge-bonus", 0.3) * ch.value / 100;
    }

    /** Every 10 ticks: steps (Overcharge gained, step sounds), and what its Overcharge shows. */
    private void tick() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        charges.entrySet().removeIf(en -> {
            Entity x = Bukkit.getEntity(en.getKey());
            return x == null || !x.isValid() || x.isDead();
        });
        for (Map.Entry<UUID, Charge> en : charges.entrySet()) {
            if (!(Bukkit.getEntity(en.getKey()) instanceof LivingEntity e)) continue;
            Charge ch = en.getValue();
            ch.ticks += 10;
            World w = e.getWorld();
            Location at = e.getLocation();
            if (ch.last != null && ch.last.getWorld() == w) {
                double moved = Math.hypot(at.getX() - ch.last.getX(), at.getZ() - ch.last.getZ());
                if (moved > 0.4 && moved < 6) {
                    gain(ch, ch.stepGain);
                    // a step: dust under the feet and a mineral creak now and then (variants)
                    w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.1, 0), 8, 0.7, 0.05, 0.7, 0, blockUnder(at));
                    if (r.nextInt(3) == 0) {
                        String[] creaks = {"block.copper.step", "block.deepslate.step", "block.gravel.step", "block.copper_grate.step"};
                        snd(at, creaks[r.nextInt(creaks.length)], 1f, 0.5f + r.nextFloat() * 0.2f);
                    }
                }
            }
            ch.last = at.clone();
            double v = ch.value;
            if (v >= 30) {
                int n = (int) (v / 25);
                for (int i = 0; i < n; i++) {
                    Location p = at.clone().add(r.nextDouble(-1.0, 1.0), r.nextDouble(0.8, 2.6), r.nextDouble(-1.0, 1.0));
                    w.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(v >= 60 ? BRIGHT : COPPER, 1.3f));
                }
            }
            if (v >= 60) {
                w.spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0, 1.0, 0), 3, 0.8, 0.4, 0.8, 0.05);
                if (ch.ticks % 40 == 0) {
                    snd(at, "block.amethyst_block.resonate", 1.2f, 0.5f);
                    snd(at, "block.copper_bulb.turn_on", 0.8f, 0.5f);
                }
            }
            if (v >= 100 && ch.ticks % 20 == 0) {
                snd(at, "block.trial_spawner.about_to_spawn_item", 1.4f, 0.6f);
                w.spawnParticle(Particle.WAX_ON, at.clone().add(0, 1.6, 0), 10, 1.0, 0.8, 1.0, 0);
            }
            boolean want = v >= 85;
            if (!ch.scene && ch.caster != null && want != ch.glowing) {
                ch.caster.glow(want ? GLOW : -1);
                ch.glowing = want;
            }
        }
    }

    // ---------------------------------------------------------------- shared

    private static void snd(Location at, String key, float volume, float pitch) {
        at.getWorld().playSound(at, key, SoundCategory.HOSTILE, volume, pitch);
    }

    private static BlockData blockUnder(Location at) {
        Block b = Fx.ground(at.getWorld(), at.getBlockX(), at.getY(), at.getBlockZ());
        return b == null ? Material.DEEPSLATE.createBlockData() : b.getBlockData();
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

    private static Vector forward(LivingEntity e) {
        Vector f = e.getLocation().getDirection().setY(0);
        return f.lengthSquared() < 1e-6 ? new Vector(0, 0, 1) : f.normalize();
    }

    /** Its right hand side (the model faces the entity's direction, its right is on the right). */
    private static Vector right(Vector f) {
        return new Vector(-f.getZ(), 0, f.getX());
    }

    private static void face(LivingEntity e, Location at) {
        e.setRotation(yawTo(flat(e.getLocation(), at)), 0);
    }

    /** Degrees between its front and a player (0 in front, 180 behind). */
    private static double angleFromFront(LivingEntity e, Location at) {
        Vector to = flat(e.getLocation(), at);
        return Math.toDegrees(forward(e).angle(to));
    }

    /** True when a fighter stands within {@code reach}, between {@code min} and {@code max} degrees from its front. */
    private static boolean inArc(LivingEntity e, double reach, double min, double max) {
        for (Player p : fighters(e, reach)) {
            double a = angleFromFront(e, p.getLocation());
            if (a >= min && a <= max) return true;
        }
        return false;
    }

    private static double groundY(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int by = (int) Math.floor(y + 1.2); by >= (int) Math.floor(y) - 4; by--) {
            Block b = w.getBlockAt(bx, by, bz);
            if (b.isCollidable()) return by + b.getBoundingBox().getHeight();
        }
        return y;
    }

    /** True when the player's feet are clear of the ground (jumping over a wave or the tail). */
    private static boolean airborne(Player p) {
        Location l = p.getLocation();
        return l.getY() - groundY(l.getWorld(), l.getX(), l.getY(), l.getZ()) > 0.55;
    }

    private static Location step(LivingEntity e, Vector dir, double speed) {
        Location at = e.getLocation().clone().add(dir.clone().multiply(speed));
        at.setY(groundY(e.getWorld(), at.getX(), at.getY(), at.getZ()));
        at.setYaw(yawTo(dir));
        at.setPitch(0);
        Mobs.move(e, at);
        return at;
    }

    /** A solid block right in front, at the height of its legs or chest. */
    private static Block wallAhead(LivingEntity e, Vector dir, double ahead) {
        Location front = e.getLocation().clone().add(dir.clone().multiply(ahead));
        for (double h : new double[]{0.6, 1.5, 2.3}) {
            Block b = front.clone().add(0, h, 0).getBlock();
            if (b.isCollidable()) return b;
        }
        return null;
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

    /** The screen of nearby players shakes (a hurt shake without damage). */
    private static void shake(Location at, double within) {
        for (Player p : at.getWorld().getPlayers()) {
            if (Mobs.fighting(p) && p.getLocation().distanceSquared(at) <= within * within) p.sendHurtAnimation(0);
        }
    }

    /** Where its snout is ({@code mouth: [forwards, height]}). */
    private static Location mouth(LivingEntity e, MobTemplate.Ability ab, double fwd, double up) {
        Object v = ab.params().get("mouth");
        if (v instanceof List<?> l && l.size() >= 2 && l.get(0) instanceof Number a && l.get(1) instanceof Number b) {
            fwd = a.doubleValue();
            up = b.doubleValue();
        }
        return e.getLocation().clone().add(forward(e).multiply(fwd)).add(0, up, 0);
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

    private static void dust(Location at, Color c, float size) {
        at.getWorld().spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, new Particle.DustOptions(c, size));
    }

    /** A copper burst: raw copper fragments, copper dust, sparks. */
    private static void burst(Location at, int count) {
        World w = at.getWorld();
        w.spawnParticle(Particle.BLOCK, at, count, 0.4, 0.3, 0.4, 0, VEIN.createBlockData());
        w.spawnParticle(Particle.DUST, at, count / 2, 0.5, 0.4, 0.5, 0, new Particle.DustOptions(BRIGHT, 1.5f));
        w.spawnParticle(Particle.WAX_ON, at, count / 3, 0.5, 0.4, 0.5, 0);
    }

    // ---------------------------------------------------------------- copper roar (first move)

    private void roar(MobTemplate.Ability ab, RockPowers.Caster c, Charge ch, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        ch.stepGain = ab.number("step-gain", 1.2);
        c.hold(44);
        c.animate("detect");
        face(e, target.getLocation());
        snd(e.getLocation(), "entity.ravager.ambient", 1.6f, 0.5f);
        timeline(e, 44, t -> {
            Location at = e.getLocation();
            if (t == 12) {
                c.animate("roar");
                title(e, ab, 40);
                snd(at, "entity.ravager.roar", 2.2f, 0.5f);
                snd(at, "entity.iron_golem.damage", 1.4f, 0.5f);
                snd(at, "block.copper_bulb.turn_on", 1.4f, 0.5f);
            }
            if (t >= 20 && t <= 34 && t % 3 == 0) {
                w.spawnParticle(Particle.CLOUD, mouth(e, ab, 2.6, 2.6), 4, 0.3, 0.2, 0.3, 0.05);
                shake(at, 12);
            }
            if (t == 24) fx.shockwave(at, 4, 6, 0.3);
            if (t == 44) c.release();
        });
    }

    // ---------------------------------------------------------------- crushing bite

    private void bite(MobTemplate.Ability ab, RockPowers.Caster c, Charge ch, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double power = ab.number("bite-power", 6) * boost(ch, ab), radius = ab.number("radius", 1.7);
        c.hold(36);
        c.animate("bite");
        snd(e.getLocation(), "entity.ravager.ambient", 1.6f, 0.55f);
        snd(e.getLocation(), "block.copper.hit", 1.2f, 0.5f);
        timeline(e, 36, t -> {
            // the head says where it will bite: it follows its target during the wind-up, then the aim is fixed
            if (t <= 8 && target.isValid() && Mobs.fighting(target)) face(e, target.getLocation());
            if (t == 5) snd(e.getLocation(), "entity.ravager.ambient", 1.8f, 0.7f);
            if (t < 9 && t % 2 == 0) dust(mouth(e, ab, 2.0, 3.0), BRIGHT, 1.2f);
            if (t == 10 || t == 11) step(e, forward(e), 0.25);
            if (t == 12) {
                // bite_hit: the jaws shut in front of it, where the snout is now
                Location m = mouth(e, ab, 2.6, 1.6);
                snd(m, "entity.evoker_fangs.attack", 2f, 0.5f);
                snd(m, "block.deepslate.break", 1.6f, 0.5f);
                snd(m, "entity.zombie.attack_iron_door", 0.8f, 1.4f);
                burst(m, 14);
                boolean any = false;
                for (Player p : fighters(e, 6)) {
                    Location pl = p.getLocation();
                    if (Math.hypot(pl.getX() - m.getX(), pl.getZ() - m.getZ()) > radius) continue;
                    if (pl.getY() > m.getY() + 1.2 || pl.getY() + 1.8 < m.getY() - 1.2) continue;
                    c.hit(p, power);
                    p.setVelocity(forward(e).multiply(0.5).setY(0.25));
                    p.sendHurtAnimation(0);
                    any = true;
                }
                if (any) gain(ch, ab.number("gain", 6));
            }
            if (t == 18) snd(e.getLocation(), "entity.ravager.ambient", 1.4f, 0.8f);
            if (t == 36) c.release();
        });
    }

    // ---------------------------------------------------------------- tectonic sweep

    private void sweep(MobTemplate.Ability ab, RockPowers.Caster c, Charge ch, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double power = ab.number("tail-power", 5) * boost(ch, ab), radius = ab.number("radius", 4.6);
        int hitStart = 15, hitEnd = 22;
        double from = ab.number("arc-from", 70), to = ab.number("arc-to", -95);
        Location center = e.getLocation().clone();
        Vector f = forward(e), back = f.clone().multiply(-1), rt = right(f);
        Set<UUID> struck = new HashSet<>();
        c.hold(52);
        c.animate("tail_sweep");
        snd(center, "entity.ravager.ambient", 1.6f, 0.5f);
        snd(center, "item.armor.equip_iron", 1.4f, 0.5f);
        timeline(e, 52, t -> {
            if (t < hitStart && t % 2 == 0) {
                // the side it will come from: dust where the tail gathers
                Location tip = center.clone().add(back.clone().rotateAroundY(Math.toRadians(-from)).multiply(3.2)).add(0, 0.4, 0);
                w.spawnParticle(Particle.BLOCK, tip, 4, 0.3, 0.1, 0.3, 0, blockUnder(tip));
            }
            if (t == hitStart - 1) {
                snd(center, "entity.player.attack.sweep", 2f, 0.5f);
                snd(center, "entity.breeze.wind_burst", 1.4f, 0.6f);
            }
            if (t >= hitStart && t <= hitEnd) {
                // the arc covered this tick, from its right (+) through behind it to its left (-)
                double a0 = from + (to - from) * (t - hitStart - 1) / (double) (hitEnd - hitStart);
                double a1 = from + (to - from) * (t - hitStart) / (double) (hitEnd - hitStart);
                double lo = Math.min(a0, a1), hi = Math.max(a0, a1);
                if (t == hitStart) {
                    lo = a1 - 6;
                    hi = a1 + 6;
                }
                for (double rr = 1.5; rr <= radius; rr += 0.9) {
                    Location p = center.clone().add(back.clone().rotateAroundY(Math.toRadians(-a1)).multiply(rr)).add(0, 0.6, 0);
                    dust(p, rr > radius - 1 ? BRIGHT : COPPER, 1.6f);
                    if (rr > radius - 1) w.spawnParticle(Particle.CLOUD, p, 2, 0.2, 0.1, 0.2, 0.02);
                }
                for (Player p : fighters(e, radius + 1)) {
                    if (struck.contains(p.getUniqueId())) continue;
                    Vector v = p.getLocation().toVector().subtract(center.toVector()).setY(0);
                    double d = v.length();
                    if (d < 1.0 || d > radius) continue;
                    v.normalize();
                    double ang = Math.toDegrees(Math.atan2(v.dot(rt), v.dot(back)));
                    if (ang < lo - 4 || ang > hi + 4) continue;
                    if (airborne(p)) continue;             // jumped over the tail
                    struck.add(p.getUniqueId());
                    c.hit(p, power);
                    Vector push = rt.clone().multiply(-1).add(v.clone().multiply(0.6)).normalize();
                    p.setVelocity(push.multiply(1.0).setY(0.35));
                    p.sendHurtAnimation(0);
                    snd(p.getLocation(), "entity.iron_golem.attack", 1.6f, 0.6f);
                    snd(p.getLocation(), "block.copper.hit", 1.4f, 0.6f);
                }
            }
            if (t == hitEnd) {
                // the tip hits the ground at the end of its arc
                Location tip = center.clone().add(back.clone().rotateAroundY(Math.toRadians(-to)).multiply(3.3));
                tip.setY(groundY(w, tip.getX(), tip.getY(), tip.getZ()));
                snd(tip, "entity.generic.big_fall", 1.6f, 0.6f);
                snd(tip, "block.copper.break", 1.4f, 0.6f);
                fx.crater(tip, 2.2, 40, 0.3, Material.COBBLED_DEEPSLATE, Material.GRAVEL);
                fx.shockwave(tip, 2.5, 4, 0.3);
                fx.debris(tip.clone().add(0, 0.3, 0), 8, blockUnder(tip), 0.9);
                shake(tip, 8);
                if (!struck.isEmpty()) gain(ch, ab.number("gain", 5) * struck.size());
                gain(ch, -ab.number("spend", 10));
            }
            if (t == 52) {
                c.release();
                // its head turns back to its prey; a player still behind it: it turns on the spot
                if (target.isValid() && Mobs.fighting(target) && angleFromFront(e, target.getLocation()) > 100) {
                    c.hold(20);
                    c.animate("turn");
                    Location aim = target.getLocation();
                    float y0 = e.getLocation().getYaw(), y1 = yawTo(flat(e.getLocation(), aim));
                    float diff = ((y1 - y0) % 360 + 540) % 360 - 180;
                    timeline(e, 20, u -> {
                        if (u >= 2 && u <= 18) e.setRotation(y0 + diff * (u - 2) / 16f, 0);
                        if (u == 6 || u == 14) snd(e.getLocation(), "entity.ravager.step", 1.2f, 0.6f);
                        if (u == 20) c.release();
                    });
                }
            }
        });
    }

    // ---------------------------------------------------------------- drill charge

    private void drill(MobTemplate.Ability ab, RockPowers.Caster c, Charge ch, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double power = ab.number("ram-power", 8) * boost(ch, ab), speed = ab.number("speed", 0.55),
                distance = ab.number("distance", 14), width = ab.number("width", 1.8);
        int windup = 24, lock = (int) ab.number("lock", 16), maxRun = (int) Math.ceil(distance / speed) + 2;
        Vector[] dir = {flat(e.getLocation(), target.getLocation())};
        Set<UUID> struck = new HashSet<>();
        double[] run = {0};
        boolean[] done = {false};
        c.hold(windup + maxRun + 40);
        c.animate("charge_windup");
        snd(e.getLocation(), "entity.ravager.ambient", 1.6f, 0.6f);
        timeline(e, windup + maxRun, t -> {
            if (done[0]) return;
            Location at = e.getLocation();
            if (t <= lock && target.isValid() && Mobs.fighting(target)) {
                dir[0] = flat(at, target.getLocation());
                e.setRotation(yawTo(dir[0]), 0);
            }
            if (t == 8) {
                ch.scene = true;
                ch.glowing = true;
                c.glow(GLOW);
                snd(at, "entity.warden.heartbeat", 1.6f, 1.2f);
                snd(at, "block.copper_bulb.turn_on", 1.4f, 0.6f);
            }
            if (t >= 8 && t < windup && t % 4 == 0) snd(at, "entity.ravager.ambient", 1.2f, 0.6f + (t - 8) * 0.04f);
            if (t >= 14 && t <= 19 && t % 2 == 0) {
                snd(at, "block.gravel.break", 1.4f, 0.6f);
                w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.2, 0), 10, 0.6, 0.1, 0.6, 0, blockUnder(at));
            }
            // the line of the charge on the ground (copper while it can still turn, red once locked)
            if (t >= 8 && t < windup && t % 2 == 0) {
                Color col = t > lock ? RED : COPPER;
                for (double k = 2; k <= distance + 2; k += 0.8) {
                    Location p = at.clone().add(dir[0].clone().multiply(k));
                    p.setY(groundY(w, p.getX(), p.getY(), p.getZ()) + 0.15);
                    dust(p, col, 1.3f);
                }
            }
            if (t == windup) {
                c.loop("charge_run");
                snd(at, "entity.ravager.roar", 1.8f, 0.7f);
                snd(at, "entity.breeze.charge", 1.4f, 0.6f);
            }
            if (t <= windup) return;
            // charging: straight on, never turning
            Block wall = wallAhead(e, dir[0], 2.0 + speed);
            if (wall != null) {
                done[0] = true;
                c.stopLoop("charge_run");
                crash(ab, c, ch, wall);
                return;
            }
            Location now = step(e, dir[0], speed);
            run[0] += speed;
            if (t % 2 == 0) w.spawnParticle(Particle.BLOCK, now.clone().add(0, 0.1, 0), 10, 0.7, 0.05, 0.7, 0, blockUnder(now));
            if (t % 3 == 0) snd(now, "entity.ravager.step", 1.6f, 0.7f);
            if (t % 4 == 0) fx.crater(now, 1.4, 30, 0.2, Material.COBBLED_DEEPSLATE);
            Location head = now.clone().add(dir[0].clone().multiply(2.0));
            for (Player p : fighters(e, 5)) {
                if (struck.contains(p.getUniqueId())) continue;
                Location pl = p.getLocation();
                if (Math.hypot(pl.getX() - head.getX(), pl.getZ() - head.getZ()) > width || Math.abs(pl.getY() - now.getY()) > 2.5) continue;
                struck.add(p.getUniqueId());
                c.hit(p, power);
                Vector side = right(dir[0]);
                if (side.dot(pl.toVector().subtract(now.toVector())) < 0) side.multiply(-1);
                p.setVelocity(dir[0].clone().multiply(1.2).add(side.multiply(0.4)).setY(0.5));
                p.sendHurtAnimation(0);
                snd(pl, "entity.player.attack.knockback", 1.6f, 0.6f);
                snd(pl, "entity.zombie.attack_iron_door", 1.0f, 0.8f);
                gain(ch, ab.number("gain", 8));
            }
            if (run[0] >= distance || t >= windup + maxRun) {
                done[0] = true;
                c.stopLoop("charge_run");
                stop(c, ch);
            }
        });
    }

    /** Brakes after a charge that hit no wall: charge_stop (24 ticks), the head shake ends it. */
    private void stop(RockPowers.Caster c, Charge ch) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        c.animate("charge_stop");
        c.hold(26);
        snd(e.getLocation(), "block.gravel.break", 1.8f, 0.5f);
        snd(e.getLocation(), "entity.ravager.step", 1.6f, 0.5f);
        timeline(e, 26, t -> {
            if (t < 8) {
                step(e, forward(e), 0.25 * (8 - t) / 8);
                w.spawnParticle(Particle.BLOCK, e.getLocation().add(0, 0.1, 0), 12, 0.6, 0.05, 0.6, 0, blockUnder(e.getLocation()));
            }
            if (t == 12) ch.scene = false;      // the next Overcharge tick sets the outline again
            if (t == 14) snd(e.getLocation(), "block.copper.hit", 1.2f, 0.7f);
            if (t == 26) c.release();
        });
    }

    /** Rammed a wall: big impact, Overcharge gained, stunned (stun, 30 ticks), then recovers. */
    private void crash(MobTemplate.Ability ab, RockPowers.Caster c, Charge ch, Block wall) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int stun = (int) ab.number("stun", 30);
        c.animate("stun");
        c.hold(stun + 6);
        Location hit = wall.getLocation().add(0.5, 0.5, 0.5);
        BlockData data = wall.getBlockData();
        snd(hit, "entity.zombie.attack_iron_door", 2f, 0.5f);
        snd(hit, "block.anvil.land", 1.4f, 0.5f);
        snd(hit, "entity.generic.explode", 1.2f, 0.7f);
        snd(hit, "block.copper.break", 1.6f, 0.5f);
        w.spawnParticle(Particle.EXPLOSION, hit, 3, 0.4, 0.4, 0.4, 0);
        burst(hit, 24);
        fx.debris(hit, 14, data, 1.2);
        fx.crater(e.getLocation(), 3, 60, 0.4, Material.COBBLED_DEEPSLATE, Material.GRAVEL);
        fx.shockwave(e.getLocation(), 4, 6, 0.4);
        shake(hit, 14);
        gain(ch, ab.number("crash-gain", 25));
        timeline(e, stun + 6, t -> {
            if (t >= 4 && t <= stun - 6 && t % 2 == 0) {
                Location head = e.getLocation().add(forward(e).multiply(2.0)).add(0, 2.6, 0);
                for (int i = 0; i < 3; i++) {
                    double b = t * 0.35 + i * Math.PI * 2 / 3;
                    w.spawnParticle(Particle.WAX_ON, head.clone().add(Math.cos(b) * 0.7, 0, Math.sin(b) * 0.7), 1, 0, 0, 0, 0);
                }
            }
            if (t % 10 == 4 && t < stun) snd(e.getLocation(), "block.copper.step", 1.2f, 0.5f + t * 0.01f);
            if (t == stun - 4) snd(e.getLocation(), "entity.ravager.ambient", 1.4f, 0.7f);
            if (t == stun - 2) ch.scene = false;
            if (t == stun + 6) c.release();
        });
    }

    // ---------------------------------------------------------------- cataclysmic resonance (ultimate)

    private void resonance(MobTemplate.Ability ab, RockPowers.Caster c, Charge ch, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Location center = e.getLocation().clone();
        double wavePower = ab.number("wave-power", 6), eruptionPower = ab.number("eruption-power", 8);
        double inner = ab.number("inner", 4.5), ringIn = ab.number("ring-in", 7.5), ringOut = ab.number("ring-out", 12.5);
        double lead = RockPowers.leadIn(ab);
        int spokes = (int) ab.number("spokes", 6);
        double spokeWidth = ab.number("spoke-width", 1.6), spokeOffset = ThreadLocalRandom.current().nextDouble(0, 60);
        int impact = 54;
        int[] eruptions = {72, 86, 100};
        int warn = 14;
        Set<UUID> waved = new HashSet<>();
        ch.scene = true;
        ch.glowing = true;
        c.hold(130);
        c.animate("ultimate");
        c.glow(GLOW);
        title(e, ab, 60);
        snd(center, "block.trial_spawner.ominous_activate", 2f, 0.6f);
        snd(center, "entity.ravager.ambient", 1.8f, 0.5f);
        timeline(e, 130, t -> {
            if (t < 30) {
                // copper particles start circling around its body
                if (t % 2 == 0) {
                    for (int i = 0; i < 6; i++) {
                        double a = t * 0.25 + i * Math.PI / 3;
                        double rr = 3.2 - t * 0.05;
                        dust(center.clone().add(Math.cos(a) * rr, 0.6 + (i % 3) * 0.7, Math.sin(a) * rr), i % 2 == 0 ? COPPER : BRIGHT, 1.4f);
                    }
                }
                if (t % 10 == 0) snd(center, "block.amethyst_block.resonate", 1.6f, 0.5f + t * 0.01f);
            }
            if (t >= 30 && t < 50) {
                // resonance lines run out on the ground; a beat that quickens
                if (t % 2 == 0) {
                    double reach = 1 + (t - 30) * 0.6;
                    for (int i = 0; i < 8; i++) {
                        Vector d = new Vector(1, 0, 0).rotateAroundY(Math.toRadians(i * 45 + t * 2));
                        for (double k = 1; k <= reach; k += 1.0) {
                            Location p = center.clone().add(d.clone().multiply(k));
                            p.setY(groundY(w, p.getX(), p.getY(), p.getZ()) + 0.12);
                            dust(p, BRIGHT, 1.2f);
                        }
                    }
                    w.spawnParticle(Particle.ELECTRIC_SPARK, center.clone().add(0, 1.4, 0), 6, 1.0, 0.8, 1.0, 0.05);
                }
                if (t % (t < 42 ? 8 : 4) == 0) snd(center, "entity.warden.heartbeat", 2f, 0.8f);
                if (t == 40) snd(center, "block.beacon.power_select", 2f, 0.5f);
            }
            if (t == impact) {
                // both feet slam the ground
                snd(center, "item.mace.smash_ground_heavy", 2f, 0.5f);
                snd(center, "entity.generic.explode", 1.8f, 0.5f);
                snd(center, "block.anvil.land", 1.6f, 0.5f);
                snd(center, "block.copper.break", 2f, 0.5f);
                w.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1);
                burst(center.clone().add(0, 0.3, 0), 40);
                fx.crater(center, 6, 90, 0.5, Material.COBBLED_DEEPSLATE, Material.GRAVEL, Material.TUFF);
                fx.shockwave(center, 11, 10, 0.6);
                shake(center, 24);
            }
            if (t >= impact && t <= impact + 10) {
                // the ground wave: hurts who stands on the ground where it passes (jump over it)
                double rr = (t - impact) * 1.1;
                Fx.ring(center, Math.max(0.5, rr), BRIGHT, 1.6f, t * 0.1);
                for (Player p : fighters(e, 14)) {
                    if (waved.contains(p.getUniqueId())) continue;
                    double d = Math.hypot(p.getLocation().getX() - center.getX(), p.getLocation().getZ() - center.getZ());
                    if (Math.abs(d - rr) > 0.9 || airborne(p)) continue;
                    waved.add(p.getUniqueId());
                    c.hit(p, wavePower);
                    p.setVelocity(p.getLocation().toVector().subtract(center.toVector()).setY(0).normalize().multiply(0.5).setY(0.45));
                }
            }
            for (int k = 0; k < 3; k++) {
                int at = eruptions[k];
                if (t >= at - warn && t < at) {
                    if (t % 2 == 0) announce(k, center, inner, ringIn, ringOut, spokes, spokeWidth, spokeOffset, (at - t) / (double) warn);
                    if (t == at - warn) snd(center, "block.note_block.bell", 2f, 0.6f + k * 0.2f);
                    if (lead > 0 && (at - t) % 4 == 0 && at - t <= 12) {
                        for (Player p : fighters(e, ringOut + 2)) {
                            if (inZone(k, center, p.getLocation(), inner, ringIn, ringOut, spokes, spokeWidth, spokeOffset))
                                c.drain(p, eruptionPower, lead / 3);
                        }
                    }
                }
                if (t == at) erupt(ab, c, k, center, inner, ringIn, ringOut, spokes, spokeWidth, spokeOffset, eruptionPower, 1 - lead);
            }
            if (t == 110) {
                c.glow(-1);
                ch.glowing = false;
                ch.scene = false;
                ch.value = ab.number("after", 0);
                snd(center, "block.beacon.deactivate", 2f, 0.5f);
                snd(center, "block.amethyst_block.resonate", 1.6f, 0.4f);
            }
            if (t > 110 && t < 128 && t % 6 == 0) w.spawnParticle(Particle.CLOUD, mouth(e, ab, 2.6, 2.4), 3, 0.2, 0.1, 0.2, 0.01);
            if (t == 130) c.release();
        });
    }

    /** True when a place is in eruption zone k (0 disc around it, 1 spokes, 2 outer ring). */
    private static boolean inZone(int k, Location center, Location at, double inner, double ringIn, double ringOut,
                                  int spokes, double width, double offset) {
        double dx = at.getX() - center.getX(), dz = at.getZ() - center.getZ();
        double d = Math.hypot(dx, dz);
        if (Math.abs(at.getY() - center.getY()) > 3) return false;
        return switch (k) {
            case 0 -> d <= inner;
            case 2 -> d >= ringIn && d <= ringOut;
            default -> {
                if (d < 2.5 || d > ringOut + 0.5) yield false;
                for (int i = 0; i < spokes; i++) {
                    double a = Math.toRadians(offset + i * 360.0 / spokes);
                    double along = dx * Math.cos(a) + dz * Math.sin(a);
                    double across = Math.abs(-dx * Math.sin(a) + dz * Math.cos(a));
                    if (along > 0 && across <= width / 2) yield true;
                }
                yield false;
            }
        };
    }

    /** Copper warning lines on the ground for eruption k ({@code left}: 1 when shown, 0 at the eruption). */
    private static void announce(int k, Location center, double inner, double ringIn, double ringOut, int spokes,
                                 double width, double offset, double left) {
        World w = center.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        switch (k) {
            case 0 -> {
                Fx.ring(center, inner, RED, 1.5f, left * 3);
                Fx.ring(center, inner * left, BRIGHT, 1.1f, 0);
                for (int i = 0; i < 10; i++) {
                    double a = r.nextDouble(Math.PI * 2), d = Math.sqrt(r.nextDouble()) * inner;
                    Location p = center.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d);
                    p.setY(groundY(w, p.getX(), p.getY(), p.getZ()) + 0.12);
                    dust(p, COPPER, 1.2f);
                }
            }
            case 2 -> {
                Fx.ring(center, ringIn, RED, 1.5f, left * 3);
                Fx.ring(center, ringOut, RED, 1.5f, -left * 3);
                Fx.ring(center, ringIn + (ringOut - ringIn) * (1 - left), BRIGHT, 1.1f, 0);
            }
            default -> {
                for (int i = 0; i < spokes; i++) {
                    double a = Math.toRadians(offset + i * 360.0 / spokes);
                    Vector d = new Vector(Math.cos(a), 0, Math.sin(a)), side = new Vector(-Math.sin(a), 0, Math.cos(a));
                    for (double s = 2.5; s <= ringOut; s += 0.9) {
                        for (int edge = -1; edge <= 1; edge += 2) {
                            Location p = center.clone().add(d.clone().multiply(s)).add(side.clone().multiply(edge * width / 2));
                            p.setY(groundY(w, p.getX(), p.getY(), p.getZ()) + 0.12);
                            dust(p, s / ringOut < 1 - left ? BRIGHT : RED, 1.2f);
                        }
                    }
                }
            }
        }
    }

    /** Eruption k: copper and mineral spikes burst out of the zone; hurts, lifts and slows who is in it. */
    private void erupt(MobTemplate.Ability ab, RockPowers.Caster c, int k, Location center, double inner, double ringIn,
                       double ringOut, int spokes, double width, double offset, double power, double share) {
        LivingEntity e = c.entity();
        World w = center.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        snd(center, "block.pointed_dripstone.land", 2f, 0.5f);
        snd(center, "block.deepslate.break", 2f, 0.5f);
        snd(center, "entity.ravager.roar", 1.2f, 0.8f + k * 0.1f);
        int spikes = 0;
        for (double x = -ringOut; x <= ringOut; x += 1.5) {
            for (double z = -ringOut; z <= ringOut; z += 1.5) {
                Location p = center.clone().add(x + r.nextDouble(-0.4, 0.4), 0, z + r.nextDouble(-0.4, 0.4));
                if (!inZone(k, center, p, inner, ringIn, ringOut, spokes, width, offset)) continue;
                p.setY(groundY(w, p.getX(), p.getY(), p.getZ()));
                w.spawnParticle(Particle.BLOCK, p.clone().add(0, 0.3, 0), 5, 0.3, 0.3, 0.3, 0,
                        (r.nextBoolean() ? VEIN : Material.DEEPSLATE_COPPER_ORE).createBlockData());
                if (r.nextInt(3) == 0) dust(p.clone().add(0, 0.6, 0), BRIGHT, 1.6f);
                if (spikes < 14 && r.nextInt(4) == 0) {
                    spike(p);
                    spikes++;
                }
            }
        }
        if (k == 0) fx.crater(center, inner, 50, 0.4, Material.COBBLED_DEEPSLATE, Material.GRAVEL);
        shake(center, ringOut + 4);
        for (Player p : fighters(e, ringOut + 2)) {
            if (!inZone(k, center, p.getLocation(), inner, ringIn, ringOut, spokes, width, offset)) continue;
            c.hitShare(p, power, share);
            p.setVelocity(p.getVelocity().setY(0.6));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 15, 1));
        }
    }

    /** A mineral spike (pointed dripstone) bursting out of the ground and sinking back. */
    private void spike(Location ground) {
        World w = ground.getWorld();
        float h = (float) ThreadLocalRandom.current().nextDouble(1.2, 2.2);
        BlockDisplay d = w.spawn(ground.clone().add(-0.3, -h, -0.3), BlockDisplay.class, it -> {
            it.setBlock(Material.POINTED_DRIPSTONE.createBlockData());
            it.setPersistent(false);
            it.addScoreboardTag(TAG);
            it.setBrightness(new Display.Brightness(12, 15));
            it.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.6f, h, 0.6f), new Quaternionf()));
            it.setTeleportDuration(3);
        });
        spawned.add(d);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (d.isValid()) d.teleport(ground.clone().add(-0.3, -0.1, -0.3));
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (d.isValid()) d.teleport(ground.clone().add(-0.3, -h - 0.2, -0.3));
        }, 22L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (d.isValid()) d.remove();
            spawned.remove(d);
        }, 27L);
    }

    // ---------------------------------------------------------------- copper absorption

    private Block nearestVein(LivingEntity e, Charge ch, MobTemplate.Ability ab) {
        int now = Bukkit.getCurrentTick();
        if (ch.veins == null || now - ch.scanned > 200) {
            ch.scanned = now;
            ch.veins = new ArrayList<>();
            Location h = ch.home != null ? ch.home : e.getLocation();
            int r = (int) ab.number("range", 22);
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    for (int y = -2; y <= 4; y++) {
                        Block b = h.getWorld().getBlockAt(h.getBlockX() + x, h.getBlockY() + y, h.getBlockZ() + z);
                        if (b.getType() == VEIN) ch.veins.add(b);
                    }
                }
            }
        }
        ch.veins.removeIf(b -> b.getType() != VEIN);
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (Block b : ch.veins) {
            double d = b.getLocation().distanceSquared(e.getLocation());
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    private void absorb(MobTemplate.Ability ab, RockPowers.Caster c, Charge ch) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Block vein = nearestVein(e, ch, ab);
        if (vein == null) return;
        Location target = vein.getLocation().add(0.5, 0, 0.5);
        int walkMax = (int) ab.number("walk-ticks", 60);
        double speed = ab.number("walk-speed", 1.6) / 20, reach = ab.number("reach", 2.6);
        double maxHp = e.getAttribute(Attribute.MAX_HEALTH) == null ? 20 : e.getAttribute(Attribute.MAX_HEALTH).getValue();
        double interrupt = ab.number("interrupt", 0.05) * maxHp;
        int[] phase = {0, 0};          // 0 walking, 1 absorbing (start tick), 2 done
        double[] hp = {0};
        c.hold(walkMax + 50);
        c.loop("walk");
        snd(e.getLocation(), "entity.ravager.ambient", 1.4f, 0.6f);
        timeline(e, walkMax + 46, t -> {
            if (phase[0] == 2) return;
            Location at = e.getLocation();
            if (phase[0] == 0) {
                Vector to = target.toVector().subtract(at.toVector()).setY(0);
                if (to.length() > reach && t < walkMax && vein.getType() == VEIN && wallAhead(e, to.clone().normalize(), 1.2) == null) {
                    step(e, to.normalize(), speed);
                    return;
                }
                c.stopLoop("walk");
                if (vein.getType() != VEIN) {
                    phase[0] = 2;
                    c.release();
                    return;
                }
                face(e, target);
                c.animate("absorb");
                phase[0] = 1;
                phase[1] = t;
                hp[0] = e.getHealth();
                snd(at, "block.beacon.ambient", 2f, 0.5f);
                return;
            }
            int u = t - phase[1];
            Location m = mouth(e, ab, 2.2, 1.0);
            if (u >= 6 && u <= 32) {
                // copper energy flows from the vein into its body; the vein cracks
                for (int i = 0; i < 3; i++) {
                    double k = ((u * 0.08) + i / 3.0) % 1.0;
                    Location p = target.clone().add(0, 0.6, 0).add(m.toVector().subtract(target.clone().add(0, 0.6, 0).toVector()).multiply(k));
                    dust(p, i == 0 ? BRIGHT : COPPER, 1.4f);
                }
                w.spawnParticle(Particle.BLOCK, target.clone().add(0, 1.0, 0), 2, 0.3, 0.2, 0.3, 0, VEIN.createBlockData());
                for (Player p : w.getPlayers()) {
                    if (p.getLocation().distanceSquared(target) < 48 * 48)
                        p.sendBlockDamage(vein.getLocation(), Math.min(0.9f, u / 32f), vein.hashCode());
                }
                if (u % 6 == 0) snd(at, "block.copper_bulb.turn_on", 1.2f, 0.5f + u * 0.02f);
                if (u % 8 == 0) snd(at, "block.amethyst_block.resonate", 1.2f, 0.6f);
            }
            // broken vein, or hurt enough meanwhile: interrupted, stunned
            if (u >= 6 && u < 32 && (vein.getType() != VEIN || hp[0] - e.getHealth() >= interrupt)) {
                phase[0] = 2;
                clearCrack(w, vein);
                snd(at, "block.copper.break", 2f, 0.5f);
                snd(at, "entity.ravager.hurt", 1.6f, 0.6f);
                burst(target.clone().add(0, 0.8, 0), 20);
                for (Player p : fighters(e, 30)) {
                    p.sendActionBar(fr.influencraft.flux.api.text.Texts.parse(ab.text("interrupted", "&#FFD94DAbsorption interrompue !")));
                }
                c.animate("stun");
                c.hold(32);
                Bukkit.getScheduler().runTaskLater(plugin, c::release, 32L);
                return;
            }
            if (u == 32) {
                clearCrack(w, vein);
                gain(ch, ab.number("gain", 35));
                if (vein.getType() == VEIN) vein.setType(DRAINED);
                burst(target.clone().add(0, 0.8, 0), 16);
                snd(at, "block.beacon.power_select", 1.6f, 0.7f);
                snd(at, "entity.ravager.ambient", 1.6f, 0.6f);
            }
            if (u >= 40) {
                phase[0] = 2;
                c.release();
            }
        });
    }

    private static void clearCrack(World w, Block vein) {
        for (Player p : w.getPlayers()) p.sendBlockDamage(vein.getLocation(), 0f, vein.hashCode());
    }

    // ---------------------------------------------------------------- phases

    private void phase(MobTemplate.Ability ab, RockPowers.Caster c, Charge ch) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        String animation = ab.text("animation", "phase_shift");
        int ticks = (int) ab.number("ticks", 40), stomp = (int) ab.number("stomp", 30);
        c.hold(ticks);
        c.animate(animation);
        title(e, ab, ticks);
        snd(e.getLocation(), "entity.ravager.roar", 2.2f, ab.number("pitch", 0.55) > 0 ? (float) ab.number("pitch", 0.55) : 0.55f);
        snd(e.getLocation(), "entity.iron_golem.damage", 1.4f, 0.45f);
        ch.gain = ab.number("gain-multiplier", 1.3);
        gain(ch, ab.number("charge", 15));
        double bonus = ab.number("speed-bonus", 0);
        AttributeInstance speed = e.getAttribute(Attribute.MOVEMENT_SPEED);
        if (bonus > 0 && speed != null) {
            NamespacedKey key = new NamespacedKey(plugin, "phase_" + ab.text("spell", "copper"));
            speed.removeModifier(key);
            speed.addModifier(new AttributeModifier(key, bonus, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
        timeline(e, ticks, t -> {
            Location at = e.getLocation();
            if (t < stomp && t % 3 == 0) w.spawnParticle(Particle.DUST, at.clone().add(0, 0.3, 0), 6, 1.4, 0.1, 1.4, 0, new Particle.DustOptions(BRIGHT, 1.4f));
            if (t == stomp) {
                snd(at, "entity.ravager.step", 2f, 0.5f);
                snd(at, "item.mace.smash_ground", 1.6f, 0.6f);
                fx.shockwave(at, 6, 8, 0.45);
                fx.crater(at, 4, 60, 0.4, Material.COBBLED_DEEPSLATE, Material.GRAVEL);
                w.spawnParticle(Particle.CLOUD, at.clone().add(0, 0.3, 0), 40, 3, 0.1, 3, 0.05);
                burst(at.clone().add(0, 0.5, 0), 24);
                shake(at, 16);
            }
            if (t == ticks) c.release();
        });
    }
}
