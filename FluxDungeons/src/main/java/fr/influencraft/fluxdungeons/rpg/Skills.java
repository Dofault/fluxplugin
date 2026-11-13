package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.fluxdungeons.rpg.core.ClassDef;
import fr.influencraft.fluxdungeons.rpg.core.CombatMath;
import fr.influencraft.fluxdungeons.rpg.core.Modifier;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * What the basic attack and the skills of each class do in the world: shapes, movement, particles, sounds. Numbers
 * come from classes.yml through {@link ClassDef}; every hit goes through {@link CombatEngine#strike} and every heal
 * through {@link CombatEngine#heal}. A skill is chosen by its {@code type}; an unknown type does nothing (logged).
 */
public final class Skills implements Listener {

    private static final String ARROW = "fluxrpg_arrow";

    /** What an arrow of the rpg carries: whose, which cast, which hit. */
    private record Shot(Player owner, String cast, CombatMath.Hit hit) {
    }

    private final FluxPlugin plugin;
    private final CombatEngine combat;
    private final RpgPlayers players;
    /** The cryomancer's frost (frost stacks, freeze, shatter) and his skills. */
    private final Frost frost;

    Skills(FluxPlugin plugin, CombatEngine combat, RpgPlayers players) {
        this.plugin = plugin;
        this.combat = combat;
        this.players = players;
        this.frost = new Frost(plugin, combat, this);
    }

    /** The rpg state of an online player, null outside the universe. */
    RpgPlayer player(Player p) {
        return players.get(p);
    }

    /** Removes what the skills left in the world (plugin stopping). */
    void stop() {
        frost.clear();
    }

    // ------------------------------------------------------------------ targets

    /** The creature an entity stands for (a model's hit box: its creature). */
    LivingEntity body(Entity e) {
        return combat.body(e);
    }

    /** Creatures of the rpg around a point a player may hurt (never players, displays, armour stands). */
    List<LivingEntity> enemies(Location at, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : at.getWorld().getNearbyEntities(at, radius, radius, radius)) {
            // a model's hit box counts as its creature (once), wherever the box stands
            LivingEntity le = e instanceof Player ? null : combat.body(e);
            if (le == null || le instanceof Player || le instanceof org.bukkit.entity.ArmorStand || le instanceof org.bukkit.entity.Mannequin || le.isDead() || out.contains(le)) continue;
            if (combat.ally(le)) continue;   // the players' pets
            if (e.getLocation().distanceSquared(at) <= radius * radius && combat.rpg(le)) out.add(le);
        }
        return out;
    }

    /** Players of the rpg around a point (the caster included): allies, as everyone fights monsters there. */
    List<Player> allies(Location at, double radius) {
        List<Player> out = new ArrayList<>();
        for (Player p : at.getWorld().getPlayers()) {
            if (!p.isDead() && p.getLocation().distanceSquared(at) <= radius * radius && players.get(p) != null) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ basic attack (left click)

    /**
     * The class's basic attack, when ready. {@code clicked}: the creature hit by the click, if any. {@code paced}: the
     * swing was already checked against the weapon's recharge (swords and axes, combat.AttackCadence), it always goes.
     */
    void basic(Player p, RpgPlayer r, LivingEntity clicked, boolean paced) {
        long now = CombatEngine.now();
        if (!paced && now < r.nextBasic()) return;
        // the client already waits for the full recharge (minimum attack charge): 2 ticks of slack so a click it lets
        // through is never lost to a rounding
        r.nextBasic(now + Math.max(2, Math.round(20 * CombatMath.attackInterval(r.stats())) - 2));
        ClassDef.Attack a = r.heroClass().basic();
        String cast = r.cast("basic");
        switch (a.style()) {
            case "arrow" -> shoot(p, cast, a.hit(), p.getEyeLocation().getDirection(), 3.0);
            case "arcane" -> bolt(p, cast, a.hit(), a.range(), Particle.WITCH, null, 1.4);
            case "holy" -> bolt(p, cast, a.hit(), a.range(), Particle.END_ROD, null, 1.2);
            case "shard" -> frost.shard(p, r, a.hit(), cast, a.range());
            default -> cleave(p, cast, a, clicked);
        }
    }

    /** Melee: the clicked creature and others in front of the player within reach, up to the attack's targets. */
    private void cleave(Player p, String cast, ClassDef.Attack a, LivingEntity clicked) {
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection().setY(0).normalize();
        List<LivingEntity> targets = new ArrayList<>();
        if (clicked != null && combat.rpg(clicked)) targets.add(clicked);
        for (LivingEntity e : enemies(p.getLocation(), a.range())) {
            if (targets.size() >= a.targets()) break;
            if (targets.contains(e)) continue;
            Vector to = e.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (to.lengthSquared() < 0.01 || to.normalize().dot(look) > 0.5) targets.add(e);
        }
        // the swing is seen where it reaches: the first creature or wall within range, else the end of the range
        Location at = reachPoint(p, a.range());
        p.getWorld().spawnParticle(Particle.SWEEP_ATTACK, at, 1, 0, 0, 0, 0);
        // the one swing sound of every weapon (same volume and category as a vanilla sword)
        fr.influencraft.fluxdungeons.combat.AttackCadence.playSwing(at);
        for (LivingEntity t : targets) {
            // a slight push, never on a boss (its fight is placed: the dungeons anchor bosses where they stand)
            if (combat.strike(p, t, a.hit(), cast) != null && !t.getScoreboardTags().contains(fr.influencraft.fluxdungeons.mob.Mobs.BOSS_TAG)) {
                t.setVelocity(t.getVelocity().add(look.clone().multiply(0.25).setY(0.15)));
            }
        }
    }

    // ------------------------------------------------------------------ skills (right click, A, F)

    /** Casts the skill of a slot when paid for (mana is the only limit, no cooldown). */
    void cast(Player p, RpgPlayer r, String slot) {
        ClassDef.Skill s = r.heroClass().skills().get(slot);
        if (s == null) return;
        long now = CombatEngine.now();
        // the same key press reported twice in a tick (drop and its echo) casts once
        if (now - r.lastCast(slot) < 2) return;
        double cost = CombatMath.manaCost(s.mana(), r.stats());
        if (r.mana() < cost) {
            plugin.messages().send(p, "skill.no-mana");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, org.bukkit.SoundCategory.PLAYERS, 0.6f, 0.6f);
            return;
        }
        if (!run(p, r, s, r.cast(slot))) return;
        r.mana(r.mana() - cost);
        r.cast(slot, now);
    }

    /** The skill's own work. False when it could not be cast (nothing paid). */
    private boolean run(Player p, RpgPlayer r, ClassDef.Skill s, String cast) {
        World w = p.getWorld();
        switch (s.type()) {
            case "charge" -> {
                Vector dir = p.getLocation().getDirection().setY(0).normalize();
                p.setVelocity(dir.clone().multiply(s.number("distance", 8) / 5).setY(0.2));
                w.playSound(p.getLocation(), Sound.ENTITY_RAVAGER_ROAR, org.bukkit.SoundCategory.PLAYERS, 0.6f, 1.4f);
                repeat(10, 1, tick -> {
                    w.spawnParticle(Particle.CLOUD, p.getLocation().add(0, 0.2, 0), 3, 0.2, 0.1, 0.2, 0.01);
                    for (LivingEntity e : enemies(p.getLocation(), 1.8)) {
                        if (combat.strike(p, e, s.hit(), cast) != null) slow(e, s.number("stun", 1), 9, r);
                    }
                });
            }
            case "war_cry" -> {
                double radius = s.number("radius", 10);
                w.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, org.bukkit.SoundCategory.PLAYERS, 0.7f, 1.2f);
                w.spawnParticle(Particle.ANGRY_VILLAGER, p.getLocation().add(0, 1, 0), 12, radius / 3, 0.5, radius / 3);
                for (LivingEntity e : enemies(p.getLocation(), radius)) if (e instanceof Mob m) m.setTarget(p);
                long until = CombatEngine.now() + Math.round(20 * CombatMath.duration(s.duration(), r.stats(), null, false));
                r.buff("war_cry", List.of(Modifier.add("damage_reduction", s.number("damage_reduction", 0.3), "war_cry")), until);
                players.recompute(p);
            }
            case "whirlwind" -> {
                double radius = s.number("radius", 4);
                int hits = s.hits();
                repeat(hits, 5, tick -> {
                    w.spawnParticle(Particle.SWEEP_ATTACK, p.getLocation().add(0, 1, 0), 6, radius / 2, 0.2, radius / 2);
                    w.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, org.bukkit.SoundCategory.PLAYERS, 0.8f, 0.8f);
                    String hitCast = cast + "#" + tick;
                    for (LivingEntity e : enemies(p.getLocation(), radius)) combat.strike(p, e, s.hit(), hitCast);
                });
            }
            case "leap_back" -> {
                Vector back = p.getLocation().getDirection().setY(0).normalize().multiply(-s.number("strength", 1.4)).setY(0.5);
                p.setVelocity(back);
                w.spawnParticle(Particle.CLOUD, p.getLocation(), 8, 0.3, 0.1, 0.3, 0.02);
                w.playSound(p.getLocation(), Sound.ENTITY_RABBIT_JUMP, org.bukkit.SoundCategory.PLAYERS, 1f, 1f);
                long until = CombatEngine.now() + Math.round(20 * CombatMath.duration(s.duration(), r.stats(), null, false));
                r.buff("leap_back", List.of(Modifier.add("movement_speed", s.number("speed", 0.3), "leap_back")), until);
                players.recompute(p);
            }
            case "volley" -> {
                int arrows = (int) s.number("arrows", 5);
                double spread = Math.toRadians(s.number("spread", 8));
                Vector dir = p.getEyeLocation().getDirection();
                for (int i = 0; i < arrows; i++) {
                    double angle = (i - (arrows - 1) / 2.0) * spread;
                    // each arrow is its own hit: several arrows on one big target all count
                    shoot(p, cast + "#" + i, s.hit(), dir.clone().rotateAroundY(angle), 2.6);
                }
            }
            case "arrow_rain" -> {
                Location at = aimed(p, s.number("range", 30));
                double radius = s.number("radius", 4);
                int hits = s.hits();
                long period = Math.max(1, Math.round(20 * Math.max(0.5, s.duration()) / hits));
                repeat(hits, period, tick -> {
                    w.spawnParticle(Particle.CRIT, at.clone().add(0, 4, 0), 30, radius / 2, 1.5, radius / 2, 0.05);
                    w.playSound(at, Sound.ENTITY_ARROW_HIT, org.bukkit.SoundCategory.PLAYERS, 0.6f, 1.2f);
                    String hitCast = cast + "#" + tick;
                    for (LivingEntity e : enemies(at, radius)) combat.strike(p, e, s.hit(), hitCast, true);
                });
            }
            case "blink" -> {
                Location from = p.getLocation();
                Location to = safeAhead(p, s.number("distance", 8));
                if (to == null) {
                    plugin.messages().send(p, "skill.blocked");
                    return false;
                }
                w.spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.2);
                p.teleport(to, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                w.spawnParticle(Particle.PORTAL, to.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.2);
                w.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, org.bukkit.SoundCategory.PLAYERS, 0.8f, 1.2f);
            }
            case "fireball" -> {
                double radius = s.number("radius", 3);
                travel(p, s.number("range", 25), s.number("speed", 1.6), Particle.FLAME, (at, direct) -> {
                    w.spawnParticle(Particle.EXPLOSION, at, 1);
                    w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, org.bukkit.SoundCategory.PLAYERS, 0.6f, 1.3f);
                    for (LivingEntity e : enemies(at, radius)) combat.strike(p, e, s.hit(), cast);
                });
                w.playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, org.bukkit.SoundCategory.PLAYERS, 0.8f, 1f);
            }
            case "frost_nova" -> {
                double radius = s.number("radius", 5);
                w.spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0, 0.5, 0), 80, radius / 2, 0.3, radius / 2, 0.05);
                w.playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, org.bukkit.SoundCategory.PLAYERS, 0.8f, 0.7f);
                for (LivingEntity e : enemies(p.getLocation(), radius)) {
                    if (combat.strike(p, e, s.hit(), cast) != null) slow(e, s.duration(), 2, r);
                }
            }
            case "heal" -> {
                Player target = allyAimed(p, s.number("range", 20));
                double amount = CombatMath.heal(r.stats(), s.coefficient(), 0);
                combat.heal(target, amount, true);
                w.spawnParticle(Particle.HEART, target.getLocation().add(0, 2, 0), 5, 0.3, 0.3, 0.3);
                w.playSound(target.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, org.bukkit.SoundCategory.PLAYERS, 1f, 1.4f);
                if (target != p) plugin.messages().send(target, "skill.healed-by", "%player%", p.getName());
            }
            case "blessing" -> {
                double radius = s.number("radius", 8);
                long until = CombatEngine.now() + Math.round(20 * CombatMath.duration(s.duration(), r.stats(), null, false));
                w.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, org.bukkit.SoundCategory.PLAYERS, 0.8f, 1.5f);
                for (Player ally : allies(p.getLocation(), radius)) {
                    RpgPlayer ar = players.get(ally);
                    if (ar == null) continue;
                    ar.buff("blessing", List.of(Modifier.add("damage_bonus", s.number("damage_bonus", 0.15), "blessing")), until);
                    players.recompute(ally);
                    ally.getWorld().spawnParticle(Particle.GLOW, ally.getLocation().add(0, 1, 0), 15, 0.4, 0.6, 0.4);
                }
            }
            case "sanctuary" -> {
                Location at = p.getLocation();
                double radius = s.number("radius", 6);
                int hits = s.hits();
                long period = Math.max(1, Math.round(20 * Math.max(1, s.duration()) / hits));
                double each = CombatMath.heal(r.stats(), s.coefficient(), 0);
                w.playSound(at, Sound.BLOCK_BEACON_ACTIVATE, org.bukkit.SoundCategory.PLAYERS, 0.8f, 1.4f);
                repeat(hits, period, tick -> {
                    ring(at, radius, Particle.HAPPY_VILLAGER);
                    for (Player ally : allies(at, radius)) combat.heal(ally, each, true);
                });
            }
            case "frost_glide" -> {
                return frost.glide(p, r, s, cast);
            }
            case "glacial_rift" -> {
                return frost.rift(p, r, s, cast);
            }
            case "winter_comet" -> {
                return frost.comet(p, r, s, cast);
            }
            default -> {
                plugin.getLogger().warning("Skill type " + s.type() + " has no code: nothing cast");
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ shapes

    /** Runs {@code step} {@code times} times, every {@code period} ticks, starting now. */
    private void repeat(int times, long period, Consumer<Integer> step) {
        new BukkitRunnable() {
            int n;

            @Override
            public void run() {
                if (n >= times) {
                    cancel();
                    return;
                }
                step.accept(n++);
            }
        }.runTaskTimer(plugin, 0L, Math.max(1, period));
    }

    /** A real arrow (looks and flies right); its vanilla damage never counts, the engine hits on impact. */
    private void shoot(Player p, String cast, CombatMath.Hit hit, Vector dir, double speed) {
        Arrow arrow = p.launchProjectile(Arrow.class, dir.clone().normalize().multiply(speed));
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        arrow.setPersistent(false);
        arrow.setCritical(false);
        arrow.setMetadata(ARROW, new FixedMetadataValue(plugin, new Shot(p, cast, hit)));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ARROW_SHOOT, org.bukkit.SoundCategory.PLAYERS, 0.8f, 1.1f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (arrow.isValid()) arrow.remove();
        }, 60L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onArrow(ProjectileHitEvent e) {
        if (!e.getEntity().hasMetadata(ARROW)) return;
        if (e.getHitEntity() instanceof Player) {
            e.setCancelled(true);   // flies through allies
            return;
        }
        // the engine hits (and tells the dungeon once); the game's own arrow damage never happens
        if (e.getHitEntity() != null) e.setCancelled(true);
        Object v = e.getEntity().getMetadata(ARROW).get(0).value();
        if (v instanceof Shot shot && e.getHitEntity() instanceof LivingEntity target) combat.strike(shot.owner(), target, shot.hit(), shot.cast(), true);
        e.getEntity().remove();
    }

    /** A bolt of particles: hits the first creature on its way. */
    private void bolt(Player p, String cast, CombatMath.Hit hit, double range, Particle trail, Particle.DustOptions dust, double speed) {
        travel(p, range, speed, trail, (at, direct) -> {
            if (direct != null) combat.strike(p, direct, hit, cast);
        });
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, org.bukkit.SoundCategory.PLAYERS, 0.5f, 1.6f);
    }

    /** Something flying from the eyes: calls {@code impact} at the first creature (given) or block (null) hit. */
    private void travel(Player p, double range, double speed, Particle trail, java.util.function.BiConsumer<Location, LivingEntity> impact) {
        Location start = p.getEyeLocation();
        Vector step = start.getDirection().normalize().multiply(speed);
        new BukkitRunnable() {
            final Location at = start.clone();
            double gone;

            @Override
            public void run() {
                for (int sub = 0; sub < 2; sub++) {
                    at.add(step.clone().multiply(0.5));
                    gone += speed * 0.5;
                    at.getWorld().spawnParticle(trail, at, 2, 0.05, 0.05, 0.05, 0);
                    for (LivingEntity e : enemies(at, 0.9)) {
                        impact.accept(at.clone(), e);
                        cancel();
                        return;
                    }
                    if (at.getBlock().getType().isSolid() || gone >= range) {
                        impact.accept(at.clone(), null);
                        cancel();
                        return;
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Where a swing of this range reaches, a little below the eyes (where the weapon passes). */
    private Location reachPoint(Player p, double range) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        RayTraceResult hit = p.getWorld().rayTrace(eye, dir, range, FluidCollisionMode.NEVER, true, 0.2,
                e -> e != p && e instanceof LivingEntity && !(e instanceof org.bukkit.entity.ArmorStand) && !(e instanceof Player));
        double d = range;
        if (hit != null) {
            d = hit.getHitPosition().distance(eye.toVector());
            if (hit.getHitBlock() != null) d = Math.max(0.8, d - 0.3);
        }
        return eye.add(dir.multiply(d)).subtract(0, 0.3, 0);
    }

    /** Where the player aims (block or max range). */
    private Location aimed(Player p, double range) {
        RayTraceResult hit = p.getWorld().rayTraceBlocks(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, FluidCollisionMode.NEVER, true);
        return hit != null ? hit.getHitPosition().toLocation(p.getWorld()) : p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(range));
    }

    /** The ally the player looks at within range, himself otherwise. */
    private Player allyAimed(Player p, double range) {
        RayTraceResult hit = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, 0.6,
                e -> e instanceof Player other && other != p && players.get(other) != null);
        return hit != null && hit.getHitEntity() instanceof Player ally ? ally : p;
    }

    /** The farthest place ahead (up to {@code distance}) where the player fits, null when blocked right away. */
    private Location safeAhead(Player p, double distance) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().setY(0).normalize();
        Location best = null;
        for (double d = 1; d <= distance; d += 0.5) {
            Location feet = p.getLocation().clone().add(dir.clone().multiply(d));
            if (feet.getBlock().isPassable() && feet.clone().add(0, 1, 0).getBlock().isPassable()) best = feet;
            else break;
        }
        return best;
    }

    private static void ring(Location at, double radius, Particle particle) {
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 * i / 24;
            at.getWorld().spawnParticle(particle, at.clone().add(Math.cos(a) * radius, 0.2, Math.sin(a) * radius), 1, 0, 0, 0, 0);
        }
    }

    /** Slows (or stuns, strength 9) a creature for a while, shortened by its tenacity. */
    private void slow(LivingEntity e, double seconds, int strength, RpgPlayer caster) {
        double d = CombatMath.duration(seconds, caster.stats(), combat.stats(e), true);
        e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) Math.round(d * 20), strength, false, true, false));
        if (strength >= 9) e.getWorld().spawnParticle(Particle.DUST, e.getEyeLocation().add(0, 0.4, 0), 6, 0.2, 0.1, 0.2,
                new Particle.DustOptions(Color.YELLOW, 1));
    }
}
