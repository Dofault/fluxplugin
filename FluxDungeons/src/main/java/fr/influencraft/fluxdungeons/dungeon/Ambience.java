package fr.influencraft.fluxdungeons.dungeon;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sounds and particles placed in a dungeon map ({@code map.ambience} of the dungeon or of its edits file, written by the
 * map scripts): a campfire that crackles, birds over a clearing, a growl from the boss room. Each emitter only plays
 * while a player of the run is within its radius, and the sound is placed at the emitter (heard from there, fading with
 * distance), never sent to the whole dungeon.
 *
 * <pre>
 * ambience:
 * - at: [x, y, z]
 *   radius: 14              # a player must be this close for it to play
 *   every: [80, 200]        # ticks between two sounds (random in the range)
 *   sounds:                 # one picked each time
 *   - {sound: block.campfire.crackle, volume: 0.8, pitch: 1.0}
 *   particle: {type: CAMPFIRE_COSY_SMOKE, count: 2, spread: [0.2, 0.4, 0.2], speed: 0.01, every: 10}
 * </pre>
 */
public final class Ambience {

    public record Sound(String key, float volume, float pitch) {
    }

    public record Emitter(double x, double y, double z, double radius, int min, int max, List<Sound> sounds,
                          Particle particle, int count, double[] spread, double speed, int particleEvery) {

        Location at(World w) {
            return new Location(w, x, y, z);
        }
    }

    private Ambience() {
    }

    static List<Emitter> read(List<Map<?, ?>> list) {
        List<Emitter> out = new ArrayList<>();
        for (Map<?, ?> m : list) {
            if (!(m.get("at") instanceof List<?> at) || at.size() < 3) continue;
            int min = 100, max = 200;
            if (m.get("every") instanceof List<?> ev && ev.size() == 2) {
                min = (int) num(ev.get(0), 100);
                max = Math.max(min, (int) num(ev.get(1), 200));
            } else if (m.get("every") instanceof Number n) {
                min = max = n.intValue();
            }
            List<Sound> sounds = new ArrayList<>();
            if (m.get("sounds") instanceof List<?> ss) {
                for (Object o : ss) {
                    if (o instanceof Map<?, ?> s && s.get("sound") != null) {
                        String key = String.valueOf(s.get("sound"));
                        sounds.add(new Sound(key.contains(":") ? key : "minecraft:" + key, (float) num(s.get("volume"), 1),
                                (float) num(s.get("pitch"), 1)));
                    }
                }
            }
            Particle particle = null;
            int count = 0, pEvery = 10;
            double speed = 0;
            double[] spread = {0.3, 0.3, 0.3};
            if (m.get("particle") instanceof Map<?, ?> p && p.get("type") != null) {
                try {
                    Particle type = Particle.valueOf(String.valueOf(p.get("type")).toUpperCase(Locale.ROOT));
                    if (type.getDataType() == Void.class) particle = type;     // particles needing data are refused
                } catch (IllegalArgumentException ignored) {
                    // unknown particle: the emitter keeps its sounds
                }
                count = (int) num(p.get("count"), 3);
                speed = num(p.get("speed"), 0);
                pEvery = Math.max(2, (int) num(p.get("every"), 10));
                if (p.get("spread") instanceof List<?> sp && sp.size() == 3) {
                    spread = new double[]{num(sp.get(0), 0.3), num(sp.get(1), 0.3), num(sp.get(2), 0.3)};
                }
            }
            if (sounds.isEmpty() && particle == null) continue;
            out.add(new Emitter(num(at.get(0), 0), num(at.get(1), 0), num(at.get(2), 0), num(m.get("radius"), 14), min, max,
                    List.copyOf(sounds), particle, count, spread, speed, pEvery));
        }
        return List.copyOf(out);
    }

    /**
     * One step of the run's emitters (called every {@code step} ticks). {@code next} holds, per emitter, the server tick
     * of its next sound.
     */
    static void tick(World w, List<Emitter> emitters, long[] next, List<Player> players, long now, int step) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < emitters.size(); i++) {
            Emitter e = emitters.get(i);
            Location at = e.at(w);
            double r2 = e.radius() * e.radius();
            boolean heard = false;
            for (Player p : players) {
                if (p.getWorld() == w && p.getLocation().distanceSquared(at) <= r2) {
                    heard = true;
                    break;
                }
            }
            if (!heard) continue;
            if (!e.sounds().isEmpty() && now >= next[i]) {
                if (next[i] != 0) {
                    Sound s = e.sounds().get(r.nextInt(e.sounds().size()));
                    w.playSound(at, s.key(), SoundCategory.AMBIENT, s.volume(), s.pitch() * (0.92f + r.nextFloat() * 0.16f));
                }
                // the first time only arms it: a player arriving does not hear every emitter at once
                next[i] = now + e.min() + (e.max() > e.min() ? r.nextInt(e.max() - e.min() + 1) : 0);
            }
            if (e.particle() != null && (now / step) % Math.max(1, e.particleEvery() / step) == 0) {
                w.spawnParticle(e.particle(), at, e.count(), e.spread()[0], e.spread()[1], e.spread()[2], e.speed());
            }
        }
    }

    private static double num(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }
}
