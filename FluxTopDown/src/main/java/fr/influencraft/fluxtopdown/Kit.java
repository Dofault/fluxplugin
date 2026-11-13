package fr.influencraft.fluxtopdown;

import fr.influencraft.fluxdungeons.api.Combat;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * What the hero does: the basic attack and the four spells (types line, leap, strike, spin of config.yml), and the
 * zones drawn on the ground while a spell is aimed. Every hit goes through FluxDungeons' rpg combat engine
 * ({@link Combat#strike}): stats, defenses, crits and dungeon rules count as for any class.
 */
final class Kit {

    private static final Color AIM = Color.fromRGB(0xB376E5);
    private static final Color AIM_TARGET = Color.fromRGB(0xFFD94D);
    private static final Color DANGER = Color.fromRGB(0xFF5C5C);
    private static final Color MOVE = Color.fromRGB(0x2ECF7B);

    private final FluxTopDown plugin;

    Kit(FluxTopDown plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- targets

    /** Monsters the hero may hit around a point. */
    static List<LivingEntity> enemies(Location at, double radius) {
        return Combat.enemies(at, radius);
    }

    /** The monster under the pointer: the closest to the pointed spot within {@code reach} blocks. */
    static LivingEntity enemyAt(Location at, double reach) {
        LivingEntity best = null;
        double bestD = reach * reach;
        for (LivingEntity e : enemies(at, reach + 2)) {
            Location l = e.getLocation();
            double dx = l.getX() - at.getX(), dz = l.getZ() - at.getZ();
            double d = dx * dx + dz * dz - e.getWidth() * e.getWidth() / 4;
            if (d <= bestD && Math.abs(l.getY() - at.getY()) < 3 + e.getHeight()) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private static double hit(Player p, LivingEntity e, Settings.Spell s, String cast) {
        return Combat.strike(p, e, s.magic(), s.coefficient(), null, cast);
    }

    // ---------------------------------------------------------------- basic attack

    /** One strike of the basic attack on the chased monster. */
    void basic(Session s, LivingEntity target, long tick) {
        Player p = s.player;
        s.face(target.getLocation().toVector(), tick, 10);
        s.avatar.swingMainHand();
        Combat.strike(p, target, false, plugin.settings().basicCoefficient(), null, Combat.cast(p, "topdown_basic"));
        Location at = target.getLocation().add(0, target.getHeight() * 0.5, 0);
        at.getWorld().spawnParticle(Particle.SWEEP_ATTACK, at, 1);
        at.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 1.1f);
    }

    // ---------------------------------------------------------------- spells

    /** Casts a spell towards {@code aim} (the pointed spot), cooldown started. */
    void cast(Session s, Settings.Spell spell, Location aim, long tick) {
        Player p = s.player;
        s.readyAt.put(spell.key(), tick + spell.cooldown());
        s.aiming = null;
        Location from = p.getLocation();
        Vector flat = aim.toVector().subtract(from.toVector()).setY(0);
        if (flat.lengthSquared() < 1e-4) flat = Session.axes(plugin.settings())[0].clone().setY(0);
        Vector dir = flat.clone().normalize();
        double reach = Math.min(spell.range(), flat.length());
        Location point = from.clone().add(dir.clone().multiply(reach));
        point.setY(spell.type().equals("strike") ? aim.getY() : from.getY());
        s.face(from.clone().add(dir).toVector(), tick, Math.max(10, spell.windup() + 6));
        s.avatar.swingMainHand();
        switch (spell.type()) {
            case "line" -> line(s, spell, from, dir, tick);
            case "leap" -> leap(s, spell, from, dir, reach, tick);
            case "strike" -> strike(s, spell, point, tick);
            case "spin" -> spin(s, spell, tick);
            default -> plugin.getLogger().warning("Unknown spell type " + spell.type() + " (" + spell.id() + ")");
        }
    }

    /** A blade wave along a line: wind up, then it sweeps forward and hits everything it crosses once. */
    private void line(Session s, Settings.Spell spell, Location from, Vector dir, long tick) {
        Player p = s.player;
        s.stop();
        s.busyUntil = tick + spell.windup();
        String cast = Combat.cast(p, "topdown_" + spell.id());
        World w = from.getWorld();
        Location origin = from.clone().add(0, 0.9, 0);
        w.playSound(origin, Sound.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.PLAYERS, 0.7f, 1.5f);
        int steps = 6;
        for (int i = 0; i < steps; i++) {
            int step = i;
            later(spell.windup() + i, () -> {
                double a = spell.range() * step / steps, b = spell.range() * (step + 1) / steps;
                for (double t = a; t <= b; t += 0.35) {
                    Location at = origin.clone().add(dir.clone().multiply(t));
                    w.spawnParticle(Particle.SWEEP_ATTACK, at, 1, 0.1, 0.05, 0.1, 0);
                    w.spawnParticle(Particle.CLOUD, at, 1, spell.width() / 4, 0.05, spell.width() / 4, 0.01);
                }
                Location mid = origin.clone().add(dir.clone().multiply((a + b) / 2));
                for (LivingEntity e : enemies(mid, (b - a) / 2 + spell.width() + 1)) {
                    Vector to = e.getLocation().toVector().subtract(from.toVector()).setY(0);
                    double along = to.dot(dir);
                    double side = to.clone().subtract(dir.clone().multiply(along)).length();
                    if (along >= -0.5 && along <= b + 0.5 && side <= spell.width() / 2 + e.getWidth() / 2) hit(p, e, spell, cast);
                }
            });
        }
    }

    /** A jump to the pointer (as far as the range allows), hitting around where he lands. */
    private void leap(Session s, Settings.Spell spell, Location from, Vector dir, double reach, long tick) {
        Player p = s.player;
        s.stop();
        Paths paths = new Paths(from.getWorld());
        // the farthest spot on the way he can stand on, not higher than a jump can reach
        Location land = null;
        for (double t = reach; t >= 0.5; t -= 0.5) {
            Location at = from.clone().add(dir.clone().multiply(t));
            double f = paths.floorNear(at.getBlockX(), (int) Math.floor(from.getY() + 0.01), at.getBlockZ());
            if (!Double.isNaN(f) && f - from.getY() <= 2.5) {
                land = at;
                land.setY(f);
                break;
            }
        }
        if (land == null) land = from.clone();
        double dist = land.toVector().subtract(from.toVector()).setY(0).length();
        double rise = land.getY() - from.getY();
        // airtime of a jump of 0.6 up to the landing height, then the horizontal speed that covers the distance
        // under the air drag (0.91 a tick)
        double y = 0, v = 0.6;
        int ticks = 0;
        while (ticks < 40) {
            y += v;
            v = (v - 0.08) * 0.98;
            ticks++;
            if (v < 0 && y <= rise) break;
        }
        double speed = dist * 0.09 / (1 - Math.pow(0.91, ticks));
        p.setVelocity(dir.clone().multiply(speed).setY(0.6));
        s.leapStart = tick;
        s.leapUntil = tick + ticks + 20;
        World w = from.getWorld();
        w.playSound(from, Sound.ENTITY_GOAT_LONG_JUMP, SoundCategory.PLAYERS, 1f, 1.2f);
        w.spawnParticle(Particle.CLOUD, from.clone().add(0, 0.2, 0), 12, 0.3, 0.05, 0.3, 0.02);
        String cast = Combat.cast(p, "topdown_" + spell.id());
        s.onLand = () -> {
            Location at = p.getLocation();
            w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.6f, 1.6f);
            w.spawnParticle(Particle.EXPLOSION, at.clone().add(0, 0.3, 0), 2, 0.4, 0.1, 0.4, 0);
            ring(w, at.clone().add(0, 0.15, 0), spell.radius(), Particle.CRIT, null);
            for (LivingEntity e : enemies(at, spell.radius() + 1)) {
                if (flatDistance(e.getLocation(), at) <= spell.radius() + e.getWidth() / 2) hit(p, e, spell, cast);
            }
        };
    }

    /** A strike on a zone after a delay, shown on the ground meanwhile to everyone (monsters may walk out). */
    private void strike(Session s, Settings.Spell spell, Location at, long tick) {
        Player p = s.player;
        s.busyUntil = tick + 4;
        World w = at.getWorld();
        Location ground = at.clone().add(0, 0.15, 0);
        String cast = Combat.cast(p, "topdown_" + spell.id());
        w.playSound(ground, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.7f, 1.8f);
        for (int t = 0; t < spell.delay(); t += 2) {
            int left = t;
            later(t, () -> ring(w, ground, spell.radius() * (0.35 + 0.65 * left / Math.max(1, spell.delay())), Particle.DUST,
                    new Particle.DustOptions(AIM_TARGET, 1.2f)));
        }
        later(spell.delay(), () -> {
            w.playSound(ground, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 1f, 1.2f);
            w.spawnParticle(Particle.ELECTRIC_SPARK, ground.clone().add(0, 1, 0), 60, spell.radius() / 2, 1, spell.radius() / 2, 0.2);
            ring(w, ground, spell.radius(), Particle.END_ROD, null);
            for (LivingEntity e : enemies(at, spell.radius() + 1)) {
                if (flatDistance(e.getLocation(), at) <= spell.radius() + e.getWidth() / 2) hit(p, e, spell, cast);
            }
        });
    }

    /** Spins in place (he may keep walking), hitting around him several times. */
    private void spin(Session s, Settings.Spell spell, long tick) {
        Player p = s.player;
        int every = Math.max(1, spell.duration() / spell.hits());
        for (int i = 0; i < spell.hits(); i++) {
            int n = i;
            later(i * every, () -> {
                if (!p.isOnline() || p.isDead()) return;
                Location at = p.getLocation();
                World w = at.getWorld();
                s.avatar.swingMainHand();
                s.face(at.clone().add(Math.cos(n * 2.1), 0, Math.sin(n * 2.1)).toVector(), Bukkit.getCurrentTick(), every);
                w.playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 0.8f + 0.1f * n);
                for (int k = 0; k < 8; k++) {
                    double a = Math.PI * 2 * k / 8 + n;
                    w.spawnParticle(Particle.SWEEP_ATTACK, at.clone().add(Math.cos(a) * spell.radius() * 0.6, 0.9, Math.sin(a) * spell.radius() * 0.6), 1);
                }
                // each turn is a hit of its own
                String cast = Combat.cast(p, "topdown_" + spell.id());
                for (LivingEntity e : enemies(at, spell.radius() + 1)) {
                    if (flatDistance(e.getLocation(), at) <= spell.radius() + e.getWidth() / 2) hit(p, e, spell, cast);
                }
            });
        }
    }

    // ---------------------------------------------------------------- aiming zones (seen by the caster only)

    void indicate(Session s, Settings.Spell spell, Location aim) {
        Player p = s.player;
        Location from = p.getLocation();
        Location ground = from.clone().add(0, 0.15, 0);
        Vector flat = aim.toVector().subtract(from.toVector()).setY(0);
        if (flat.lengthSquared() < 1e-4) flat = Session.axes(plugin.settings())[0].clone().setY(0);
        Vector dir = flat.clone().normalize();
        double reach = Math.min(spell.range(), flat.length());
        Particle.DustOptions aimDust = new Particle.DustOptions(AIM, 0.9f);
        Particle.DustOptions targetDust = new Particle.DustOptions(AIM_TARGET, 1.1f);
        switch (spell.type()) {
            case "line" -> {
                Vector side = new Vector(-dir.getZ(), 0, dir.getX()).multiply(spell.width() / 2);
                for (double t = 0; t <= spell.range(); t += 0.4) {
                    Location c = ground.clone().add(dir.clone().multiply(t));
                    dust(p, c.clone().add(side), aimDust);
                    dust(p, c.clone().subtract(side), aimDust);
                }
                Location end = ground.clone().add(dir.clone().multiply(spell.range()));
                for (double t = -1; t <= 1; t += 0.25) dust(p, end.clone().add(side.clone().multiply(t)), targetDust);
            }
            case "leap" -> {
                ring(p, ground, spell.range(), aimDust);
                Location land = ground.clone().add(dir.clone().multiply(reach));
                ring(p, land, spell.radius(), targetDust);
            }
            case "strike" -> {
                ring(p, ground, spell.range(), aimDust);
                Location target = from.clone().add(dir.clone().multiply(reach));
                target.setY(aim.getY() + 0.15);
                ring(p, target, spell.radius(), targetDust);
            }
            default -> ring(p, ground, spell.radius(), targetDust);
        }
    }

    /** Where a click sent him: a small green ring, for him only. */
    void marker(Player p, Location at) {
        ring(p, at.clone().add(0, 0.15, 0), 0.45, new Particle.DustOptions(MOVE, 1f));
    }

    /** The monster he goes for: a red ring under it, for him only. */
    void marked(Player p, LivingEntity e) {
        ring(p, e.getLocation().add(0, 0.15, 0), Math.max(0.5, e.getWidth() * 0.7), new Particle.DustOptions(DANGER, 1f));
    }

    // ---------------------------------------------------------------- drawing

    private static void dust(Player p, Location at, Particle.DustOptions o) {
        p.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, o);
    }

    private static void ring(Player p, Location c, double r, Particle.DustOptions o) {
        int n = Math.max(10, (int) (r * 2 * Math.PI / 0.45));
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            dust(p, c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), o);
        }
    }

    private static <T> void ring(World w, Location c, double r, Particle particle, T data) {
        int n = Math.max(10, (int) (r * 2 * Math.PI / 0.45));
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            w.spawnParticle(particle, c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, 0, data);
        }
    }

    private static double flatDistance(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void later(long ticks, Runnable r) {
        if (ticks <= 0) r.run();
        else Bukkit.getScheduler().runTaskLater(plugin, r, ticks);
    }
}
