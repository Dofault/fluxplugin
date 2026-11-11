package fr.influencraft.fluxdungeons.mob;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Abilities of the Field Mole (King Taupérion), a huge mole whose head is a living drill. It fights as a digging
 * creature: it rams, sweeps with its drill, burrows and travels under the ground, surges under a player, and its
 * ultimate drives the drill into the ground to make it erupt. Every timing follows its model's animations tick for
 * tick (docs/technique/boss/taupe-champs.md, assets/monstres/taupe_champs_anim.py):
 * <ul>
 *   <li>{@code mole_ram} (charge_windup 28, charge_run, charge_stop / stun): lowers itself, shovels planted, drill
 *   spinning; the line of the rush on the ground, locked at {@code lock}; then a straight rush; into a wall:
 *   stunned;</li>
 *   <li>{@code mole_burrow} (burrow 44, emerge 40): digs in (earth bursting around it at 24, under the ground at 40),
 *   travels under the ground towards a player (its trail shows), the place where it will come out shakes for
 *   {@code warn} ticks, then it surges (impact at 8 of emerge); {@code surges} times in a row;</li>
 *   <li>{@code mole_sweep} (sweep 46): head turned to its right, the drill sweeps an arc in front of it from its right
 *   to its left between ticks 22 and 32; whoever the drill passes is hit and thrown to the side;</li>
 *   <li>{@code mole_quake} (ultimate 130): seismic drill: still, the ground cracks around it, the drill slams into
 *   the ground at 50 (a wave runs out: jump over it), earth erupts under the players at 70, 85 and 100 (each place
 *   announced), it sinks at 108-130, then a last powerful surge under the most crowded place;</li>
 *   <li>{@code mole_phase} (phase_shift 40): enraged: rears up, slams the ground at 28; faster, one more surge.</li>
 * </ul>
 * While it is under the ground its model is hidden and it cannot be hurt. Nothing ever changes in the map.
 */
final class MolePowers {

    static final Set<String> TYPES = Set.of("mole_ram", "mole_burrow", "mole_sweep", "mole_quake", "mole_phase");
    /** Bones of the model (hidden while it travels under the ground). */
    static final List<String> BONES = List.of("Body", "Head", "Drill", "Tail", "Arm_front_right", "Arm_front_left",
            "Paw_front_right", "Paw_front_left", "Leg_back_right", "Leg_back_left");
    private static final Color EARTH = Color.fromRGB(0x765642), WARN = Color.fromRGB(0xB49A78), RED = Color.fromRGB(0xFF5C5C),
            VIOLET = Color.fromRGB(0x7950A0);
    private static final org.bukkit.NamespacedKey SPEED = new org.bukkit.NamespacedKey("fluxdungeons", "mole_rage");

    /** What the game knows of one mole. */
    private static final class State {
        boolean hidden;
        int extraSurges;
        Location last;
        int ticks;
    }

    private final Plugin plugin;
    private final Fx fx;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, LivingEntity> moles = new HashMap<>();

    MolePowers(Plugin plugin, Fx fx) {
        this.plugin = plugin;
        this.fx = fx;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    boolean cast(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        if (!TYPES.contains(ab.type())) return false;
        State st = state(c.entity());
        switch (ab.type()) {
            case "mole_ram" -> ram(ab, c, target);
            case "mole_burrow" -> burrow(ab, c, st, target, (int) ab.number("surges", 1) + st.extraSurges);
            case "mole_sweep" -> sweep(ab, c, target);
            case "mole_quake" -> quake(ab, c, st, target);
            case "mole_phase" -> phase(ab, c, st);
            default -> {
            }
        }
        return true;
    }

    /** False when the ability has nobody to hit (the sweep needs a player in front of it). */
    boolean worth(MobTemplate.Ability ab, LivingEntity e) {
        if (!TYPES.contains(ab.type())) return true;
        state(e);
        if (states.get(e.getUniqueId()).hidden) return false;
        if ("mole_sweep".equals(ab.type())) return inFront(e, ab.number("radius", 5.2) + 0.5, 80);
        return true;
    }

    /** True while the mole travels under the ground (its model hidden). */
    boolean hidden(LivingEntity e) {
        State st = states.get(e.getUniqueId());
        return st != null && st.hidden;
    }

    void clear() {
        states.clear();
        moles.clear();
    }

    private State state(LivingEntity e) {
        moles.put(e.getUniqueId(), e);
        return states.computeIfAbsent(e.getUniqueId(), k -> new State());
    }

    /** Earth thrown by the shovels while it walks (every 4 ticks when it moved). */
    private void tick() {
        moles.values().removeIf(e -> !e.isValid());
        states.keySet().removeIf(u -> !moles.containsKey(u));
        for (LivingEntity e : moles.values()) {
            State st = states.get(e.getUniqueId());
            if (st == null || st.hidden) continue;
            st.ticks += 2;
            Location at = e.getLocation();
            boolean moved = st.last != null && st.last.getWorld() == at.getWorld() && st.last.distanceSquared(at) > 0.01;
            st.last = at.clone();
            if (!moved || st.ticks % 4 != 0) continue;
            Vector f = forward(e), r = right(f);
            BlockData earth = blockUnder(at);
            int side = (st.ticks / 4) % 2 == 0 ? 1 : -1;
            Location paw = at.clone().add(f.clone().multiply(1.4)).add(r.clone().multiply(1.4 * side)).add(0, 0.15, 0);
            fx.spray(paw, f.clone().multiply(-1).setY(0.6), earth, 5);
        }
    }

    // ---------------------------------------------------------------- shared

    private static void snd(Location at, String key, float volume, float pitch) {
        at.getWorld().playSound(at, key, SoundCategory.HOSTILE, volume, pitch);
    }

    private static BlockData blockUnder(Location at) {
        Block b = Fx.ground(at.getWorld(), at.getBlockX(), at.getY(), at.getBlockZ());
        return b == null ? Material.DIRT.createBlockData() : b.getBlockData();
    }

    private static List<Player> fighters(LivingEntity e, double within) {
        return fighters(e.getLocation(), within);
    }

    private static List<Player> fighters(Location at, double within) {
        List<Player> out = new ArrayList<>();
        for (Player p : at.getWorld().getPlayers()) {
            if (Mobs.fighting(p) && p.getLocation().distanceSquared(at) <= within * within) out.add(p);
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

    /** Its right hand side. */
    private static Vector right(Vector f) {
        return new Vector(-f.getZ(), 0, f.getX());
    }

    /** Signed angle of a place from its front, in degrees: > 0 on its left, < 0 on its right. */
    private static double side(LivingEntity e, Location at) {
        Vector f = forward(e), to = flat(e.getLocation(), at);
        double cross = right(f).dot(to);
        return -Math.toDegrees(Math.atan2(cross, f.dot(to)));
    }

    private static boolean inFront(LivingEntity e, double reach, double half) {
        for (Player p : fighters(e, reach)) if (Math.abs(side(e, p.getLocation())) <= half) return true;
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

    private static Location onGround(Location l) {
        Location g = l.clone();
        g.setY(groundY(l.getWorld(), l.getX(), l.getY(), l.getZ()));
        return g;
    }

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

    private static Block wallAhead(LivingEntity e, Vector dir, double ahead) {
        Location front = e.getLocation().clone().add(dir.clone().multiply(ahead));
        for (double h : new double[]{0.6, 1.5}) {
            Block b = front.clone().add(0, h, 0).getBlock();
            if (b.isCollidable()) return b;
        }
        return null;
    }

    /** The point of its drill (in front of its face, at the height of its head). */
    private static Location tip(LivingEntity e, double ahead, double up) {
        return e.getLocation().clone().add(forward(e).multiply(ahead)).add(0, up, 0);
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

    /** Players thrown away from a point (and up). */
    private static void throwFrom(Player p, Location from, double out, double up) {
        Vector v = p.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (v.lengthSquared() < 1e-4) v = new Vector(ThreadLocalRandom.current().nextDouble(-1, 1), 0, ThreadLocalRandom.current().nextDouble(-1, 1));
        p.setVelocity(v.normalize().multiply(out).setY(up));
    }

    /** A place inside its arena: no more than {@code area} blocks from its home. */
    private static Location inside(Location at, Location home, double area) {
        Vector v = at.toVector().subtract(home.toVector()).setY(0);
        if (v.length() <= area) return at;
        Location l = home.clone().add(v.normalize().multiply(area));
        l.setY(at.getY());
        return onGround(l);
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

    private void hide(RockPowers.Caster c, State st) {
        st.hidden = true;
        c.parts(false, BONES);
        c.entity().setInvulnerable(true);
    }

    private void show(RockPowers.Caster c, State st) {
        st.hidden = false;
        c.parts(true, BONES);
    }

    // ---------------------------------------------------------------- frontal ram

    private void ram(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double power = ab.number("ram-power", 9), speed = ab.number("speed", 0.6), distance = ab.number("distance", 14),
                width = ab.number("width", 1.9);
        int windup = 28, lock = (int) ab.number("lock", 18), maxRun = (int) Math.ceil(distance / speed) + 2;
        Vector[] dir = {flat(e.getLocation(), target.getLocation())};
        Set<UUID> struck = new HashSet<>();
        double[] run = {0};
        boolean[] done = {false};
        c.hold(windup + maxRun + 40);
        c.animate("charge_windup");
        snd(e.getLocation(), "entity.ravager.ambient", 1.6f, 0.55f);
        timeline(e, windup + maxRun, t -> {
            if (done[0]) return;
            Location at = e.getLocation();
            if (t <= lock && target.isValid() && Mobs.fighting(target)) {
                dir[0] = flat(at, target.getLocation());
                e.setRotation(yawTo(dir[0]), 0);
            }
            Vector f = dir[0], r = right(f);
            BlockData earth = blockUnder(at);
            // shovels planted (6-14): earth thrown up on both sides
            if (t >= 6 && t <= 14 && t % 2 == 0) {
                for (int s = -1; s <= 1; s += 2) fx.spray(at.clone().add(f.clone().multiply(1.4)).add(r.clone().multiply(1.5 * s)),
                        r.clone().multiply(0.5 * s).setY(0.8), earth, 6);
                snd(at, "block.rooted_dirt.break", 1.3f, 0.6f);
            }
            if (t == 10) fx.crater(at.clone().add(f.clone().multiply(1.5)), 2.2, 40, 0.4, Fx.BROKEN_EARTH);
            // the drill revs up
            if (t >= 4 && t < windup && t % 3 == 0) snd(at, "block.grindstone.use", 1.2f, 0.5f + 0.5f * t / windup);
            if (t >= 4 && t < windup && t % 2 == 0) Fx.drillSpin(tip(e, 3.0, 1.0), f, 0.5, t, earth);
            // the line of the rush (earth while it can still turn, red once locked)
            if (t >= 4 && t < windup && t % 2 == 0) {
                Color col = t > lock ? RED : WARN;
                for (double k = 2; k <= distance + 2; k += 0.8) {
                    Location p = at.clone().add(f.clone().multiply(k));
                    p.setY(groundY(w, p.getX(), p.getY(), p.getZ()) + 0.15);
                    w.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.3f));
                    if (k + 0.8 > distance + 2) Fx.ring(p, width * 0.6, col, 1f, t * 0.2);
                }
            }
            if (t == 16) snd(at, "entity.ravager.roar", 1.4f, 0.55f);
            if (t == windup) {
                c.loop("charge_run");
                snd(at, "entity.ravager.roar", 1.8f, 0.6f);
                snd(at, "entity.breeze.charge", 1.4f, 0.5f);
            }
            if (t <= windup) return;
            // rushing in a straight line, never turning
            Block wall = wallAhead(e, f, 2.6 + speed);
            if (wall != null) {
                done[0] = true;
                c.stopLoop("charge_run");
                crash(ab, c, wall);
                return;
            }
            Location now = step(e, f, speed);
            run[0] += speed;
            fx.spray(now.clone().add(r.clone().multiply(1.6)), r.clone().setY(0.7), earth, 4);
            fx.spray(now.clone().add(r.clone().multiply(-1.6)), r.clone().multiply(-1).setY(0.7), earth, 4);
            Fx.drillSpin(tip(e, 3.0, 1.0), f, 0.55, t, earth);
            if (t % 3 == 0) {
                snd(now, "block.grindstone.use", 1.6f, 0.75f);
                snd(now, "entity.ravager.step", 1.4f, 0.6f);
                fx.crater(now, 1.6, 30, 0.35, Fx.BROKEN_EARTH);
            }
            Location head = now.clone().add(f.clone().multiply(2.4));
            for (Player p : fighters(e, 6)) {
                if (struck.contains(p.getUniqueId())) continue;
                Location pl = p.getLocation();
                if (Math.hypot(pl.getX() - head.getX(), pl.getZ() - head.getZ()) > width || Math.abs(pl.getY() - now.getY()) > 2.5) continue;
                struck.add(p.getUniqueId());
                c.hit(p, power);
                Vector out = r.clone();
                if (out.dot(pl.toVector().subtract(now.toVector())) < 0) out.multiply(-1);
                p.setVelocity(f.clone().multiply(1.3).add(out.multiply(0.5)).setY(0.6));
                snd(pl, "entity.player.attack.knockback", 1.6f, 0.6f);
                snd(pl, "block.rooted_dirt.break", 1.4f, 0.5f);
                fx.debris(pl.clone().add(0, 0.5, 0), 8, earth, 1.0);
            }
            if (run[0] >= distance || t >= windup + maxRun) {
                done[0] = true;
                c.stopLoop("charge_run");
                brake(c);
            }
        });
    }

    /** Brakes after a rush that met no wall (charge_stop, 24 ticks). */
    private void brake(RockPowers.Caster c) {
        LivingEntity e = c.entity();
        c.animate("charge_stop");
        c.hold(26);
        snd(e.getLocation(), "block.gravel.break", 1.8f, 0.5f);
        snd(e.getLocation(), "block.grindstone.use", 1.2f, 0.45f);
        timeline(e, 26, t -> {
            if (t < 8) {
                Location at = step(e, forward(e), 0.28 * (8 - t) / 8);
                fx.spray(at.clone().add(forward(e).multiply(1.6)), forward(e).setY(0.5), blockUnder(at), 6);
            }
            if (t == 26) c.release();
        });
    }

    /** Rammed a wall: the drill bites the wall, the gallery shakes, stunned (stun, 30 ticks). */
    private void crash(MobTemplate.Ability ab, RockPowers.Caster c, Block wall) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int stun = (int) ab.number("stun", 30);
        c.animate("stun");
        c.hold(stun + 6);
        Location hit = wall.getLocation().add(0.5, 0.5, 0.5);
        BlockData data = wall.getBlockData();
        snd(hit, "entity.generic.explode", 1.4f, 0.6f);
        snd(hit, "block.grindstone.use", 2f, 0.4f);
        snd(hit, "block.rooted_dirt.break", 2f, 0.5f);
        w.spawnParticle(Particle.EXPLOSION, hit, 2, 0.4, 0.4, 0.4, 0);
        fx.debris(hit, 16, data, 1.3);
        fx.collapse(e.getLocation(), 3, 8, 5, Material.DIRT.createBlockData());
        fx.crater(e.getLocation(), 3, 60, 0.4, Fx.BROKEN_EARTH);
        fx.shockwave(e.getLocation(), 4, 6, 0.4);
        shake(hit, 14);
        timeline(e, stun + 6, t -> {
            if (t >= 4 && t <= stun - 6 && t % 2 == 0) {
                Location head = tip(e, 2.0, 2.6);
                for (int i = 0; i < 3; i++) {
                    double b = t * 0.35 + i * Math.PI * 2 / 3;
                    w.spawnParticle(Particle.DUST, head.clone().add(Math.cos(b) * 0.7, 0, Math.sin(b) * 0.7), 1, 0, 0, 0, 0,
                            new Particle.DustOptions(VIOLET, 1f));
                }
            }
            if (t == stun - 4) snd(e.getLocation(), "entity.ravager.ambient", 1.4f, 0.6f);
            if (t == stun + 6) c.release();
        });
    }

    // ---------------------------------------------------------------- burrow, travel, surge

    /**
     * Digs in (burrow, 44 ticks: earth bursting around it at 24, under the ground at 40), then travels to a player and
     * surges under him; {@code left} surges in a row (it dives again after each one but the last).
     */
    private void burrow(MobTemplate.Ability ab, RockPowers.Caster c, State st, Player aimed, int left) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        // far-min: goes for the player furthest away when somebody keeps that far (archers do not stay safe)
        Player far = ab.number("far-min", 0) > 0 ? TidePowers.farthest(e, ab.number("far-min", 0)) : null;
        Player target = far != null ? far : aimed;
        double ringR = ab.number("ring-radius", 3.2), ringPower = ab.number("ring-power", 4);
        c.hold(44 + (int) ab.number("travel-ticks", 50) + (int) ab.number("warn", 26) + 60);
        c.animate("burrow");
        snd(e.getLocation(), "block.grindstone.use", 1.4f, 0.5f);
        timeline(e, 44, t -> {
            Location at = e.getLocation();
            Vector f = forward(e), r = right(f);
            BlockData earth = blockUnder(at);
            if (t < 40 && t % 2 == 0) Fx.drillSpin(tip(e, 2.6, 0.3), f.clone().setY(-1), 0.5, t, earth);
            if (t < 40 && t % 4 == 0) snd(at, "block.grindstone.use", 1.3f, 0.6f + 0.4f * t / 40);
            // the shovels throw the earth back (6-34)
            if (t >= 6 && t <= 34 && t % 2 == 0) {
                int s = (t / 2) % 2 == 0 ? 1 : -1;
                fx.spray(at.clone().add(f.clone().multiply(1.3)).add(r.clone().multiply(1.4 * s)), f.clone().multiply(-1).setY(0.9), earth, 7);
                if (t % 4 == 0) snd(at, "block.rooted_dirt.break", 1.3f, 0.7f);
            }
            if (t == 6) fx.crater(at.clone().add(f.clone().multiply(2.4)), 2.0, 70, 0.5, Fx.BROKEN_EARTH);
            if (t >= 14 && t <= 38 && t % 6 == 2) fx.crater(at, 2.4 + t * 0.03, 40, 0.5, Fx.BROKEN_EARTH);
            if (t == 24) {
                // the earth bursts around it
                fx.eruption(at, ringR * 0.6, 1.0);
                fx.shockwave(at, ringR, 6, 0.45);
                snd(at, "entity.generic.explode", 1.0f, 0.8f);
                snd(at, "block.rooted_dirt.break", 2f, 0.5f);
                for (Player p : fighters(e, ringR)) {
                    c.hit(p, ringPower);
                    throwFrom(p, at, 0.8, 0.45);
                }
            }
            if (t == 38) e.setInvulnerable(true);
            if (t == 40) {
                hide(c, st);
                snd(at, "entity.warden.dig", 1.6f, 0.7f);
            }
            if (t == 44) travel(ab, c, st, target, left);
        });
    }

    /** Under the ground: moves towards its prey, its trail shows on the ground, then the surge is announced. */
    private void travel(MobTemplate.Ability ab, RockPowers.Caster c, State st, Player target, int left) {
        LivingEntity e = c.entity();
        double speed = ab.number("travel-speed", 0.45), area = ab.number("area", 13);
        int maxTicks = (int) ab.number("travel-ticks", 50);
        Location home = c.home();
        Player[] prey = {target != null && target.isValid() && Mobs.fighting(target) ? target : null};
        if (prey[0] == null) {
            List<Player> all = fighters(e, 40);
            if (!all.isEmpty()) prey[0] = all.get(ThreadLocalRandom.current().nextInt(all.size()));
        }
        boolean[] done = {false};
        timeline(e, maxTicks, t -> {
            if (done[0]) return;
            Location at = e.getLocation();
            Location dest = prey[0] != null && prey[0].isValid() && Mobs.fighting(prey[0]) ? prey[0].getLocation() : home;
            dest = inside(dest, home, area);
            double d = Math.hypot(dest.getX() - at.getX(), dest.getZ() - at.getZ());
            if (d < 1.2 || t >= maxTicks) {
                done[0] = true;
                surge(ab, c, st, onGround(dest), ab.number("surge-power", 8), ab.number("surge-radius", 3.5),
                        (int) ab.number("warn", 26), 1.4, left);
                return;
            }
            Vector dir = flat(at, dest);
            Location now = step(e, dir, Math.min(speed, d));
            if (t % 2 == 0) fx.trail(now);
            if (t % 5 == 0) {
                snd(now, "entity.warden.dig", 1.2f, 0.6f);
                snd(now, "block.rooted_dirt.break", 1.0f, 0.6f);
            }
        });
    }

    /**
     * The ground shakes at {@code spot} for {@code warn} ticks (step out of the disc!), then it bursts out of it
     * (emerge: drill through the ground at 4-6, body out and impact at 8, lands at 20). Dives again when {@code left}
     * is above 1.
     */
    private void surge(MobTemplate.Ability ab, RockPowers.Caster c, State st, Location spot, double power, double radius,
                       int warn, double launch, int left) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        fx.crater(spot, radius, warn + 30, 0.4, Fx.BROKEN_EARTH);
        timeline(e, warn + 40, t -> {
            if (t < warn) {
                double k = t / (double) warn;
                if (t % 2 == 0) Fx.rumble(spot, radius, k, k > 0.6 ? RED : WARN);
                if (t % 5 == 0) {
                    snd(spot, "entity.warden.dig", 1.4f, 0.5f + 0.4f * (float) k);
                    snd(spot, "block.gravel.break", 1.2f, 0.6f);
                }
                if (t % 8 == 0) shake(spot, radius + 2);
                return;
            }
            int u = t - warn;
            if (u == 0) {
                // under the spot, facing whoever stands nearest, the drill 1.6 blocks behind the burst point
                Player near = null;
                double best = Double.MAX_VALUE;
                for (Player p : fighters(spot, 20)) {
                    double d = p.getLocation().distanceSquared(spot);
                    if (d > 1 && d < best) {
                        best = d;
                        near = p;
                    }
                }
                Vector f = near != null ? flat(spot, near.getLocation()) : forward(e);
                Location at = spot.clone().subtract(f.clone().multiply(1.6));
                at.setY(groundY(w, at.getX(), spot.getY() + 1, at.getZ()));
                at.setYaw(yawTo(f));
                at.setPitch(0);
                Mobs.move(e, at);
                show(c, st);
                c.animate("emerge");
                snd(spot, "block.grindstone.use", 2f, 0.7f);
            }
            if (u == 4) fx.spray(spot, new Vector(0, 1, 0), blockUnder(spot), 20);
            if (u == 8) {
                fx.eruption(spot, radius * 0.75, 2.2);
                fx.shockwave(spot, radius + 1.5, 6, 0.5);
                snd(spot, "entity.generic.explode", 1.6f, 0.6f);
                snd(spot, "block.rooted_dirt.break", 2f, 0.4f);
                snd(spot, "entity.ravager.roar", 1.6f, 0.6f);
                shake(spot, radius + 6);
                e.setInvulnerable(false);
                for (Player p : fighters(spot, radius)) {
                    if (Math.abs(p.getLocation().getY() - spot.getY()) > 3) continue;
                    c.hit(p, power);
                    throwFrom(p, spot, 0.7, launch * 0.7);
                }
            }
            if (u == 20) {
                fx.crater(e.getLocation(), 2.5, 30, 0.3, Fx.BROKEN_EARTH);
                snd(e.getLocation(), "entity.ravager.step", 1.6f, 0.5f);
            }
            if (u == 40) {
                if (left > 1) {
                    List<Player> all = fighters(e, 40);
                    Player next = all.isEmpty() ? null : all.get(ThreadLocalRandom.current().nextInt(all.size()));
                    burrow(ab, c, st, next, left - 1);
                } else {
                    c.release();
                }
            }
        });
    }

    // ---------------------------------------------------------------- drill sweep

    private void sweep(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double radius = ab.number("radius", 5.2), power = ab.number("sweep-power", 7);
        if (target != null && target.isValid()) e.setRotation(yawTo(flat(e.getLocation(), target.getLocation())), 0);
        Location at = e.getLocation().clone();
        Vector f = forward(e);
        Set<UUID> struck = new HashSet<>();
        double[] prev = {-62};
        c.hold(48);
        c.animate("sweep");
        snd(at, "entity.ravager.ambient", 1.4f, 0.6f);
        timeline(e, 46, t -> {
            BlockData earth = blockUnder(at);
            if (t < 22 && t % 2 == 0) {
                // the swept arc, announced (earth, then red)
                Fx.cone(at, f, 128, radius, t >= 14 ? RED : WARN, EARTH);
                if (t >= 8) Fx.drillSpin(drillAt(at, f, -62, radius * 0.55), aim(f, -62), 0.5, t, earth);
            }
            if (t >= 6 && t <= 12 && t % 3 == 0) {
                fx.spray(at.clone().add(f.clone().multiply(1.4)).add(right(f).multiply(1.7)), new Vector(0, 1, 0), earth, 6);
                fx.spray(at.clone().add(f.clone().multiply(1.4)).add(right(f).multiply(-1.7)), new Vector(0, 1, 0), earth, 6);
            }
            if (t >= 8 && t < 22 && t % 3 == 0) snd(at, "block.grindstone.use", 1.3f, 0.6f + 0.03f * t);
            if (t >= 22 && t <= 32) {
                // the drill passes from its right (-62) to its left (+62)
                double now = -62 + 124 * (t - 22) / 10.0;
                Location d = drillAt(at, f, now, radius * 0.6);
                // the drill moves towards its left: earth and players thrown that way
                Vector tang = aim(f, now).rotateAroundY(Math.PI / 2);
                fx.spray(d, tang.clone().setY(0.4), earth, 10);
                Fx.drillSpin(d, aim(f, now), 0.6, t, earth);
                if (t % 2 == 0) {
                    snd(d, "block.grindstone.use", 1.8f, 0.9f);
                    snd(d, "entity.player.attack.sweep", 1.4f, 0.5f);
                }
                for (Player p : fighters(e, radius + 0.6)) {
                    if (struck.contains(p.getUniqueId())) continue;
                    double a = side(e, p.getLocation());
                    if (a < Math.min(prev[0], now) - 6 || a > Math.max(prev[0], now) + 6) continue;
                    if (Math.abs(p.getLocation().getY() - at.getY()) > 2.5) continue;
                    struck.add(p.getUniqueId());
                    c.hit(p, power);
                    p.setVelocity(tang.clone().multiply(1.1).add(flat(at, p.getLocation()).multiply(0.5)).setY(0.45));
                    snd(p.getLocation(), "entity.player.attack.knockback", 1.6f, 0.6f);
                }
                prev[0] = now;
            }
            if (t == 46) c.release();
        });
    }

    /**
     * Its front turned by {@code angle} degrees, > 0 to its left (Bukkit's rotation about y turns +z towards +x: the
     * left of a creature facing +z).
     */
    private static Vector aim(Vector f, double angle) {
        return f.clone().rotateAroundY(Math.toRadians(angle));
    }

    /** Where the drill is when the head is turned by {@code angle} (> 0 to its left), {@code dist} blocks out. */
    private static Location drillAt(Location at, Vector f, double angle, double dist) {
        return at.clone().add(aim(f, angle).multiply(dist)).add(0, 0.9, 0);
    }

    // ---------------------------------------------------------------- seismic drill (ultimate)

    private void quake(MobTemplate.Ability ab, RockPowers.Caster c, State st, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Location center = e.getLocation().clone();
        Location home = c.home();
        double area = ab.number("area", 13);
        double wavePower = ab.number("wave-power", 5), eruptionPower = ab.number("eruption-power", 6),
                eruptionRadius = ab.number("eruption-radius", 2.2), waveReach = ab.number("wave-reach", 15);
        double lead = RockPowers.leadIn(ab);
        int warn = (int) ab.number("eruption-warn", 20);
        int[] eruptions = {70, 85, 100};
        Map<Integer, List<Location>> spots = new HashMap<>();
        Set<UUID> waved = new HashSet<>();
        c.hold(140 + 80);
        c.animate("ultimate");
        e.setInvulnerable(true);
        title(center, ab, 60);
        snd(center, "entity.ravager.roar", 2f, 0.5f);
        timeline(e, 130, t -> {
            BlockData earth = blockUnder(center);
            Vector f = forward(e);
            // the drill speeds up, the ground trembles and cracks around it
            if (t < 50 && t % 3 == 0) snd(center, "block.grindstone.use", 1.5f, 0.45f + 0.012f * t);
            if (t < 50 && t % 2 == 0) Fx.drillSpin(tip(e, 3.0, 1.0), f, 0.55, t, earth);
            if (t < 50 && t % 10 == 0) {
                snd(center, "entity.warden.dig", 1.6f, 0.5f + 0.01f * t);
                fx.crater(center, 3 + t * 0.08, 60, 0.4, Fx.BROKEN_EARTH);
                fx.fissure(center, new Vector(1, 0, 0).rotateAroundY(ThreadLocalRandom.current().nextDouble(Math.PI * 2)),
                        5 + t * 0.12, 8, 90);
                shake(center, 18);
            }
            if (t >= 20 && t < 50 && t % 4 == 0) fx.collapse(center, area * 0.8, 2, 7, earth);
            if (t == 50) {
                // slam: the drill driven into the ground, a wave runs out under the arena (jump over it)
                snd(center, "entity.generic.explode", 2f, 0.5f);
                snd(center, "entity.warden.sonic_boom", 0.7f, 0.5f);
                snd(center, "block.rooted_dirt.break", 2f, 0.4f);
                fx.eruption(tip(e, 2.6, 0), 2.0, 1.5);
                fx.shockwave(center, waveReach, 16, 0.55);
                shake(center, 30);
                for (int k = 0; k < 6; k++) {
                    fx.fissure(center, new Vector(1, 0, 0).rotateAroundY(Math.PI * 2 * k / 6 + 0.3), waveReach * 0.8, 12, 110);
                }
            }
            if (t > 50 && t <= 66) {
                // the wave reaches waveReach at tick 66; players on the ground where it passes are hit and bumped
                double r0 = waveReach * (t - 51) / 16.0, r1 = waveReach * (t - 50) / 16.0;
                for (Player p : fighters(center, r1 + 0.6)) {
                    double d = Math.hypot(p.getLocation().getX() - center.getX(), p.getLocation().getZ() - center.getZ());
                    if (d < r0 - 0.6 || waved.contains(p.getUniqueId()) || airborne(p)) continue;
                    waved.add(p.getUniqueId());
                    c.hit(p, wavePower);
                    p.setVelocity(flat(center, p.getLocation()).multiply(0.4).setY(0.75));
                    snd(p.getLocation(), "block.rooted_dirt.break", 1.4f, 0.6f);
                }
            }
            // eruptions: each announced warn ticks before, under every player and at a few more places
            for (int at : eruptions) {
                if (t == at - warn) {
                    List<Location> list = new ArrayList<>();
                    ThreadLocalRandom r = ThreadLocalRandom.current();
                    for (Player p : fighters(center, area + 4)) {
                        list.add(onGround(inside(p.getLocation().clone().add(r.nextDouble(-0.8, 0.8), 0, r.nextDouble(-0.8, 0.8)), home, area)));
                    }
                    for (int k = 0; k < 3; k++) {
                        double a = r.nextDouble(Math.PI * 2), d = r.nextDouble(4, area);
                        list.add(onGround(home.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d)));
                    }
                    spots.put(at, list);
                }
                List<Location> list = spots.get(at);
                if (list == null) continue;
                if (t > at - warn && t < at && t % 2 == 0) {
                    double k = 1 - (at - t) / (double) warn;
                    for (Location s : list) Fx.rumble(s, eruptionRadius, k, k > 0.5 ? RED : WARN);
                    if (t % 6 == 0) for (Location s : list) snd(s, "entity.warden.dig", 1.0f, 0.6f + 0.3f * (float) k);
                }
                // lead-in: a share of the hit lost in small hits by whoever stays in an announced place (lead-ticks
                // at most the warning)
                RockPowers.leadTick(ab, c, t, at, eruptionPower, p -> {
                    for (Location s : list) if (s.getWorld() == p.getWorld() && s.distanceSquared(p.getLocation()) <= eruptionRadius * eruptionRadius) return true;
                    return false;
                });
                if (t == at) {
                    Set<UUID> hit = new HashSet<>();
                    for (Location s : list) {
                        fx.eruption(s, eruptionRadius, 2.6);
                        snd(s, "entity.generic.explode", 1.2f, 0.8f);
                        snd(s, "block.rooted_dirt.break", 1.6f, 0.5f);
                        for (Player p : fighters(s, eruptionRadius)) {
                            if (!hit.add(p.getUniqueId())) continue;
                            c.hitShare(p, eruptionPower, 1 - lead);
                            throwFrom(p, s, 0.3, 1.0);
                        }
                    }
                    shake(center, 30);
                }
            }
            if (t >= 108 && t < 130 && t % 3 == 0) {
                fx.spray(e.getLocation(), new Vector(0, 1, 0), earth, 12);
                snd(e.getLocation(), "block.grindstone.use", 1.4f, 0.7f);
            }
            if (t == 126) snd(e.getLocation(), "entity.warden.dig", 1.8f, 0.5f);
            if (t == 130) {
                // under the ground: the last, powerful surge under the most crowded place
                hide(c, st);
                Location spot = crowded(e, home, area + 4);
                surge(ab, c, st, spot == null ? onGround(home) : spot, ab.number("final-power", 11), ab.number("final-radius", 4.5),
                        (int) ab.number("final-warn", 34), 2.0, 1);
            }
        });
    }

    /** The place of the player with the most other players around him (on the ground, inside the arena). */
    private static Location crowded(LivingEntity e, Location home, double within) {
        List<Player> all = fighters(home, within);
        Player best = null;
        int most = -1;
        for (Player p : all) {
            int n = 0;
            for (Player q : all) if (q != p && q.getLocation().distanceSquared(p.getLocation()) < 16) n++;
            if (n > most) {
                most = n;
                best = p;
            }
        }
        return best == null ? null : onGround(inside(best.getLocation(), home, within - 4));
    }

    // ---------------------------------------------------------------- phase (enraged)

    private void phase(MobTemplate.Ability ab, RockPowers.Caster c, State st) {
        LivingEntity e = c.entity();
        Location at = e.getLocation();
        c.hold(42);
        c.animate("phase_shift");
        e.setInvulnerable(true);
        title(at, ab, 50);
        snd(at, "entity.ravager.roar", 2f, 0.5f);
        st.extraSurges += (int) ab.number("extra-surges", 1);
        double bonus = ab.number("speed-bonus", 0.15);
        AttributeInstance speed = e.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null && bonus > 0) {
            speed.removeModifier(SPEED);
            speed.addModifier(new AttributeModifier(SPEED, bonus, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
        timeline(e, 42, t -> {
            if (t < 26 && t % 3 == 0) {
                snd(at, "block.grindstone.use", 1.6f, 0.6f + 0.02f * t);
                Fx.drillSpin(tip(e, 2.8, 1.6), forward(e).setY(0.4), 0.6, t, blockUnder(at));
            }
            if (t == 28) {
                fx.eruption(at.clone().add(forward(e).multiply(1.6)), 2.4, 1.6);
                fx.shockwave(at, 7, 8, 0.5);
                fx.collapse(at, 8, 10, 7, blockUnder(at));
                snd(at, "entity.generic.explode", 1.6f, 0.6f);
                shake(at, 20);
                for (Player p : fighters(e, 4)) {
                    c.hit(p, ab.number("slam-power", 4));
                    throwFrom(p, at, 0.9, 0.5);
                }
                e.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20, 1, false, false));
            }
            if (t == 40) e.setInvulnerable(false);
            if (t == 42) c.release();
        });
    }
}
