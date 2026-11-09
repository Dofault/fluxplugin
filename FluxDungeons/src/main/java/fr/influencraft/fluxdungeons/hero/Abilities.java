package fr.influencraft.fluxdungeons.hero;

import fr.influencraft.fluxdungeons.hero.HeroClass.AbilitySpec;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What each ability {@code type} of {@code classes.yml} does. Numbers come from the class file ({@code power},
 * {@code radius}, {@code duration}...); damage grows with the class level and the player's attack (his weapon).
 * Abilities only ever hurt dungeon monsters and only ever help the players of the same run ({@link Context}).
 */
public final class Abilities {

    /** What an ability needs to know about the run it is cast in. */
    public interface Context {
        /** A monster of the run, which abilities may hurt. */
        boolean enemy(Entity entity);

        /** Players of the caster's run (the caster included), whom abilities may help. */
        Collection<Player> allies(Player caster);
    }

    /** Damage carried by a projectile shot by an ability, read when it hits. */
    private final NamespacedKey powerKey;
    /** Radius of the blast of an ability's fireball. */
    private final NamespacedKey blastKey;
    private final Plugin plugin;

    public Abilities(Plugin plugin) {
        this.plugin = plugin;
        this.powerKey = new NamespacedKey(plugin, "ability_power");
        this.blastKey = new NamespacedKey(plugin, "ability_blast");
    }

    /** Casts the ability; false when it found nothing to do (no target...), so no mana is spent. */
    public boolean cast(Player p, Hero hero, AbilitySpec a, Context ctx) {
        double power = scaled(p, hero, a.param("power", 0));
        World w = p.getWorld();
        Location at = p.getLocation();
        switch (a.type()) {
            case "charge" -> {
                Vector dir = at.getDirection().setY(0).normalize().multiply(a.param("distance", 1.6)).setY(0.25);
                p.setVelocity(dir);
                w.playSound(at, Sound.ENTITY_RAVAGER_ROAR, 0.6f, 1.6f);
                double radius = a.param("radius", 2.5);
                later(6, () -> {
                    Location l = p.getLocation();
                    w.spawnParticle(Particle.SWEEP_ATTACK, l.clone().add(0, 1, 0), 6, 0.8, 0.3, 0.8, 0);
                    for (LivingEntity e : enemies(l, radius, ctx)) {
                        hit(e, power, p);
                        e.setVelocity(e.getLocation().toVector().subtract(l.toVector()).setY(0).normalize().multiply(0.8).setY(0.35));
                    }
                });
                return true;
            }
            case "war_cry" -> {
                double radius = a.param("radius", 10);
                int ticks = (int) (a.param("duration", 6) * 20);
                for (LivingEntity e : enemies(at, radius, ctx)) if (e instanceof Mob m) m.setTarget(p);
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ticks, (int) a.param("resistance", 1), false, true));
                for (Player ally : ctx.allies(p)) {
                    if (ally.getLocation().distanceSquared(at) <= radius * radius) {
                        ally.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, ticks, 0, false, true));
                    }
                }
                w.playSound(at, Sound.EVENT_RAID_HORN, 0.8f, 1.2f);
                w.spawnParticle(Particle.ANGRY_VILLAGER, at.clone().add(0, 2, 0), 8, 0.6, 0.3, 0.6, 0);
                return true;
            }
            case "whirlwind" -> {
                double radius = a.param("radius", 4);
                ring(at.clone().add(0, 1, 0), radius, Particle.SWEEP_ATTACK, 12);
                w.playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.7f);
                for (LivingEntity e : enemies(at, radius, ctx)) hit(e, power, p);
                return true;
            }
            case "volley" -> {
                int arrows = (int) a.param("arrows", 5);
                double spread = Math.toRadians(a.param("spread", 10));
                Vector base = p.getEyeLocation().getDirection();
                for (int i = 0; i < arrows; i++) {
                    double angle = (i - (arrows - 1) / 2.0) * spread;
                    Vector dir = rotateY(base.clone(), angle).multiply(a.param("speed", 2.4));
                    Arrow arrow = p.launchProjectile(Arrow.class, dir);
                    arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                    arrow.getPersistentDataContainer().set(powerKey, PersistentDataType.DOUBLE, power);
                }
                w.playSound(at, Sound.ENTITY_ARROW_SHOOT, 1f, 0.8f);
                return true;
            }
            case "leap_back" -> {
                Vector back = at.getDirection().setY(0).normalize().multiply(-a.param("strength", 1.4)).setY(0.55);
                p.setVelocity(back);
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, (int) (a.param("duration", 3) * 20), 1, false, true));
                w.spawnParticle(Particle.CLOUD, at, 12, 0.4, 0.1, 0.4, 0.02);
                w.playSound(at, Sound.ENTITY_BAT_TAKEOFF, 0.8f, 1.2f);
                return true;
            }
            case "arrow_rain" -> {
                Block target = p.getTargetBlockExact((int) a.param("range", 30), FluidCollisionMode.NEVER);
                if (target == null) return false;
                Location spot = target.getLocation().add(0.5, 1, 0.5);
                double radius = a.param("radius", 4);
                int pulses = (int) Math.max(1, a.param("duration", 3) * 2);
                w.playSound(spot, Sound.ENTITY_ARROW_SHOOT, 1f, 0.5f);
                new BukkitRunnable() {
                    int n;

                    public void run() {
                        if (n++ >= pulses || !p.isOnline()) {
                            cancel();
                            return;
                        }
                        w.spawnParticle(Particle.CRIT, spot.clone().add(0, 4, 0), 40, radius / 2, 1.5, radius / 2, 0.05);
                        w.playSound(spot, Sound.ENTITY_ARROW_HIT, 0.6f, 1.2f);
                        for (LivingEntity e : enemies(spot, radius, ctx)) {
                            e.setNoDamageTicks(0);
                            hit(e, power / pulses * 2, p);
                        }
                    }
                }.runTaskTimer(plugin, 10L, 10L);
                return true;
            }
            case "fireball" -> {
                SmallFireball ball = p.launchProjectile(SmallFireball.class, p.getEyeLocation().getDirection().multiply(a.param("speed", 1.6)));
                ball.setIsIncendiary(false);
                ball.getPersistentDataContainer().set(powerKey, PersistentDataType.DOUBLE, power);
                ball.getPersistentDataContainer().set(blastKey, PersistentDataType.DOUBLE, a.param("radius", 2.5));
                w.playSound(at, Sound.ENTITY_BLAZE_SHOOT, 1f, 1f);
                return true;
            }
            case "frost_nova" -> {
                double radius = a.param("radius", 5);
                int ticks = (int) (a.param("duration", 4) * 20);
                ring(at.clone().add(0, 0.3, 0), radius, Particle.SNOWFLAKE, 40);
                w.playSound(at, Sound.BLOCK_GLASS_BREAK, 1f, 0.6f);
                for (LivingEntity e : enemies(at, radius, ctx)) {
                    hit(e, power, p);
                    e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 2, false, true));
                    e.setFreezeTicks(Math.max(e.getFreezeTicks(), ticks));
                }
                return true;
            }
            case "blink" -> {
                Location to = blinkTarget(p, a.param("distance", 8));
                if (to == null) return false;
                w.spawnParticle(Particle.PORTAL, at.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.2);
                p.teleport(to);
                p.setFallDistance(0);
                w.spawnParticle(Particle.REVERSE_PORTAL, to.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.05);
                w.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.3f);
                return true;
            }
            case "heal" -> {
                double radius = a.param("radius", 8);
                double amount = a.param("power", 6) * levelFactor(hero);
                for (Player ally : ctx.allies(p)) {
                    if (ally.isDead() || ally.getLocation().distanceSquared(at) > radius * radius) continue;
                    ally.heal(amount);
                    w.spawnParticle(Particle.HEART, ally.getLocation().add(0, 2, 0), 4, 0.4, 0.3, 0.4, 0);
                }
                w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
                return true;
            }
            case "blessing" -> {
                double radius = a.param("radius", 8);
                int ticks = (int) (a.param("duration", 8) * 20);
                int absorption = (int) Math.max(0, a.param("absorption", 1));
                for (Player ally : ctx.allies(p)) {
                    if (ally.isDead() || ally.getLocation().distanceSquared(at) > radius * radius) continue;
                    ally.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, ticks, absorption, false, true));
                    ally.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, ticks, 0, false, true));
                    w.spawnParticle(Particle.END_ROD, ally.getLocation().add(0, 1, 0), 10, 0.4, 0.6, 0.4, 0.02);
                }
                w.playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.6f);
                return true;
            }
            case "smite" -> {
                RayTraceResult hit = w.rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(),
                        a.param("range", 20), 0.6, ctx::enemy);
                if (hit == null || !(hit.getHitEntity() instanceof LivingEntity target)) return false;
                Location l = target.getLocation();
                w.strikeLightningEffect(l);
                w.spawnParticle(Particle.ELECTRIC_SPARK, l.clone().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.1);
                hit(target, power, p);
                return true;
            }
            default -> {
                plugin.getLogger().warning("Unknown ability type: " + a.type());
                return false;
            }
        }
    }

    /** Damage of a projectile shot by an ability, or -1 for any other projectile. */
    public double projectilePower(Projectile projectile) {
        Double v = projectile.getPersistentDataContainer().get(powerKey, PersistentDataType.DOUBLE);
        return v == null ? -1 : v;
    }

    /** An ability's fireball hit something: its blast hurts the monsters around. */
    public void onProjectileHit(Projectile projectile, Context ctx) {
        Double blast = projectile.getPersistentDataContainer().get(blastKey, PersistentDataType.DOUBLE);
        if (blast == null || !(projectile.getShooter() instanceof Player p)) return;
        double power = projectilePower(projectile);
        Location l = projectile.getLocation();
        l.getWorld().spawnParticle(Particle.EXPLOSION, l, 1);
        l.getWorld().spawnParticle(Particle.FLAME, l, 20, blast / 2, 0.4, blast / 2, 0.03);
        l.getWorld().playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f);
        for (LivingEntity e : enemies(l, blast, ctx)) {
            e.setNoDamageTicks(0);
            hit(e, power * 0.6, p);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Power of an ability as written in classes.yml: it counts in normal hits (6 = an iron sword), the damage
     * listener turns it into dungeon damage (Combat: level, weapon, potions), as EliteMobs scales its skills.
     */
    private static double scaled(Player p, Hero hero, double power) {
        return power;
    }

    /** Power of the ability hit being dealt, -1 outside. */
    private double striking = -1;

    public double striking() {
        return striking;
    }

    private void hit(LivingEntity e, double power, Player p) {
        double before = striking;
        striking = power;
        try {
            e.damage(power, p);
        } finally {
            striking = before;
        }
    }

    private static double levelFactor(Hero hero) {
        return 1 + 0.04 * (hero.level() - 1);
    }

    private static List<LivingEntity> enemies(Location at, double radius, Context ctx) {
        return new ArrayList<>(at.getWorld().getNearbyLivingEntities(at, radius, radius, radius,
                e -> ctx.enemy(e) && !e.isDead()));
    }

    private static void ring(Location center, double radius, Particle particle, int points) {
        for (int i = 0; i < points; i++) {
            double t = 2 * Math.PI * i / points;
            center.getWorld().spawnParticle(particle, center.clone().add(Math.cos(t) * radius, 0, Math.sin(t) * radius), 1, 0, 0, 0, 0);
        }
    }

    private static Vector rotateY(Vector v, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return new Vector(v.getX() * cos - v.getZ() * sin, v.getY(), v.getX() * sin + v.getZ() * cos);
    }

    /** Farthest free spot in front of the player, stopping before walls; null when he cannot move. */
    private static Location blinkTarget(Player p, double distance) {
        Location eye = p.getLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) return null;
        dir.normalize();
        Location best = null;
        for (double d = 0.5; d <= distance; d += 0.5) {
            Location l = eye.clone().add(dir.clone().multiply(d));
            Block feet = l.getBlock(), head = feet.getRelative(0, 1, 0);
            if (!feet.isPassable() || !head.isPassable()) break;
            best = l;
        }
        return best == null || best.distanceSquared(eye) < 1 ? null : best;
    }

    private void later(long ticks, Runnable task) {
        Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
    }
}
