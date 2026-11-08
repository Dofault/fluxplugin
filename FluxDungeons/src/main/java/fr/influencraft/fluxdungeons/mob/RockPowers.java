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
 * </ul>
 * Damage goes through the monster's own hit ({@link Caster#hit}): {@code power} is absolute, as for other abilities.
 */
final class RockPowers {

    /** The monster casting. */
    interface Caster {
        LivingEntity entity();

        /** Hurts the player with the ability's power (or this fallback multiple of the monster's hit). */
        void hit(Player p, double fallback);

        void animate(String animation);
    }

    static final Set<String> TYPES = Set.of("boulder", "rockfall", "quake", "ice_spikes", "stone_skin");
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
        if (!animation.isBlank()) c.animate(animation);
        Runnable go = () -> {
            if (!c.entity().isValid() || c.entity().isDead()) return;
            switch (ab.type()) {
                case "boulder" -> boulder(ab, c, target);
                case "rockfall" -> rockfall(ab, c);
                case "quake" -> quake(ab, c);
                case "ice_spikes" -> iceSpikes(ab, c, target);
                case "stone_skin" -> stoneSkin(ab, c);
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
