package fr.influencraft.fluxdungeons.mob;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vex;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Abilities of the Oversized Evoker, an enchanter who changes sizes: he shrinks players, enlarges them, sends vexes
 * and, for his ultimate, grows huge while everybody else turns tiny (fangs then rise around him). Every timing
 * follows his model's animations tick for tick (docs/technique/boss/evocateur-demesure.md,
 * assets/monstres/evocateur_demesure_anim.py):
 * <ul>
 *   <li>{@code evo_intro} (intro 130): first move of the fight, he notices the players; cannot be hurt;</li>
 *   <li>{@code evo_pinch} (attack_pinch 30): a ring closes on a place locked at tick 6, the hand snaps at 13: whoever
 *   stands there is hurt and shrunk;</li>
 *   <li>{@code evo_expand} (attack_expand 46): a sphere grows between his hands, the place is locked at 20, released
 *   at 26: whoever stands there is hurt, enlarged, slowed and pushed out;</li>
 *   <li>{@code evo_summon} (attack_summon 62): a rift opens above his left hand (6), vexes come out (24, 28, 32),
 *   he sends them at 43, the rift closes (47-54);</li>
 *   <li>{@code evo_decree} (ultimate_decree 136): he grows from tick 36 to 60 while the players shrink, closes his
 *   fist at 78, brings it down at 88: rings of fangs run out from him, each with two safe lanes;</li>
 *   <li>{@code evo_restore} (shrink_back 50): back to his size (12-36) once his time as a giant is over.</li>
 * </ul>
 * Sizes are the scale attribute: his own base value, a transient modifier on players (never saved, removed when its
 * time is over, when the player leaves the fight or when no evoker is left). He runs after players who stay away.
 */
final class EvokerPowers {

    static final Set<String> TYPES = Set.of("evo_intro", "evo_pinch", "evo_expand", "evo_summon", "evo_decree", "evo_restore");
    private static final Color VIOLET = Color.fromRGB(0x72538C), DEEP = Color.fromRGB(0x493064), BLUE = Color.fromRGB(0x8198E8),
            PALE = Color.fromRGB(0xE1E7FF), GOLD = Color.fromRGB(0xD2B66D);
    private static final NamespacedKey SIZE = new NamespacedKey("fluxdungeons", "evoker_size");
    private static final NamespacedKey RUN = new NamespacedKey("fluxdungeons", "evoker_run");
    /** Movement attribute bonus that turns his walk (1.6 blocks a second) into his run (4.4): speed follows its square. */
    private static final double RUN_BONUS = 0.66;

    /** What the game knows of one evoker. */
    private static final class State {
        double baseScale = 1;
        boolean giant, running;
        long giantUntil, busyUntil;
    }

    /** A player whose size was changed. */
    private static final class Sized {
        double now = 1, goal = 1;
        long until;
    }

    private final Plugin plugin;
    private final Mobs mobs;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, LivingEntity> evokers = new HashMap<>();
    private final Map<UUID, Sized> sized = new HashMap<>();

    EvokerPowers(Plugin plugin, Mobs mobs) {
        this.plugin = plugin;
        this.mobs = mobs;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    boolean cast(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        if (!TYPES.contains(ab.type())) return false;
        State st = state(c.entity());
        switch (ab.type()) {
            case "evo_intro" -> intro(ab, c, st);
            case "evo_pinch" -> pinch(ab, c, st, target);
            case "evo_expand" -> expand(ab, c, st, target);
            case "evo_summon" -> summon(ab, c, st, target);
            case "evo_decree" -> decree(ab, c, st);
            case "evo_restore" -> restore(c, st);
            default -> {
            }
        }
        return true;
    }

    /** The ultimate only at his own size, the way back only once his time as a giant is over. */
    boolean worth(MobTemplate.Ability ab, LivingEntity e) {
        if (!TYPES.contains(ab.type())) return true;
        State st = state(e);
        if ("evo_decree".equals(ab.type())) return !st.giant;
        if ("evo_restore".equals(ab.type())) return st.giant && System.currentTimeMillis() >= st.giantUntil;
        return true;
    }

    void clear() {
        for (UUID id : sized.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) apply(p, 1);
        }
        sized.clear();
        states.clear();
        evokers.clear();
    }

    private State state(LivingEntity e) {
        if (!evokers.containsKey(e.getUniqueId())) {
            evokers.put(e.getUniqueId(), e);
            State st = new State();
            AttributeInstance scale = e.getAttribute(Attribute.SCALE);
            if (scale != null) st.baseScale = scale.getBaseValue();
            states.put(e.getUniqueId(), st);
        }
        return states.get(e.getUniqueId());
    }

    // ---------------------------------------------------------------- every 2 ticks: sizes of players, his run

    private void tick() {
        evokers.values().removeIf(e -> !e.isValid() || e.isDead());
        states.keySet().removeIf(u -> !evokers.containsKey(u));
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Sized>> it = sized.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Sized> en = it.next();
            Sized s = en.getValue();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            // back to normal when the time is over, out of the fight, or once no evoker is left near him
            if (now >= s.until || !Mobs.fighting(p) || !near(p)) s.goal = 1;
            double step = 0.07;
            s.now = Math.abs(s.goal - s.now) <= step ? s.goal : s.now + Math.signum(s.goal - s.now) * step;
            apply(p, s.now);
            if (s.now == 1 && s.goal == 1) it.remove();
        }
        for (LivingEntity e : evokers.values()) {
            State st = states.get(e.getUniqueId());
            boolean run = false;
            if (now >= st.busyUntil) {
                double nearest = Double.MAX_VALUE;
                for (Player p : fighters(e.getLocation(), 48)) nearest = Math.min(nearest, p.getLocation().distance(e.getLocation()));
                // runs after whoever stays away, walks again once close (two thresholds: no flicker)
                run = nearest < 48 && (st.running ? nearest > 6 : nearest > 10);
            }
            if (run != st.running) running(e, st, run);
        }
    }

    private boolean near(Player p) {
        for (LivingEntity e : evokers.values()) if (e.getWorld() == p.getWorld()) return true;
        return false;
    }

    private void running(LivingEntity e, State st, boolean on) {
        st.running = on;
        AttributeInstance speed = e.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(RUN);
            if (on) speed.addTransientModifier(new AttributeModifier(RUN, RUN_BONUS, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
        mobs.loop(e, "run", on);
    }

    /** The player grows or shrinks towards {@code factor} (1 = his own size) and stays so for {@code ticks}. */
    private void resize(Player p, double factor, int ticks) {
        Sized s = sized.computeIfAbsent(p.getUniqueId(), k -> new Sized());
        s.goal = factor;
        s.until = System.currentTimeMillis() + ticks * 50L;
    }

    private static void apply(Player p, double factor) {
        AttributeInstance scale = p.getAttribute(Attribute.SCALE);
        if (scale == null) return;
        scale.removeModifier(SIZE);
        if (Math.abs(factor - 1) > 1e-3) {
            scale.addTransientModifier(new AttributeModifier(SIZE, factor - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
    }

    /** How much bigger than his own size he is now (1, or more as a giant). */
    private static double grown(LivingEntity e, State st) {
        AttributeInstance scale = e.getAttribute(Attribute.SCALE);
        return scale == null || st.baseScale <= 0 ? 1 : scale.getBaseValue() / st.baseScale;
    }

    private void busy(RockPowers.Caster c, State st, int ticks) {
        c.hold(ticks + 2);
        st.busyUntil = System.currentTimeMillis() + (ticks + 2) * 50L;
        if (st.running) running(c.entity(), st, false);
    }

    // ---------------------------------------------------------------- first meeting

    private void intro(MobTemplate.Ability ab, RockPowers.Caster c, State st) {
        LivingEntity e = c.entity();
        busy(c, st, 130);
        c.animate("intro");
        e.setInvulnerable(true);
        title(e.getLocation(), ab, 90);
        timeline(e, 130, t -> {
            Location hand = palm(e, st, 1);
            if (t == 24) snd(e.getLocation(), "entity.evoker.ambient", 1.6f, 0.7f);
            if (t == 66 || t == 72 || t == 78) {
                snd(hand, "block.amethyst_block.chime", 1.6f, 0.8f + 0.2f * (t - 66) / 6);
                dust(hand, BLUE, 1.0f, 6, 0.25);
                dust(hand, GOLD, 0.7f, 3, 0.4);
            }
            if (t == 92) snd(hand, "block.amethyst_cluster.break", 1.4f, 0.7f);
            if (t == 112) snd(e.getLocation(), "entity.evoker.celebrate", 1.6f, 0.7f);
            if (t == 130) {
                e.setInvulnerable(false);
                c.release();
            }
        });
    }

    // ---------------------------------------------------------------- dimensional pinch

    private void pinch(MobTemplate.Ability ab, RockPowers.Caster c, State st, Player target) {
        LivingEntity e = c.entity();
        double radius = ab.number("radius", 2.2), reach = ab.number("reach", 7) * grown(e, st), power = ab.number("power", 5);
        double size = ab.number("size", 0.45);
        int lasts = (int) (ab.number("seconds", 8) * 20);
        Location[] zone = {null};
        busy(c, st, 30);
        c.animate("attack_pinch");
        snd(e.getLocation(), "entity.evoker.prepare_attack", 1.4f, 1.3f);
        timeline(e, 30, t -> {
            if (t <= 6 && target != null && target.isValid() && target.getWorld() == e.getWorld()) {
                // follows the target until the place is locked
                face(e, target.getLocation());
                zone[0] = within(e.getLocation(), target.getLocation(), reach);
            }
            if (zone[0] != null && t >= 2 && t <= 13) {
                // the ring closes on the place: space contracting
                double r = radius + 1.6 * (1 - Math.min(1, (t - 2) / 11.0));
                Fx.ring(zone[0].clone().add(0, 0.15, 0), radius, t > 6 ? VIOLET : DEEP, 1.1f, t * 0.2);
                inward(zone[0].clone().add(0, 0.9, 0), r, t);
            }
            if (t == 13 && zone[0] != null) {
                snd(zone[0], "block.amethyst_block.break", 1.8f, 0.6f);
                snd(zone[0], "entity.illusioner.mirror_move", 1.4f, 1.6f);
                zone[0].getWorld().spawnParticle(Particle.REVERSE_PORTAL, zone[0].clone().add(0, 1, 0), 40, 0.2, 0.4, 0.2, 0.6);
                for (Player p : fighters(zone[0], radius)) {
                    c.hit(p, power);
                    resize(p, size, lasts);
                    snd(p.getLocation(), "entity.puffer_fish.blow_out", 1.2f, 1.6f);
                }
            }
            if (t == 30) c.release();
        });
    }

    // ---------------------------------------------------------------- forced expansion

    private void expand(MobTemplate.Ability ab, RockPowers.Caster c, State st, Player target) {
        LivingEntity e = c.entity();
        double k = grown(e, st);
        double radius = ab.number("radius", 3.5), reach = ab.number("reach", 16) * k, power = ab.number("power", 6);
        double size = ab.number("size", 1.5);
        int lasts = (int) (ab.number("seconds", 8) * 20);
        Location[] zone = {null};
        busy(c, st, 46);
        c.animate("attack_expand");
        snd(e.getLocation(), "entity.evoker.prepare_summon", 1.4f, 1.2f);
        timeline(e, 46, t -> {
            if (t <= 20 && target != null && target.isValid() && target.getWorld() == e.getWorld()) {
                face(e, target.getLocation());
                zone[0] = within(e.getLocation(), target.getLocation(), reach);
            }
            if (t >= 9 && t <= 23) {
                // the sphere growing between his hands
                Location orb = e.getLocation().add(forward(e).multiply(1.5 * k)).add(0, 2.9 * k, 0);
                double grow = (t - 9) / 14.0;
                dust(orb, VIOLET, 0.8f + (float) grow, 4, 0.1 + 0.3 * grow);
                dust(orb, BLUE, 0.7f, 2, 0.1 + 0.35 * grow);
                if (t % 4 == 1) snd(orb, "block.beacon.ambient", 1.2f, 1.2f + (float) grow * 0.6f);
            }
            if (zone[0] != null && t >= 9 && t < 26) {
                // the place, its edge fixed, lines running outwards from its centre
                Fx.ring(zone[0].clone().add(0, 0.15, 0), radius, t > 20 ? VIOLET : DEEP, 1.1f, t * 0.2);
                Fx.ring(zone[0].clone().add(0, 0.2, 0), radius * ((t % 8) + 1) / 8.0, BLUE, 0.7f, t * 0.4);
            }
            if (t == 26 && zone[0] != null) {
                snd(zone[0], "entity.illusioner.cast_spell", 1.8f, 0.6f);
                snd(zone[0], "block.amethyst_block.resonate", 1.8f, 0.6f);
                zone[0].getWorld().spawnParticle(Particle.END_ROD, zone[0].clone().add(0, 1, 0), 30, 0.2, 0.3, 0.2, 0.25);
                for (Player p : fighters(zone[0], radius)) {
                    c.hit(p, power);
                    resize(p, size, lasts);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, lasts, 1, false, false, true));
                    throwFrom(p, zone[0], 0.8, 0.4);
                }
            }
            if (zone[0] != null && t > 26 && t <= 32) {
                Fx.ring(zone[0].clone().add(0, 0.3, 0), radius * (1 + (t - 26) / 6.0 * 0.6), PALE, 1.0f, t * 0.3);
            }
            if (t == 46) c.release();
        });
    }

    // ---------------------------------------------------------------- vexes

    private void summon(MobTemplate.Ability ab, RockPowers.Caster c, State st, Player target) {
        LivingEntity e = c.entity();
        double k = grown(e, st);
        int count = (int) ab.number("count", 3), max = (int) ab.number("max", 6);
        int life = (int) (ab.number("life", 25) * 20);
        String mob = ab.text("mob", "evo_vex");
        List<LivingEntity> out = new ArrayList<>();
        busy(c, st, 62);
        c.animate("attack_summon");
        if (target != null && target.isValid()) face(e, target.getLocation());
        snd(e.getLocation(), "entity.evoker.prepare_summon", 1.6f, 0.9f);
        timeline(e, 62, t -> {
            Location rift = palm(e, st, -1).add(0, 1.2 * k, 0);
            if (t >= 6 && t <= 54) {
                // the rift: a violet ring, a blue heart, gold fragments turning around it
                double open = t < 20 ? (t - 6) / 14.0 * 0.7 : t < 36 ? 0.7 + (t - 20) / 16.0 * 0.5 : t < 47 ? 1.2 : 1.2 * (54 - t) / 7.0;
                Fx.ring(rift, Math.max(0.1, open * k), VIOLET, 1.0f, t * 0.35);
                dust(rift, BLUE, 1.2f, 2, 0.08);
                double a = t * 0.5;
                dust(rift.clone().add(Math.cos(a) * open * k * 1.3, 0.2, Math.sin(a) * open * k * 1.3), GOLD, 0.7f, 1, 0.02);
                dust(rift.clone().add(-Math.cos(a) * open * k * 1.3, -0.2, -Math.sin(a) * open * k * 1.3), GOLD, 0.7f, 1, 0.02);
            }
            int index = t == 24 ? 0 : t == 28 ? 1 : t == 32 ? 2 : -1;
            if (index >= 0 && index < count && mobs.summonsOf(e) < max) {
                Location at = rift.clone().add(Math.cos(index * 2.1) * 0.8, 0.3, Math.sin(index * 2.1) * 0.8);
                at.getWorld().spawnParticle(Particle.SOUL, at, 12, 0.2, 0.3, 0.2, 0.02);
                snd(at, "entity.vex.ambient", 1.4f, 0.8f);
                LivingEntity vex = mobs.summonFor(e, mob, at);
                if (vex != null) {
                    // held near the rift until he gives the order
                    vex.setAI(false);
                    if (vex instanceof Vex v) v.setLimitedLifetimeTicks(life);
                    out.add(vex);
                }
            }
            if (t == 43) {
                snd(e.getLocation(), "entity.vex.charge", 1.8f, 0.8f);
                for (LivingEntity vex : out) {
                    if (!vex.isValid()) continue;
                    vex.setAI(true);
                    if (vex instanceof Mob m && target != null && target.isValid()) m.setTarget(target);
                }
            }
            if (t == 47) snd(rift, "block.amethyst_cluster.break", 1.2f, 0.6f);
            if (t == 62) c.release();
        });
    }

    // ---------------------------------------------------------------- ultimate: he grows, they shrink, fangs

    private void decree(MobTemplate.Ability ab, RockPowers.Caster c, State st) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Location at = e.getLocation().clone();
        double grow = ab.number("grow", 1.6), tiny = ab.number("size", 0.45), area = ab.number("area", 30);
        int giant = (int) (ab.number("giant-seconds", 20) * 20);
        int rings = (int) ab.number("rings", 5);
        double first = ab.number("ring-first", 4), gap = ab.number("ring-gap", 2.4), lane = ab.number("lane", 50);
        AttributeInstance scale = e.getAttribute(Attribute.SCALE);
        busy(c, st, 136);
        c.animate("ultimate_decree");
        e.setInvulnerable(true);
        title(at, ab, 80);
        snd(at, "entity.evoker.prepare_wololo", 2f, 0.6f);
        st.giant = true;
        st.giantUntil = System.currentTimeMillis() + (136 + giant) * 50L;
        timeline(e, 136, t -> {
            if (t >= 16 && t <= 60 && t % 2 == 0) {
                // measuring lines standing around him, gold marks along them: a ruler about to break
                double high = 4.5 * grown(e, st) + 1;
                for (int i = 0; i < 6; i++) {
                    double a = i * Math.PI / 3 + t * 0.02;
                    Location base = at.clone().add(Math.cos(a) * 2.6, 0, Math.sin(a) * 2.6);
                    double y = (t * 0.25 + i) % high;
                    dust(base.clone().add(0, y, 0), VIOLET, 1.0f, 1, 0);
                    dust(base.clone().add(0, (y + high / 2) % high, 0), GOLD, 0.8f, 1, 0);
                }
            }
            if (t == 36) {
                snd(at, "block.beacon.power_select", 2f, 0.5f);
                for (Player p : fighters(at, area)) resize(p, tiny, 136 - 36 + giant);
            }
            if (t >= 36 && t <= 60 && scale != null) {
                double u = (t - 36) / 24.0;
                scale.setBaseValue(st.baseScale * (1 + (grow - 1) * u * u * (3 - 2 * u)));
            }
            if (t == 78) {
                snd(at, "block.amethyst_block.break", 2f, 0.5f);
                snd(at, "entity.evoker.cast_spell", 2f, 0.6f);
                dust(at.clone().add(0, 6.5 * grow, 0), PALE, 1.6f, 14, 0.4);
            }
            if (t >= 88 && (t - 88) % 4 == 0 && (t - 88) / 4 < rings) {
                int ring = (t - 88) / 4;
                fangRing(e, at, first + gap * ring, lane, 35.0 * ring);
                snd(at, "entity.evoker_fangs.attack", 2f, 0.5f + 0.08f * ring);
            }
            if (t >= 88 && t <= 104) Fx.ring(at.clone().add(0, 0.2, 0), (t - 88) * 0.9 + 1, PALE, 1.2f, t * 0.3);
            if (t == 88) {
                snd(at, "entity.warden.sonic_boom", 1.6f, 0.6f);
                for (Player p : fighters(at, area)) p.sendHurtAnimation(0);
            }
            if (t == 110) e.setInvulnerable(false);
            if (t == 136) c.release();
        });
    }

    /** A ring of fangs around him, two opposite lanes left free ({@code lane} degrees wide, turned by {@code turn}). */
    private void fangRing(LivingEntity owner, Location center, double radius, double lane, double turn) {
        int n = Math.max(8, (int) Math.round(2 * Math.PI * radius / 1.25));
        for (int i = 0; i < n; i++) {
            double deg = 360.0 * i / n;
            double off = Math.abs(((deg - turn) % 180 + 180) % 180 - 90);
            if (off > 90 - lane / 2) continue;
            double a = Math.toRadians(deg);
            Location l = onGround(center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius));
            if (l == null) continue;
            l.setYaw((float) deg);
            center.getWorld().spawn(l, EvokerFangs.class, f -> {
                f.setOwner(owner);
                f.setAttackDelay(8);
            });
        }
    }

    private void restore(RockPowers.Caster c, State st) {
        LivingEntity e = c.entity();
        Location at = e.getLocation().clone();
        AttributeInstance scale = e.getAttribute(Attribute.SCALE);
        double from = scale == null ? st.baseScale : scale.getBaseValue();
        busy(c, st, 50);
        c.animate("shrink_back");
        snd(at, "block.beacon.deactivate", 1.8f, 0.7f);
        timeline(e, 50, t -> {
            if (t == 12) for (Player p : fighters(at, 64)) if (sized.containsKey(p.getUniqueId())) resize(p, 1, 0);
            if (t >= 12 && t <= 36) {
                double u = (t - 12) / 24.0;
                if (scale != null) scale.setBaseValue(from + (st.baseScale - from) * u * u * (3 - 2 * u));
                if (t % 2 == 0) Fx.ring(at.clone().add(0, 0.2, 0), 4 * (1 - u) + 0.5, VIOLET, 1.0f, t * 0.3);
            }
            if (t == 50) {
                st.giant = false;
                c.release();
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    /** Lines of the pinch: six points on a circle that closes, a little higher each turn. */
    private static void inward(Location center, double radius, int t) {
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3 + t * 0.15;
            dust(center.clone().add(Math.cos(a) * radius, 0.4 * Math.sin(t * 0.6 + i), Math.sin(a) * radius), BLUE, 0.9f, 1, 0);
        }
    }

    /** Where a palm is: {@code side} 1 his right hand (raised in front of him), -1 his left one raised for a summon. */
    private static Location palm(LivingEntity e, State st, int side) {
        double k = grown(e, st);
        Vector f = forward(e), r = right(f);
        return e.getLocation().add(f.multiply(1.1 * k)).add(r.multiply(1.5 * side * k)).add(0, 3.3 * k, 0);
    }

    private static void dust(Location at, Color color, float size, int count, double spread) {
        at.getWorld().spawnParticle(Particle.DUST, at, count, spread, spread, spread, 0, new Particle.DustOptions(color, size));
    }

    private static void snd(Location at, String key, float volume, float pitch) {
        at.getWorld().playSound(at, key, SoundCategory.HOSTILE, volume, pitch);
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

    private static Vector right(Vector f) {
        return new Vector(-f.getZ(), 0, f.getX());
    }

    private static void face(LivingEntity e, Location to) {
        Vector v = to.toVector().subtract(e.getLocation().toVector()).setY(0);
        if (v.lengthSquared() < 1e-4) return;
        e.setRotation((float) Math.toDegrees(Math.atan2(-v.getX(), v.getZ())), 0);
    }

    /** The target's place, brought back within {@code reach} blocks of him, on the ground. */
    private static Location within(Location from, Location to, double reach) {
        Vector v = to.toVector().subtract(from.toVector()).setY(0);
        Location l = v.length() <= reach ? to.clone() : from.clone().add(v.normalize().multiply(reach));
        l.setY(to.getY());
        Location ground = onGround(l);
        return ground == null ? l : ground;
    }

    private static Location onGround(Location l) {
        Block b = Fx.ground(l.getWorld(), l.getBlockX(), l.getY() + 1, l.getBlockZ());
        if (b == null) return null;
        Location out = l.clone();
        out.setY(b.getY() + b.getBoundingBox().getHeight());
        return out;
    }

    private static void throwFrom(Player p, Location from, double out, double up) {
        Vector v = p.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (v.lengthSquared() < 1e-4) v = new Vector(1, 0, 0);
        p.setVelocity(v.normalize().multiply(out).setY(up));
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
