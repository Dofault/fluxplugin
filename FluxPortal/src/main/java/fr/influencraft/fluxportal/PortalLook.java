package fr.influencraft.fluxportal;

import fr.influencraft.fluxportal.api.Portal;
import fr.influencraft.fluxportal.api.PortalEnd;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * What makes a hole feel like a portal, around the window: violet to pink dust flowing along its sides and top (never
 * its bottom, on the floor), motes drawn into it, a low hum nearby, and a whoosh, a crystal chime and a burst when
 * someone goes through. Particles reach Bedrock players too. A side whose other side is missing gets a grey border
 * (for the staff placing it). Settings: {@code look} in the config.
 */
final class PortalLook {

    private final JavaPlugin plugin;
    private final Supplier<Collection<Portal>> portals;
    private BukkitTask task;
    private long tick;

    PortalLook(JavaPlugin plugin, Supplier<Collection<Portal>> portals) {
        this.plugin = plugin;
        this.portals = portals;
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::draw, 2L, 2L);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("look");
        return c == null ? plugin.getConfig().createSection("look") : c;
    }

    // ------------------------------------------------------------------ border, motes, hum

    private void draw() {
        tick += 2;
        if (!cfg().getBoolean("enabled", true)) return;
        double seen = cfg().getDouble("particle-distance", 48);
        for (Portal p : portals.get()) {
            if (p.complete() && !p.open()) continue;
            for (int side = 1; side <= 2; side++) {
                PortalEnd end = p.side(side);
                World w = end == null ? null : Bukkit.getWorld(end.world());
                if (w == null) continue;
                Location middle = new Location(w, end.x(), end.y(), end.z());
                if (!near(middle, seen)) continue;
                Frame f = new Frame(end, p.width(), p.height());
                border(w, f, p.complete());
                if (!p.complete()) continue;
                motes(w, f);
                if (tick % cfg().getLong("hum-every", 80) == 0 && near(middle, 16)) {
                    w.playSound(middle, cfg().getString("hum.sound", "minecraft:block.beacon.ambient"), SoundCategory.BLOCKS,
                            (float) cfg().getDouble("hum.volume", 0.5), (float) cfg().getDouble("hum.pitch", 0.6));
                }
            }
        }
    }

    private static boolean near(Location c, double distance) {
        for (Player pl : c.getWorld().getPlayers()) if (pl.getLocation().distanceSquared(c) <= distance * distance) return true;
        return false;
    }

    /** A point of the hole's plane, (a, b) from its middle, a little in front so the window does not hide it. */
    private static Location at(World w, Frame f, double a, double b) {
        return new Location(w, f.worldX(a, 0.08), f.worldY(b), f.worldZ(a, 0.08));
    }

    /**
     * Points along the left side, the top and the right side (a path from the bottom left corner to the bottom right
     * one), a third of them lit each time and moving along it, so the border seems to flow; colours from violet to
     * pink by place and time.
     */
    private void border(World w, Frame f, boolean complete) {
        double hw = f.halfWidth(), hh = f.halfHeight();
        double length = 4 * hh + 2 * hw;
        double step = cfg().getDouble("border-step", 0.25);
        int points = (int) Math.round(length / step);
        Color from = color("from", 0xB376E5), to = color("to", 0xE060A0);
        for (int i = 0; i < points; i++) {
            if ((i + tick / 2) % 3 != 0) continue;
            double s = (i + 0.5) * length / points;
            double a, b;
            if (s < 2 * hh) {
                a = -hw;
                b = -hh + s;
            } else if (s < 2 * hh + 2 * hw) {
                a = -hw + (s - 2 * hh);
                b = hh;
            } else {
                a = hw;
                b = hh - (s - 2 * hh - 2 * hw);
            }
            Location p = at(w, f, a, b);
            if (!complete) {
                w.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(0x8A8580), 1f));
                continue;
            }
            double t = (Math.sin(s * 1.3 + tick * 0.08) + 1) / 2;
            w.spawnParticle(Particle.DUST_COLOR_TRANSITION, p, 1, 0, 0, 0, 0,
                    new Particle.DustTransition(mix(from, to, t), mix(to, from, t), (float) cfg().getDouble("border-size", 1.1)));
        }
    }

    /** Motes starting on the border and drawn into the hole. */
    private void motes(World w, Frame f) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double hw = f.halfWidth(), hh = f.halfHeight();
        for (int i = 0; i < cfg().getInt("motes", 2); i++) {
            double a, b;
            switch (r.nextInt(3)) {
                case 0 -> {
                    a = -hw;
                    b = r.nextDouble(-hh, hh);
                }
                case 1 -> {
                    a = hw;
                    b = r.nextDouble(-hh, hh);
                }
                default -> {
                    a = r.nextDouble(-hw, hw);
                    b = hh;
                }
            }
            Location from = at(w, f, a, b), middle = at(w, f, 0, 0);
            org.bukkit.util.Vector in = middle.toVector().subtract(from.toVector()).normalize();
            w.spawnParticle(Particle.REVERSE_PORTAL, from, 0, in.getX(), in.getY(), in.getZ(), 0.06);
        }
    }

    // ------------------------------------------------------------------ going through

    /** Someone left {@code from} and came out at {@code to}: a burst on both sides, the whoosh and chime where he is. */
    void travelled(Location from, Location to) {
        if (!cfg().getBoolean("enabled", true)) return;
        burst(from);
        burst(to);
        World w = to.getWorld();
        float volume = (float) cfg().getDouble("travel.volume", 0.9);
        w.playSound(to, cfg().getString("travel.sound", "minecraft:entity.illusioner.mirror_move"), SoundCategory.PLAYERS, volume,
                (float) cfg().getDouble("travel.pitch", 1.2));
        w.playSound(to, cfg().getString("travel.chime", "minecraft:block.amethyst_block.resonate"), SoundCategory.PLAYERS, volume, 1.4f);
    }

    private void burst(Location at) {
        at.getWorld().spawnParticle(Particle.REVERSE_PORTAL, at.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.05);
        at.getWorld().spawnParticle(Particle.DUST, at.clone().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0,
                new Particle.DustOptions(color("to", 0xE060A0), 1.2f));
    }

    private Color color(String key, int fallback) {
        String s = cfg().getString(key, null);
        try {
            return Color.fromRGB(s == null ? fallback : Integer.parseInt(s.replace("#", ""), 16));
        } catch (NumberFormatException e) {
            return Color.fromRGB(fallback);
        }
    }

    private static Color mix(Color a, Color b, double t) {
        return Color.fromRGB((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t), (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
