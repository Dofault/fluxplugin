package fr.influencraft.fluxdungeons.mob;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Abilities of creatures of the water (the Tide Otter first), meant to be cast from the surface by a swimming monster:
 * <ul>
 *   <li>{@code geyser}: the water boils under each player (and a few spots around), then bursts up and throws whoever
 *   stayed there ({@code delay} ticks after the cast, {@code radius}, {@code height}, {@code extra} spots);</li>
 *   <li>{@code whirlpool}: a whirl at the monster's home (centre of its pool) pulls and spins the swimmers for
 *   {@code duration} ticks; at its heart they lose air and life ({@code radius}, {@code pull});</li>
 *   <li>{@code bubble_barrage}: {@code shots} bubbles spat one after the other at the players, slowly homing; a bubble
 *   slows and takes air ({@code speed}, {@code spacing});</li>
 *   <li>{@code tidal_breach}: the monster goes to its home, a warning ring shows the danger, then (with its animation
 *   leaping out and falling flat) a tidal wave runs out from the centre and throws everyone it reaches
 *   ({@code radius}, {@code launch}, {@code move} ticks to reach the centre).</li>
 * </ul>
 * Like the rock abilities: {@code animation}, {@code impact} (ticks to the effect), {@code hold}, {@code power}.
 */
final class TidePowers {

    static final Set<String> TYPES = Set.of("geyser", "whirlpool", "bubble_barrage", "tidal_breach");

    private final Plugin plugin;
    private final Set<BlockDisplay> bubbles = new HashSet<>();

    TidePowers(Plugin plugin) {
        this.plugin = plugin;
    }

    boolean cast(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        if (!TYPES.contains(ab.type())) return false;
        switch (ab.type()) {
            case "geyser" -> geyser(ab, c);
            case "whirlpool" -> whirlpool(ab, c);
            case "bubble_barrage" -> barrage(ab, c, target);
            case "tidal_breach" -> breach(ab, c);
            default -> {
            }
        }
        return true;
    }

    private static List<Player> fighters(LivingEntity e, double within) {
        List<Player> out = new ArrayList<>();
        for (Player p : e.getWorld().getPlayers()) {
            if (Mobs.fighting(p) && p.getLocation().distanceSquared(e.getLocation()) <= within * within) out.add(p);
        }
        return out;
    }

    private static void start(MobTemplate.Ability ab, RockPowers.Caster c) {
        String animation = ab.text("animation", "");
        if (!animation.isBlank()) c.animate(animation);
        int hold = (int) ab.number("hold", 0);
        if (hold > 0) c.hold(hold);
    }

    // ---------------------------------------------------------------- geyser

    private void geyser(MobTemplate.Ability ab, RockPowers.Caster c) {
        start(ab, c);
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int delay = (int) Math.max(10, ab.number("delay", 30));
        double radius = ab.number("radius", 1.4), height = ab.number("height", 4);
        List<Location> spots = new ArrayList<>();
        for (Player p : fighters(e, 32)) spots.add(p.getLocation());
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < (int) ab.number("extra", 2); i++) {
            Location home = c.home();
            spots.add(home.clone().add(r.nextDouble(-7, 7), 0, r.nextDouble(-7, 7)));
        }
        w.playSound(e.getLocation(), Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, 2f, 0.6f);
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                t += 2;
                if (t < delay) {
                    // the water boils where it will burst, more and more
                    double k = (double) t / delay;
                    for (Location s : spots) {
                        for (int i = 0; i < 10; i++) {
                            double a = Math.PI * 2 * i / 10 + t * 0.2;
                            w.spawnParticle(Particle.BUBBLE_POP, s.clone().add(Math.cos(a) * radius, 0.2, Math.sin(a) * radius), 1, 0, 0, 0, 0);
                        }
                        w.spawnParticle(Particle.BUBBLE_COLUMN_UP, s.clone().add(0, -0.5, 0), (int) (4 + 12 * k), radius / 2, 0.3, radius / 2, 0.1);
                        w.spawnParticle(Particle.SPLASH, s.clone().add(0, 0.3, 0), (int) (2 + 10 * k), radius / 2, 0.05, radius / 2, 0.1);
                        if (t % 10 == 0) w.playSound(s, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 1.2f, 0.6f + (float) k);
                    }
                    return;
                }
                cancel();
                for (Location s : spots) {
                    for (double y = 0; y < height; y += 0.5) {
                        w.spawnParticle(Particle.SPLASH, s.clone().add(0, y, 0), 12, 0.35, 0.2, 0.35, 0.3);
                        w.spawnParticle(Particle.FALLING_WATER, s.clone().add(0, y, 0), 6, 0.4, 0.2, 0.4, 0);
                    }
                    w.spawnParticle(Particle.CLOUD, s.clone().add(0, height, 0), 15, 0.5, 0.3, 0.5, 0.05);
                    w.playSound(s, Sound.ENTITY_GENERIC_SPLASH, 1.6f, 0.6f);
                    w.playSound(s, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, 1.6f, 0.8f);
                    for (Player p : fighters(e, 40)) {
                        Location pl = p.getLocation();
                        if (Math.hypot(pl.getX() - s.getX(), pl.getZ() - s.getZ()) > radius + 0.4 || Math.abs(pl.getY() - s.getY()) > 3) continue;
                        c.hit(p, 1.2);
                        p.setVelocity(new Vector(0, RockPowers.launchSpeed(height), 0));
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // ---------------------------------------------------------------- whirlpool

    private void whirlpool(MobTemplate.Ability ab, RockPowers.Caster c) {
        start(ab, c);
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Location center = c.home().clone();
        int duration = (int) ab.number("duration", 100), begin = (int) ab.number("impact", 12);
        double radius = ab.number("radius", 7), pull = ab.number("pull", 0.07);
        boolean clockwise = ThreadLocalRandom.current().nextBoolean();
        w.playSound(center, Sound.BLOCK_CONDUIT_ACTIVATE, 2f, 0.6f);
        // the monster itself spins (its animation leans and sweeps, the body turns here): {@code spin} turns in
        // {@code spin-ticks}, kept out of the animation so the next one does not unwind it
        int spinTicks = (int) ab.number("spin-ticks", 24);
        double turns = ab.number("spin", 2);
        for (int i = 1; i <= spinTicks; i++) {
            int n = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!e.isValid() || e.isDead()) return;
                Location l = e.getLocation();
                l.setYaw((float) (l.getYaw() + (clockwise ? 1 : -1) * 360 * turns / spinTicks));
                Mobs.move(e, l);
                if (n % 3 == 0) w.spawnParticle(Particle.SPLASH, l.clone().add(0, 0.8, 0), 25, 1.2, 0.1, 1.2, 0.3);
                if (n % 8 == 0) w.playSound(l, Sound.ENTITY_PLAYER_SPLASH, 1.2f, 0.7f + n * 0.02f);
            }, i);
        }
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                t++;
                if (t < begin) return;
                int life = t - begin;
                if (life > duration || !e.isValid()) {
                    cancel();
                    w.playSound(center, Sound.BLOCK_CONDUIT_DEACTIVATE, 2f, 0.7f);
                    return;
                }
                double k = Math.min(1, life / 20.0) * Math.min(1, (duration - life) / 20.0);
                // three arms turning towards the heart
                if (life % 2 == 0) {
                    for (int arm = 0; arm < 3; arm++) {
                        for (double rr = 0.6; rr <= radius; rr += 0.7) {
                            double a = (clockwise ? 1 : -1) * (life * 0.25 - rr * 0.55) + arm * Math.PI * 2 / 3;
                            Location l = center.clone().add(Math.cos(a) * rr, 0.25, Math.sin(a) * rr);
                            w.spawnParticle(rr < 2.5 ? Particle.BUBBLE_POP : Particle.SPLASH, l, 1, 0.05, 0.02, 0.05, 0);
                        }
                    }
                    w.spawnParticle(Particle.NAUTILUS, center.clone().add(0, 0.5, 0), 6, 1, 0.3, 1, 0.5);
                    w.spawnParticle(Particle.BUBBLE_COLUMN_UP, center.clone().add(0, -1, 0), 10, 0.6, 0.6, 0.6, 0.1);
                }
                if (life % 20 == 0) w.playSound(center, Sound.BLOCK_CONDUIT_AMBIENT, 2f, 0.6f);
                for (Player p : fighters(e, 40)) {
                    Location pl = p.getLocation();
                    Vector in = center.toVector().subtract(pl.toVector()).setY(0);
                    double d = in.length();
                    if (d > radius || Math.abs(pl.getY() - center.getY()) > 5 || d < 0.01) continue;
                    Vector tangent = new Vector(-in.getZ(), 0, in.getX()).normalize().multiply(clockwise ? 1 : -1);
                    Vector v = p.getVelocity().add(in.normalize().multiply(pull * k)).add(tangent.multiply(0.09 * k));
                    p.setVelocity(v);
                    if (d < 1.8 && life % 10 == 0) {
                        // the heart of the whirl drags him under: air and life go
                        c.hit(p, 0.5);
                        p.setRemainingAir(Math.max(-20, p.getRemainingAir() - 60));
                        w.playSound(pl, Sound.ENTITY_PLAYER_HURT_DROWN, 1f, 0.8f);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ---------------------------------------------------------------- bubble barrage

    private void barrage(MobTemplate.Ability ab, RockPowers.Caster c, Player first) {
        start(ab, c);
        LivingEntity e = c.entity();
        int shots = (int) ab.number("shots", 3), impact = (int) ab.number("impact", 12), spacing = (int) ab.number("spacing", 6);
        for (int i = 0; i < shots; i++) {
            int n = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!e.isValid() || e.isDead()) return;
                List<Player> all = fighters(e, 30);
                if (all.isEmpty()) return;
                Player aim = n == 0 && first != null && all.contains(first) ? first : all.get(ThreadLocalRandom.current().nextInt(all.size()));
                bubble(ab, c, aim);
            }, impact + (long) i * spacing);
        }
    }

    private void bubble(MobTemplate.Ability ab, RockPowers.Caster c, Player aim) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Vector facing = e.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 0.01) facing = new Vector(0, 0, 1);
        Location at = e.getLocation().add(facing.normalize().multiply(1.6)).add(0, 1.2, 0);
        float size = (float) ab.number("size", 0.7);
        BlockDisplay d = w.spawn(at, BlockDisplay.class, b -> {
            b.setBlock(Material.LIGHT_BLUE_STAINED_GLASS.createBlockData());
            b.setPersistent(false);
            b.setTeleportDuration(1);
            b.setInterpolationDuration(1);
            b.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            b.setTransformation(new Transformation(new Vector3f(-size / 2, -size / 2, -size / 2), new AxisAngle4f(),
                    new Vector3f(size, size, size), new AxisAngle4f()));
        });
        bubbles.add(d);
        w.playSound(at, Sound.ENTITY_PUFFER_FISH_BLOW_OUT, 1.6f, 1.2f);
        double speed = ab.number("speed", 0.55);
        Vector v = aim.getEyeLocation().toVector().subtract(at.toVector()).normalize().multiply(speed);
        new BukkitRunnable() {
            int t;
            final Location pos = at.clone();

            @Override
            public void run() {
                if (!d.isValid() || ++t > 60) {
                    pop(d, pos, false);
                    cancel();
                    return;
                }
                // a slow homing: it bends a little towards its target each tick
                if (aim.isOnline() && !aim.isDead()) {
                    Vector want = aim.getLocation().add(0, 1, 0).toVector().subtract(pos.toVector()).normalize().multiply(speed);
                    v.multiply(0.92).add(want.multiply(0.08)).normalize().multiply(speed);
                }
                pos.add(v);
                if (pos.getBlock().getType().isSolid()) {
                    pop(d, pos, true);
                    cancel();
                    return;
                }
                d.teleport(pos);
                d.setTransformation(new Transformation(new Vector3f(-size / 2, -size / 2, -size / 2),
                        new Quaternionf().rotateXYZ(t * 0.2f, t * 0.3f, 0), new Vector3f(size, size, size), new Quaternionf()));
                d.setInterpolationDelay(0);
                w.spawnParticle(Particle.BUBBLE_POP, pos, 3, 0.15, 0.15, 0.15, 0.01);
                if (t % 3 == 0) w.spawnParticle(Particle.SPLASH, pos, 2, 0.1, 0.1, 0.1, 0.05);
                for (Player p : fighters(e, 40)) {
                    if (p.getLocation().add(0, 1, 0).distanceSquared(pos) > 1.1 * 1.1) continue;
                    c.hit(p, 0.8);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, true));
                    p.setRemainingAir(Math.max(-20, p.getRemainingAir() - 90));
                    pop(d, pos, true);
                    cancel();
                    return;
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void pop(BlockDisplay d, Location at, boolean loud) {
        bubbles.remove(d);
        if (d.isValid()) d.remove();
        World w = at.getWorld();
        w.spawnParticle(Particle.BUBBLE_POP, at, 25, 0.4, 0.4, 0.4, 0.1);
        w.spawnParticle(Particle.SPLASH, at, 20, 0.3, 0.3, 0.3, 0.2);
        w.playSound(at, Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, loud ? 2f : 1f, 0.7f);
    }

    // ---------------------------------------------------------------- tidal breach

    private void breach(MobTemplate.Ability ab, RockPowers.Caster c) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int move = (int) Math.max(1, ab.number("move", 12)), impact = (int) ab.number("impact", 63);
        int hold = (int) Math.max(move + impact, ab.number("hold", move + impact));
        double radius = ab.number("radius", 7), launch = ab.number("launch", 3);
        c.hold(hold);
        Location from = e.getLocation().clone();
        // the centre at the surface: its home is where it came out of the ice, at the water's level
        Location center = c.home().clone();
        String subtitle = ab.text("subtitle", ""), title = ab.text("title", "");
        for (Player p : fighters(e, 40)) {
            if (!title.isBlank() || !subtitle.isBlank()) {
                p.showTitle(net.kyori.adventure.title.Title.title(fr.influencraft.flux.api.text.Texts.parse(title),
                        fr.influencraft.flux.api.text.Texts.parse(subtitle), net.kyori.adventure.title.Title.Times.times(
                                java.time.Duration.ofMillis(200), java.time.Duration.ofMillis((move + impact) * 50L - 600), java.time.Duration.ofMillis(300))));
            }
        }
        w.playSound(from, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 2f, 0.7f);
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (!e.isValid() || e.isDead()) {
                    cancel();
                    return;
                }
                t++;
                if (t <= move) {
                    // swims fast to the centre, under the surface
                    double u = (double) t / move, f = u * u * (3 - 2 * u);
                    Location l = from.clone().add(center.toVector().subtract(from.toVector()).multiply(f));
                    l.setYaw(e.getLocation().getYaw());
                    Mobs.move(e, l);
                    w.spawnParticle(Particle.BUBBLE, l.clone().add(0, 0.6, 0), 6, 0.4, 0.3, 0.4, 0.02);
                    if (t == move) c.animate(ab.text("animation", ""));
                }
                // the ring of the danger, brighter as the fall comes
                if (t % 2 == 0 && t < move + impact) {
                    double k = (double) t / (move + impact);
                    int points = (int) (radius * 8);
                    for (int i = 0; i < points; i++) {
                        double a = Math.PI * 2 * i / points + t * 0.03;
                        Location l = center.clone().add(Math.cos(a) * radius, 0.2, Math.sin(a) * radius);
                        w.spawnParticle(Particle.SPLASH, l, 2, 0.05, 0.05, 0.05, 0.02);
                        if (k > 0.5 && i % 3 == 0) w.spawnParticle(Particle.BUBBLE_POP, l.clone().add(0, 0.3, 0), 1, 0, 0.1, 0, 0);
                    }
                    if (t % 20 == 0) w.playSound(center, Sound.BLOCK_CONDUIT_AMBIENT_SHORT, 2f, 0.5f + (float) k);
                }
                if (t == move + impact) {
                    wave(ab, c, center, radius, launch);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** A ring of water running out from the centre: everyone it reaches is hit once and thrown out and up. */
    private void wave(MobTemplate.Ability ab, RockPowers.Caster c, Location center, double radius, double launch) {
        World w = center.getWorld();
        w.spawnParticle(Particle.EXPLOSION_EMITTER, center.clone().add(0, 1, 0), 1);
        w.spawnParticle(Particle.SPLASH, center.clone().add(0, 1, 0), 400, 2, 1, 2, 0.6);
        w.spawnParticle(Particle.FALLING_WATER, center.clone().add(0, 4, 0), 200, 2.5, 2, 2.5, 0);
        w.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 2.5f, 0.5f);
        w.playSound(center, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 2.5f, 0.5f);
        w.playSound(center, Sound.AMBIENT_UNDERWATER_EXIT, 2f, 0.6f);
        double reach = radius + 3;
        int steps = 14;
        Set<UUID> hit = new HashSet<>();
        double vy = RockPowers.launchSpeed(launch);
        for (int s = 0; s <= steps; s++) {
            double r = 1 + (reach - 1) * s / steps;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                int points = (int) Math.max(12, r * 7);
                for (int i = 0; i < points; i++) {
                    double a = Math.PI * 2 * i / points;
                    Location l = center.clone().add(Math.cos(a) * r, 0.3, Math.sin(a) * r);
                    w.spawnParticle(Particle.SPLASH, l, 4, 0.1, 0.4, 0.1, 0.2);
                    if (i % 2 == 0) w.spawnParticle(Particle.FALLING_WATER, l.clone().add(0, 1.4, 0), 2, 0.1, 0.3, 0.1, 0);
                }
                if (r < reach * 0.6) w.spawnParticle(Particle.CLOUD, center, 10, r / 2, 0.2, r / 2, 0.05);
                for (Player p : center.getWorld().getPlayers()) {
                    if (!Mobs.fighting(p) || hit.contains(p.getUniqueId())) continue;
                    Location pl = p.getLocation();
                    double d = Math.hypot(pl.getX() - center.getX(), pl.getZ() - center.getZ());
                    if (d > r + 0.8 || Math.abs(pl.getY() - center.getY()) > 4) continue;
                    hit.add(p.getUniqueId());
                    c.hit(p, 2.5);
                    Vector out = pl.toVector().subtract(center.toVector()).setY(0);
                    if (out.lengthSquared() < 0.01) out = new Vector(1, 0, 0);
                    p.setVelocity(out.normalize().multiply(1.2).setY(vy));
                    w.playSound(pl, Sound.ENTITY_GENERIC_SPLASH, 1.5f, 0.6f);
                }
            }, s);
        }
    }

    /** Every bubble still flying (plugin stopping). */
    void clear() {
        for (BlockDisplay d : bubbles) if (d.isValid()) d.remove();
        bubbles.clear();
    }
}
