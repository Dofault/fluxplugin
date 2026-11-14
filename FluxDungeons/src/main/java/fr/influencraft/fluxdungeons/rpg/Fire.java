package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.fluxdungeons.mob.Mobs;
import fr.influencraft.fluxdungeons.rpg.core.ClassDef;
import fr.influencraft.fluxdungeons.rpg.core.CombatMath;
import fr.influencraft.fluxdungeons.rpg.core.Modifier;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The pyromancer's fire (2026-11-14): his basic attack, his three skills and the two mechanics they share.
 * <ul>
 *   <li><b>Burn</b>: each of his hits sets the creature alight, one more stack each time (up to {@code burn-max});
 *   every second it takes {@code burn} x magic power per stack, for {@code burn-seconds} after the last hit.</li>
 *   <li><b>Wildfire</b>: a burning creature that dies bursts: {@code wildfire} x magic power (at full stacks) on the
 *   creatures within {@code wildfire-radius}, set alight in turn ({@code wildfire-stacks}): a pack burns down in a
 *   chain, one death feeding the next.</li>
 *   <li><b>Heat</b>: each hit that lands heats the pyromancer himself (0 to 100, shown on a boss bar; lost after
 *   {@code heat-decay-after} seconds without hitting). Heat is damage (up to {@code heat-damage} at 100); at 100 he
 *   enters the <b>blaze</b> for {@code blaze-seconds}: his basic attack becomes a fan of {@code blaze-bolts} bolts,
 *   faster ({@code blaze-speed}), then the heat is spent. Entering the blaze gives him {@code blaze-heal} of his life
 *   back.</li>
 * </ul>
 * Drawn with block displays (never real blocks), every hit through {@link CombatEngine#strike}. Numbers come from
 * classes.yml ({@code passive} of the class, {@code extra} of each skill).
 */
final class Fire {

    /** The fire a creature carries. */
    private static final class Burn {
        final LivingEntity entity;
        int stacks;
        long until, nextTick;
        UUID owner;
        Location last;

        Burn(LivingEntity entity) {
            this.entity = entity;
        }
    }

    /** The heat of a pyromancer. */
    private static final class Heat {
        double value;
        long lastGain, blazeUntil, blazeFrom;
        int step = -1;
        BossBar bar;
    }

    private static final Material[] EMBERS = {Material.MAGMA_BLOCK, Material.SHROOMLIGHT, Material.NETHERRACK};
    private static final BlockData FLAMES = Material.FIRE.createBlockData();
    private static final Particle.DustOptions GLOW_DUST = new Particle.DustOptions(Color.fromRGB(0xFF9A2E), 1.4f);
    private static final Particle.DustOptions DEEP_DUST = new Particle.DustOptions(Color.fromRGB(0xC2331C), 1.7f);

    private final FluxPlugin plugin;
    private final CombatEngine combat;
    private final Skills skills;
    private final RpgPlayers players;
    private final SpellFx fx;
    private final Map<UUID, Burn> burns = new HashMap<>();
    private final Map<UUID, Heat> heats = new HashMap<>();
    private BukkitTask ticker;

    Fire(FluxPlugin plugin, CombatEngine combat, Skills skills, RpgPlayers players, SpellFx fx) {
        this.plugin = plugin;
        this.combat = combat;
        this.skills = skills;
        this.players = players;
        this.fx = fx;
    }

    /** Forgets every fire and heat (plugin stopping; the displays are removed by {@link SpellFx#clear}). */
    void clear() {
        if (ticker != null) ticker.cancel();
        ticker = null;
        for (Burn b : burns.values()) if (b.entity.isValid()) b.entity.setVisualFire(false);
        burns.clear();
        for (Map.Entry<UUID, Heat> e : heats.entrySet()) {
            LivingEntity p = skills.caster(e.getKey());
            if (p != null && e.getValue().bar != null) p.hideBossBar(e.getValue().bar);
        }
        heats.clear();
    }

    // ================================================================== the shared mechanics

    /**
     * A fire hit of the pyromancer: through the engine, then burn ({@code stacks}) and heat for himself. Null when
     * it did not land.
     */
    CombatMath.Outcome hit(LivingEntity p, RpgPlayer r, LivingEntity target, CombatMath.Hit hit, String cast, int stacks, boolean basic) {
        LivingEntity body = skills.body(target);
        if (body == null) return null;
        CombatMath.Outcome o = combat.strike(p, body, hit, cast);
        if (o == null || o.dodged()) return o;
        heat(p, r, r.heroClass().passive(basic ? "heat-basic" : "heat-spell", basic ? 6 : 3));
        // a creature killed by this hit already burst (the death is told to died() before strike returns)
        if (!body.isDead() && body.getHealth() > 0) ignite(p, r, body, stacks);
        return o;
    }

    /** Sets a creature alight, or feeds its fire. */
    void ignite(LivingEntity p, RpgPlayer r, LivingEntity e, int stacks) {
        if (stacks <= 0 || e.isDead()) return;
        ClassDef cls = r.heroClass();
        int max = (int) Math.max(1, cls.passive("burn-max", 5));
        long now = CombatEngine.now();
        Burn b = burns.computeIfAbsent(e.getUniqueId(), k -> new Burn(e));
        boolean lit = b.stacks == 0;
        boolean full = b.stacks < max && b.stacks + stacks >= max;
        b.stacks = Math.min(max, b.stacks + stacks);
        b.until = now + Math.round(20 * cls.passive("burn-seconds", 5));
        b.owner = p.getUniqueId();
        b.last = e.getLocation();
        if (lit) b.nextTick = now + 20;
        World w = e.getWorld();
        Location mid = e.getLocation().add(0, e.getHeight() / 2, 0);
        if (!boss(e)) e.setVisualFire(true);
        w.spawnParticle(Particle.FLAME, mid, 4 * b.stacks, e.getWidth() / 2, e.getHeight() / 3, e.getWidth() / 2, 0.02);
        if (full) {
            // full blaze on it: seen and heard, the pack around knows what is coming
            w.spawnParticle(Particle.LAVA, mid, 10, e.getWidth() / 2, e.getHeight() / 3, e.getWidth() / 2);
            w.playSound(mid, Sound.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 0.8f, 0.7f);
        } else if (lit) {
            w.playSound(mid, Sound.BLOCK_FIRE_AMBIENT, SoundCategory.PLAYERS, 1.2f, 1.3f);
        }
        ensureTicker();
    }

    /** A creature died: when it was burning, the wildfire. Called for every death of the rpg (Skills). */
    void died(LivingEntity e) {
        Burn b = burns.remove(e.getUniqueId());
        if (b == null || b.stacks <= 0) return;
        LivingEntity p = b.owner == null ? null : skills.caster(b.owner);
        RpgPlayer r = p == null ? null : skills.player(p);
        if (r == null) return;
        ClassDef cls = r.heroClass();
        double max = Math.max(1, cls.passive("burn-max", 5));
        double radius = cls.passive("wildfire-radius", 3.5);
        double coefficient = cls.passive("wildfire", 0.8) * b.stacks / max;
        int passed = (int) cls.passive("wildfire-stacks", 2);
        Location center = e.getLocation().add(0, e.getHeight() / 2, 0);
        World w = center.getWorld();
        w.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.7f, 1.5f);
        w.playSound(center, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 1f, 0.7f);
        w.spawnParticle(Particle.FLAME, center, 60, 0.2, 0.2, 0.2, 0.22);
        w.spawnParticle(Particle.LAVA, center, 14, 0.4, 0.3, 0.4);
        w.spawnParticle(Particle.LARGE_SMOKE, center, 10, 0.4, 0.4, 0.4, 0.03);
        fx.debris(center, 8, EMBERS, 15);
        Location floor = SpellFx.ground(e.getLocation().add(0, 0.2, 0));
        if (floor != null) flameRing(floor, radius * 0.8, 9, 0.7f, 1.1f, 14);
        String burst = "wildfire#" + e.getUniqueId();
        CombatMath.Hit hit = new CombatMath.Hit(CombatMath.Type.MAGIC, "fire", coefficient, 0, false, true);
        // the flames reach the neighbours a moment later: the chain is seen running through the pack
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!Skills.here(p)) return;
            RpgPlayer now = skills.player(p);
            if (now == null) return;
            for (LivingEntity other : skills.enemies(center, radius)) {
                LivingEntity body = skills.body(other);
                if (body == null || body.equals(e)) continue;
                CombatMath.Outcome o = combat.strike(p, body, hit, burst);
                if (o != null && !o.dodged() && !body.isDead() && body.getHealth() > 0) ignite(p, now, body, passed);
            }
        }, 4L);
    }

    /** Heats the pyromancer; at 100 the blaze starts. */
    private void heat(LivingEntity p, RpgPlayer r, double amount) {
        if (amount <= 0) return;
        long now = CombatEngine.now();
        Heat h = heats.computeIfAbsent(p.getUniqueId(), k -> new Heat());
        h.lastGain = now;
        if (h.blazeUntil > now) return;
        h.value = Math.min(100, h.value + amount);
        if (h.value >= 100) blaze(p, r, h);
        else level(p, r, h);
        ensureTicker();
    }

    /** The heat's bonus damage, by tenths of the gauge (the stats are only computed again when a tenth is crossed). */
    private void level(LivingEntity p, RpgPlayer r, Heat h) {
        int step = (int) Math.floor(h.value / 10);
        if (step == h.step) return;
        h.step = step;
        double bonus = r.heroClass().passive("heat-damage", 0.25) * step / 10.0;
        r.buff("heat", List.of(Modifier.add("damage_bonus", bonus, "heat")), step == 0 ? 0 : Long.MAX_VALUE);
        if (p instanceof Player pl) players.recompute(pl);
    }

    private void blaze(LivingEntity p, RpgPlayer r, Heat h) {
        ClassDef cls = r.heroClass();
        long now = CombatEngine.now();
        h.blazeFrom = now;
        h.blazeUntil = now + Math.round(20 * cls.passive("blaze-seconds", 7));
        h.value = 100;
        h.step = 10;
        r.buff("heat", List.of(Modifier.add("damage_bonus", cls.passive("heat-damage", 0.25), "heat"),
                Modifier.add("attack_speed", cls.passive("blaze-speed", 0.35), "heat")), h.blazeUntil);
        if (p instanceof Player pl) players.recompute(pl);
        World w = p.getWorld();
        Location at = p.getLocation();
        w.playSound(at, Sound.ENTITY_BLAZE_AMBIENT, SoundCategory.PLAYERS, 1.2f, 0.6f);
        w.playSound(at, Sound.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 1.2f, 0.5f);
        w.playSound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.PLAYERS, 0.35f, 1.7f);
        w.spawnParticle(Particle.FLAME, at.clone().add(0, 1, 0), 80, 0.3, 0.6, 0.3, 0.12);
        w.spawnParticle(Particle.LAVA, at.clone().add(0, 1, 0), 16, 0.4, 0.5, 0.4);
        flameRing(at, 1.6, 10, 0.6f, 1.6f, 16);
        // reborn from his own flames: his only way to mend in a long fight
        double mend = cls.passive("blaze-heal", 0);
        if (mend > 0) combat.heal(p, mend * combat.maxHealth(p), true);
        if (p instanceof Player pl) plugin.messages().actionBar(pl, "fire.blaze");
    }

    /** True while the pyromancer burns at full heat. */
    private boolean blazing(LivingEntity p) {
        Heat h = heats.get(p.getUniqueId());
        return h != null && h.blazeUntil > CombatEngine.now();
    }

    /** Every tick: burns bite once a second and are seen, heat cools down, blazes end, the heat bar follows. */
    private void ensureTicker() {
        if (ticker != null) return;
        ticker = new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                t++;
                long now = CombatEngine.now();
                for (Iterator<Burn> it = burns.values().iterator(); it.hasNext(); ) {
                    Burn b = it.next();
                    LivingEntity e = b.entity;
                    if (!e.isValid() || e.isDead()) {
                        // died() already took the ones that died burning; this one was removed (end of a run)
                        it.remove();
                        continue;
                    }
                    if (now >= b.until) {
                        e.setVisualFire(false);
                        it.remove();
                        continue;
                    }
                    b.last = e.getLocation();
                    if (t % 4 == 0) {
                        e.getWorld().spawnParticle(Particle.SMALL_FLAME, e.getLocation().add(0, e.getHeight() * 0.6, 0), b.stacks,
                                e.getWidth() / 2, e.getHeight() / 3, e.getWidth() / 2, 0.01);
                    }
                    if (now < b.nextTick) continue;
                    b.nextTick = now + 20;
                    LivingEntity p = skills.caster(b.owner);
                    RpgPlayer r = p == null ? null : skills.player(p);
                    if (r == null) continue;
                    // a burn never crits nor is dodged: steady, readable damage
                    combat.strike(p, e, new CombatMath.Hit(CombatMath.Type.MAGIC, "fire",
                            r.heroClass().passive("burn", 0.1) * b.stacks, 0, false, false), null);
                }
                for (Iterator<Map.Entry<UUID, Heat>> it = heats.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<UUID, Heat> en = it.next();
                    Heat h = en.getValue();
                    LivingEntity p = skills.caster(en.getKey());
                    RpgPlayer r = p == null ? null : skills.player(p);
                    if (r == null || !r.heroClass().passive().containsKey("burn-max")) {
                        if (p != null && h.bar != null) p.hideBossBar(h.bar);
                        if (r != null) r.buff("heat", List.of(), 0);
                        it.remove();
                        continue;
                    }
                    ClassDef cls = r.heroClass();
                    if (h.blazeUntil > 0) {
                        if (now >= h.blazeUntil) {
                            // the blaze spent everything
                            h.blazeUntil = 0;
                            h.value = 0;
                            h.step = -1;
                            level(p, r, h);
                            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.8f, 0.8f);
                        } else {
                            h.value = 100.0 * (h.blazeUntil - now) / Math.max(1, h.blazeUntil - h.blazeFrom);
                            if (t % 2 == 0) {
                                p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 1, 0), 3, 0.3, 0.5, 0.3, 0.02);
                                p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 0.1, 0), 2, 0.4, 0.05, 0.4, GLOW_DUST);
                            }
                        }
                    } else if (h.value > 0 && now - h.lastGain > 20 * cls.passive("heat-decay-after", 4)) {
                        h.value = Math.max(0, h.value - cls.passive("heat-decay", 12) / 20.0);
                        level(p, r, h);
                    }
                    if (h.value <= 0 && h.blazeUntil == 0) {
                        if (h.bar != null) p.hideBossBar(h.bar);
                        it.remove();
                        continue;
                    }
                    if (t % 2 == 0) bar(p, h);
                }
                if (burns.isEmpty() && heats.isEmpty()) {
                    cancel();
                    ticker = null;
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void bar(LivingEntity caster, Heat h) {
        // a stand-in (the class showcase's actor) has no screen to show the gauge on
        if (!(caster instanceof Player p)) return;
        boolean blaze = h.blazeUntil > 0;
        net.kyori.adventure.text.Component title = plugin.messages().get(blaze ? "fire.bar-blaze" : "fire.bar-heat", p,
                "%heat%", String.valueOf((int) Math.round(h.value)));
        float progress = (float) Math.max(0, Math.min(1, h.value / 100));
        BossBar.Color color = blaze ? BossBar.Color.YELLOW : BossBar.Color.RED;
        if (h.bar == null) {
            h.bar = BossBar.bossBar(title, progress, color, BossBar.Overlay.NOTCHED_10);
            p.showBossBar(h.bar);
        } else {
            h.bar.name(title);
            h.bar.progress(progress);
            h.bar.color(color);
        }
    }

    // ================================================================== basic attack: ember bolt

    /** A bolt of embers where the player looks; in the blaze, a fan of them. One burn stack on what it hits. */
    void ember(LivingEntity p, RpgPlayer r, CombatMath.Hit hit, String cast, double range, LivingEntity aimed) {
        Location eye = p.getEyeLocation();
        Vector dir = aimed == null ? eye.getDirection().normalize() : AimAssist.toward(eye, aimed);
        World w = p.getWorld();
        w.playSound(eye, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 0.5f, 1.5f);
        w.playSound(eye, Sound.BLOCK_FIRE_AMBIENT, SoundCategory.PLAYERS, 0.7f, 1.8f);
        int bolts = blazing(p) ? (int) Math.max(1, r.heroClass().passive("blaze-bolts", 3)) : 1;
        double spread = Math.toRadians(11);
        for (int i = 0; i < bolts; i++) {
            // the side bolts belong to the same attack: a creature takes one bolt of a fan, the fan takes several creatures
            Vector d = dir.clone().rotateAroundY((i - (bolts - 1) / 2.0) * spread);
            bolt(p, hit, cast, range, d, bolts > 1);
        }
    }

    private void bolt(LivingEntity p, CombatMath.Hit hit, String cast, double range, Vector dir, boolean blazing) {
        Location eye = p.getEyeLocation();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1e-4) right = new Vector(1, 0, 0);
        right.normalize();
        Location start = eye.clone().add(right.multiply(0.35)).add(0, -0.25, 0).add(dir.clone().multiply(0.6));
        float size = blazing ? 0.34f : 0.26f;
        BlockDisplay ember = fx.display(start, (blazing ? Material.SHROOMLIGHT : Material.MAGMA_BLOCK).createBlockData(), SpellFx.cube(size), 15);
        ember.setTeleportDuration(1);
        World w = p.getWorld();
        double speed = 1.5;
        new BukkitRunnable() {
            final Location at = start.clone();
            double gone;
            int t;

            @Override
            public void run() {
                t++;
                for (int sub = 0; sub < 3; sub++) {
                    at.add(dir.clone().multiply(speed / 3));
                    gone += speed / 3;
                    LivingEntity e = skills.struck(at, 0.3);
                    if (e != null) {
                        impact(e);
                        return;
                    }
                    if (!at.getBlock().isPassable() || gone >= range || !ember.isValid()) {
                        impact(null);
                        return;
                    }
                }
                ember.teleport(at);
                SpellFx.animate(ember, SpellFx.cube(size, new Quaternionf().rotateXYZ(t * 0.7f, t * 0.5f, t * 0.3f)), 1);
                w.spawnParticle(Particle.FLAME, at, 2, 0.04, 0.04, 0.04, 0.005);
                if (t % 2 == 0) w.spawnParticle(Particle.SMOKE, at, 1, 0.03, 0.03, 0.03, 0.005);
            }

            private void impact(LivingEntity e) {
                cancel();
                fx.forget(ember);
                w.spawnParticle(Particle.FLAME, at, 12, 0.1, 0.1, 0.1, 0.07);
                w.spawnParticle(Particle.LAVA, at, 2, 0.1, 0.1, 0.1);
                w.playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.35f, 1.7f);
                RpgPlayer now = skills.player(p);
                if (e != null && now != null) hit(p, now, e, hit, cast, 1, true);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ================================================================== right click: phoenix flight

    /**
     * The player takes off in a burst of flames (creatures around him are burnt and thrown back), flies where he
     * looks on wings of fire and lands in a second burst.
     */
    boolean flight(LivingEntity p, RpgPlayer r, ClassDef.Skill s, String cast) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) return false;
        dir.normalize();
        double radius = s.number("radius", 3);
        World w = p.getWorld();
        Location from = p.getLocation();
        burst(p, from, s, cast + "#up", radius, true);
        w.playSound(from, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 1f, 0.6f);
        w.playSound(from, Sound.ENTITY_PHANTOM_FLAP, SoundCategory.PLAYERS, 1.2f, 0.7f);
        p.setVelocity(dir.clone().multiply(s.number("strength", 1.15)).setY(s.number("lift", 0.72)));
        p.setFallDistance(0);
        Vector side = new Vector(-dir.getZ(), 0, dir.getX());
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                t++;
                if (!Skills.here(p) || p.isDead()) {
                    cancel();
                    return;
                }
                p.setFallDistance(0);
                Location at = p.getLocation();
                // the wings: two lines of flame swept back from his shoulders, beating
                double beat = Math.sin(t * 0.9) * 0.35;
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    for (double k = 0.3; k <= 1.5; k += 0.3) {
                        Location wing = at.clone().add(side.clone().multiply(sgn * k)).add(dir.clone().multiply(-k * 0.5)).add(0, 1.2 + beat * k, 0);
                        w.spawnParticle(Particle.FLAME, wing, 1, 0.02, 0.02, 0.02, 0.003);
                    }
                }
                w.spawnParticle(Particle.DUST, at.clone().add(0, 0.4, 0), 3, 0.2, 0.2, 0.2, GLOW_DUST);
                if (t % 3 == 0) w.spawnParticle(Particle.LAVA, at, 1);
                // down again (never before he really left the ground), or a flight cut short
                if ((t > 4 && p.isOnGround()) || t > 50) {
                    cancel();
                    burst(p, at, s, cast + "#down", radius, false);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    /** The burst of a take-off or a landing: a ring of flames, creatures around hit, set alight and pushed away. */
    private void burst(LivingEntity p, Location at, ClassDef.Skill s, String cast, double radius, boolean takeOff) {
        World w = at.getWorld();
        Location floor = SpellFx.ground(at.clone().add(0, 0.3, 0));
        if (floor != null) {
            flameRing(floor, radius * 0.55, 7, 0.6f, 1.0f, 12);
            flameRing(floor, radius, 12, 0.7f, 1.5f, 16);
        }
        w.spawnParticle(Particle.FLAME, at.clone().add(0, 0.4, 0), 50, 0.3, 0.2, 0.3, 0.16);
        w.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 0.3, 0), 8, radius / 3, 0.1, radius / 3, 0.02);
        w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, takeOff ? 0.5f : 0.8f, takeOff ? 1.7f : 1.2f);
        w.playSound(at, Sound.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 1f, takeOff ? 1.2f : 0.8f);
        RpgPlayer r = skills.player(p);
        if (r == null) return;
        int stacks = (int) s.number("burn", 1);
        for (LivingEntity e : skills.enemies(at, radius)) {
            if (Math.abs(e.getLocation().getY() - at.getY()) > 3) continue;
            if (hit(p, r, e, s.hit(), cast, stacks, false) != null && !boss(e)) {
                Vector away = e.getLocation().toVector().subtract(at.toVector()).setY(0);
                if (away.lengthSquared() > 1e-4) e.setVelocity(e.getVelocity().add(away.normalize().multiply(0.55)).setY(0.3));
            }
        }
    }

    // ================================================================== A: fire wave

    /**
     * A wave of flames rolling out in a fan in front of the player: rows of fire bursting from the ground, taller and
     * wider as it goes; every creature it passes is hit, set alight and pushed back.
     */
    boolean wave(LivingEntity p, RpgPlayer r, ClassDef.Skill s, String cast) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) return false;
        dir.normalize();
        Location from = p.getLocation();
        double range = s.number("range", 10);
        double half = Math.toRadians(s.number("angle", 35));
        int stacks = (int) s.number("burn", 2);
        World w = p.getWorld();
        w.playSound(from, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 1.2f, 0.5f);
        w.playSound(from, Sound.ENTITY_ENDER_DRAGON_SHOOT, SoundCategory.PLAYERS, 0.5f, 1.3f);
        new BukkitRunnable() {
            double d = 1.5;

            @Override
            public void run() {
                if (d > range || !Skills.here(p)) {
                    cancel();
                    return;
                }
                double k = d / range;
                double arc = d * half;
                int n = (int) Math.max(1, Math.round(arc * 2 / 1.25));
                for (int i = 0; i <= n; i++) {
                    double a = n == 0 ? 0 : -half + 2 * half * i / n;
                    Location l = SpellFx.ground(from.clone().add(dir.clone().rotateAroundY(a).multiply(d)).add(0, 0.5, 0));
                    if (l == null) continue;
                    float h = (float) (0.6 + 0.9 * k) + SpellFx.rnd(0.3f);
                    fx.spike(l, FLAMES, 0.9f + (float) (0.5 * k), h, 0, 0, 12, 15);
                    if (i % 2 == 0) fx.spike(l, Material.MAGMA_BLOCK.createBlockData(), 0.3f, 0.25f + SpellFx.rnd(0.2f), SpellFx.rnd(6f), 0.5f, 18, 15);
                    w.spawnParticle(Particle.FLAME, l.clone().add(0, 0.5, 0), 4, 0.3, 0.3, 0.3, 0.03);
                }
                Location mid = from.clone().add(dir.clone().multiply(d));
                w.spawnParticle(Particle.LAVA, mid.clone().add(0, 0.5, 0), 3, arc / 2, 0.2, arc / 2);
                w.playSound(mid, Sound.BLOCK_FIRE_AMBIENT, SoundCategory.PLAYERS, 1.6f, (float) (0.6 + 0.8 * k));
                RpgPlayer now = skills.player(p);
                if (now != null) {
                    for (LivingEntity e : skills.enemies(from, d + 1.2)) {
                        Vector to = e.getLocation().toVector().subtract(from.toVector());
                        if (Math.abs(to.getY()) > 3) continue;
                        to.setY(0);
                        double dist = to.length();
                        // inside the band the wave has reached, inside the fan (a creature right on him counts)
                        if (dist > d + 0.8 || (dist > 1.2 && to.clone().normalize().dot(dir) < Math.cos(half + 0.12))) continue;
                        if (hit(p, now, e, s.hit(), cast, stacks, false) != null && !boss(e)) {
                            e.setVelocity(e.getVelocity().add(dir.clone().multiply(0.45)).setY(0.25));
                        }
                    }
                }
                d += 1.25;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    // ================================================================== F: little sun

    /**
     * A sun rises where the player aims: it turns above the ground and scorches every creature under it, pulse after
     * pulse (beams of fire, burn), then collapses and bursts: one last heavy hit and a ring of flames running out.
     */
    boolean sun(LivingEntity p, RpgPlayer r, ClassDef.Skill s, String cast) {
        Location target = SpellFx.aimedGround(p, s.number("range", 22), skills);
        if (target == null) {
            skills.tell(p, "skill.blocked");
            return false;
        }
        double radius = s.number("radius", 5.5);
        float size = (float) s.number("size", 2.2);
        double height = s.number("height", 4.5);
        int pulses = Math.max(1, s.hits());
        int period = (int) Math.max(4, Math.round(20 * Math.max(1, s.duration()) / pulses));
        int stacks = (int) s.number("burn", 1);
        World w = p.getWorld();
        Location low = target.clone().add(0, 0.6, 0);
        BlockDisplay core = fx.display(low, Material.SHROOMLIGHT.createBlockData(), SpellFx.cube(0.2f), 15);
        BlockDisplay shell = fx.display(low, Material.MAGMA_BLOCK.createBlockData(), SpellFx.cube(0.2f), 15);
        BlockDisplay halo = fx.display(low, Material.ORANGE_STAINED_GLASS.createBlockData(), SpellFx.cube(0.2f), 15);
        for (BlockDisplay d : List.of(core, shell, halo)) d.setTeleportDuration(10);
        Location high = target.clone().add(0, height, 0);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (BlockDisplay d : List.of(core, shell, halo)) if (d.isValid()) d.teleport(high);
        }, 2L);
        w.playSound(target, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.2f, 0.6f);
        w.playSound(target, Sound.ENTITY_BLAZE_AMBIENT, SoundCategory.PLAYERS, 1f, 0.5f);
        w.playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, SoundCategory.PLAYERS, 1f, 0.7f);
        int rise = 12, life = rise + pulses * period;
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                t++;
                if (!Skills.here(p)) {
                    end(false);
                    return;
                }
                // it turns, and breathes with each pulse
                float grow = (float) Math.min(1, t / (double) rise);
                float breath = 1 + 0.08f * (float) Math.sin(t * 0.5);
                SpellFx.animate(core, SpellFx.cube(size * 0.78f * grow * breath, new Quaternionf().rotateXYZ(t * 0.11f, t * 0.17f, 0)), 2);
                SpellFx.animate(shell, SpellFx.cube(size * grow, new Quaternionf().rotateXYZ(-t * 0.07f, t * 0.09f, t * 0.05f)), 2);
                SpellFx.animate(halo, SpellFx.cube(size * 1.3f * grow * breath, new Quaternionf().rotateXYZ(t * 0.04f, -t * 0.06f, t * 0.03f)), 2);
                Location at = low.clone().add(0, (height - 0.6) * grow, 0);
                w.spawnParticle(Particle.FLAME, at, 4, size / 2, size / 2, size / 2, 0.02);
                if (t % 2 == 0) SpellFx.ring(target, radius, 28, Particle.DUST, t % 4 == 0 ? GLOW_DUST : DEEP_DUST);
                if (t % 5 == 0) w.spawnParticle(Particle.LAVA, target.clone().add(0, 0.3, 0), 3, radius / 2, 0.1, radius / 2);
                if (t > rise && (t - rise) % period == 0) pulse(at, (t - rise) / period);
                if (t >= life) end(true);
            }

            private void pulse(Location at, int n) {
                w.playSound(at, Sound.BLOCK_FIRE_AMBIENT, SoundCategory.PLAYERS, 2f, 0.7f + 0.06f * n);
                w.playSound(at, Sound.ENTITY_BLAZE_BURN, SoundCategory.PLAYERS, 0.9f, 1.2f);
                RpgPlayer now = skills.player(p);
                if (now == null) return;
                String hitCast = cast + "#" + n;
                for (LivingEntity e : skills.enemies(target, radius)) {
                    if (Math.abs(e.getLocation().getY() - target.getY()) > 4) continue;
                    if (hit(p, now, e, s.hit(), hitCast, stacks, false) == null) continue;
                    // a beam from the sun down to it
                    Location to = e.getLocation().add(0, e.getHeight() / 2, 0);
                    Vector step = to.toVector().subtract(at.toVector());
                    int parts = (int) Math.max(3, step.length() / 0.4);
                    step.multiply(1.0 / parts);
                    Location beam = at.clone();
                    for (int i = 0; i < parts; i++) {
                        beam.add(step);
                        w.spawnParticle(Particle.DUST, beam, 1, 0, 0, 0, GLOW_DUST);
                        if (i % 3 == 0) w.spawnParticle(Particle.SMALL_FLAME, beam, 1, 0, 0, 0, 0);
                    }
                }
            }

            private void end(boolean burst) {
                cancel();
                if (!burst) {
                    for (BlockDisplay d : List.of(core, shell, halo)) fx.forget(d);
                    return;
                }
                // collapse, then the nova
                for (BlockDisplay d : List.of(core, shell, halo)) SpellFx.animate(d, SpellFx.cube(0.15f), 6);
                w.playSound(high, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 1.4f, 1.5f);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    for (BlockDisplay d : List.of(core, shell, halo)) fx.forget(d);
                    nova(p, target, high, s, cast, radius);
                }, 7L);
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    private void nova(LivingEntity p, Location target, Location sun, ClassDef.Skill s, String cast, double radius) {
        World w = target.getWorld();
        w.playSound(target, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 1.6f, 0.6f);
        w.playSound(target, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.PLAYERS, 1.2f, 0.8f);
        w.playSound(target, Sound.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 0.35f, 1.6f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, sun, 1);
        w.spawnParticle(Particle.FLAME, sun, 220, 0.3, 0.3, 0.3, 0.5);
        w.spawnParticle(Particle.LAVA, target.clone().add(0, 0.5, 0), 40, radius / 2, 0.3, radius / 2);
        fx.debris(sun, 20, EMBERS, 15);
        int rings = (int) Math.max(3, Math.ceil(radius / 1.1));
        for (int i = 0; i < rings; i++) {
            double rr = 1.0 + (radius - 1.0) * i / (rings - 1.0);
            boolean rim = i == rings - 1;
            Bukkit.getScheduler().runTaskLater(plugin, () -> flameRing(target, rr, (int) Math.max(8, rr * 3.5), rim ? 1.0f : 0.75f, rim ? 2.6f : 1.3f, rim ? 30 : 14), 1L + i * 2L);
        }
        RpgPlayer r = skills.player(p);
        if (r == null) return;
        double coefficient = s.number("final", 2.5);
        CombatMath.Hit last = new CombatMath.Hit(CombatMath.Type.MAGIC, "fire", coefficient, 0, true, true);
        int stacks = (int) s.number("final-burn", 2);
        for (LivingEntity e : skills.enemies(target, radius)) {
            if (Math.abs(e.getLocation().getY() - target.getY()) > 4) continue;
            if (hit(p, r, e, last, cast + "#nova", stacks, false) != null && !boss(e)) {
                Vector away = e.getLocation().toVector().subtract(target.toVector()).setY(0);
                if (away.lengthSquared() > 1e-4) e.setVelocity(e.getVelocity().add(away.normalize().multiply(0.5)).setY(0.4));
            }
        }
    }

    // ================================================================== shapes

    /** A ring of flames bursting out of the ground. */
    private void flameRing(Location center, double radius, int count, float width, float height, int life) {
        double off = SpellFx.rnd(1f);
        for (int k = 0; k < count; k++) {
            double a = off + Math.PI * 2 * k / count;
            Location l = SpellFx.ground(center.clone().add(Math.cos(a) * radius, 0.4, Math.sin(a) * radius));
            if (l == null) continue;
            fx.spike(l, FLAMES, width, height * (0.8f + SpellFx.rnd(0.4f)), 0, 0, life, 15);
        }
        center.getWorld().spawnParticle(Particle.FLAME, center.clone().add(0, 0.3, 0), count * 2, radius / 2, 0.1, radius / 2, 0.04);
    }

    private static boolean boss(LivingEntity e) {
        return e.getScoreboardTags().contains(Mobs.BOSS_TAG);
    }
}
