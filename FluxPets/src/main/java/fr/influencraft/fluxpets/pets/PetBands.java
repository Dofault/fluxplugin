package fr.influencraft.fluxpets.pets;

import com.willfp.eco.core.config.interfaces.Config;
import fr.influencraft.flux.api.music.Band;
import fr.influencraft.flux.api.music.Music;
import fr.influencraft.fluxpets.pets.PetDisplay.Follower;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Music pets play together ({@code pet-settings.band} of config.yml). A pet with {@code instruments} in its file is a
 * musician. Musicians standing close to each other form a band; from {@code min-pets} different pets on, the band
 * plays the songs of the list one after another, in a shuffled order, through Flux's {@link Music#band}: each pet
 * plays the tracks of its own instruments, the instruments no pet of the band plays stay silent, and those who hear
 * it lose their background music meanwhile. Nobody starts anything: the pets play as soon as they meet, and stop when
 * they part.
 *
 * <p>While a song plays, the pets loop their {@code playing} animation and answer each of their notes with a gesture
 * of their file ({@code band-gestures}, taken in turn), started 200 ms ahead so its contact falls on the note; a note
 * particle coloured by the pitch rises above the pet that just played.</p>
 */
final class PetBands {

    private static final String PLAYING = "playing";

    /** What the music's clock thread reads of a band. */
    private record Snapshot(String world, double[] centre, Map<String, double[]> sources, List<Player> listeners) {
    }

    private final class Group implements Band {
        final Set<Follower> members = new HashSet<>();
        /** Members looping their playing animation. */
        final Set<Follower> performing = new HashSet<>();
        final Map<Follower, Integer> gestureTurn = new HashMap<>();
        /** Instruments with a note coming, told by the clock thread, played as gestures on the next tick. */
        final Set<String> cues = ConcurrentHashMap.newKeySet();
        /** Notes just sent (instrument -> pitch, the highest of a chord), shown as particles on the next tick. */
        final Map<String, Float> notes = new ConcurrentHashMap<>();
        /** Tick each member last showed a note particle at. */
        final Map<Follower, Long> shownAt = new HashMap<>();
        final List<String> queue = new ArrayList<>();
        volatile Snapshot snapshot = new Snapshot("", new double[3], Map.of(), List.of());
        Band.Session session;
        String last;
        long nextAt;

        public Collection<Player> listeners() {
            return snapshot.listeners();
        }

        public String world() {
            return snapshot.world();
        }

        public double[] centre() {
            return snapshot.centre();
        }

        public double radius() {
            return radius;
        }

        public double[] source(String instrument) {
            return snapshot.sources().get(instrument);
        }

        public void cue(String instrument, float pitch) {
            cues.add(instrument);
        }

        public void played(String instrument, float pitch) {
            notes.merge(instrument, pitch, Math::max);
        }
    }

    private final PetDisplay display;
    private final List<Group> groups = new ArrayList<>();
    private boolean enabled;
    private double link, radius;
    private int minPets;
    private long pauseMillis;
    private List<String> songs = List.of();
    private long tick;

    PetBands(PetDisplay display) {
        this.display = display;
        reload();
        Bukkit.getScheduler().runTaskTimer(Pets.plugin(), this::tick, 20L, 1L);
    }

    void reload() {
        Config c = Pets.settings();
        enabled = c != null && c.has("band.enabled") && c.getBool("band.enabled");
        link = number(c, "band.link-distance", 12);
        radius = number(c, "band.radius", 24);
        minPets = (int) Math.max(1, number(c, "band.min-pets", 2));
        pauseMillis = (long) (number(c, "band.pause-seconds", 4) * 1000);
        songs = c != null && c.has("band.songs") ? List.copyOf(c.getStrings("band.songs")) : List.of();
        new ArrayList<>(groups).forEach(this::end);
    }

    private static double number(Config c, String key, double def) {
        return c == null || c.getDoubleOrNull(key) == null ? def : c.getDouble(key);
    }

    void shutdown() {
        new ArrayList<>(groups).forEach(this::end);
    }

    private void tick() {
        if (!enabled || songs.isEmpty() || !Music.available()) {
            if (!groups.isEmpty()) new ArrayList<>(groups).forEach(this::end);
            return;
        }
        if (tick++ % 10 == 0) regroup();
        for (Group g : groups) {
            gestures(g);
            particles(g);
        }
    }

    // ---------------------------------------------------------------- who plays with whom

    private void regroup() {
        List<Follower> musicians = new ArrayList<>();
        for (Follower f : display.shown()) {
            if (!f.pet.instruments().isEmpty() && f.owner.isOnline() && f.carrier.isValid() && !f.tracker.isClosed()) {
                musicians.add(f);
            }
        }
        // bands: musicians linked one to the next within the link distance, in the same world
        List<Set<Follower>> bands = new ArrayList<>();
        Set<Follower> seen = new HashSet<>();
        for (Follower start : musicians) {
            if (!seen.add(start)) continue;
            Set<Follower> band = new HashSet<>();
            List<Follower> open = new ArrayList<>(List.of(start));
            while (!open.isEmpty()) {
                Follower f = open.remove(open.size() - 1);
                band.add(f);
                Location at = f.carrier.getLocation();
                for (Follower other : musicians) {
                    if (seen.contains(other) || other.carrier.getWorld() != at.getWorld()) continue;
                    if (other.carrier.getLocation().distanceSquared(at) <= link * link) {
                        seen.add(other);
                        open.add(other);
                    }
                }
            }
            Set<String> different = new HashSet<>();
            for (Follower f : band) different.add(f.pet.id());
            if (different.size() >= minPets) bands.add(band);
        }
        // a group goes on with the band that kept one of its musicians, else it ends
        for (Group g : new ArrayList<>(groups)) {
            Set<Follower> mine = null;
            for (Set<Follower> band : bands) {
                if (!Collections.disjoint(band, g.members)) {
                    mine = band;
                    break;
                }
            }
            if (mine == null) {
                end(g);
                continue;
            }
            bands.remove(mine);
            update(g, mine);
        }
        for (Set<Follower> band : bands) {
            Group g = new Group();
            groups.add(g);
            update(g, band);
        }
    }

    private void update(Group g, Set<Follower> band) {
        for (Follower gone : new ArrayList<>(g.members)) {
            if (!band.contains(gone)) {
                rest(g, gone);
                g.members.remove(gone);
                g.gestureTurn.remove(gone);
                g.shownAt.remove(gone);
            }
        }
        g.members.addAll(band);
        double x = 0, y = 0, z = 0;
        Map<String, double[]> sources = new HashMap<>();
        Location first = null;
        for (Follower f : g.members) {
            Location at = f.carrier.getLocation();
            if (first == null) first = at;
            x += at.getX();
            y += at.getY();
            z += at.getZ();
            for (String instrument : f.pet.instruments()) {
                sources.putIfAbsent(instrument, new double[]{at.getX(), at.getY() + 0.5, at.getZ()});
            }
        }
        int n = g.members.size();
        Location centre = new Location(first.getWorld(), x / n, y / n, z / n);
        List<Player> hear = new ArrayList<>();
        for (Player p : first.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(centre) <= (radius + link) * (radius + link)) hear.add(p);
        }
        g.snapshot = new Snapshot(first.getWorld().getName(), new double[]{centre.getX(), centre.getY(), centre.getZ()},
                Map.copyOf(sources), List.copyOf(hear));

        long now = System.currentTimeMillis();
        if (g.session != null && g.session.done()) {   // the song is over: a pause, then the next one
            g.session = null;
            g.nextAt = now + pauseMillis;
            for (Follower f : new ArrayList<>(g.performing)) rest(g, f);
        }
        if (g.session == null && now >= g.nextAt) {
            g.session = Music.band(g, next(g));
            if (g.session == null) g.nextAt = now + 10_000;   // unknown song: tried again later
        }
        if (g.session != null) {
            for (Follower f : g.members) {
                if (g.performing.add(f)) f.tracker.animate(PLAYING);
            }
        }
    }

    /** Next song of the shuffled list, never the one just played twice in a row. */
    private String next(Group g) {
        if (g.queue.isEmpty()) {
            List<String> shuffled = new ArrayList<>(songs);
            Collections.shuffle(shuffled);
            if (shuffled.size() > 1 && shuffled.get(0).equals(g.last)) Collections.swap(shuffled, 0, 1);
            g.queue.addAll(shuffled);
        }
        g.last = g.queue.remove(0);
        return g.last;
    }

    private void rest(Group g, Follower f) {
        if (g.performing.remove(f) && !f.tracker.isClosed()) f.tracker.stopAnimation(PLAYING);
    }

    private void end(Group g) {
        if (g.session != null) g.session.cancel();
        for (Follower f : new ArrayList<>(g.performing)) rest(g, f);
        groups.remove(g);
    }

    // ---------------------------------------------------------------- a gesture per note

    private void gestures(Group g) {
        if (g.cues.isEmpty()) return;
        Set<String> coming = new HashSet<>(g.cues);
        g.cues.removeAll(coming);
        for (Follower f : g.members) {
            List<String> gestures = f.pet.bandGestures();
            if (gestures.isEmpty() || f.tracker.isClosed() || Collections.disjoint(coming, f.pet.instruments())) continue;
            int turn = g.gestureTurn.merge(f, 1, Integer::sum);
            String gesture = gestures.get(turn % gestures.size());
            f.tracker.stopAnimation(gesture);
            // override: the bones the gesture leaves alone keep the playing loop under it
            f.tracker.animate(gesture, AnimationModifier.builder().type(AnimationIterator.Type.PLAY_ONCE)
                    .start(0).end(2).override(true).build());
        }
    }

    // ---------------------------------------------------------------- a note particle above the pet playing

    /** Ticks between two note particles of the same pet (fast runs would hide it under a cloud of notes). */
    private static final int PARTICLE_EVERY = 4;
    private static final double PARTICLE_HEIGHT = 1.5;

    /** One note particle above each pet that just played, coloured by the pitch, seen by those hearing the band. */
    private void particles(Group g) {
        if (g.notes.isEmpty()) return;
        Map<String, Float> fresh = new HashMap<>(g.notes);
        fresh.keySet().forEach(g.notes::remove);
        List<Player> viewers = g.snapshot.listeners();
        if (viewers.isEmpty()) return;
        for (Follower f : g.members) {
            float pitch = 0;
            for (String instrument : f.pet.instruments()) pitch = Math.max(pitch, fresh.getOrDefault(instrument, 0f));
            if (pitch <= 0 || !f.carrier.isValid()) continue;
            Long last = g.shownAt.get(f);
            if (last != null && tick - last < PARTICLE_EVERY) continue;
            g.shownAt.put(f, tick);
            Location at = f.carrier.getLocation();
            double colour = Math.max(0, Math.min(1, (Math.log(pitch) / Math.log(2) + 1) / 2));   // pitch 0.5..2 -> 0..1
            for (Player p : viewers) {
                if (p.isOnline() && p.getWorld() == at.getWorld()) {
                    // count 0: the "offset" is the note's colour
                    p.spawnParticle(org.bukkit.Particle.NOTE, at.getX(), at.getY() + PARTICLE_HEIGHT, at.getZ(), 0, colour, 0, 0, 1);
                }
            }
        }
    }
}
