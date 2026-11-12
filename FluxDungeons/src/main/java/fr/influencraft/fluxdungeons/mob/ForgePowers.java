package fr.influencraft.fluxdungeons.mob;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
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
 * Abilities of the Fondeur, the foundry golem (model golem_fonderie: a hammer in the right hand, a furnace in the chest,
 * bone {@code glow_torse}). Every attack comes from the furnace building heat and letting it out; timings follow the
 * model's animations tick for tick (docs/technique/boss/golem-fonderie.md, assets/monstres/golem_fonderie.py):
 * <ul>
 *   <li>{@code forge_awaken} (awaken 80): cold and bent, the furnace lights up (20), it rises and roars (60);</li>
 *   <li>{@code forge_slam} (slam 52), seismic strike: the reach is drawn on the ground (red from 18), the hammer falls
 *   at 30: a shockwave hurts whoever stands in it on the ground (a jump dodges it); in the last phase a second wave
 *   runs further out at 36; bent and open until 48;</li>
 *   <li>{@code forge_breath} (breath 56), fusion breath: the cone is drawn from 4 and locked at 18, molten heat pours
 *   out from 24 to 40 in a front cone and leaves burning ground for a few seconds; step aside;</li>
 *   <li>{@code forge_hurl} (hurl 40), hunt of the smiths: against players keeping away, it picks one to three of them
 *   (never the same one twice in a row when another is there), marks them, and throws a mass of glowing metal at each
 *   (16, 20, 24) on a readable arc; the landing spot is shown when it leaves; a side step dodges it; burning ground;</li>
 *   <li>{@code forge_ult} (forge_ult 140), forge apocalypse: accumulation (0-40, the furnace goes from red to white,
 *   embers gravitate, the arena's pillars answer), pressure (40-88, both arms up, quick pulses, a distinct alarm),
 *   release at 90: a wave runs through the arena; a pillar (any solid block) between it and a player shelters him;
 *   then exhaustion (92-140): the furnace nearly out, smoke, cooling metal, open to hits;</li>
 *   <li>{@code forge_overheat} (overheat 40): phase change (below its health thresholds), the furnace flares.</li>
 * </ul>
 * Phases follow its life (above 66 %, above 33 %, below): more embers, more masses thrown, a second seismic wave.
 * Burning ground is drawn for the players (blocks sent to clients only): the map never changes. When it dies its
 * furnace goes out (smoke, cooling metal): there is no death animation.
 */
final class ForgePowers {

    static final Set<String> TYPES = Set.of("forge_awaken", "forge_slam", "forge_breath", "forge_hurl", "forge_ult", "forge_overheat");
    private static final Color RED = Color.fromRGB(0xFF3A1A), ORANGE = Color.fromRGB(0xFF8A1E), YELLOW = Color.fromRGB(0xFFD94D),
            WHITE = Color.fromRGB(0xFFF4D6), WARN = Color.fromRGB(0xF0A800), SMOKE = Color.fromRGB(0x3A3236);
    private static final int EXPOSED = 0xFF8A1E;
    private static final BlockData MAGMA = Material.MAGMA_BLOCK.createBlockData();
    private static final Material[] CRACKED = {Material.MAGMA_BLOCK, Material.BLACKSTONE, Material.BASALT};

    /** Ground burning for a while (drawn for the players only). */
    private record Burn(Location at, double radius, long until, double power, RockPowers.Caster c) {
    }

    private final Plugin plugin;
    private final Fx fx;
    private final Map<UUID, LivingEntity> golems = new HashMap<>();
    private final Map<UUID, Location> lastPos = new HashMap<>();
    private final Map<UUID, Double> walked = new HashMap<>();
    /** The last player each golem threw at (spread the hunt over the archers). */
    private final Map<UUID, UUID> lastTarget = new HashMap<>();
    private final List<Burn> burns = new ArrayList<>();
    private final Set<BlockDisplay> alive = new HashSet<>();
    private int ticks;

    ForgePowers(Plugin plugin, Fx fx) {
        this.plugin = plugin;
        this.fx = fx;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    boolean cast(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        if (!TYPES.contains(ab.type())) return false;
        golems.put(c.entity().getUniqueId(), c.entity());
        switch (ab.type()) {
            case "forge_awaken" -> awaken(ab, c, target);
            case "forge_slam" -> slam(ab, c);
            case "forge_breath" -> breath(ab, c, target);
            case "forge_hurl" -> hurl(ab, c);
            case "forge_ult" -> ultimate(ab, c);
            case "forge_overheat" -> overheat(ab, c);
            default -> {
            }
        }
        return true;
    }

    /** Whether casting now makes sense (distance of the players), the hunt first when someone keeps away. */
    boolean worth(MobTemplate.Ability ab, LivingEntity e) {
        if (!TYPES.contains(ab.type())) return true;
        golems.put(e.getUniqueId(), e);
        return switch (ab.type()) {
            case "forge_slam" -> !fighters(e.getLocation(), ab.number("radius", 5.5)).isEmpty();
            case "forge_breath" -> {
                double range = ab.number("range", 10);
                for (Player p : fighters(e.getLocation(), range)) {
                    if (p.getLocation().distance(e.getLocation()) >= 2.5 && Math.abs(side(e, p.getLocation())) <= 50) yield true;
                }
                yield false;
            }
            case "forge_hurl" -> !far(e, ab.number("min-range", 8), ab.number("max-range", 34)).isEmpty();
            case "forge_ult" -> !fighters(e.getLocation(), ab.number("wave-radius", 18)).isEmpty();
            default -> true;
        };
    }

    void clear() {
        for (BlockDisplay d : alive) if (d.isValid()) d.remove();
        alive.clear();
        for (Burn b : burns) unburn(b);
        burns.clear();
        golems.clear();
    }

    // ---------------------------------------------------------------- every tick

    private void tick() {
        ticks++;
        // a golem gone: its furnace goes out where it stood (no death animation)
        for (Iterator<Map.Entry<UUID, LivingEntity>> it = golems.entrySet().iterator(); it.hasNext(); ) {
            LivingEntity e = it.next().getValue();
            if (e.isValid()) continue;
            it.remove();
            if (e.isDead()) extinguish(e.getLocation());
        }
        for (LivingEntity e : golems.values()) {
            // the furnace lives: embers and smoke, a stronger pulse every few seconds, more as it heats up
            int phase = phase(e);
            Location furnace = furnace(e);
            if (ticks % (8 - 2 * phase) == 0) {
                e.getWorld().spawnParticle(Particle.SMALL_FLAME, furnace, phase, 0.25, 0.25, 0.1, 0.01);
                e.getWorld().spawnParticle(Particle.DUST, furnace, 2, 0.3, 0.3, 0.1, 0, new Particle.DustOptions(phase >= 3 ? YELLOW : ORANGE, 1.2f));
            }
            if (ticks % 30 == 0) e.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, furnace.clone().add(0, 1.6, 0), 1, 0.2, 0.1, 0.2, 0.01);
            if (ticks % 80 == 0) {
                e.getWorld().spawnParticle(Particle.FLAME, furnace, 10 + 6 * phase, 0.2, 0.2, 0.2, 0.04);
                snd(furnace, "block.blastfurnace.fire_crackle", 0.9f, 0.6f);
            }
            if (ticks % 140 == 70) snd(furnace, "block.fire.ambient", 0.8f, 0.5f);
            // heavy steps: dust, embers and a deep metal knock at each footfall
            Location at = e.getLocation(), last = lastPos.put(e.getUniqueId(), at.clone());
            if (last == null || last.getWorld() != at.getWorld()) continue;
            double moved = Math.hypot(at.getX() - last.getX(), at.getZ() - last.getZ());
            if (moved < 0.02 || moved > 1.5) continue;
            double w = walked.merge(e.getUniqueId(), moved, Double::sum);
            if (w < 0.9) continue;
            walked.put(e.getUniqueId(), 0.0);
            int s = (ticks / 18) % 2 == 0 ? 1 : -1;
            Location foot = at.clone().add(right(forward(e)).multiply(0.6 * s)).add(0, 0.1, 0);
            e.getWorld().spawnParticle(Particle.BLOCK, foot, 8, 0.3, 0.05, 0.3, 0, blockUnder(foot));
            e.getWorld().spawnParticle(Particle.LAVA, foot, 1, 0.2, 0, 0.2, 0);
            snd(foot, "entity.iron_golem.step", 1.4f, 0.5f);
            snd(foot, "block.anvil.land", 0.25f, 0.5f);
        }
        // burning ground: flames on it, hurts whoever stands in it every half second
        long now = System.currentTimeMillis();
        for (Iterator<Burn> it = burns.iterator(); it.hasNext(); ) {
            Burn b = it.next();
            if (now > b.until()) {
                unburn(b);
                it.remove();
                continue;
            }
            if (ticks % 4 == 0) b.at().getWorld().spawnParticle(Particle.FLAME, b.at().clone().add(0, 0.2, 0), 2, b.radius() * 0.5, 0.05, b.radius() * 0.5, 0.01);
            if (ticks % 10 != 0) continue;
            for (Player p : fighters(b.at(), b.radius())) {
                if (Math.abs(p.getLocation().getY() - b.at().getY()) > 1.2) continue;
                b.c().drain(p, b.power(), 1.0);
                p.setFireTicks(Math.max(p.getFireTicks(), 20));
            }
        }
    }

    // ---------------------------------------------------------------- shared

    /** 1 above 66 % of its life, 2 above 33 %, then 3. */
    private static int phase(LivingEntity e) {
        var a = e.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        double max = a == null ? 1 : a.getValue(), r = e.getHealth() / Math.max(1, max);
        return r > 0.66 ? 1 : r > 0.33 ? 2 : 3;
    }

    /** The furnace in its chest (model: front face, about 2.5 blocks up). */
    private static Location furnace(LivingEntity e) {
        return e.getLocation().clone().add(forward(e).multiply(0.65)).add(0, 2.5, 0);
    }

    private static void snd(Location at, String key, float volume, float pitch) {
        at.getWorld().playSound(at, key, SoundCategory.HOSTILE, volume, pitch);
    }

    private static BlockData blockUnder(Location at) {
        Block b = Fx.ground(at.getWorld(), at.getBlockX(), at.getY(), at.getBlockZ());
        return b == null ? Material.BLACKSTONE.createBlockData() : b.getBlockData();
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

    private static Vector flat(Location from, Location to) {
        Vector v = to.toVector().subtract(from.toVector()).setY(0);
        return v.lengthSquared() < 1e-6 ? from.getDirection().setY(0).normalize() : v.normalize();
    }

    private static float yawTo(Vector dir) {
        return (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
    }

    private static double side(LivingEntity e, Location at) {
        Vector f = forward(e), to = flat(e.getLocation(), at);
        return Math.toDegrees(Math.atan2(right(f).dot(to), f.dot(to)));
    }

    private static void face(LivingEntity e, Location to) {
        e.setRotation(yawTo(flat(e.getLocation(), to)), 0);
    }

    private static double groundY(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int by = (int) Math.floor(y + 1.2); by >= (int) Math.floor(y) - 6; by--) {
            Block b = w.getBlockAt(bx, by, bz);
            if (b.isCollidable()) return by + b.getBoundingBox().getHeight();
        }
        return y;
    }

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

    private void exposed(RockPowers.Caster c, int ticks) {
        c.glow(EXPOSED);
        Bukkit.getScheduler().runTaskLater(plugin, () -> c.glow(-1), ticks);
    }

    /** Heat colour of the furnace from 0 (dark red) to 1 (white hot). */
    private static Color heat(double k) {
        Color[] steps = {RED, ORANGE, YELLOW, WHITE};
        double x = Math.max(0, Math.min(1, k)) * (steps.length - 1);
        int i = Math.min(steps.length - 2, (int) x);
        double f = x - i;
        Color a = steps[i], b = steps[i + 1];
        return Color.fromRGB((int) (a.getRed() + (b.getRed() - a.getRed()) * f), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * f),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * f));
    }

    /** Ground drawn burning for the players around (client-side blocks), hurting whoever stands on it. */
    private void burn(Location center, double radius, int seconds, double power, RockPowers.Caster c) {
        Location at = center.clone();
        at.setY(groundY(at.getWorld(), at.getX(), at.getY() + 1, at.getZ()));
        Burn b = new Burn(at, radius, System.currentTimeMillis() + seconds * 1000L, power, c);
        burns.add(b);
        for (Block blk : floor(b)) for (Player p : fighters(at, 48)) p.sendBlockChange(blk.getLocation(), MAGMA);
    }

    private void unburn(Burn b) {
        for (Block blk : floor(b)) for (Player p : fighters(b.at(), 48)) p.sendBlockChange(blk.getLocation(), blk.getBlockData());
    }

    private static List<Block> floor(Burn b) {
        List<Block> out = new ArrayList<>();
        int r = (int) Math.ceil(b.radius());
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > b.radius() * b.radius()) continue;
                Block blk = b.at().getWorld().getBlockAt(b.at().getBlockX() + dx, (int) Math.floor(b.at().getY() - 0.5), b.at().getBlockZ() + dz);
                if (blk.getType().isSolid() && blk.getRelative(0, 1, 0).isPassable()) out.add(blk);
            }
        }
        return out;
    }

    private static boolean onGround(Player p) {
        return p.getLocation().getY() - groundY(p.getWorld(), p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ()) < 0.6;
    }

    /** Players keeping away, in sight, farthest first. */
    private static List<Player> far(LivingEntity e, double min, double max) {
        List<Player> out = new ArrayList<>();
        for (Player p : fighters(e.getLocation(), max)) {
            if (p.getLocation().distance(e.getLocation()) >= min && e.hasLineOfSight(p)) out.add(p);
        }
        out.sort((a, b) -> Double.compare(b.getLocation().distanceSquared(e.getLocation()), a.getLocation().distanceSquared(e.getLocation())));
        return out;
    }

    /** Whether a solid block (a pillar) stands between the blast and the player. */
    private static boolean sheltered(Location from, Player p) {
        Location eye = p.getEyeLocation(), src = from.clone().add(0, 1.4, 0);
        Vector dir = eye.toVector().subtract(src.toVector());
        double d = dir.length();
        if (d < 1) return false;
        RayTraceResult r = from.getWorld().rayTraceBlocks(src, dir.normalize(), d - 0.5, FluidCollisionMode.NEVER, true);
        return r != null && r.getHitBlock() != null;
    }

    private void extinguish(Location at) {
        World w = at.getWorld();
        w.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 2.2, 0), 60, 0.8, 1.2, 0.8, 0.03);
        w.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, at.clone().add(0, 2.5, 0), 12, 0.4, 0.4, 0.4, 0.02);
        snd(at, "block.fire.extinguish", 2.0f, 0.5f);
        snd(at, "block.lava.extinguish", 1.6f, 0.6f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> snd(at, "block.anvil.land", 0.8f, 0.4f), 10L);
    }

    // ---------------------------------------------------------------- awakening

    private void awaken(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        c.hold(82);
        c.animate("awaken");
        e.setInvulnerable(true);
        if (target != null) face(e, target.getLocation());
        title(e.getLocation(), ab, 60);
        timeline(e, 80, t -> {
            Location f = furnace(e);
            if (t == 0) snd(f, "block.respawn_anchor.deplete", 1.4f, 0.5f);
            if (t == 20) {
                snd(f, "block.blastfurnace.fire_crackle", 1.6f, 0.6f);
                snd(f, "item.firecharge.use", 1.4f, 0.5f);
                e.getWorld().spawnParticle(Particle.FLAME, f, 30, 0.2, 0.2, 0.2, 0.08);
            }
            if (t > 20 && t < 60 && t % 4 == 0) e.getWorld().spawnParticle(Particle.DUST, f, 6, 0.3, 0.3, 0.2, 0, new Particle.DustOptions(heat((t - 20) / 40.0), 1.4f));
            if (t >= 32 && t < 60 && t % 6 == 0) snd(e.getLocation(), "block.chain.step", 1.2f, 0.5f);
            if (t == 60) {
                snd(e.getLocation(), "entity.ravager.roar", 2.0f, 0.4f);
                snd(e.getLocation(), "entity.iron_golem.hurt", 1.6f, 0.4f);
                fx.shockwave(e.getLocation(), 4, 8, 0.25);
                shake(e.getLocation(), 24);
            }
            if (t == 80) {
                e.setInvulnerable(false);
                c.release();
            }
        });
    }

    // ---------------------------------------------------------------- seismic strike

    private void slam(MobTemplate.Ability ab, RockPowers.Caster c) {
        LivingEntity e = c.entity();
        double radius = ab.number("radius", 5.5), power = ab.number("slam-power", 7), outer = ab.number("outer-radius", 9);
        boolean second = phase(e) >= 3;
        Set<UUID> struck = new HashSet<>();
        c.hold(54);
        c.animate("slam");
        snd(e.getLocation(), "entity.iron_golem.repair", 1.4f, 0.5f);
        timeline(e, 52, t -> {
            Location at = e.getLocation();
            // the reach on the ground, orange then red, cracks of light, a rising rumble
            if (t >= 4 && t <= 30 && t % 3 == 0) {
                Fx.ring(at, radius, t >= 18 ? RED : WARN, 1.5f, t * 0.04);
                if (second) Fx.ring(at, outer, t >= 18 ? RED : WARN, 1.0f, -t * 0.03);
            }
            if (t >= 10 && t < 30 && t % 5 == 0) snd(at, "entity.warden.heartbeat", 0.9f, 0.5f);
            if (t == 12) fx.crater(at, radius * 0.7, 40, 0.15, CRACKED);
            if (t > 4 && t < 30 && t % 3 == 0) e.getWorld().spawnParticle(Particle.DUST, furnace(e), 4, 0.25, 0.25, 0.1, 0, new Particle.DustOptions(heat(t / 30.0), 1.3f));
            // 30: the hammer hits the ground
            if (t == 30) {
                Location hammer = at.clone().add(forward(e).multiply(1.6)).add(right(forward(e)).multiply(1.4));
                snd(at, "entity.generic.explode", 1.8f, 0.5f);
                snd(at, "block.anvil.land", 1.8f, 0.4f);
                fx.shockwave(at, radius, 8, 0.4);
                fx.debris(hammer, 14, Material.BLACKSTONE.createBlockData(), 0.9);
                e.getWorld().spawnParticle(Particle.LAVA, hammer, 16, 1.2, 0.2, 1.2, 0);
                e.getWorld().spawnParticle(Particle.EXPLOSION, hammer, 2, 0.4, 0.1, 0.4, 0);
                shake(at, 22);
            }
            // the wave runs out to the reach in 6 ticks: on the ground it hurts, a jump goes over it
            if (t >= 30 && t <= 36) wave(c, at, radius * (t - 29) / 7.0, radius, power, struck, 1.2);
            if (second && t >= 36 && t <= 44) {
                if (t == 36) snd(at, "entity.generic.explode", 1.4f, 0.6f);
                wave(c, at, radius + (outer - radius) * (t - 35) / 9.0, outer, power * 0.6, struck, 0.9);
            }
            if (t == 32) exposed(c, 16);
            if (t > 32 && t < 48 && t % 4 == 0) e.getWorld().spawnParticle(Particle.LAVA, at, 1, radius * 0.3, 0.1, radius * 0.3, 0);
            if (t == 46) snd(at, "block.anvil.place", 0.8f, 0.5f);
        });
    }

    /** One step of a ring wave: whoever is on the ground at its front is hurt and thrown out, once. */
    private void wave(RockPowers.Caster c, Location at, double r, double max, double power, Set<UUID> struck, double push) {
        World w = at.getWorld();
        int n = (int) Math.max(16, r * 6);
        for (int i = 0; i < n; i++) {
            double b = i * Math.PI * 2 / n;
            Location p = at.clone().add(Math.cos(b) * r, 0.2, Math.sin(b) * r);
            w.spawnParticle(Particle.BLOCK, p, 1, 0.1, 0.05, 0.1, 0, blockUnder(p));
            if (i % 3 == 0) w.spawnParticle(Particle.FLAME, p, 1, 0.05, 0.05, 0.05, 0.01);
        }
        for (Player p : fighters(at, max)) {
            if (struck.contains(p.getUniqueId())) continue;
            double d = Math.hypot(p.getLocation().getX() - at.getX(), p.getLocation().getZ() - at.getZ());
            if (d > r || d < r - 2.2 || Math.abs(p.getLocation().getY() - at.getY()) > 2.5 || !onGround(p)) continue;
            struck.add(p.getUniqueId());
            c.hit(p, power);
            p.setVelocity(flat(at, p.getLocation()).multiply(push).setY(0.5));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1, false, true));
        }
    }

    // ---------------------------------------------------------------- fusion breath

    private void breath(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        double range = ab.number("range", 10), half = Math.toRadians(ab.number("angle", 60) / 2 + (phase(e) >= 3 ? 8 : 0)),
                power = ab.number("breath-power", 2.2), burnPower = ab.number("burn-power", 0.6);
        Vector[] dir = {target == null ? forward(e) : flat(e.getLocation(), target.getLocation())};
        Map<UUID, Integer> lastHit = new HashMap<>();
        c.hold(58);
        c.animate("breath");
        timeline(e, 56, t -> {
            Location at = e.getLocation(), f = furnace(e);
            if (t <= 18 && target != null && target.isValid() && Mobs.fighting(target)) {
                dir[0] = flat(at, target.getLocation());
                e.setRotation(yawTo(dir[0]), 0);
            }
            // 0-22: embers drawn into the furnace, its colour climbs to white, the cone is drawn on the ground
            if (t < 22 && t % 2 == 0) {
                for (int i = 0; i < 4; i++) {
                    Vector off = new Vector(ThreadLocalRandom.current().nextDouble(-2, 2), ThreadLocalRandom.current().nextDouble(-0.5, 1.5),
                            ThreadLocalRandom.current().nextDouble(-2, 2));
                    e.getWorld().spawnParticle(Particle.FLAME, f.clone().add(off), 0, -off.getX(), -off.getY(), -off.getZ(), 0.06);
                }
                e.getWorld().spawnParticle(Particle.DUST, f, 5, 0.2, 0.2, 0.1, 0, new Particle.DustOptions(heat(t / 22.0), 1.4f));
            }
            if (t >= 4 && t <= 22 && t % 3 == 0) cone(at, dir[0], range, half, t >= 14 ? RED : WARN);
            if (t == 8) snd(f, "block.piston.contract", 1.2f, 0.5f);
            if (t == 16) snd(f, "block.respawn_anchor.charge", 1.4f, 0.6f);
            // 24-40: the breath pours out
            if (t == 24) {
                snd(f, "entity.blaze.shoot", 2.0f, 0.5f);
                snd(f, "block.fire.extinguish", 1.6f, 0.4f);
            }
            if (t >= 24 && t <= 40) {
                if (t % 4 == 0) snd(f, "block.blastfurnace.fire_crackle", 1.4f, 0.6f);
                for (int i = 0; i < 10; i++) {
                    double a = ThreadLocalRandom.current().nextDouble(-half, half);
                    Vector v = rotate(dir[0], a).multiply(ThreadLocalRandom.current().nextDouble(0.5, 1.0)).setY(ThreadLocalRandom.current().nextDouble(-0.15, 0.05));
                    e.getWorld().spawnParticle(i % 3 == 0 ? Particle.LAVA : Particle.FLAME, f, 0, v.getX(), v.getY(), v.getZ(), 0.9);
                }
                e.getWorld().spawnParticle(Particle.LARGE_SMOKE, f, 2, 0.3, 0.3, 0.3, 0.05);
                for (Player p : fighters(at, range)) {
                    Vector to = flat(at, p.getLocation());
                    if (to.angle(dir[0]) > half || Math.abs(p.getLocation().getY() - at.getY()) > 3) continue;
                    Integer last = lastHit.get(p.getUniqueId());
                    if (last != null && t - last < 8) continue;
                    lastHit.put(p.getUniqueId(), t);
                    c.hit(p, power);
                    p.setFireTicks(Math.max(p.getFireTicks(), 40));
                }
                if (t == 32) {
                    // the heat stays on the ground in the cone a few seconds
                    for (int k = 0; k < 3; k++) {
                        double a = (k - 1) * half * 0.6, d = range * (0.45 + 0.2 * k);
                        burn(at.clone().add(rotate(dir[0], a).multiply(d)), 1.6, 5, burnPower, c);
                    }
                }
            }
            // 40-56: the furnace dims, smoke
            if (t > 40 && t % 4 == 0) e.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, f, 2, 0.2, 0.3, 0.2, 0.02);
            if (t == 44) snd(f, "block.lava.extinguish", 1.0f, 0.7f);
        });
    }

    private static Vector rotate(Vector v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new Vector(v.getX() * c - v.getZ() * s, 0, v.getX() * s + v.getZ() * c).normalize();
    }

    private static void cone(Location at, Vector dir, double range, double half, Color color) {
        World w = at.getWorld();
        Particle.DustOptions d = new Particle.DustOptions(color, 1.3f);
        for (double a : new double[]{-half, half}) {
            Vector v = rotate(dir, a);
            for (double r = 1.5; r <= range; r += 0.8) w.spawnParticle(Particle.DUST, at.clone().add(v.clone().multiply(r)).add(0, 0.15, 0), 1, 0, 0, 0, 0, d);
        }
        for (double a = -half; a <= half; a += 0.08) w.spawnParticle(Particle.DUST, at.clone().add(rotate(dir, a).multiply(range)).add(0, 0.15, 0), 1, 0, 0, 0, 0, d);
    }

    // ---------------------------------------------------------------- hunt of the smiths (against archers)

    private void hurl(MobTemplate.Ability ab, RockPowers.Caster c) {
        LivingEntity e = c.entity();
        int count = Math.min((int) ab.number("count", 1) + phase(e) - 1, (int) ab.number("max-count", 3));
        double power = ab.number("hurl-power", 4.5), burnPower = ab.number("burn-power", 0.6);
        List<Player> pool = far(e, ab.number("min-range", 8), ab.number("max-range", 34));
        if (pool.isEmpty()) return;
        // never the same player twice in a row when another one keeps away too
        UUID last = lastTarget.get(e.getUniqueId());
        if (pool.size() > 1 && last != null) pool.sort((a, b) -> Boolean.compare(a.getUniqueId().equals(last), b.getUniqueId().equals(last)));
        List<Player> targets = new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
        lastTarget.put(e.getUniqueId(), targets.get(0).getUniqueId());
        c.hold(42);
        c.animate("hurl");
        face(e, targets.get(0).getLocation());
        for (Player p : targets) snd(p.getLocation(), "block.anvil.place", 1.2f, 1.6f);
        timeline(e, 40, t -> {
            Location f = furnace(e);
            // marked: a red glow over each target, quick pulses of the furnace
            if (t < 16 && t % 2 == 0) {
                for (Player p : targets) {
                    if (p.isValid()) e.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 2.4, 0), 3, 0.2, 0.1, 0.2, 0, new Particle.DustOptions(RED, 1.2f));
                }
                if (t % 4 == 0) {
                    e.getWorld().spawnParticle(Particle.FLAME, f, 6, 0.15, 0.15, 0.1, 0.02);
                    snd(f, "block.note_block.basedrum", 1.2f, 0.6f);
                }
            }
            if (t <= 12 && targets.get(0).isValid()) face(e, targets.get(0).getLocation());
            for (int i = 0; i < targets.size(); i++) {
                if (t != 16 + 4 * i) continue;
                Player p = targets.get(i);
                if (!p.isValid() || !Mobs.fighting(p)) continue;
                Vector lead = p.getVelocity().clone().setY(0).multiply(6);
                Location land = p.getLocation().clone().add(lead);
                land.setY(groundY(p.getWorld(), land.getX(), land.getY() + 1, land.getZ()));
                throwMass(c, e, land, power, burnPower);
            }
        });
    }

    private void throwMass(RockPowers.Caster c, LivingEntity e, Location land, double power, double burnPower) {
        World w = e.getWorld();
        Location from = e.getLocation().clone().add(forward(e).multiply(1.0)).add(right(forward(e)).multiply(-1.1)).add(0, 2.8, 0);
        BlockDisplay mass = w.spawn(from, BlockDisplay.class, d -> {
            d.setBlock(MAGMA);
            d.setPersistent(false);
            d.addScoreboardTag("fluxdungeons_fx");
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(-0.3f, -0.3f, -0.3f), new Quaternionf(), new Vector3f(0.6f, 0.6f, 0.6f), new Quaternionf()));
            d.setTeleportDuration(1);
        });
        alive.add(mass);
        snd(from, "entity.blaze.shoot", 1.6f, 0.6f);
        double dist = from.distance(land);
        int flight = (int) Math.max(14, Math.min(26, dist * 0.9));
        double peak = Math.min(6, 2 + dist * 0.18);
        timelineFree(flight + 1, t -> {
            // the landing spot, shown from the throw: a side step dodges it
            if (t % 2 == 0) Fx.ring(land, 1.8, t >= flight - 6 ? RED : WARN, 1.2f, t * 0.1);
            double k = t / (double) flight;
            Location p = from.clone().add(land.toVector().subtract(from.toVector()).multiply(k)).add(0, 4 * peak * k * (1 - k), 0);
            if (mass.isValid()) mass.teleport(p);
            w.spawnParticle(Particle.FLAME, p, 2, 0.1, 0.1, 0.1, 0.01);
            w.spawnParticle(Particle.SMOKE, p, 1, 0.05, 0.05, 0.05, 0);
            if (t == flight) {
                if (mass.isValid()) mass.remove();
                alive.remove(mass);
                w.spawnParticle(Particle.EXPLOSION, land, 1, 0, 0, 0, 0);
                w.spawnParticle(Particle.LAVA, land, 14, 0.8, 0.2, 0.8, 0);
                snd(land, "entity.generic.explode", 1.2f, 0.8f);
                snd(land, "block.lava.pop", 1.4f, 0.6f);
                for (Player pl : fighters(land, 2.2)) {
                    c.hit(pl, power);
                    pl.setFireTicks(Math.max(pl.getFireTicks(), 40));
                }
                burn(land, 1.6, 4, burnPower, c);
            }
        });
    }

    private void timelineFree(int length, java.util.function.IntConsumer body) {
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (t > length) {
                    cancel();
                    return;
                }
                body.accept(t);
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ---------------------------------------------------------------- forge apocalypse (ultimate)

    private void ultimate(MobTemplate.Ability ab, RockPowers.Caster c) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double radius = ab.number("wave-radius", 18), power = ab.number("wave-power", 12), shelter = ab.number("shelter", 0.15);
        Location center = e.getLocation().clone();
        List<Location> pillars = new ArrayList<>();
        for (Object o : ab.params().get("pillars") instanceof List<?> l0 ? l0 : List.of()) {
            if (o instanceof List<?> l && l.size() >= 2 && l.get(0) instanceof Number x && l.get(1) instanceof Number z) {
                pillars.add(c.home().clone().add(x.doubleValue(), 0, z.doubleValue()));
            }
        }
        Set<UUID> waved = new HashSet<>();
        c.hold(142);
        c.animate("forge_ult");
        title(center, ab, 80);
        timeline(e, 140, t -> {
            Location at = e.getLocation(), f = furnace(e);
            // 0-40 accumulation: furnace from red to white, embers gravitate, the pillars and the ground answer
            if (t < 88 && t % 2 == 0) {
                double k = Math.min(1, t / 80.0);
                w.spawnParticle(Particle.DUST, f, 6, 0.3, 0.3, 0.15, 0, new Particle.DustOptions(heat(k), 1.6f));
                for (int i = 0; i < 3; i++) {
                    double a = t * 0.15 + i * 2.1, r = 2.6 - k;
                    w.spawnParticle(Particle.FLAME, at.clone().add(Math.cos(a) * r, 1 + i * 0.7, Math.sin(a) * r), 1, 0, 0, 0, 0);
                }
            }
            if (t == 4) snd(at, "block.respawn_anchor.charge", 1.6f, 0.5f);
            if (t == 20) fx.crater(at, 3.5, 120, 0.25, CRACKED);
            if (t < 88 && t % 6 == 0) {
                for (Location p : pillars) {
                    double h = 1 + (t / 88.0) * 8;
                    w.spawnParticle(Particle.FLAME, p.clone().add(0, h, 0), 4, 0.6, 0.6, 0.6, 0.01);
                    w.spawnParticle(Particle.DRIPPING_LAVA, p.clone().add(0, h + 1, 0), 2, 0.6, 0.2, 0.6, 0);
                }
            }
            if (t < 88 && t % 3 == 0) Fx.ring(at, radius, t >= 64 ? RED : WARN, 1.1f, t * 0.02);
            // 40-88 pressure: quick pulses, metal under pressure, a distinct alarm
            if (t >= 40 && t < 88) {
                if (t % 4 == 0) snd(f, "block.note_block.basedrum", 1.6f, 0.5f + (t - 40) * 0.01f);
                if (t % 8 == 0) snd(f, "block.piston.extend", 1.2f, 0.5f);
                if (t % 12 == 0) snd(at, "block.bell.resonate", 1.4f, 0.6f);
                if (t % 2 == 0) w.spawnParticle(Particle.FLAME, f, 4, 0.2, 0.2, 0.2, 0.05);
            }
            if (t == 64) shake(at, radius + 6);
            // 90 release: a flash, heat columns, magma bursts, a wave through the whole arena
            if (t == 90) {
                snd(at, "entity.generic.explode", 2.4f, 0.4f);
                snd(at, "entity.warden.sonic_boom", 1.6f, 0.5f);
                w.spawnParticle(Particle.END_ROD, f, 40, 0.3, 0.3, 0.3, 0.35);
                w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1, 0, 0, 0, 0);
                fx.shockwave(at, radius, 14, 0.5);
                shake(at, radius + 8);
                for (int i = 0; i < 6; i++) {
                    double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2), r = ThreadLocalRandom.current().nextDouble(4, radius - 2);
                    Location col = at.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
                    w.spawnParticle(Particle.FLAME, col.clone().add(0, 2, 0), 30, 0.3, 2, 0.3, 0.02);
                    w.spawnParticle(Particle.LAVA, col, 8, 0.4, 0.2, 0.4, 0);
                }
            }
            if (t >= 90 && t <= 104) {
                double r = radius * (t - 89) / 15.0;
                int n = (int) Math.max(20, r * 5);
                for (int i = 0; i < n; i++) {
                    double b = i * Math.PI * 2 / n;
                    Location p = at.clone().add(Math.cos(b) * r, 0.4, Math.sin(b) * r);
                    w.spawnParticle(i % 2 == 0 ? Particle.FLAME : Particle.LARGE_SMOKE, p, 1, 0.1, 0.2, 0.1, 0.01);
                }
                for (Player p : fighters(at, radius)) {
                    if (waved.contains(p.getUniqueId())) continue;
                    double d = Math.hypot(p.getLocation().getX() - at.getX(), p.getLocation().getZ() - at.getZ());
                    if (d > r || Math.abs(p.getLocation().getY() - at.getY()) > 6) continue;
                    waved.add(p.getUniqueId());
                    if (sheltered(at, p)) {
                        c.hitShare(p, power, shelter);
                        snd(p.getLocation(), "block.anvil.land", 0.8f, 1.2f);
                    } else {
                        c.hit(p, power);
                        p.setVelocity(flat(at, p.getLocation()).multiply(1.5).setY(0.6));
                        p.setFireTicks(Math.max(p.getFireTicks(), 60));
                    }
                }
            }
            // 92-140 exhaustion: the furnace nearly out, smoke, cooling metal, open to hits
            if (t == 94) {
                exposed(c, 46);
                snd(f, "block.fire.extinguish", 1.6f, 0.4f);
            }
            if (t > 94 && t % 6 == 0) {
                w.spawnParticle(Particle.LARGE_SMOKE, f.clone().add(0, 0.6, 0), 4, 0.4, 0.5, 0.4, 0.02);
                w.spawnParticle(Particle.DUST, f, 2, 0.2, 0.2, 0.1, 0, new Particle.DustOptions(SMOKE, 1.4f));
            }
            if (t == 104 || t == 118 || t == 130) snd(at, "block.anvil.land", 0.5f, 0.4f + (t - 104) * 0.01f);
        });
    }

    // ---------------------------------------------------------------- phase change

    private void overheat(MobTemplate.Ability ab, RockPowers.Caster c) {
        LivingEntity e = c.entity();
        c.hold(42);
        c.animate("overheat");
        title(e.getLocation(), ab, 40);
        timeline(e, 40, t -> {
            Location f = furnace(e);
            if (t % 3 == 0) e.getWorld().spawnParticle(Particle.FLAME, f, 8, 0.3, 0.3, 0.2, 0.06);
            if (t % 6 == 0) snd(f, "block.blastfurnace.fire_crackle", 1.4f, 0.5f + t * 0.01f);
            if (t == 28) {
                snd(e.getLocation(), "entity.ravager.roar", 1.8f, 0.5f);
                fx.shockwave(e.getLocation(), 3.5, 6, 0.3);
                shake(e.getLocation(), 18);
            }
        });
    }
}
