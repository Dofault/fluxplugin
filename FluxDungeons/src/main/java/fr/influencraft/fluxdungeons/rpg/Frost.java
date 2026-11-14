package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.fluxdungeons.mob.Mobs;
import fr.influencraft.fluxdungeons.rpg.core.ClassDef;
import fr.influencraft.fluxdungeons.rpg.core.CombatMath;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.util.Transformation;
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
 * The cryomancer's frost (2026-11-13): his basic attack, his three skills and the mechanic they share.
 * <ul>
 *   <li><b>Chill</b>: each of his hits leaves frost stacks on the creature (slower at each stack). At
 *   {@code chill-max} stacks it is <b>frozen</b>: shut in a shell of ice, unable to move ({@code freeze} seconds,
 *   shortened by its tenacity; a boss is only slowed, for half as long).</li>
 *   <li><b>Shatter</b>: a frozen creature hit again (by another cast than the one that froze it) bursts: a heavy hit
 *   ({@code shatter} x magic power) and shards flying out that hit and chill the creatures around
 *   ({@code shatter-splash} of it, {@code shatter-radius} blocks). A shard reaching another frozen creature shatters it
 *   too: a frozen group goes up in a chain.</li>
 * </ul>
 * Everything is drawn with block displays (never real blocks: nothing stays in the map), every hit goes through
 * {@link CombatEngine#strike}. Numbers come from classes.yml ({@code passive} of the class, {@code extra} of each skill).
 */
final class Frost {

    /** What frost a creature carries. */
    private static final class Chill {
        final LivingEntity entity;
        int stacks;
        long until;
        long frozenUntil;
        String frozenBy;
        BlockDisplay shell;
        final List<BlockDisplay> crust = new ArrayList<>();

        Chill(LivingEntity entity) {
            this.entity = entity;
        }

        boolean frozen(long now) {
            return frozenUntil > now;
        }
    }

    private static final Material[] ICE = {Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE};
    private static final Particle.DustOptions FROST_DUST = new Particle.DustOptions(Color.fromRGB(0xA8E4FF), 1.2f);
    private static final Particle.DustOptions DEEP_DUST = new Particle.DustOptions(Color.fromRGB(0x3D7BAB), 1.6f);

    private final FluxPlugin plugin;
    private final CombatEngine combat;
    private final Skills skills;
    private final Map<UUID, Chill> chills = new HashMap<>();
    private final Set<BlockDisplay> alive = new HashSet<>();
    private BukkitTask ticker;

    Frost(FluxPlugin plugin, CombatEngine combat, Skills skills) {
        this.plugin = plugin;
        this.combat = combat;
        this.skills = skills;
    }

    /** Removes every piece of ice still standing (plugin stopping). */
    void clear() {
        if (ticker != null) ticker.cancel();
        ticker = null;
        for (BlockDisplay d : alive) if (d.isValid()) d.remove();
        alive.clear();
        chills.clear();
    }

    // ================================================================== the shared mechanic

    /**
     * A frost hit of the cryomancer: through the engine, then chill ({@code stacks}) or, on a frozen creature, a
     * shatter. Null when it did not land.
     */
    CombatMath.Outcome hit(Player p, RpgPlayer r, LivingEntity target, CombatMath.Hit hit, String cast, int stacks) {
        LivingEntity body = skills.body(target);
        if (body == null) return null;
        CombatMath.Outcome o = combat.strike(p, body, hit, cast);
        if (o == null || o.dodged() || body.isDead()) return o;
        Chill c = chills.get(body.getUniqueId());
        long now = CombatEngine.now();
        if (c != null && c.frozen(now) && !castRoot(cast).equals(c.frozenBy)) shatter(p, r, c, cast);
        else chill(p, r, body, stacks, cast);
        return o;
    }

    /** Adds frost stacks; at the class's maximum the creature freezes. */
    void chill(Player p, RpgPlayer r, LivingEntity e, int stacks, String cast) {
        if (stacks <= 0 || e.isDead()) return;
        ClassDef cls = r.heroClass();
        int max = (int) Math.max(1, cls.passive("chill-max", 3));
        long now = CombatEngine.now();
        Chill c = chills.computeIfAbsent(e.getUniqueId(), k -> new Chill(e));
        if (c.frozen(now)) return;
        c.stacks = Math.min(max, c.stacks + stacks);
        c.until = now + Math.round(20 * cls.passive("chill-seconds", 4));
        if (c.stacks >= max) {
            freeze(r, c, cast);
            return;
        }
        if (!boss(e)) {
            e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) (c.until - now), c.stacks - 1, false, false, false));
        }
        World w = e.getWorld();
        w.spawnParticle(Particle.SNOWFLAKE, e.getLocation().add(0, e.getHeight() / 2, 0), 6 * c.stacks, e.getWidth() / 2, e.getHeight() / 3, e.getWidth() / 2, 0.02);
        w.playSound(e.getLocation(), Sound.BLOCK_POWDER_SNOW_STEP, SoundCategory.PLAYERS, 0.8f, 0.8f + 0.25f * c.stacks);
        ensureTicker();
    }

    /** Shuts a creature in ice (immediately, whatever its stacks: the comet). */
    void freeze(RpgPlayer r, LivingEntity e, String cast) {
        if (e.isDead()) return;
        Chill c = chills.computeIfAbsent(e.getUniqueId(), k -> new Chill(e));
        if (c.frozen(CombatEngine.now())) return;
        freeze(r, c, cast);
    }

    private void freeze(RpgPlayer r, Chill c, String cast) {
        LivingEntity e = c.entity;
        boolean boss = boss(e);
        double seconds = r.heroClass().passive("freeze", 1.6) * (boss ? 0.5 : 1);
        double d = CombatMath.duration(seconds, r.stats(), combat.stats(e), true);
        long now = CombatEngine.now();
        c.stacks = 0;
        c.frozenUntil = now + Math.max(10, Math.round(d * 20));
        c.until = c.frozenUntil;
        c.frozenBy = castRoot(cast);
        // a boss's fight is placed (the dungeons anchor it): slowed, never stopped
        e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) (c.frozenUntil - now), boss ? 2 : 9, false, false, false));
        removeShell(c);
        // the shell: a block of ice a little larger than the creature, growing from its feet
        float width = (float) e.getWidth() + 0.4f, height = (float) e.getHeight() + 0.3f;
        Location at = e.getLocation();
        at.setPitch(0);
        at.setYaw(ThreadLocalRandom.current().nextFloat() * 360);
        c.shell = display(at, Material.ICE.createBlockData(), new Transformation(new Vector3f(-width / 2, 0, -width / 2),
                new Quaternionf(), new Vector3f(width, 0.01f, width), new Quaternionf()));
        c.shell.setTeleportDuration(1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> animate(c.shell, new Transformation(new Vector3f(-width / 2, 0, -width / 2),
                new Quaternionf(), new Vector3f(width, height, width), new Quaternionf()), 4), 1L);
        // crystals stuck around its base, leaning out
        int n = 5;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n + ThreadLocalRandom.current().nextDouble(0.5);
            Location base = e.getLocation().add(Math.cos(a) * width * 0.45, 0, Math.sin(a) * width * 0.45);
            BlockDisplay crystal = crystal(base, ICE[1 + i % 2].createBlockData(), 0.22f + rnd(0.1f), height * (0.35f + rnd(0.25f)),
                    lean(Math.cos(a), Math.sin(a)), 0.5f + rnd(0.3f), -1);
            crystal.setTeleportDuration(1);
            c.crust.add(crystal);
        }
        World w = e.getWorld();
        w.playSound(at, Sound.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 1.2f, 0.6f);
        w.playSound(at, Sound.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 1f, 0.7f);
        w.spawnParticle(Particle.SNOWFLAKE, at.clone().add(0, height / 2, 0), 40, width / 2, height / 2, width / 2, 0.06);
        w.spawnParticle(Particle.DUST, at.clone().add(0, 0.1, 0), 25, width / 2, 0.05, width / 2, FROST_DUST);
        ensureTicker();
    }

    /** A frozen creature bursts: heavy hit, then shards that hit, chill and shatter in turn what is around. */
    private void shatter(Player p, RpgPlayer r, Chill c, String cast) {
        LivingEntity e = c.entity;
        ClassDef cls = r.heroClass();
        c.frozenUntil = 0;
        c.stacks = 0;
        c.frozenBy = null;
        e.removePotionEffect(PotionEffectType.SLOWNESS);
        removeShell(c);
        Location center = e.getLocation().add(0, e.getHeight() / 2, 0);
        World w = center.getWorld();
        w.playSound(center, Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.6f, 0.55f);
        w.playSound(center, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 1.4f, 0.7f);
        w.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.35f, 1.8f);
        w.spawnParticle(Particle.BLOCK, center, 70, e.getWidth() / 2, e.getHeight() / 3, e.getWidth() / 2, 0.2, Material.ICE.createBlockData());
        w.spawnParticle(Particle.SNOWFLAKE, center, 50, 0.2, 0.2, 0.2, 0.35);
        w.spawnParticle(Particle.END_ROD, center, 16, 0.1, 0.1, 0.1, 0.25);
        shards(center, 12);
        String burst = castRoot(cast) + "#shatter" + e.getEntityId();
        combat.strike(p, e, CombatMath.Hit.magic(cls.passive("shatter", 1.4), "frost"), burst);
        double radius = cls.passive("shatter-radius", 3);
        CombatMath.Hit splash = CombatMath.Hit.magic(cls.passive("shatter", 1.4) * cls.passive("shatter-splash", 0.5), "frost");
        // the shards reach their neighbours a moment later: the chain is seen running from one to the next
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            RpgPlayer now = rpg(p);
            if (now == null) return;
            for (LivingEntity other : skills.enemies(center, radius)) {
                if (other.equals(e)) continue;
                hit(p, now, other, splash, burst, 1);
            }
        }, 3L);
    }

    /** Twice a second at most per creature: frost seen on chilled ones, shells following the frozen, thaws. */
    private void ensureTicker() {
        if (ticker != null) return;
        ticker = new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                t++;
                long now = CombatEngine.now();
                for (Iterator<Chill> it = chills.values().iterator(); it.hasNext(); ) {
                    Chill c = it.next();
                    LivingEntity e = c.entity;
                    if (!e.isValid() || e.isDead()) {
                        if (c.shell != null && c.shell.isValid()) {
                            c.shell.getWorld().spawnParticle(Particle.BLOCK, c.shell.getLocation().add(0, 0.8, 0), 40, 0.4, 0.5, 0.4, 0.1, Material.ICE.createBlockData());
                        }
                        removeShell(c);
                        it.remove();
                        continue;
                    }
                    if (c.shell != null) {
                        if (!c.frozen(now)) {
                            thaw(c);
                        } else if (c.shell.isValid()) {
                            Location to = e.getLocation();
                            to.setYaw(c.shell.getLocation().getYaw());
                            to.setPitch(0);
                            if (to.distanceSquared(c.shell.getLocation()) > 0.0025) {
                                // the crystals keep their place around the shell
                                Vector moved = to.toVector().subtract(c.shell.getLocation().toVector());
                                c.shell.teleport(to);
                                for (BlockDisplay d : c.crust) if (d.isValid()) d.teleport(d.getLocation().add(moved));
                            }
                            if (t % 4 == 0) e.getWorld().spawnParticle(Particle.SNOWFLAKE, to.clone().add(0, e.getHeight(), 0), 2, e.getWidth() / 3, 0.1, e.getWidth() / 3, 0.01);
                        }
                    }
                    if (c.shell == null) {
                        if (now >= c.until) {
                            it.remove();
                            continue;
                        }
                        if (t % 5 == 0) {
                            e.getWorld().spawnParticle(Particle.SNOWFLAKE, e.getLocation().add(0, e.getHeight() * 0.6, 0), c.stacks * 2,
                                    e.getWidth() / 2, e.getHeight() / 3, e.getWidth() / 2, 0.01);
                        }
                    }
                }
                if (chills.isEmpty()) {
                    cancel();
                    ticker = null;
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void thaw(Chill c) {
        if (c.shell != null && c.shell.isValid()) {
            Location at = c.shell.getLocation();
            at.getWorld().playSound(at, Sound.BLOCK_GLASS_HIT, SoundCategory.PLAYERS, 1f, 0.6f);
            at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 0.8, 0), 25, 0.4, 0.5, 0.4, 0.05, Material.ICE.createBlockData());
        }
        removeShell(c);
        c.frozenBy = null;
        c.until = CombatEngine.now() + 20;
    }

    private void removeShell(Chill c) {
        if (c.shell != null) forget(c.shell);
        c.shell = null;
        for (BlockDisplay d : c.crust) forget(d);
        c.crust.clear();
    }

    // ================================================================== basic attack: ice shard

    /** A shard of ice flying where the player looks; one stack of frost on the creature it hits. */
    void shard(Player p, RpgPlayer r, CombatMath.Hit hit, String cast, double range, LivingEntity aimed) {
        Location eye = p.getEyeLocation();
        // aim assist: towards the enemy he aimed beside (from his eyes, the hand is only a few centimetres off)
        Vector dir = aimed == null ? eye.getDirection().normalize() : AimAssist.toward(eye, aimed);
        // from the weapon's hand, a little to the right and below the eyes
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1e-4) right = new Vector(1, 0, 0);
        right.normalize();
        Location start = eye.clone().add(right.multiply(0.35)).add(0, -0.25, 0).add(dir.clone().multiply(0.6));
        start.setDirection(dir);
        BlockDisplay shard = display(start, ICE[ThreadLocalRandom.current().nextInt(2) + 1].createBlockData(),
                new Transformation(new Vector3f(-0.09f, -0.09f, -0.5f), new Quaternionf(), new Vector3f(0.18f, 0.18f, 0.9f), new Quaternionf()));
        shard.setTeleportDuration(1);
        World w = p.getWorld();
        w.playSound(start, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 1.7f);
        w.playSound(start, Sound.ENTITY_BREEZE_SHOOT, SoundCategory.PLAYERS, 0.4f, 1.8f);
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
                    if (!at.getBlock().isPassable() || gone >= range || !shard.isValid()) {
                        impact(null);
                        return;
                    }
                }
                Location look = at.clone();
                look.setDirection(dir);
                shard.teleport(look);
                // spin around its own length
                Quaternionf roll = new Quaternionf().rotateZ(t * 0.9f);
                animate(shard, new Transformation(roll.transform(new Vector3f(-0.09f, -0.09f, -0.5f)), roll,
                        new Vector3f(0.18f, 0.18f, 0.9f), new Quaternionf()), 1);
                w.spawnParticle(Particle.SNOWFLAKE, at, 2, 0.05, 0.05, 0.05, 0.005);
                w.spawnParticle(Particle.DUST, at, 1, 0.03, 0.03, 0.03, FROST_DUST);
            }

            private void impact(LivingEntity e) {
                cancel();
                forget(shard);
                w.spawnParticle(Particle.BLOCK, at, 14, 0.15, 0.15, 0.15, 0.08, Material.ICE.createBlockData());
                w.spawnParticle(Particle.SNOWFLAKE, at, 8, 0.1, 0.1, 0.1, 0.08);
                w.playSound(at, Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 0.5f, 1.9f);
                RpgPlayer now = rpg(p);
                if (e != null && now != null) hit(p, now, e, hit, cast, 1);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ================================================================== right click: frost glide

    /**
     * The player surfs ahead on a trail of ice bursting out of the ground behind him; the creatures he passes through
     * are hit and chilled.
     */
    boolean glide(Player p, RpgPlayer r, ClassDef.Skill s, String cast) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) return false;
        dir.normalize();
        int ticks = (int) Math.max(4, s.number("ticks", 8));
        double speed = s.number("distance", 9) / ticks;
        int stacks = (int) s.number("chill", 1);
        World w = p.getWorld();
        w.playSound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, SoundCategory.PLAYERS, 1f, 1.3f);
        w.playSound(p.getLocation(), Sound.BLOCK_POWDER_SNOW_BREAK, SoundCategory.PLAYERS, 1f, 0.8f);
        Vector side = new Vector(-dir.getZ(), 0, dir.getX());
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (t++ >= ticks || !p.isOnline() || p.isDead()) {
                    cancel();
                    if (p.isOnline()) {
                        p.setVelocity(dir.clone().multiply(speed * 0.4).setY(0.1));
                        w.spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0, 0.3, 0), 30, 0.6, 0.2, 0.6, 0.1);
                        w.playSound(p.getLocation(), Sound.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 0.9f, 1.4f);
                    }
                    return;
                }
                double vy = p.getVelocity().getY();
                p.setVelocity(dir.clone().multiply(speed).setY(Math.max(-0.4, Math.min(0.25, vy))));
                p.setFallDistance(0);
                Location feet = Frost.ground(p.getLocation());
                if (feet != null) {
                    // the ice sheet under him and crystals bursting out on each side, leaning away
                    BlockDisplay sheet = display(feet.clone().add(0, 0.01, 0), Material.ICE.createBlockData(), new Transformation(
                            new Vector3f(-0.75f, 0, -0.75f), new Quaternionf(), new Vector3f(1.5f, 0.06f, 1.5f), new Quaternionf()));
                    later(sheet, 36);
                    for (int sgn = -1; sgn <= 1; sgn += 2) {
                        Location l = Frost.ground(feet.clone().add(side.clone().multiply(sgn * (0.8 + rnd(0.3f)))));
                        if (l == null) continue;
                        crystal(l, ICE[1 + ThreadLocalRandom.current().nextInt(2)].createBlockData(), 0.3f + rnd(0.12f),
                                0.7f + rnd(0.6f) + t * 0.05f, lean(side.getX() * sgn, side.getZ() * sgn), 0.45f + rnd(0.25f), 28 + t);
                    }
                    w.spawnParticle(Particle.SNOWFLAKE, feet.clone().add(0, 0.2, 0), 6, 0.4, 0.05, 0.4, 0.03);
                    w.spawnParticle(Particle.CLOUD, feet.clone().add(0, 0.15, 0), 2, 0.2, 0.02, 0.2, 0.01);
                    if (t % 2 == 0) w.playSound(feet, Sound.BLOCK_GLASS_HIT, SoundCategory.PLAYERS, 0.6f, 1.2f + t * 0.06f);
                }
                RpgPlayer now = rpg(p);
                if (now == null) return;
                for (LivingEntity e : skills.enemies(p.getLocation(), 1.9)) {
                    if (hit(p, now, e, s.hit(), cast, stacks) != null && !boss(e)) {
                        e.setVelocity(e.getVelocity().add(side.clone().multiply(sideOf(p, e, side) * 0.5).setY(0.35)));
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private static double sideOf(Player p, LivingEntity e, Vector side) {
        return e.getLocation().toVector().subtract(p.getLocation().toVector()).dot(side) >= 0 ? 1 : -1;
    }

    // ================================================================== A: glacial rift

    /**
     * A rift running along the ground where the player looks: ice spikes burst out of it, taller and taller, with
     * smaller ones leaning out on each side; creatures on its path are thrown up and chilled; at its end, a crown of
     * huge spikes.
     */
    boolean rift(Player p, RpgPlayer r, ClassDef.Skill s, String cast) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) return false;
        dir.normalize();
        Location from = p.getLocation();
        double range = s.number("range", 14);
        double width = s.number("width", 1.6);
        double lift = s.number("lift", 0.55);
        int stacks = (int) s.number("chill", 2);
        Vector side = new Vector(-dir.getZ(), 0, dir.getX());
        float yaw = lean(dir.getX(), dir.getZ());
        World w = p.getWorld();
        w.playSound(from, Sound.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 1f, 0.5f);
        w.playSound(from, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.8f, 1.6f);
        new BukkitRunnable() {
            double d = 1.5;
            Location last = from;

            @Override
            public void run() {
                if (d > range || !p.isOnline()) {
                    cancel();
                    crown(p, last, s, cast, yaw);
                    return;
                }
                Location l = Frost.ground(from.clone().add(dir.clone().multiply(d)));
                if (l == null) {
                    cancel();
                    crown(p, last, s, cast, yaw);
                    return;
                }
                last = l;
                double k = d / range;
                // the spine: one big spike leaning forward, taller as the rift goes
                float h = (float) ((0.9 + 1.9 * k) * 0.7);
                crystal(l, ICE[1 + ThreadLocalRandom.current().nextInt(2)].createBlockData(), 0.45f + (float) (0.4 * k), h,
                        yaw + rnd(0.5f) - 0.25f, 0.18f + rnd(0.2f), 30);
                // flanks: smaller spikes leaning out
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    Location fl = Frost.ground(l.clone().add(side.clone().multiply(sgn * (0.65 + 0.5 * k + rnd(0.3f)))));
                    if (fl == null) continue;
                    crystal(fl, ICE[ThreadLocalRandom.current().nextInt(3)].createBlockData(), 0.28f + rnd(0.1f), h * (0.45f + rnd(0.2f)),
                            lean(side.getX() * sgn, side.getZ() * sgn), 0.5f + rnd(0.3f), 26);
                }
                w.spawnParticle(Particle.SNOWFLAKE, l.clone().add(0, 0.8, 0), 10, 0.4, 0.6, 0.4, 0.04);
                w.spawnParticle(Particle.DUST, l.clone().add(0, 0.1, 0), 6, 0.5, 0.05, 0.5, DEEP_DUST);
                w.playSound(l, Sound.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 0.9f, (float) (0.7 + 0.9 * k));
                w.playSound(l, Sound.BLOCK_AMETHYST_CLUSTER_PLACE, SoundCategory.PLAYERS, 0.7f, (float) (0.6 + 0.8 * k));
                RpgPlayer now = rpg(p);
                if (now != null) {
                    for (LivingEntity e : skills.enemies(l, width + 1.5)) {
                        Vector to = e.getLocation().toVector().subtract(l.toVector());
                        if (Math.abs(to.getY()) > 2.5 || Math.abs(to.dot(side)) > width || Math.abs(to.dot(dir)) > 1.2) continue;
                        if (hit(p, now, e, s.hit(), cast, stacks) != null && !boss(e)) {
                            e.setVelocity(e.getVelocity().add(dir.clone().multiply(0.15)).setY(lift));
                        }
                    }
                }
                d += 1.0;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    /** The end of the rift: a big spike and a ring of others leaning out, a burst that hits around. */
    private void crown(Player p, Location at, ClassDef.Skill s, String cast, float yaw) {
        World w = at.getWorld();
        double radius = s.number("end-radius", 2.5);
        crystal(at, Material.PACKED_ICE.createBlockData(), 1.1f, 3.6f, yaw, 0.05f, 40);
        crystal(at, Material.BLUE_ICE.createBlockData(), 0.6f, 4.4f, yaw + 0.8f, 0.12f, 40);
        int n = 7;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n + rnd(0.4f);
            Location l = Frost.ground(at.clone().add(Math.cos(a) * 1.3, 0, Math.sin(a) * 1.3));
            if (l == null) continue;
            crystal(l, ICE[i % 3].createBlockData(), 0.45f + rnd(0.2f), 1.6f + rnd(0.9f), lean(Math.cos(a), Math.sin(a)), 0.6f + rnd(0.25f), 34);
        }
        w.playSound(at, Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.4f, 0.5f);
        w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.5f, 1.5f);
        w.spawnParticle(Particle.SNOWFLAKE, at.clone().add(0, 1.5, 0), 60, 1, 1, 1, 0.12);
        w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.5, 0), 40, 1, 0.3, 1, 0.1, Material.PACKED_ICE.createBlockData());
        RpgPlayer now = rpg(p);
        if (now == null) return;
        for (LivingEntity e : skills.enemies(at, radius)) hit(p, now, e, s.hit(), cast, (int) s.number("chill", 2));
    }

    // ================================================================== F: winter comet

    /**
     * A rune of frost spreads where the player aims while a comet of ice falls from the sky; at the impact every
     * creature inside is hit and frozen at once, a ring of spikes runs outwards and a frozen ground keeps chilling
     * whoever stays on it.
     */
    boolean comet(Player p, RpgPlayer r, ClassDef.Skill s, String cast) {
        Location target = aimed(p, s.number("range", 24));
        if (target == null) {
            plugin.messages().send(p, "skill.blocked");
            return false;
        }
        double radius = s.number("radius", 5);
        int delay = (int) Math.max(8, Math.round(20 * s.number("delay", 0.9)));
        World w = p.getWorld();
        // from behind the player, high in the sky
        Vector back = target.toVector().subtract(p.getLocation().toVector()).setY(0);
        if (back.lengthSquared() < 1e-4) back = p.getLocation().getDirection().setY(0);
        back.normalize().multiply(-7);
        Location sky = target.clone().add(back).add(0, 20, 0);
        float size = (float) s.number("size", 2.4);
        BlockDisplay comet = display(sky, Material.PACKED_ICE.createBlockData(), new Transformation(new Vector3f(-size / 2, -size / 2, -size / 2),
                new Quaternionf(), new Vector3f(size, size, size), new Quaternionf()));
        BlockDisplay core = display(sky, Material.BLUE_ICE.createBlockData(), new Transformation(new Vector3f(-size * 0.35f, -size * 0.35f, -size * 0.35f),
                new Quaternionf(), new Vector3f(size * 0.7f, size * 0.7f, size * 0.7f), new Quaternionf()));
        comet.setTeleportDuration(1);
        core.setTeleportDuration(1);
        w.playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, SoundCategory.PLAYERS, 1f, 0.6f);
        w.playSound(target, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1.2f, 0.5f);
        w.playSound(target, Sound.ITEM_TRIDENT_RIPTIDE_3, SoundCategory.PLAYERS, 1.5f, 0.6f);
        Vector fall = target.toVector().subtract(sky.toVector()).multiply(1.0 / delay);
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                t++;
                // the rune: a ring closing in on its radius and six spokes turning
                double grow = Math.min(1, t / (double) delay * 1.4);
                ring(target, radius * grow, 32, Particle.DUST, FROST_DUST);
                double spin = t * 0.12;
                for (int i = 0; i < 6; i++) {
                    double a = spin + Math.PI * 2 * i / 6;
                    for (double k = 0.3; k < radius * grow; k += 0.7) {
                        w.spawnParticle(Particle.DUST, target.clone().add(Math.cos(a + k * 0.15) * k, 0.12, Math.sin(a + k * 0.15) * k), 1, 0, 0, 0, DEEP_DUST);
                    }
                }
                if (t % 3 == 0) w.spawnParticle(Particle.SNOWFLAKE, target.clone().add(0, 0.3, 0), 10, radius / 2, 0.1, radius / 2, 0.01);
                // the comet: falls and tumbles
                Location at = sky.clone().add(fall.clone().multiply(t));
                Quaternionf spinQ = new Quaternionf().rotateXYZ(t * 0.35f, t * 0.22f, t * 0.15f);
                comet.teleport(at);
                core.teleport(at);
                animate(comet, new Transformation(spinQ.transform(new Vector3f(-size / 2, -size / 2, -size / 2)), spinQ,
                        new Vector3f(size, size, size), new Quaternionf()), 1);
                Quaternionf coreQ = new Quaternionf().rotateXYZ(-t * 0.3f, t * 0.4f, 0);
                animate(core, new Transformation(coreQ.transform(new Vector3f(-size * 0.35f, -size * 0.35f, -size * 0.35f)), coreQ,
                        new Vector3f(size * 0.7f, size * 0.7f, size * 0.7f), new Quaternionf()), 1);
                w.spawnParticle(Particle.SNOWFLAKE, at, 14, size / 3, size / 3, size / 3, 0.05);
                w.spawnParticle(Particle.CLOUD, at, 4, size / 4, size / 4, size / 4, 0.02);
                w.spawnParticle(Particle.END_ROD, at, 2, size / 4, size / 4, size / 4, 0.02);
                if (t >= delay) {
                    cancel();
                    forget(comet);
                    forget(core);
                    impact(p, target, s, cast, radius);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private void impact(Player p, Location at, ClassDef.Skill s, String cast, double radius) {
        World w = at.getWorld();
        w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 1.4f, 0.6f);
        w.playSound(at, Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 2f, 0.5f);
        w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_BREAK, SoundCategory.PLAYERS, 1.5f, 0.5f);
        w.playSound(at, Sound.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 1.5f, 0.5f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, at.clone().add(0, 0.5, 0), 1);
        w.spawnParticle(Particle.END_ROD, at.clone().add(0, 1, 0), 40, 0.3, 0.3, 0.3, 0.4);
        w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.6, 0), 160, radius / 2, 0.6, radius / 2, 0.3, Material.PACKED_ICE.createBlockData());
        w.spawnParticle(Particle.SNOWFLAKE, at.clone().add(0, 0.8, 0), 200, 0.4, 0.4, 0.4, 0.45);
        shards(at.clone().add(0, 0.8, 0), 18);
        RpgPlayer r = rpg(p);
        if (r != null) {
            for (LivingEntity e : skills.enemies(at, radius)) {
                if (Math.abs(e.getLocation().getY() - at.getY()) > 3) continue;
                LivingEntity body = skills.body(e);
                if (body == null) continue;
                CombatMath.Outcome o = combat.strike(p, body, s.hit(), cast);
                if (o != null && !o.dodged() && !body.isDead()) {
                    Chill c = chills.get(body.getUniqueId());
                    // already frozen by something else: the comet shatters it
                    if (c != null && c.frozen(CombatEngine.now()) && !castRoot(cast).equals(c.frozenBy)) shatter(p, r, c, cast);
                    else freeze(r, body, cast);
                }
            }
        }
        // the shock wave: rings of spikes running out, leaning outwards, taller at the rim (a crown)
        int rings = (int) Math.max(3, Math.ceil(radius / 0.9));
        for (int i = 0; i < rings; i++) {
            double rr = 1.0 + (radius - 1.0) * i / (rings - 1.0);
            boolean rim = i == rings - 1;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                int n = (int) Math.max(8, rr * 4.5);
                double off = rnd(1f);
                for (int k = 0; k < n; k++) {
                    double a = off + Math.PI * 2 * k / n;
                    Location l = Frost.ground(at.clone().add(Math.cos(a) * rr, 0, Math.sin(a) * rr));
                    if (l == null) continue;
                    float h = rim ? 1.1f + rnd(0.6f) : 0.4f + rnd(0.3f);
                    crystal(l, ICE[ThreadLocalRandom.current().nextInt(3)].createBlockData(), rim ? 0.55f + rnd(0.2f) : 0.35f + rnd(0.15f), h,
                            lean(Math.cos(a), Math.sin(a)), rim ? 0.35f + rnd(0.2f) : 0.7f + rnd(0.2f), rim ? 70 : 30);
                }
                w.playSound(at, Sound.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 1f, (float) (0.6 + rr * 0.1));
            }, 1L + i * 2L);
        }
        // the frozen ground: stays a while and chills whoever stands on it
        double seconds = s.duration();
        if (seconds <= 0) return;
        BlockDisplay floor = display(at.clone().add(0, 0.02, 0), Material.ICE.createBlockData(), new Transformation(
                new Vector3f((float) -radius, 0, (float) -radius), new Quaternionf(), new Vector3f((float) radius * 2, 0.05f, (float) radius * 2), new Quaternionf()));
        int pulses = (int) Math.max(1, Math.round(seconds));
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (t++ >= pulses * 4 || !p.isOnline()) {
                    cancel();
                    forget(floor);
                    w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.2, 0), 30, radius / 2, 0.1, radius / 2, 0, Material.ICE.createBlockData());
                    return;
                }
                w.spawnParticle(Particle.SNOWFLAKE, at.clone().add(0, 0.4, 0), 12, radius / 2, 0.2, radius / 2, 0.01);
                if (t % 4 != 0) return;
                RpgPlayer now = rpg(p);
                if (now == null) return;
                for (LivingEntity e : skills.enemies(at, radius)) {
                    LivingEntity body = skills.body(e);
                    if (body != null && Math.abs(body.getLocation().getY() - at.getY()) < 2) chill(p, now, body, 1, cast + "#ground" + t);
                }
            }
        }.runTaskTimer(plugin, 10L, 5L);
    }

    // ================================================================== shapes

    /** The {@code yaw} of {@link #crystal} leaning towards this horizontal direction. */
    private static float lean(double x, double z) {
        return (float) Math.atan2(x, z);
    }

    /**
     * A crystal bursting out of the ground at {@code base}: it grows from its foot along its own axis (leaning
     * {@code tilt} radians towards {@code yaw}, see {@link #lean}), overshoots a little, settles, then sinks back after {@code life} ticks
     * (never when {@code life} is negative: removed by its owner).
     */
    private BlockDisplay crystal(Location base, BlockData data, float width, float height, float yaw, float tilt, int life) {
        Location at = base.clone();
        at.setYaw(0);
        at.setPitch(0);
        Quaternionf q = new Quaternionf().rotateY(yaw).rotateX(tilt).rotateY(rnd(1.6f));
        Vector3f offset = q.transform(new Vector3f(-width / 2, 0, -width / 2));
        BlockDisplay d = display(at, data, new Transformation(offset, q, new Vector3f(width, 0.01f, width), new Quaternionf()));
        at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 0.15, 0), 5, width * 0.4, 0.05, width * 0.4, 0, data);
        Bukkit.getScheduler().runTaskLater(plugin, () -> animate(d, new Transformation(offset, q, new Vector3f(width, height * 1.12f, width), new Quaternionf()), 2), 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> animate(d, new Transformation(offset, q, new Vector3f(width, height, width), new Quaternionf()), 3), 3L);
        if (life > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> animate(d, new Transformation(offset, q, new Vector3f(width * 0.8f, 0.01f, width * 0.8f), new Quaternionf()), 5), Math.max(6, life - 5L));
            later(d, life);
        }
        return d;
    }

    /** Small pieces of ice flying out of a point, falling and spinning, then gone. */
    private void shards(Location center, int count) {
        for (int i = 0; i < count; i++) {
            float size = 0.12f + rnd(0.16f);
            BlockDisplay s = display(center, ICE[ThreadLocalRandom.current().nextInt(3)].createBlockData(), new Transformation(
                    new Vector3f(-size / 2, -size / 2, -size / 2), new Quaternionf(), new Vector3f(size, size, size), new Quaternionf()));
            s.setTeleportDuration(1);
            Vector v = new Vector(ThreadLocalRandom.current().nextGaussian(), 0.6 + ThreadLocalRandom.current().nextDouble(), ThreadLocalRandom.current().nextGaussian())
                    .normalize().multiply(0.35 + ThreadLocalRandom.current().nextDouble(0.3));
            new BukkitRunnable() {
                final Location at = center.clone();
                int t;

                @Override
                public void run() {
                    if (++t > 16 || !s.isValid()) {
                        cancel();
                        forget(s);
                        return;
                    }
                    at.add(v);
                    v.setY(v.getY() - 0.06);
                    if (!at.getBlock().isPassable()) {
                        v.multiply(0);
                        at.setY(Math.floor(at.getY()) + 1.02);
                    }
                    s.teleport(at);
                    Quaternionf q = new Quaternionf().rotateXYZ(t * 0.6f, t * 0.4f, 0);
                    animate(s, new Transformation(q.transform(new Vector3f(-size / 2, -size / 2, -size / 2)), q, new Vector3f(size, size, size), new Quaternionf()), 1);
                }
            }.runTaskTimer(plugin, 1L, 1L);
        }
    }

    private BlockDisplay display(Location at, BlockData data, Transformation tr) {
        BlockDisplay d = at.getWorld().spawn(at, BlockDisplay.class, b -> {
            b.setBlock(data);
            b.setPersistent(false);
            b.setBrightness(new Display.Brightness(13, 15));
            b.setTransformation(tr);
            b.setViewRange(1.5f);
        });
        alive.add(d);
        return d;
    }

    private static void animate(BlockDisplay d, Transformation tr, int ticks) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(tr);
    }

    private void later(BlockDisplay d, long ticks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> forget(d), ticks);
    }

    private void forget(BlockDisplay d) {
        alive.remove(d);
        if (d.isValid()) d.remove();
    }

    private static void ring(Location at, double radius, int points, Particle particle, Particle.DustOptions dust) {
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            at.getWorld().spawnParticle(particle, at.clone().add(Math.cos(a) * radius, 0.12, Math.sin(a) * radius), 1, 0, 0, 0, 0, dust);
        }
    }

    /** Where the player aims: the floor under the creature looked at, else under the block looked at, within range. */
    private Location aimed(Player p, double range) {
        LivingEntity looked = skills.lookedAt(p, range);
        if (looked != null) {
            // a flying creature: the floor under it when there is one near, else where it is
            Location under = ground(looked.getLocation().add(0, 0.1, 0));
            return under != null ? under : looked.getLocation();
        }
        Location eye = p.getEyeLocation();
        RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        Location at = hit != null ? hit.getHitPosition().toLocation(p.getWorld()).subtract(eye.getDirection().multiply(0.2))
                : eye.clone().add(eye.getDirection().multiply(range));
        return ground(at);
    }

    /** Feet level on the floor under (or just above) a place, null when there is none near. */
    static Location ground(Location l) {
        Block b = l.getBlock();
        for (int dy = 2; dy >= -6; dy--) {
            Block at = b.getRelative(0, dy, 0);
            if (at.isPassable() && !at.getRelative(0, -1, 0).isPassable()) {
                return new Location(l.getWorld(), l.getX(), at.getY(), l.getZ());
            }
        }
        return null;
    }

    private static boolean boss(LivingEntity e) {
        return e.getScoreboardTags().contains(Mobs.BOSS_TAG);
    }

    private RpgPlayer rpg(Player p) {
        return skills.player(p);
    }

    /** The cast a hit belongs to, without the suffix of one of its parts ("…:a:12#shatter…" → "…:a:12"). */
    private static String castRoot(String cast) {
        int i = cast.indexOf('#');
        return i < 0 ? cast : cast.substring(0, i);
    }

    private static float rnd(float max) {
        return ThreadLocalRandom.current().nextFloat() * max;
    }
}
