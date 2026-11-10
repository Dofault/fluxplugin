package fr.influencraft.fluxdungeons.mob;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A monster swimming in its body of water ({@code swim:} section of its file), moved by FluxDungeons alone, like a
 * dolphin or a sea serpent: it never waits next to a player. It <b>passes</b> through him at full speed, from where it
 * is to a point beyond him (it does not stop at him), maybe several times in a row, bending its way towards him while
 * it comes ({@code homing}: degrees a tick; once past him it goes straight on); <b>circles</b> around him, leaping
 * through the surface; then <b>retreats</b> far from the players and <b>surfaces</b> for a moment, the only time it
 * casts its abilities. Whoever it touches while passing or circling is hit and thrown. After an ability (a hold), it
 * dives again first.
 *
 * <p>The water is found once, when it appears: every water block joined to the one it stands in, within
 * {@code area} blocks. Nothing is assumed about the arena: any shape of pool, lake or flooded room works.
 *
 * <pre>
 * swim:
 *   pass-speed: 10        # blocks a second while passing through a player
 *   homing: 4             # degrees a tick it turns towards him while it comes (0: straight line)
 *   circle-speed: 7       # while circling around him
 *   cruise-speed: 6       # retreating
 *   overshoot: [5, 9]     # how far beyond the player a pass goes (blocks)
 *   passes: [1, 3]        # passes in a row
 *   circle-chance: 0.5    # chance to circle around him after the passes
 *   circle-radius: 4.5
 *   circle-time: [1.5, 2.5]   # seconds
 *   pause: [1.2, 2.0]     # seconds at the surface after a retreat (abilities are cast then)
 *   contact: {power: 5, radius: 1.8, cooldown: 0.8, knockback: 1.0}
 *   area: 32              # how far the water is searched
 * </pre>
 */
final class Swimmer {

    /** What the swimmer needs from the monster's owner. */
    interface Host {
        LivingEntity entity();

        /** Dying, changing phase: it stops for good. */
        boolean over();

        /** The player it fights now, if any. */
        Player target();

        /** Hurts a player it touched (contact power, the run's difficulty applied). */
        void touch(Player p, double power);
    }

    private enum Mode { DIVE, PASS, CIRCLE, RETREAT, PAUSE }

    /** A planned move: where the monster's feet are at each tick of it (null: the move ended early). */
    private record Plan(Mode mode, int ticks, java.util.function.IntFunction<Location> at) {
    }

    private final Host host;
    private final Map<Long, int[]> columns = new HashMap<>();
    private final List<long[]> keys = new ArrayList<>();
    private final World world;
    private final Map<String, Object> s;
    private final ArrayDeque<Mode> next = new ArrayDeque<>();
    private final Map<UUID, Integer> lastTouch = new HashMap<>();
    private Plan plan;
    private int t, clock;
    private boolean held, underwater;
    private Location last;

    private Swimmer(Host host, Map<String, Object> settings) {
        this.host = host;
        this.s = settings;
        this.world = host.entity().getWorld();
    }

    /** Starts swimming, or null when there is no water around the monster (it then walks as usual). */
    static Swimmer start(Plugin plugin, Host host, Map<String, Object> settings) {
        Swimmer sw = new Swimmer(host, settings);
        if (!sw.findWater()) return null;
        host.entity().setGravity(false);
        sw.next.add(Mode.DIVE);
        new BukkitRunnable() {
            @Override
            public void run() {
                LivingEntity e = host.entity();
                if (!e.isValid() || host.over()) {
                    cancel();
                    return;
                }
                sw.tick();
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return sw;
    }

    /** Abilities are cast only while it rests at the surface after a retreat. */
    boolean mayCast() {
        return plan != null && plan.mode == Mode.PAUSE && t >= 10 && !held;
    }

    // ---------------------------------------------------------------- water

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private boolean findWater() {
        Block start = host.entity().getLocation().getBlock();
        for (int i = 0; i < 4 && !water(start); i++) start = start.getRelative(0, -1, 0);
        if (!water(start)) return false;
        int area = (int) num("area", 32), limit = (int) num("limit", 30000);
        ArrayDeque<Block> queue = new ArrayDeque<>();
        Set<Block> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty() && seen.size() < limit) {
            Block b = queue.poll();
            long k = key(b.getX(), b.getZ());
            int[] c = columns.get(k);
            if (c == null) {
                columns.put(k, new int[]{b.getY(), b.getY()});
                keys.add(new long[]{b.getX(), b.getZ()});
            } else {
                c[0] = Math.min(c[0], b.getY());
                c[1] = Math.max(c[1], b.getY());
            }
            for (int[] d : dirs) {
                Block n = b.getRelative(d[0], d[1], d[2]);
                if (Math.abs(n.getX() - start.getX()) > area || Math.abs(n.getZ() - start.getZ()) > area) continue;
                if (water(n) && seen.add(n)) queue.add(n);
            }
        }
        return columns.size() >= 9;
    }

    private static boolean water(Block b) {
        return b.getType() == Material.WATER || b.getType() == Material.BUBBLE_COLUMN;
    }

    private int[] column(double x, double z) {
        return columns.get(key((int) Math.floor(x), (int) Math.floor(z)));
    }

    /** Feet height resting at the surface (body half out of the water). */
    private static double surface(int[] c) {
        return c[1] + 0.5;
    }

    /** Lowest and highest feet height fully under water. */
    private static double low(int[] c) {
        return c[0] + 0.05;
    }

    private static double high(int[] c) {
        return Math.max(c[0] + 0.05, c[1] - 1.2);
    }

    private double depth(int[] c) {
        return low(c) + ThreadLocalRandom.current().nextDouble() * (high(c) - low(c));
    }

    /** Whether the way between two spots stays in the water (sampled every half block). */
    private boolean clear(Location a, Location b) {
        double d = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
        int n = Math.max(1, (int) Math.ceil(d / 0.5));
        for (int i = 0; i <= n; i++) {
            double f = (double) i / n;
            if (column(a.getX() + (b.getX() - a.getX()) * f, a.getZ() + (b.getZ() - a.getZ()) * f) == null) return false;
        }
        return true;
    }

    /** Where to aim at a player: his spot in the water, or the water nearest to him when he stands outside it. */
    private Location aim(Player p) {
        Location pl = p.getLocation();
        int[] c = column(pl.getX(), pl.getZ());
        if (c != null) return new Location(world, pl.getX(), clamp(pl.getY() - 0.4, low(c), surface(c)), pl.getZ());
        long[] best = null;
        double bd = Double.MAX_VALUE;
        for (long[] k : keys) {
            double d = Math.hypot(k[0] + 0.5 - pl.getX(), k[1] + 0.5 - pl.getZ());
            if (d < bd) {
                bd = d;
                best = k;
            }
        }
        int[] bc = columns.get(key((int) best[0], (int) best[1]));
        return new Location(world, best[0] + 0.5, surface(bc) - 0.3, best[1] + 0.5);
    }

    // ---------------------------------------------------------------- moving

    private void tick() {
        LivingEntity e = host.entity();
        clock++;
        if (e instanceof Mob m && !m.hasAI()) {
            // an ability holds it (its animation plays in place): it waits, then dives again
            held = true;
            last = e.getLocation();
            return;
        }
        if (held) {
            held = false;
            next.clear();
            next.add(Mode.DIVE);
            plan = null;
        }
        if (plan == null || t >= plan.ticks) {
            plan = plan(e.getLocation());
            t = 0;
        }
        Location to = plan.at.apply(t++);
        if (to == null) {
            // the move ended before its time (a pass that went past its player and reached its end)
            plan = plan(e.getLocation());
            t = 0;
            to = plan.at.apply(t++);
            if (to == null) to = e.getLocation();
        }
        Location from = last == null ? e.getLocation() : last;
        Vector delta = to.toVector().subtract(from.toVector());
        Player face = host.target();
        if (plan.mode == Mode.PAUSE && face != null) {
            to.setDirection(face.getLocation().toVector().subtract(to.toVector()).setY(0));
        } else if (delta.clone().setY(0).lengthSquared() > 0.0004) {
            to.setDirection(delta.clone().setY(0));
        } else {
            to.setYaw(e.getLocation().getYaw());
        }
        to.setPitch(0);
        Mobs.move(e, to);
        // whatever pushed it (a hit, an enchantment) is undone: only the swim moves it
        e.setVelocity(new Vector());
        last = to;
        effects(to, delta);
        if (plan.mode == Mode.PASS || plan.mode == Mode.CIRCLE) touch(to, delta);
    }

    private void effects(Location at, Vector delta) {
        int[] c = column(at.getX(), at.getZ());
        if (c == null) return;
        boolean under = at.getY() < c[1] + 0.2;
        Location mid = at.clone().add(0, 0.6, 0);
        if (under) {
            world.spawnParticle(Particle.BUBBLE, mid, 4, 0.4, 0.3, 0.4, 0.02);
            if (delta.length() > 0.3 && clock % 4 == 0) world.spawnParticle(Particle.BUBBLE_COLUMN_UP, mid, 6, 0.3, 0.2, 0.3, 0.05);
        } else if (delta.length() > 0.15) {
            world.spawnParticle(Particle.SPLASH, at.clone().add(0, 0.9, 0), 10, 0.5, 0.1, 0.5, 0.1);
        }
        if (under != underwater) {
            // breaking through the surface, one way or the other
            world.spawnParticle(Particle.SPLASH, new Location(world, at.getX(), c[1] + 1, at.getZ()), 50, 0.8, 0.2, 0.8, 0.3);
            world.spawnParticle(Particle.BUBBLE_POP, new Location(world, at.getX(), c[1] + 1, at.getZ()), 20, 0.6, 0.1, 0.6, 0.05);
            world.playSound(at, under ? Sound.ENTITY_DOLPHIN_SPLASH : Sound.ENTITY_DOLPHIN_JUMP, 1.6f, 0.6f);
            underwater = under;
        }
        if (clock % 25 == 0 && under) world.playSound(at, Sound.ENTITY_DOLPHIN_SWIM, 1.2f, 0.5f);
    }

    private void touch(Location at, Vector delta) {
        double radius = num(contact(), "radius", 1.8), power = num(contact(), "power", 5);
        int cooldown = (int) Math.round(num(contact(), "cooldown", 0.8) * 20);
        double knock = num(contact(), "knockback", 1);
        Vector center = at.toVector().add(new Vector(0, 0.7, 0));
        for (Player p : world.getPlayers()) {
            if (!Mobs.fighting(p)) continue;
            if (p.getLocation().toVector().add(new Vector(0, 0.9, 0)).distanceSquared(center) > radius * radius) continue;
            Integer was = lastTouch.get(p.getUniqueId());
            if (was != null && clock - was < cooldown) continue;
            lastTouch.put(p.getUniqueId(), clock);
            host.touch(p, power);
            Vector push = delta.clone().setY(0);
            push = push.lengthSquared() < 0.0001 ? p.getLocation().toVector().subtract(at.toVector()).setY(0) : push;
            if (push.lengthSquared() < 0.0001) push = new Vector(1, 0, 0);
            p.setVelocity(push.normalize().multiply(0.9 * knock).setY(0.45 * knock));
            world.spawnParticle(Particle.SPLASH, p.getLocation().add(0, 1, 0), 40, 0.4, 0.4, 0.4, 0.3);
            world.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.2f, 0.6f);
            world.playSound(p.getLocation(), Sound.ENTITY_DOLPHIN_ATTACK, 1.5f, 0.5f);
        }
    }

    // ---------------------------------------------------------------- planning

    private Plan plan(Location from) {
        Player p = host.target();
        if (next.isEmpty()) {
            if (p == null) {
                next.add(Mode.RETREAT);
            } else {
                int[] passes = range("passes", 1, 3);
                int n = passes[0] + ThreadLocalRandom.current().nextInt(passes[1] - passes[0] + 1);
                for (int i = 0; i < n; i++) next.add(Mode.PASS);
                if (ThreadLocalRandom.current().nextDouble() < num("circle-chance", 0.5)) {
                    next.add(Mode.CIRCLE);
                    next.add(Mode.PASS);
                }
                next.add(Mode.RETREAT);
            }
        }
        Mode m = next.poll();
        Plan out = switch (m) {
            case DIVE -> dive(from);
            case PASS -> p == null ? null : pass(from, p);
            case CIRCLE -> p == null ? null : circle(from, aim(p));
            case RETREAT -> retreat(from);
            case PAUSE -> pause(from);
        };
        if (m == Mode.RETREAT && out != null) next.addFirst(Mode.PAUSE);
        if (m == Mode.PASS && out != null) world.playSound(from, Sound.ENTITY_DOLPHIN_ATTACK, 1.2f, 0.4f);
        // a move that cannot be made here (no room to circle...) gives way to the next one
        return out != null ? out : stay(from, 5);
    }

    private Plan stay(Location at, int ticks) {
        Location here = at.clone();
        return new Plan(Mode.DIVE, ticks, t -> here.clone());
    }

    /** Down under the surface, fast, where it is (after appearing or after an ability). */
    private Plan dive(Location from) {
        int[] c = column(from.getX(), from.getZ());
        if (c == null) return stay(from, 2);
        Location to = new Location(world, from.getX(), high(c), from.getZ());
        world.playSound(from, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.6f, 0.7f);
        world.spawnParticle(Particle.SPLASH, new Location(world, from.getX(), c[1] + 1, from.getZ()), 80, 1, 0.3, 1, 0.4);
        int ticks = 10;
        return new Plan(Mode.DIVE, ticks, t -> lerp(from, to, ease((double) t / ticks)));
    }

    /**
     * Through the player, from where it is to a point beyond him, never stopping at him. While it comes it bends its way
     * towards where he is now ({@code homing} degrees a tick at most: a player who steps aside late is still caught, one
     * who moves early and far is not); once past him (or right on him) it goes straight on for {@code overshoot} blocks,
     * slowing at the end, and stops early at the edge of the water.
     */
    private Plan pass(Location from, Player target) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double speed = num("pass-speed", 10) / 20, turn = Math.toRadians(num("homing", 4));
        double[] over = span("overshoot", 5, 9);
        double ov = over[0] + r.nextDouble() * (over[1] - over[0]);
        Vector first = aim(target).toVector().subtract(from.toVector());
        if (first.lengthSquared() < 0.01) first = new Vector(r.nextDouble() - 0.5, 0, r.nextDouble() - 0.5);
        Vector dir = first.normalize();
        Location[] pos = {from.clone()};
        double[] left = {-1};
        return new Plan(Mode.PASS, 200, t -> {
            if (left[0] < 0) {
                // coming: turn towards him, a little each tick; past him (or on him): go straight on
                Vector to = target.isOnline() && target.getWorld() == world ? aim(target).toVector().subtract(pos[0].toVector()) : dir.clone();
                if (to.length() < 1.2 || to.clone().normalize().dot(dir) < 0) left[0] = ov;
                else turnTowards(dir, to.normalize(), turn);
            }
            double s = speed * Math.min(1, (t + 1) / 6.0);
            if (left[0] >= 0) {
                s *= Math.max(0.35, Math.min(1, left[0] / (ov * 0.4)));
                left[0] -= s;
                if (left[0] <= 0) return null;
            }
            Location next = pos[0].clone().add(dir.clone().multiply(s));
            int[] c = column(next.getX(), next.getZ());
            if (c == null) return null;
            next.setY(clamp(next.getY(), low(c), surface(c) + 0.4));
            pos[0] = next;
            return next.clone();
        });
    }

    /** Turns {@code dir} towards {@code want} (both unit vectors) by {@code max} radians at most, in place. */
    private static void turnTowards(Vector dir, Vector want, double max) {
        double cos = Math.max(-1, Math.min(1, dir.dot(want)));
        double angle = Math.acos(cos);
        if (angle <= max || angle < 1e-4) {
            dir.copy(want);
            return;
        }
        double sin = Math.sin(angle);
        Vector out = dir.clone().multiply(Math.sin(angle - max) / sin).add(want.clone().multiply(Math.sin(max) / sin));
        dir.copy(out.normalize());
    }

    /** Around the player, leaping through the surface like a dolphin. */
    private Plan circle(Location from, Location center) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double radius = num("circle-radius", 4.5);
        for (; radius >= 2.5; radius -= 0.5) {
            int ok = 0;
            for (int i = 0; i < 16; i++) {
                double a = Math.PI * 2 * i / 16;
                if (column(center.getX() + Math.cos(a) * radius, center.getZ() + Math.sin(a) * radius) != null) ok++;
            }
            if (ok >= 13) break;
        }
        if (radius < 2.5) return null;
        double[] time = span("circle-time", 1.5, 2.5);
        int ticks = (int) Math.round((time[0] + r.nextDouble() * (time[1] - time[0])) * 20);
        double omega = num("circle-speed", 7) / 20 / radius * (r.nextBoolean() ? 1 : -1);
        double a0 = Math.atan2(from.getZ() - center.getZ(), from.getX() - center.getX());
        double rad = radius;
        Location start = from.clone();
        Location[] keep = {from.clone()};
        return new Plan(Mode.CIRCLE, ticks, t -> {
            double a = a0 + omega * t;
            double x = center.getX() + Math.cos(a) * rad, z = center.getZ() + Math.sin(a) * rad;
            int[] c = column(x, z);
            if (c == null) return keep[0].clone();
            // dolphin leaps: down to the bottom of its depth, up a little out of the water, one leap a second
            double mid = (high(c) + surface(c)) / 2, amp = (surface(c) + 0.6 - high(c)) / 2;
            Location on = new Location(world, x, mid + amp * Math.sin(Math.PI * 2 * t / 20.0), z);
            Location out = t < 8 ? lerp(start, on, ease(t / 8.0)) : on;
            keep[0] = out;
            return out.clone();
        });
    }

    /** Far from every player, under water. */
    private Plan retreat(Location from) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long[] best = null;
        double score = -1;
        for (int i = 0; i < 24; i++) {
            long[] k = keys.get(r.nextInt(keys.size()));
            double near = Double.MAX_VALUE;
            for (Player p : world.getPlayers()) {
                if (!Mobs.fighting(p)) continue;
                near = Math.min(near, Math.hypot(k[0] + 0.5 - p.getLocation().getX(), k[1] + 0.5 - p.getLocation().getZ()));
            }
            if (near > score) {
                score = near;
                best = k;
            }
        }
        if (best == null) return null;
        int[] c = columns.get(key((int) best[0], (int) best[1]));
        Location to = new Location(world, best[0] + 0.5, depth(c), best[1] + 0.5);
        double d = dist(from, to);
        int ticks = Math.max(6, (int) Math.ceil(d / (num("cruise-speed", 6) / 20)));
        return new Plan(Mode.RETREAT, ticks, t -> lerp(from, to, ease((double) t / ticks)));
    }

    /** Up to the surface where it is, still a moment: the time it casts. */
    private Plan pause(Location from) {
        int[] c = column(from.getX(), from.getZ());
        if (c == null) return null;
        double[] p = span("pause", 1.2, 2.0);
        int rest = (int) Math.round((p[0] + ThreadLocalRandom.current().nextDouble() * (p[1] - p[0])) * 20);
        Location top = new Location(world, from.getX(), surface(c), from.getZ());
        return new Plan(Mode.PAUSE, 10 + rest, t -> t < 10 ? lerp(from, top, ease(t / 10.0))
                : top.clone().add(0, 0.08 * Math.sin(t * 0.3), 0));
    }

    // ---------------------------------------------------------------- small things

    private static Location lerp(Location a, Location b, double f) {
        return new Location(a.getWorld(), a.getX() + (b.getX() - a.getX()) * f, a.getY() + (b.getY() - a.getY()) * f,
                a.getZ() + (b.getZ() - a.getZ()) * f);
    }

    private static double ease(double u) {
        u = clamp(u, 0, 1);
        return u * u * (3 - 2 * u);
    }

    private static double dist(Location a, Location b) {
        return a.toVector().distance(b.toVector());
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private double num(String key, double fallback) {
        return s.get(key) instanceof Number n ? n.doubleValue() : fallback;
    }

    private static double num(Map<?, ?> m, String key, double fallback) {
        return m != null && m.get(key) instanceof Number n ? n.doubleValue() : fallback;
    }

    private Map<?, ?> contact() {
        return s.get("contact") instanceof Map<?, ?> m ? m : null;
    }

    private double[] span(String key, double lo, double hi) {
        if (s.get(key) instanceof List<?> l && l.size() >= 2 && l.get(0) instanceof Number a && l.get(1) instanceof Number b) {
            return new double[]{Math.min(a.doubleValue(), b.doubleValue()), Math.max(a.doubleValue(), b.doubleValue())};
        }
        return new double[]{lo, hi};
    }

    private int[] range(String key, int lo, int hi) {
        double[] v = span(key, lo, hi);
        return new int[]{(int) Math.round(v[0]), (int) Math.round(v[1])};
    }
}
