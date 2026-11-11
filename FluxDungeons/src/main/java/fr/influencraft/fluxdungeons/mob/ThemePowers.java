package fr.influencraft.fluxdungeons.mob;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Abilities of the dungeon troops, one set per theme, every one telegraphed and drawn with particles and moving items
 * (nothing is left in the map). Any monster may take any of them.
 * <ul>
 *   <li>water: {@code hook_pull} (a hook thrown at a player who keeps away, who is reeled in), {@code water_jet} (a
 *   line shown on the ground, then a jet that pushes back), {@code ink_cloud} (a cloud of ink lobbed on a player:
 *   blinded and slowed while inside), {@code net_throw} (a net falling on a player: rooted, {@code message} in his
 *   action bar);</li>
 *   <li>fields: {@code sweep} (an arc shown in front, then a sweeping blow: scythe, pickaxe or claw by {@code look}),
 *   {@code seed_volley} (seeds shot in a fan), {@code pollen_cloud} (a cloud that keeps hurting whoever stands in it),
 *   {@code crow_swarm} (crows circling a player, then diving at him);</li>
 *   <li>earth: {@code burrow_ambush} (the monster digs in and comes out under a player, throwing him up).</li>
 * </ul>
 * Shared: {@code power} (damage, through the monster's hit), {@code impact} (ticks of warning), {@code hold} (ticks
 * the monster stays still), {@code far-min} (blocks: aim at the player furthest away when somebody keeps that far).
 */
final class ThemePowers {

    static final Set<String> TYPES = Set.of("hook_pull", "water_jet", "ink_cloud", "net_throw", "sweep", "seed_volley",
            "pollen_cloud", "crow_swarm", "burrow_ambush");

    private final Plugin plugin;
    private final Fx fx;
    private final Set<org.bukkit.entity.Entity> alive = new HashSet<>();

    ThemePowers(Plugin plugin, Fx fx) {
        this.plugin = plugin;
        this.fx = fx;
    }

    /** Whether there is somebody to hit (abilities that would hit nobody wait). */
    boolean worth(MobTemplate.Ability ab, LivingEntity e) {
        if (!TYPES.contains(ab.type())) return true;
        return switch (ab.type()) {
            case "hook_pull" -> TidePowers.farthest(e, ab.number("min-range", 6)) != null;
            case "sweep" -> nearest(e, ab.number("range", 3.5) + 0.8) != null;
            case "water_jet" -> nearest(e, ab.number("range", 9)) != null;
            default -> nearest(e, 24) != null;
        };
    }

    boolean cast(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        if (!TYPES.contains(ab.type())) return false;
        Player aim = target;
        if (ab.number("far-min", 0) > 0) {
            Player far = TidePowers.farthest(c.entity(), ab.number("far-min", 0));
            if (far != null) aim = far;
        }
        String animation = ab.text("animation", "");
        if (!animation.isBlank()) c.animate(animation);
        int hold = (int) ab.number("hold", 0);
        if (hold > 0) c.hold(hold);
        switch (ab.type()) {
            case "hook_pull" -> hook(ab, c);
            case "water_jet" -> jet(ab, c, aim);
            case "ink_cloud" -> cloud(ab, c, aim, true);
            case "pollen_cloud" -> cloud(ab, c, aim, false);
            case "net_throw" -> net(ab, c, aim);
            case "sweep" -> sweep(ab, c, aim);
            case "seed_volley" -> seeds(ab, c, aim);
            case "crow_swarm" -> crows(ab, c, aim);
            case "burrow_ambush" -> ambush(ab, c, aim);
            default -> {
            }
        }
        return true;
    }

    void clear() {
        for (org.bukkit.entity.Entity e : alive) if (e.isValid()) e.remove();
        alive.clear();
    }

    // ---------------------------------------------------------------- helpers

    private static Player nearest(LivingEntity e, double within) {
        Player best = null;
        double bd = within * within;
        for (Player p : e.getWorld().getPlayers()) {
            if (!Mobs.fighting(p)) continue;
            double d = p.getLocation().distanceSquared(e.getLocation());
            if (d <= bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private static List<Player> within(Location c, double radius, double height) {
        List<Player> out = new ArrayList<>();
        for (Player p : c.getWorld().getPlayers()) {
            if (!Mobs.fighting(p)) continue;
            Location l = p.getLocation();
            if (Math.abs(l.getY() - c.getY()) <= height && Math.hypot(l.getX() - c.getX(), l.getZ() - c.getZ()) <= radius) out.add(p);
        }
        return out;
    }

    private static Color color(MobTemplate.Ability ab, int fallback) {
        String s = ab.text("color", "");
        try {
            return Color.fromRGB(s.isBlank() ? fallback : Integer.parseInt(s.replace("#", ""), 16));
        } catch (NumberFormatException e) {
            return Color.fromRGB(fallback);
        }
    }

    private static Material material(MobTemplate.Ability ab, String key, Material fallback) {
        Material m = Material.matchMaterial(ab.text(key, ""));
        return m == null ? fallback : m;
    }

    private ItemDisplay item(Location at, Material m, float size) {
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, it -> {
            it.setItemStack(new ItemStack(m));
            it.setPersistent(false);
            it.addScoreboardTag("fluxdungeons_fx");
            it.setTeleportDuration(1);
            it.setBrightness(new Display.Brightness(15, 15));
            it.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(size, size, size), new AxisAngle4f()));
        });
        alive.add(d);
        return d;
    }

    private void drop(org.bukkit.entity.Entity e) {
        alive.remove(e);
        if (e.isValid()) e.remove();
    }

    /** Runs {@code step} every tick from 0 until it returns false or {@code max} ticks passed, then {@code end}. */
    private void every(int max, java.util.function.IntPredicate step, Runnable end) {
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                boolean go = t <= max && step.test(t);
                t++;
                if (!go) {
                    cancel();
                    if (end != null) end.run();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private static boolean blocked(Location l) {
        return l.getBlock().getType().isSolid();
    }

    // ---------------------------------------------------------------- water

    /** A hook flies at the player furthest away; caught, he is reeled in towards the monster and hurt. */
    private void hook(MobTemplate.Ability ab, RockPowers.Caster c) {
        LivingEntity e = c.entity();
        Player p = TidePowers.farthest(e, ab.number("min-range", 6));
        if (p == null) return;
        World w = e.getWorld();
        double speed = ab.number("speed", 1.3), pull = ab.number("pull", 1.6);
        Location pos = e.getEyeLocation();
        ItemDisplay hook = item(pos, material(ab, "item", Material.TRIPWIRE_HOOK), 0.7f);
        w.playSound(pos, Sound.ENTITY_FISHING_BOBBER_THROW, 1.4f, 0.7f);
        every(60, t -> {
            if (!e.isValid() || !p.isOnline() || p.getWorld() != w) return false;
            Location now = hook.getLocation();
            Vector to = p.getLocation().add(0, 1, 0).toVector().subtract(now.toVector());
            if (to.length() < 1.1) {
                // caught: reeled in, the line drawn back
                c.hit(p, 1);
                Vector back = e.getLocation().toVector().subtract(p.getLocation().toVector());
                p.setVelocity(back.clone().setY(0).normalize().multiply(Math.min(pull, back.length() * 0.18)).setY(0.45));
                w.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.6f, 0.8f);
                w.spawnParticle(Particle.SPLASH, p.getLocation().add(0, 1, 0), 30, 0.4, 0.5, 0.4, 0.1);
                return false;
            }
            Location next = now.clone().add(to.normalize().multiply(speed));
            if (blocked(next)) return false;
            next.setDirection(to);
            hook.teleport(next);
            // the line between the monster and the hook
            Vector line = next.toVector().subtract(e.getEyeLocation().toVector());
            for (double s = 0; s < line.length(); s += 0.6) {
                w.spawnParticle(Particle.DUST, e.getEyeLocation().add(line.clone().normalize().multiply(s)), 1, 0, 0, 0, 0,
                        new Particle.DustOptions(Color.fromRGB(0xD8D8D8), 0.5f));
            }
            return true;
        }, () -> drop(hook));
    }

    /** A line shown on the ground towards the target, then a jet of water along it pushing back whoever is on it. */
    private void jet(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double range = ab.number("range", 9), width = ab.number("width", 1.2), push = ab.number("push", 1.1);
        int impact = (int) ab.number("impact", 16);
        Location from = e.getLocation().add(0, 0.2, 0);
        Vector dir = target.getLocation().toVector().subtract(from.toVector()).setY(0).normalize();
        Color warn = color(ab, 0x43B7B0);
        w.playSound(from, Sound.ENTITY_DROWNED_SHOOT, 1.4f, 0.6f);
        Set<UUID> hit = new HashSet<>();
        every(impact + 10, t -> {
            if (!e.isValid()) return false;
            if (t < impact) {
                if (t % 3 == 0) {
                    for (double s = 1; s <= range; s += 0.7) {
                        Location l = from.clone().add(dir.clone().multiply(s));
                        w.spawnParticle(Particle.DUST, l, 1, width / 3, 0, width / 3, 0, new Particle.DustOptions(warn, 1.2f));
                    }
                }
                return true;
            }
            if (t == impact) w.playSound(from, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.6f, 0.7f);
            double reach = Math.min(range, (t - impact + 1) * range / 4);
            for (double s = 1; s <= reach; s += 0.5) {
                Location l = from.clone().add(dir.clone().multiply(s)).add(0, 0.9, 0);
                w.spawnParticle(Particle.SPLASH, l, 6, 0.25, 0.25, 0.25, 0.2);
                w.spawnParticle(Particle.BUBBLE_POP, l, 2, 0.2, 0.2, 0.2, 0.05);
            }
            for (Player p : w.getPlayers()) {
                if (!Mobs.fighting(p) || hit.contains(p.getUniqueId())) continue;
                Vector rel = p.getLocation().toVector().subtract(from.toVector());
                double along = rel.dot(dir);
                if (along < 0 || along > reach || Math.abs(rel.getY()) > 2.5) continue;
                if (rel.clone().subtract(dir.clone().multiply(along)).setY(0).length() > width) continue;
                hit.add(p.getUniqueId());
                c.hit(p, 1);
                p.setVelocity(dir.clone().multiply(push).setY(0.3));
            }
            return true;
        }, null);
    }

    /** A cloud lobbed on a player: ink (blinds, slows) or pollen (keeps hurting); it lasts {@code duration} ticks. */
    private void cloud(MobTemplate.Ability ab, RockPowers.Caster c, Player target, boolean ink) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double radius = ab.number("radius", ink ? 2.6 : 3);
        int duration = (int) ab.number("duration", ink ? 60 : 80), tick = (int) Math.max(5, ab.number("tick", 10));
        Location start = e.getEyeLocation();
        Location spot = target.getLocation().clone();
        int flight = (int) Math.max(8, Math.min(26, start.distance(spot) * 1.4));
        ItemDisplay ball = item(start, ink ? Material.INK_SAC : Material.DANDELION, 0.8f);
        w.playSound(start, ink ? Sound.ENTITY_SQUID_SQUIRT : Sound.ENTITY_BEE_POLLINATE, 1.5f, 0.8f);
        Color warn = color(ab, ink ? 0x1A1030 : 0xF4D35E);
        every(flight + duration, t -> {
            if (t < flight) {
                double k = (double) t / flight;
                Location l = start.clone().add(spot.toVector().subtract(start.toVector()).multiply(k)).add(0, Math.sin(Math.PI * k) * 3, 0);
                ball.teleport(l);
                if (t % 2 == 0) Fx.ring(spot, radius, warn, 1.1f, t * 0.1);
                return true;
            }
            if (t == flight) {
                drop(ball);
                w.playSound(spot, ink ? Sound.ENTITY_GLOW_SQUID_SQUIRT : Sound.BLOCK_AZALEA_LEAVES_BREAK, 1.6f, 0.6f);
            }
            int k = t - flight;
            if (k % 2 == 0) {
                w.spawnParticle(ink ? Particle.SQUID_INK : Particle.DUST, spot.clone().add(0, 1, 0), ink ? 14 : 18, radius / 2, 0.7, radius / 2, 0.01,
                        ink ? null : new Particle.DustOptions(warn, 1.4f));
                if (!ink) w.spawnParticle(Particle.FALLING_SPORE_BLOSSOM, spot.clone().add(0, 2, 0), 4, radius / 2, 0.3, radius / 2, 0);
                Fx.ring(spot, radius, warn, 0.9f, -k * 0.08);
            }
            if (k % tick == 0) {
                for (Player p : within(spot, radius, 2.5)) {
                    c.hit(p, 0.4);
                    if (ink) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0, false, false));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 25, 1, false, false));
                    } else {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 0, false, false));
                    }
                }
            }
            return true;
        }, () -> drop(ball));
    }

    /** A net lobbed on a player: shown on the ground first, then whoever stays under it is rooted. */
    private void net(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double radius = ab.number("radius", 1.8);
        int root = (int) ab.number("root", 40);
        Location start = e.getEyeLocation(), spot = target.getLocation().clone();
        int flight = (int) Math.max(10, Math.min(24, start.distance(spot) * 1.3));
        ItemDisplay net = item(start, Material.COBWEB, 1.4f);
        w.playSound(start, Sound.ENTITY_FISHING_BOBBER_THROW, 1.4f, 0.5f);
        every(flight, t -> {
            double k = (double) t / flight;
            net.teleport(start.clone().add(spot.toVector().subtract(start.toVector()).multiply(k)).add(0, Math.sin(Math.PI * k) * 2.5, 0));
            if (t % 2 == 0) Fx.ring(spot, radius, Color.fromRGB(0xE8E8E8), 1.2f, t * 0.15);
            return t < flight;
        }, () -> {
            drop(net);
            w.playSound(spot, Sound.BLOCK_COBWEB_PLACE, 1.6f, 0.7f);
            w.spawnParticle(Particle.BLOCK, spot.clone().add(0, 0.5, 0), 40, radius / 2, 0.3, radius / 2, 0, Material.COBWEB.createBlockData());
            for (Player p : within(spot, radius, 2)) {
                c.hit(p, 0.8);
                p.setVelocity(new Vector());
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, root, 5, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, root, 128, false, false));
                if (!ab.text("message", "").isBlank()) p.sendActionBar(fr.influencraft.flux.api.text.Texts.parse(ab.text("message", "")));
            }
        });
    }

    // ---------------------------------------------------------------- fields

    /** An arc shown in front of the monster, then a sweeping blow over it, pushing outwards. */
    private void sweep(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        double range = ab.number("range", 3.5), angle = ab.number("angle", 150), push = ab.number("push", 0.7);
        int impact = (int) ab.number("impact", 12);
        String look = ab.text("look", "scythe");
        Location from = e.getLocation();
        Vector dir = target.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (dir.lengthSquared() < 1e-4) dir = from.getDirection().setY(0);
        dir.normalize();
        Color edge = color(ab, look.equals("pickaxe") ? 0xB8B2AA : look.equals("claw") ? 0x96765A : 0xF0A800);
        Vector d = dir;
        if (e instanceof org.bukkit.entity.Mob m) m.lookAt(target);
        w.playSound(from, look.equals("pickaxe") ? Sound.BLOCK_GRINDSTONE_USE : Sound.ITEM_HOE_TILL, 1.4f, 0.6f);
        every(impact + 4, t -> {
            if (!e.isValid()) return false;
            if (t < impact) {
                if (t % 3 == 0) Fx.cone(from, d, angle, range, edge, Color.fromRGB(0x5A3A1A));
                return true;
            }
            if (t == impact) {
                w.playSound(from, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.6f, look.equals("pickaxe") ? 0.6f : 0.8f);
                double half = Math.toRadians(angle / 2);
                for (double a = -half; a <= half; a += 0.18) {
                    Vector v = d.clone().rotateAroundY(a).multiply(range * 0.8);
                    w.spawnParticle(Particle.SWEEP_ATTACK, from.clone().add(v).add(0, 1, 0), 1, 0, 0, 0, 0);
                }
                for (Player p : w.getPlayers()) {
                    if (!Mobs.fighting(p)) continue;
                    Vector to = p.getLocation().toVector().subtract(from.toVector());
                    if (to.length() > range + 0.6 || Math.abs(to.getY()) > 2.5) continue;
                    Vector flat = to.clone().setY(0);
                    if (flat.lengthSquared() > 0.25 && Math.toDegrees(flat.angle(d)) > angle / 2) continue;
                    c.hit(p, 1.2);
                    p.setVelocity(flat.lengthSquared() < 1e-4 ? d.clone().multiply(push) : flat.normalize().multiply(push).setY(0.3));
                }
            }
            return true;
        }, null);
    }

    /** Seeds shot in a fan at the target, each one hurting whoever it meets. */
    private void seeds(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int count = (int) ab.number("count", 5);
        double spread = ab.number("spread", 24), speed = ab.number("speed", 0.8);
        Material m = material(ab, "item", Material.WHEAT_SEEDS);
        Location eye = e.getEyeLocation();
        Vector base = target.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector()).normalize();
        w.playSound(eye, Sound.ENTITY_LLAMA_SPIT, 1.4f, 1.2f);
        for (int i = 0; i < count; i++) {
            double a = count == 1 ? 0 : Math.toRadians(-spread / 2 + spread * i / (count - 1));
            Vector dir = base.clone().rotateAroundY(a).multiply(speed);
            ItemDisplay seed = item(eye.clone(), m, 0.45f);
            every(40, t -> {
                if (!seed.isValid()) return false;
                Location next = seed.getLocation().add(dir);
                if (blocked(next)) return false;
                seed.teleport(next);
                w.spawnParticle(Particle.ITEM, next, 1, 0, 0, 0, 0, new ItemStack(m));
                for (Player p : w.getPlayers()) {
                    if (Mobs.fighting(p) && p.getBoundingBox().expand(0.3).contains(next.toVector())) {
                        c.hit(p, 0.5);
                        w.playSound(next, Sound.BLOCK_CROP_BREAK, 1.2f, 1.2f);
                        return false;
                    }
                }
                return true;
            }, () -> drop(seed));
        }
    }

    /** Crows circling a player (the one furthest away with far-min), then diving at him one after the other. */
    private void crows(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int count = (int) ab.number("count", 4), circle = (int) ab.number("circle", 30);
        List<ItemDisplay> crows = new ArrayList<>();
        for (int i = 0; i < count; i++) crows.add(item(e.getEyeLocation(), material(ab, "item", Material.BLACK_DYE), 0.6f));
        w.playSound(e.getLocation(), Sound.ENTITY_PARROT_IMITATE_PHANTOM, 1.6f, 0.6f);
        Set<Integer> done = new HashSet<>();
        every(circle + count * 6 + 20, t -> {
            if (!target.isOnline() || target.getWorld() != w || !Mobs.fighting(target)) return false;
            Location centre = target.getLocation().add(0, 3.2, 0);
            for (int i = 0; i < count; i++) {
                ItemDisplay crow = crows.get(i);
                if (done.contains(i) || !crow.isValid()) continue;
                int diveAt = circle + i * 6;
                if (t < diveAt) {
                    double a = Math.PI * 2 * i / count + t * 0.25;
                    Location spot = centre.clone().add(Math.cos(a) * 2.2, Math.sin(t * 0.3 + i) * 0.3, Math.sin(a) * 2.2);
                    Location now = crow.getLocation();
                    crow.teleport(now.clone().add(spot.toVector().subtract(now.toVector()).multiply(t < 10 ? 0.25 : 0.6)));
                    if (t % 4 == i % 4) w.spawnParticle(Particle.SMOKE, crow.getLocation(), 1, 0, 0, 0, 0);
                } else {
                    Location now = crow.getLocation();
                    Vector to = target.getLocation().add(0, 1, 0).toVector().subtract(now.toVector());
                    if (to.length() < 0.9) {
                        c.hit(target, 0.4);
                        w.playSound(now, Sound.ENTITY_PHANTOM_BITE, 1.2f, 1.4f);
                        w.spawnParticle(Particle.SQUID_INK, now, 6, 0.2, 0.2, 0.2, 0.02);
                        done.add(i);
                        drop(crow);
                    } else {
                        crow.teleport(now.add(to.normalize().multiply(0.8)));
                    }
                }
            }
            return done.size() < count;
        }, () -> crows.forEach(this::drop));
    }

    // ---------------------------------------------------------------- earth

    /**
     * Digs in, travels under the ground (the soil rises where it goes, its life bar shows through), comes out under a
     * player and throws him up. Invulnerable while under the ground.
     */
    private void ambush(MobTemplate.Ability ab, RockPowers.Caster c, Player target) {
        LivingEntity e = c.entity();
        World w = e.getWorld();
        int delay = (int) ab.number("delay", 30);
        double radius = ab.number("radius", 2), launch = ab.number("launch", 0.8);
        Location from = e.getLocation();
        BlockData earth = from.clone().add(0, -0.5, 0).getBlock().getBlockData();
        c.hold(delay + 30);
        fx.eruption(from, 1.4, 0.6);
        w.playSound(from, Sound.BLOCK_ROOTED_DIRT_BREAK, 1.6f, 0.6f);
        // dug in after a short warning
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!e.isValid()) return;
            e.setGravity(false);
            e.setInvulnerable(true);
            Mobs.move(e, from.clone().add(0, -2.6, 0));
        }, 8L);
        every(delay, t -> {
            if (!e.isValid() || !target.isOnline() || target.getWorld() != w) return false;
            Location spot = target.getLocation();
            double k = (double) t / delay;
            Location under = from.clone().add(spot.toVector().subtract(from.toVector()).multiply(Math.min(1, k * 1.3)));
            if (t > 8) Mobs.move(e, under.clone().add(0, -2.6, 0));
            if (t % 3 == 0) fx.trail(under);
            if (t % 2 == 0) Fx.ring(spot, radius, Color.fromRGB(k > 0.7 ? 0xFF5C5C : 0x96765A), 1.2f, t * 0.2);
            if (t % 6 == 0) w.playSound(spot, Sound.BLOCK_GRAVEL_STEP, 1.2f, 0.6f);
            return true;
        }, () -> {
            if (!e.isValid()) return;
            Location spot = target.isOnline() && target.getWorld() == w && Mobs.fighting(target) ? target.getLocation() : from;
            Location out = spot.clone();
            Vector look = spot.toVector().subtract(from.toVector()).setY(0);
            if (look.lengthSquared() > 1e-4) out.setDirection(look);
            Mobs.move(e, out);
            e.setGravity(true);
            e.setInvulnerable(false);
            fx.eruption(spot, radius, 1);
            fx.spray(spot, new Vector(0, 1, 0), earth, 12);
            w.playSound(spot, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 0.6f);
            for (Player p : within(spot, radius, 2)) {
                c.hit(p, 1.2);
                p.setVelocity(new Vector(0, launch, 0));
            }
        });
    }
}
