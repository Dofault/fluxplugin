package fr.influencraft.fluxdungeons.mob;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
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
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Abilities of rock and ice monsters, drawn with moving blocks (block displays, never real blocks: nothing is left in
 * the map). Each one may first play an animation of the monster's model ({@code animation}) and wait for its impact
 * ({@code impact}, ticks).
 * <ul>
 *   <li>{@code boulder}: a block thrown in an arc at the target, rolling over itself, bursting on landing
 *   ({@code block}, {@code size}, {@code radius}, {@code power}).</li>
 *   <li>{@code rockfall}: warning marks on the ground around the players, then blocks falling from above
 *   ({@code count}, {@code spread}, {@code block}, {@code power}).</li>
 *   <li>{@code quake}: a ring of rocks bursting out of the ground and running outwards; those it reaches are hurt and
 *   slowed ({@code radius}, {@code power}).</li>
 *   <li>{@code ice_spikes}: a line of ice spikes rising towards the target, freezing whoever stands on it
 *   ({@code range}, {@code power}).</li>
 *   <li>{@code stone_skin}: stones circling the monster, which takes far less damage meanwhile ({@code duration},
 *   {@code level}).</li>
 *   <li>{@code bite}: a heavy bite in front of the monster ({@code range}, {@code power}).</li>
 *   <li>{@code uplift}: the monster strikes the ground, everyone around flies up {@code height} blocks, blinded while
 *   in the air, with a text on screen ({@code radius}, {@code height}, {@code title}, {@code subtitle}).</li>
 *   <li>{@code cataclysm}: a ring of fire shows the area from the start of the animation until the impact, a warning
 *   is written on the screen of the players near; at the impact everyone inside the ring (the same radius) takes a
 *   huge hit, then waves of rocks run outwards ({@code radius}, {@code power}, {@code title}, {@code subtitle}).</li>
 *   <li>{@code rock_shard}: a shard of rock flies out of the monster and chases a player who keeps away (archers),
 *   {@code min-range} blocks or more ({@code power}).</li>
 * </ul>
 * {@code hold} (ticks): the monster stays still that long (its attack animation must not be cut by walking).
 * Damage goes through the monster's own hit ({@link Caster#hit}): {@code power} is absolute, as for other abilities.
 */
final class RockPowers {

    /** The monster casting. */
    interface Caster {
        LivingEntity entity();

        /** Hurts the player with the ability's power (or this fallback multiple of the monster's hit). */
        void hit(Player p, double fallback);

        void animate(String animation);

        /** The monster stops moving (and casting) for this long. */
        void hold(int ticks);
    }

    static final Set<String> TYPES = Set.of("boulder", "rockfall", "quake", "ice_spikes", "stone_skin", "bite", "uplift",
            "cataclysm", "rock_shard");
    private static final String TAG = "fluxdungeons_fx";
    private static final Material[] ROCKS = {Material.STONE, Material.ANDESITE, Material.GRANITE, Material.DIORITE,
            Material.TUFF, Material.COBBLED_DEEPSLATE, Material.DEEPSLATE, Material.CALCITE};

    private final Plugin plugin;
    private final Set<BlockDisplay> alive = new HashSet<>();

    RockPowers(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Casts the ability: false when the type is not one of these. */
    boolean cast(MobTemplate.Ability ab, Caster c, Player target) {
        if (!TYPES.contains(ab.type())) return false;
        String animation = ab.text("animation", "");
        long impact = (long) ab.number("impact", 0);
        // a bite needs someone in front; a shard someone keeping away: otherwise nothing is played
        if (ab.type().equals("bite") && closest(c.entity(), ab.number("range", 4.5) + 1) == null) return true;
        if (ab.type().equals("rock_shard")) {
            rockShard(ab, c);
            return true;
        }
        if (!animation.isBlank()) c.animate(animation);
        int hold = (int) ab.number("hold", 0);
        if (hold > 0) c.hold(hold);
        if (ab.type().equals("cataclysm")) {
            cataclysm(ab, c, impact);
            return true;
        }
        Runnable go = () -> {
            if (!c.entity().isValid() || c.entity().isDead()) return;
            switch (ab.type()) {
                case "boulder" -> boulder(ab, c, target);
                case "rockfall" -> rockfall(ab, c);
                case "quake" -> quake(ab, c);
                case "ice_spikes" -> iceSpikes(ab, c, target);
                case "stone_skin" -> stoneSkin(ab, c);
                case "bite" -> bite(ab, c);
                case "uplift" -> uplift(ab, c);
                default -> {
                }
            }
        };
        if (impact > 0) Bukkit.getScheduler().runTaskLater(plugin, go, impact);
        else go.run();
        return true;
    }

    /** Every moving block left (plugin stopping). */
    void clear() {
        for (BlockDisplay d : alive) if (d.isValid()) d.remove();
        alive.clear();
    }

    // ---------------------------------------------------------------- boulder

    private void boulder(MobTemplate.Ability ab, Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        BlockData data = material(ab.text("block", "COBBLESTONE"), Material.COBBLESTONE).createBlockData();
        float size = (float) ab.number("size", 1.0);
        double radius = ab.number("radius", 2.5);
        Location from = e.getEyeLocation().add(0, 0.6, 0);
        Location to = target.getLocation();
        double dist = from.distance(to);
        int ticks = (int) Math.max(10, Math.min(32, dist * 1.3));
        double arc = 1.5 + dist * 0.18;
        BlockDisplay rock = block(from, data, size);
        rock.setTeleportDuration(1);
        w.playSound(from, Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.6f);
        w.playSound(from, Sound.BLOCK_STONE_BREAK, 1f, 0.7f);
        new BukkitRunnable() {
            int t;
            float yaw, pitch;

            @Override
            public void run() {
                if (!rock.isValid()) {
                    cancel();
                    return;
                }
                t++;
                double k = (double) t / ticks;
                Location at = from.clone().add(to.clone().subtract(from).toVector().multiply(k)).add(0, 4 * arc * k * (1 - k), 0);
                yaw += 31;
                pitch += 23;
                at.setYaw(yaw);
                at.setPitch(pitch % 180 - 90);
                rock.teleport(at);
                w.spawnParticle(Particle.BLOCK, at, 3, 0.15, 0.15, 0.15, 0, data);
                boolean blocked = t > 2 && !at.getBlock().isPassable();
                if (t >= ticks || blocked) {
                    cancel();
                    burst(at, data, radius, c, 1);
                    forget(rock);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** A rock bursting: debris, dust, sound, and the players around hurt. */
    private void burst(Location at, BlockData data, double radius, Caster c, double fallback) {
        World w = at.getWorld();
        w.spawnParticle(Particle.BLOCK, at, 70, radius * 0.4, 0.4, radius * 0.4, 0.15, data);
        w.spawnParticle(Particle.EXPLOSION, at, 1);
        w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at, 6, radius * 0.3, 0.2, radius * 0.3, 0.02);
        w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.5f);
        w.playSound(at, Sound.BLOCK_STONE_BREAK, 1.2f, 0.6f);
        for (Player p : w.getPlayers()) {
            if (Mobs.fighting(p) && p.getLocation().distanceSquared(at) <= radius * radius) c.hit(p, fallback);
        }
        debris(at, data, 6);
    }

    /** Small pieces flying out and falling back. */
    private void debris(Location at, BlockData data, int count) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            BlockDisplay piece = block(at, data, 0.3f);
            piece.setTeleportDuration(1);
            Vector v = new Vector(r.nextDouble(-0.25, 0.25), r.nextDouble(0.2, 0.45), r.nextDouble(-0.25, 0.25));
            new BukkitRunnable() {
                int t;
                final Location pos = at.clone();

                @Override
                public void run() {
                    if (!piece.isValid() || ++t > 14) {
                        cancel();
                        forget(piece);
                        return;
                    }
                    v.setY(v.getY() - 0.07);
                    pos.add(v);
                    pos.setYaw(pos.getYaw() + 40);
                    if (!pos.getBlock().isPassable()) {
                        cancel();
                        forget(piece);
                        return;
                    }
                    piece.teleport(pos);
                }
            }.runTaskTimer(plugin, 1L, 1L);
        }
    }

    // ---------------------------------------------------------------- rockfall

    private void rockfall(MobTemplate.Ability ab, Caster c) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        List<Player> near = new ArrayList<>();
        for (Player p : w.getPlayers()) if (Mobs.fighting(p) && p.getLocation().distanceSquared(e.getLocation()) < 24 * 24) near.add(p);
        if (near.isEmpty()) return;
        int count = (int) ab.number("count", 5);
        double spread = ab.number("spread", 3);
        BlockData data = material(ab.text("block", "COBBLESTONE"), Material.COBBLESTONE).createBlockData();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        w.playSound(e.getLocation(), Sound.BLOCK_DEEPSLATE_BREAK, 1.2f, 0.5f);
        for (int i = 0; i < count; i++) {
            Player p = near.get(r.nextInt(near.size()));
            Location spot = ground(p.getLocation().add(r.nextDouble(-spread, spread), 0, r.nextDouble(-spread, spread)));
            if (spot == null) continue;
            int delay = i * 5;
            // warning on the ground for a second, then the rock comes down
            new BukkitRunnable() {
                int t;

                @Override
                public void run() {
                    if (++t > 20) {
                        cancel();
                        drop(spot, data, c);
                        return;
                    }
                    for (int k = 0; k < 10; k++) {
                        double a = Math.PI * 2 * k / 10 + t * 0.2;
                        w.spawnParticle(Particle.DUST, spot.clone().add(Math.cos(a) * 1.4, 0.1, Math.sin(a) * 1.4), 1, 0, 0, 0, 0,
                                new Particle.DustOptions(org.bukkit.Color.fromRGB(150, 140, 130), 1.2f));
                    }
                    w.spawnParticle(Particle.FALLING_DUST, spot.clone().add(0, 6, 0), 2, 0.6, 0.2, 0.6, 0, data);
                }
            }.runTaskTimer(plugin, delay, 1L);
        }
    }

    private void drop(Location spot, BlockData data, Caster c) {
        Location start = spot.clone().add(0, 11, 0);
        BlockDisplay rock = block(start, data, 1.3f);
        rock.setTeleportDuration(1);
        new BukkitRunnable() {
            double speed = 0.25;
            final Location pos = start.clone();

            @Override
            public void run() {
                if (!rock.isValid()) {
                    cancel();
                    return;
                }
                speed += 0.09;
                pos.subtract(0, speed, 0);
                pos.setYaw(pos.getYaw() + 12);
                if (pos.getY() <= spot.getY() + 0.3) {
                    cancel();
                    forget(rock);
                    burst(spot.clone().add(0, 0.4, 0), data, 1.8, c, 1);
                    spot.getWorld().playSound(spot, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.5f);
                    return;
                }
                rock.teleport(pos);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ---------------------------------------------------------------- quake

    private void quake(MobTemplate.Ability ab, Caster c) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Location center = e.getLocation();
        double radius = ab.number("radius", 7);
        Set<UUID> hit = new HashSet<>();
        w.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f);
        w.playSound(center, Sound.ENTITY_RAVAGER_ROAR, 0.8f, 0.6f);
        w.spawnParticle(Particle.BLOCK, center, 60, 1.5, 0.2, 1.5, 0.2, Material.STONE.createBlockData());
        new BukkitRunnable() {
            double r = 1;

            @Override
            public void run() {
                if (r > radius || !e.isValid()) {
                    cancel();
                    return;
                }
                int points = (int) Math.max(8, r * 7);
                double offset = ThreadLocalRandom.current().nextDouble(Math.PI);
                for (int i = 0; i < points; i++) {
                    double a = offset + Math.PI * 2 * i / points;
                    Location l = ground(center.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r));
                    if (l == null) continue;
                    pillar(l, ROCKS[ThreadLocalRandom.current().nextInt(ROCKS.length)].createBlockData(), 0.7f, 0.5f + (float) Math.random() * 0.6f, 8);
                }
                w.playSound(center, Sound.BLOCK_STONE_BREAK, 1f, (float) (0.6 + r * 0.05));
                for (Player p : w.getPlayers()) {
                    if (!Mobs.fighting(p) || hit.contains(p.getUniqueId())) continue;
                    double d = Math.hypot(p.getLocation().getX() - center.getX(), p.getLocation().getZ() - center.getZ());
                    if (Math.abs(p.getLocation().getY() - center.getY()) < 2.5 && d <= r + 0.4 && d >= r - 1.1) {
                        hit.add(p.getUniqueId());
                        c.hit(p, 1);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1, false, true));
                    }
                }
                r += 1;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // ---------------------------------------------------------------- ice spikes

    private void iceSpikes(MobTemplate.Ability ab, Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Location from = e.getLocation();
        Vector dir = target.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        dir.normalize();
        double range = ab.number("range", 12);
        Set<UUID> hit = new HashSet<>();
        w.playSound(from, Sound.ENTITY_PLAYER_HURT_FREEZE, 1f, 0.6f);
        new BukkitRunnable() {
            double d = 1.2;

            @Override
            public void run() {
                if (d > range || !e.isValid()) {
                    cancel();
                    return;
                }
                Location l = ground(from.clone().add(dir.clone().multiply(d)));
                if (l == null) {
                    cancel();
                    return;
                }
                boolean big = ((int) d) % 2 == 0;
                pillar(l, (big ? Material.PACKED_ICE : Material.BLUE_ICE).createBlockData(), big ? 0.55f : 0.4f, big ? 1.9f : 1.3f, 26);
                w.spawnParticle(Particle.SNOWFLAKE, l.clone().add(0, 1, 0), 8, 0.3, 0.6, 0.3, 0.02);
                w.playSound(l, Sound.BLOCK_GLASS_PLACE, 0.8f, 1.6f);
                for (Player p : w.getPlayers()) {
                    if (!Mobs.fighting(p) || hit.contains(p.getUniqueId())) continue;
                    Location pl = p.getLocation();
                    if (Math.hypot(pl.getX() - l.getX(), pl.getZ() - l.getZ()) < 1.3 && Math.abs(pl.getY() - l.getY()) < 2) {
                        hit.add(p.getUniqueId());
                        c.hit(p, 1);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 3, false, true));
                        p.setFreezeTicks(Math.max(p.getFreezeTicks(), 120));
                    }
                }
                d += 1;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ---------------------------------------------------------------- stone skin

    private void stoneSkin(MobTemplate.Ability ab, Caster c) {
        LivingEntity e = c.entity();
        int ticks = (int) (ab.number("duration", 6) * 20);
        e.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ticks, (int) ab.number("level", 2) - 1, false, true));
        e.getWorld().playSound(e.getLocation(), Sound.BLOCK_DEEPSLATE_PLACE, 1.2f, 0.5f);
        List<BlockDisplay> stones = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            BlockDisplay s = block(e.getLocation(), (i % 2 == 0 ? Material.CALCITE : Material.POLISHED_DEEPSLATE).createBlockData(), 0.45f);
            s.setTeleportDuration(2);
            stones.add(s);
        }
        double height = Math.max(1, e.getHeight() * 0.55);
        double ring = Math.max(1.1, e.getWidth() * 0.9);
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (++t > ticks || !e.isValid() || e.isDead()) {
                    cancel();
                    for (BlockDisplay s : stones) {
                        s.getWorld().spawnParticle(Particle.BLOCK, s.getLocation(), 10, 0.2, 0.2, 0.2, 0, s.getBlock());
                        forget(s);
                    }
                    return;
                }
                Location base = e.getLocation();
                for (int i = 0; i < stones.size(); i++) {
                    double a = t * 0.12 + Math.PI * 2 * i / stones.size();
                    Location at = base.clone().add(Math.cos(a) * ring, height + Math.sin(t * 0.15 + i) * 0.25, Math.sin(a) * ring);
                    at.setYaw((float) Math.toDegrees(a));
                    stones.get(i).teleport(at);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ---------------------------------------------------------------- bite

    private void bite(MobTemplate.Ability ab, Caster c) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double range = ab.number("range", 4.5);
        Vector facing = e.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 0.01) facing = new Vector(0, 0, 1);
        facing.normalize();
        Location mouth = e.getLocation().add(facing.clone().multiply(Math.max(1.5, e.getWidth() * 0.8))).add(0, 1, 0);
        w.playSound(mouth, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1.5f, 0.5f);
        w.playSound(mouth, Sound.BLOCK_DEEPSLATE_BREAK, 1.5f, 0.6f);
        w.spawnParticle(Particle.BLOCK, mouth, 40, 0.8, 0.5, 0.8, 0.1, Material.DEEPSLATE.createBlockData());
        w.spawnParticle(Particle.CRIT, mouth, 20, 0.6, 0.4, 0.6, 0.2);
        for (Player p : w.getPlayers()) {
            if (!Mobs.fighting(p)) continue;
            Vector to = p.getLocation().toVector().subtract(e.getLocation().toVector());
            double flat = Math.hypot(to.getX(), to.getZ());
            if (flat > range + e.getWidth() / 2 || Math.abs(to.getY()) > 3) continue;
            if (flat > 1 && to.clone().setY(0).normalize().dot(facing) < 0.35) continue;
            c.hit(p, 1.5);
        }
    }

    // ---------------------------------------------------------------- uplift

    private void uplift(MobTemplate.Ability ab, Caster c) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        Location center = e.getLocation();
        double radius = ab.number("radius", 6);
        double vy = launchSpeed(ab.number("height", 5));
        w.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.4f);
        w.playSound(center, Sound.BLOCK_ANVIL_LAND, 1f, 0.4f);
        w.spawnParticle(Particle.EXPLOSION, center.clone().add(0, 0.5, 0), 4, 1.5, 0.2, 1.5, 0);
        // a ring of rocks bursting out at once, as far as the hit goes
        for (double r = 1.5; r <= radius; r += 1.5) {
            int points = (int) Math.max(8, r * 6);
            for (int i = 0; i < points; i++) {
                double a = Math.PI * 2 * i / points;
                Location l = ground(center.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r));
                if (l != null) pillar(l, ROCKS[ThreadLocalRandom.current().nextInt(ROCKS.length)].createBlockData(), 0.8f, 0.9f, 10);
            }
        }
        String title = ab.text("title", ""), subtitle = ab.text("subtitle", "");
        for (Player p : w.getPlayers()) {
            if (!Mobs.fighting(p) || p.getLocation().distanceSquared(center) > radius * radius) continue;
            c.hit(p, 0.6);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline() || p.isDead()) return;
                p.setVelocity(new Vector(0, vy, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 45, 0, false, false, false));
                if (!title.isBlank() || !subtitle.isBlank()) {
                    p.showTitle(net.kyori.adventure.title.Title.title(fr.influencraft.flux.api.text.Texts.parse(title),
                            fr.influencraft.flux.api.text.Texts.parse(subtitle), net.kyori.adventure.title.Title.Times.times(
                                    java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(1500), java.time.Duration.ofMillis(400))));
                }
            });
        }
    }

    /** Upward speed that lifts a player about {@code height} blocks (vanilla gravity and drag). */
    private static double launchSpeed(double height) {
        double v = 0.4;
        for (int i = 0; i < 40; i++) {
            double y = 0, s = v;
            while (s > 0) {
                y += s;
                s = (s - 0.08) * 0.98;
            }
            v += (height - y) * 0.05;
        }
        return Math.max(0.3, Math.min(2.5, v));
    }

    // ---------------------------------------------------------------- cataclysm

    private void cataclysm(MobTemplate.Ability ab, Caster c, long impact) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double radius = ab.number("radius", 10);
        long charge = Math.max(20, impact);
        String title = ab.text("title", ""), subtitle = ab.text("subtitle", "");
        for (Player p : w.getPlayers()) {
            if (!Mobs.fighting(p) || p.getLocation().distanceSquared(e.getLocation()) > radius * radius * 6.25) continue;
            if (!title.isBlank() || !subtitle.isBlank()) {
                p.showTitle(net.kyori.adventure.title.Title.title(fr.influencraft.flux.api.text.Texts.parse(title),
                        fr.influencraft.flux.api.text.Texts.parse(subtitle), net.kyori.adventure.title.Title.Times.times(
                                java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(charge * 50 - 600), java.time.Duration.ofMillis(300))));
            }
            p.playSound(p.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, 0.5f);
        }
        w.playSound(e.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 2f, 0.4f);
        // the ring of fire shows exactly where the hit will land, brighter as the impact comes
        new BukkitRunnable() {
            long t;

            @Override
            public void run() {
                if (!e.isValid() || e.isDead()) {
                    cancel();
                    return;
                }
                t += 2;
                Location center = e.getLocation();
                double k = Math.min(1, (double) t / charge);
                int points = (int) (radius * 9);
                double spin = t * 0.05;
                for (int i = 0; i < points; i++) {
                    double a = spin + Math.PI * 2 * i / points;
                    Location l = center.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius);
                    w.spawnParticle(Particle.FLAME, l, 1, 0.05, 0.05, 0.05, 0.005);
                    if (k > 0.5 && i % 3 == 0) w.spawnParticle(Particle.SOUL_FIRE_FLAME, l.clone().add(0, 0.4, 0), 1, 0.05, 0.2, 0.05, 0.01);
                }
                int inner = (int) (6 + 30 * k);
                for (int i = 0; i < inner; i++) {
                    double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2), r = Math.sqrt(ThreadLocalRandom.current().nextDouble()) * radius;
                    w.spawnParticle(k > 0.75 ? Particle.LAVA : Particle.SMALL_FLAME, center.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r), 1, 0, 0, 0, 0);
                }
                if (t % 20 == 0) w.playSound(center, Sound.BLOCK_FIRE_AMBIENT, 2f, 0.6f + (float) k);
                if (t >= charge) {
                    cancel();
                    cataclysmImpact(ab, c, center, radius);
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void cataclysmImpact(MobTemplate.Ability ab, Caster c, Location center, double radius) {
        World w = center.getWorld();
        w.spawnParticle(Particle.EXPLOSION_EMITTER, center.clone().add(0, 1, 0), 2);
        w.spawnParticle(Particle.FLAME, center.clone().add(0, 0.5, 0), 300, radius / 2, 0.5, radius / 2, 0.15);
        w.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.4f);
        w.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 2f, 0.6f);
        for (Player p : w.getPlayers()) {
            if (!Mobs.fighting(p)) continue;
            Location pl = p.getLocation();
            if (Math.hypot(pl.getX() - center.getX(), pl.getZ() - center.getZ()) <= radius && Math.abs(pl.getY() - center.getY()) < 5) {
                c.hit(p, 3);
                p.setFireTicks(0);
            }
        }
        // three waves of rock pillars running outwards, then the last pulse
        for (int wave = 0; wave < 3; wave++) {
            double r = radius * (0.4 + wave * 0.3);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                int points = (int) Math.max(10, r * 5);
                for (int i = 0; i < points; i++) {
                    double a = Math.PI * 2 * i / points;
                    Location l = ground(center.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r));
                    if (l != null) pillar(l, (i % 2 == 0 ? Material.DEEPSLATE : Material.COBBLED_DEEPSLATE).createBlockData(), 1.1f, 1.8f, 16);
                }
                w.spawnParticle(Particle.BLOCK, center, 50, r / 2, 0.3, r / 2, 0.1, Material.AMETHYST_BLOCK.createBlockData());
                w.playSound(center, Sound.BLOCK_DEEPSLATE_BREAK, 2f, 0.5f);
            }, 10L + wave * 10L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            w.spawnParticle(Particle.REVERSE_PORTAL, center.clone().add(0, 1.5, 0), 120, radius / 3, 1, radius / 3, 0.05);
            w.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 2f, 0.5f);
        }, 40L);
    }

    // ---------------------------------------------------------------- rock shard

    /** A shard of rock chasing a player who keeps away from the monster (archers): melee is the way to fight it. */
    private void rockShard(MobTemplate.Ability ab, Caster c) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double min = ab.number("min-range", 7);
        Player target = null;
        double best = 0;
        for (Player p : w.getPlayers()) {
            if (!Mobs.fighting(p) || !e.hasLineOfSight(p)) continue;
            double d = p.getLocation().distance(e.getLocation());
            if (d < min || d > 32) continue;
            if (d > best) {
                best = d;
                target = p;
            }
        }
        if (target == null) return;
        Player aim = target;
        Location start = e.getLocation().add(0, Math.max(1.5, e.getHeight()), 0);
        BlockDisplay shard = block(start, (ThreadLocalRandom.current().nextBoolean() ? Material.DEEPSLATE : Material.BASALT).createBlockData(), 0.55f);
        shard.setTeleportDuration(1);
        w.playSound(start, Sound.BLOCK_POINTED_DRIPSTONE_FALL, 1.5f, 0.6f);
        w.spawnParticle(Particle.BLOCK, start, 15, 0.3, 0.3, 0.3, 0, Material.DEEPSLATE.createBlockData());
        new BukkitRunnable() {
            int t;
            final Location pos = start.clone();

            @Override
            public void run() {
                if (!shard.isValid() || ++t > 70 || !aim.isOnline() || aim.isDead() || aim.getWorld() != pos.getWorld()) {
                    cancel();
                    forget(shard);
                    return;
                }
                Vector to = aim.getLocation().add(0, 1, 0).toVector().subtract(pos.toVector());
                double dist = to.length();
                if (dist < 1.1) {
                    cancel();
                    forget(shard);
                    c.hit(aim, 0.5);
                    w.spawnParticle(Particle.BLOCK, pos, 25, 0.3, 0.3, 0.3, 0.1, Material.DEEPSLATE.createBlockData());
                    w.playSound(pos, Sound.BLOCK_DEEPSLATE_BREAK, 1.2f, 1.2f);
                    return;
                }
                pos.add(to.multiply(Math.min(dist, 0.75) / dist));
                pos.setYaw(pos.getYaw() + 35);
                pos.setPitch((pos.getPitch() + 25) % 180 - 90);
                if (!pos.getBlock().isPassable()) {
                    cancel();
                    forget(shard);
                    w.spawnParticle(Particle.BLOCK, pos, 15, 0.2, 0.2, 0.2, 0.05, Material.DEEPSLATE.createBlockData());
                    return;
                }
                shard.teleport(pos);
                w.spawnParticle(Particle.FALLING_DUST, pos, 1, 0.1, 0.1, 0.1, 0, Material.DEEPSLATE.createBlockData());
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** The closest player fighting within this distance of the monster, or null. */
    private static Player closest(LivingEntity e, double distance) {
        Player best = null;
        double bestD = distance * distance;
        for (Player p : e.getWorld().getPlayers()) {
            if (!Mobs.fighting(p)) continue;
            double d = p.getLocation().distanceSquared(e.getLocation());
            if (d <= bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- moving blocks

    /** A block display centred on {@code at}, {@code size} blocks wide, never saved. */
    private BlockDisplay block(Location at, BlockData data, float size) {
        BlockDisplay d = at.getWorld().spawn(at, BlockDisplay.class, b -> {
            b.setBlock(data);
            b.setPersistent(false);
            b.setBrightness(new Display.Brightness(12, 15));
            b.setTransformation(new Transformation(new Vector3f(-size / 2, -size / 2, -size / 2), new AxisAngle4f(),
                    new Vector3f(size, size, size), new AxisAngle4f()));
            b.addScoreboardTag(TAG);
        });
        alive.add(d);
        return d;
    }

    /**
     * A pillar bursting out of the ground at {@code at} (feet level): starts under the ground, rises in 3 ticks, stays,
     * then sinks back and disappears ({@code life} ticks in all).
     */
    private void pillar(Location at, BlockData data, float width, float height, int life) {
        Location base = at.clone();
        base.setYaw(ThreadLocalRandom.current().nextFloat() * 360);
        base.setPitch(0);
        BlockDisplay d = base.getWorld().spawn(base, BlockDisplay.class, b -> {
            b.setBlock(data);
            b.setPersistent(false);
            b.setBrightness(new Display.Brightness(12, 15));
            b.setTransformation(new Transformation(new Vector3f(-width / 2, -height - 0.05f, -width / 2), new AxisAngle4f(),
                    new Vector3f(width, height, width), new AxisAngle4f()));
            b.addScoreboardTag(TAG);
        });
        alive.add(d);
        base.getWorld().spawnParticle(Particle.BLOCK, base.clone().add(0, 0.2, 0), 6, width * 0.4, 0.1, width * 0.4, 0, data);
        Bukkit.getScheduler().runTaskLater(plugin, () -> move(d, width, height, 0, 3), 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> move(d, width, height, -height - 0.05f, 4), life - 4L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (d.isValid()) base.getWorld().spawnParticle(Particle.BLOCK, base.clone().add(0, 0.3, 0), 8, width * 0.4, 0.2, width * 0.4, 0, data);
            forget(d);
        }, life);
    }

    private static void move(BlockDisplay d, float width, float height, float y, int ticks) {
        if (!d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(new Transformation(new Vector3f(-width / 2, y, -width / 2), new AxisAngle4f(),
                new Vector3f(width, height, width), new AxisAngle4f()));
    }

    private void forget(BlockDisplay d) {
        alive.remove(d);
        if (d.isValid()) d.remove();
    }

    /** The floor under (or just above) a place: feet level on the first solid block, null when there is none near. */
    private static Location ground(Location l) {
        Block b = l.getBlock();
        for (int dy = 2; dy >= -5; dy--) {
            Block at = b.getRelative(0, dy, 0);
            if (at.isPassable() && !at.getRelative(0, -1, 0).isPassable()) {
                return new Location(l.getWorld(), l.getX(), at.getY(), l.getZ());
            }
        }
        return null;
    }

    private static Material material(String name, Material fallback) {
        Material m = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        return m == null || !m.isBlock() ? fallback : m;
    }
}
