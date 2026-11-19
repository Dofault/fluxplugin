package fr.influencraft.fluxmusic.play;

import fr.influencraft.fluxmusic.song.Instrument;
import fr.influencraft.fluxmusic.song.Song;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Stages: a band of musicians (BetterModel models, one per note block instrument) playing the music of a place.
 *
 * <p>A place of the {@link Ambience} given a stage no longer plays one song per player: the stage broadcasts the
 * place's songs to everybody there on one clock ({@link Jukebox.Broadcast}), so the band plays what every player
 * hears. Far from the stage the music sounds in the headphones as before; within the stage's radius it comes from
 * the musicians. The band plays as long as somebody is in the place; a player who turned music off in his settings
 * sees it play and hears nothing. Each musician moves only while his instrument sounds: {@code playing} while its notes are sent,
 * {@code ready} (instrument held, still) for the rest of a song he plays in, {@code idle} otherwise. A note
 * particle rises from him on his notes, and his playing follows the pace of his notes (faster on a quick run).
 * Only the musicians of the song being played stand on the stage: at each new song those who have nothing to play
 * leave, the others take their places again across the stage.</p>
 *
 * <p>Stages are placed by command and kept in {@code stages.yml}; the band (which model plays which instrument,
 * front row first) is {@code band} of config.yml.</p>
 */
public final class Stages {

    /** Still seen as playing this long after his last note (shorter and he would twitch between two notes). */
    private static final long PLAYING_NANOS = 900_000_000L;
    /** Musicians are shown to players within this distance of the stage. */
    private static final double VIEW_DISTANCE = 72;
    private static final double ROW_SPACING = 2.0, SIDE_SPACING = 1.3, SIDE_SPACING_MAX = 2.2;
    /** Speed of the playing animation: bounds, and gestures per second each model's loop shows at speed 1. */
    private static final float SPEED_MIN = 0.6f, SPEED_MAX = 2.2f;
    private static final Map<Instrument, Float> GESTURES = new EnumMap<>(Map.ofEntries(
            Map.entry(Instrument.GUITAR, 3.3f), Map.entry(Instrument.BANJO, 8f), Map.entry(Instrument.HARP, 2f),
            Map.entry(Instrument.BASS, 1f), Map.entry(Instrument.FLUTE, 1.7f), Map.entry(Instrument.BASEDRUM, 2f),
            Map.entry(Instrument.SNARE, 5f), Map.entry(Instrument.HAT, 2f), Map.entry(Instrument.COW_BELL, 1.5f),
            Map.entry(Instrument.BELL, 2.5f), Map.entry(Instrument.CHIME, 1f), Map.entry(Instrument.XYLOPHONE, 2.7f),
            Map.entry(Instrument.IRON_XYLOPHONE, 1.25f), Map.entry(Instrument.BIT, 4.7f),
            Map.entry(Instrument.PLING, 4.7f)));   // the didgeridoo holds long notes: always at speed 1

    private final Plugin plugin;
    private final Jukebox jukebox;
    private final Ambience ambience;
    private final Function<String, Song> songs;
    private final Predicate<Player> wants;
    private final File file;
    private final boolean models;
    private final Map<String, Stage> stages = new LinkedHashMap<>();
    /** Instrument -> model, in the order of the rows (front first). */
    private final Map<Instrument, String> band = new LinkedHashMap<>();
    private long ticks;

    public Stages(Plugin plugin, Jukebox jukebox, Ambience ambience, Function<String, Song> songs,
                  Predicate<Player> wants) {
        this.plugin = plugin;
        this.jukebox = jukebox;
        this.ambience = ambience;
        this.songs = songs;
        this.wants = wants;
        this.file = new File(plugin.getDataFolder(), "stages.yml");
        this.models = Bukkit.getPluginManager().isPluginEnabled("BetterModel");
        ambience.staged(id -> stages.containsKey(id.toLowerCase(Locale.ROOT)));
    }

    // ---------------------------------------------------------------- data

    /** Reads the band (config.yml) and the stages (stages.yml); everything shown is built again. */
    public void load(ConfigurationSection bandSection) {
        clear();
        band.clear();
        if (bandSection != null) for (String key : bandSection.getKeys(false)) {
            try {
                band.put(Instrument.named(key), bandSection.getString(key, ""));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Band: unknown instrument " + key);
            }
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        for (String id : data.getKeys(false)) {
            ConfigurationSection s = data.getConfigurationSection(id);
            if (s == null) continue;
            stages.put(id.toLowerCase(Locale.ROOT), new Stage(id.toLowerCase(Locale.ROOT), s.getString("world", ""),
                    s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw"),
                    s.getDouble("width", 6), s.getDouble("depth", 7), s.getDouble("radius", 16),
                    (float) s.getDouble("scale", 1)));
        }
        // a tick later: BetterModel's models are ready
        // the band comes on with the first song (a tick later at least: BetterModel's models are ready)
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (Stage s : stages.values()) {
            ConfigurationSection c = data.createSection(s.id);
            c.set("world", s.world);
            c.set("x", s.x);
            c.set("y", s.y);
            c.set("z", s.z);
            c.set("yaw", s.yaw);
            c.set("width", s.width);
            c.set("depth", s.depth);
            c.set("radius", s.radius);
            c.set("scale", s.scale);
        }
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("stages.yml not saved: " + e.getMessage());
        }
    }

    /**
     * Places the stage of a place (or moves it) where the location stands, the band facing the way it looks.
     * False when the place is unknown. Width, depth or radius of 0 or less keep the former value (or the default).
     */
    public boolean place(String placeId, Location at, double width, double depth, double radius) {
        Ambience.PlaceInfo info = ambience.place(placeId);
        if (info == null || at.getWorld() == null) return false;
        String id = info.id().toLowerCase(Locale.ROOT);
        Stage old = stages.remove(id);
        if (old != null) old.close();
        float yaw = Math.round(at.getYaw() / 5f) * 5f;
        Stage s = new Stage(id, at.getWorld().getName(), Math.round(at.getX() * 2) / 2.0, at.getY(),
                Math.round(at.getZ() * 2) / 2.0, yaw,
                width > 0 ? width : old != null ? old.width : 6, depth > 0 ? depth : old != null ? old.depth : 7,
                radius > 0 ? radius : old != null ? old.radius : 16, old != null ? old.scale : 1f);
        stages.put(id, s);
        save();
        return true;
    }

    /** Changes the radius the music comes from the stage in; false when the place has no stage. */
    public boolean radius(String placeId, double radius) {
        Stage old = stages.get(placeId.toLowerCase(Locale.ROOT));
        if (old == null) return false;
        World w = Bukkit.getWorld(old.world);
        if (w == null) return false;
        return place(placeId, new Location(w, old.x, old.y, old.z, old.yaw, 0), 0, 0, Math.max(4, radius));
    }

    /** Removes the stage of a place (its ambience plays per player again); false when it had none. */
    public boolean remove(String placeId) {
        Stage s = stages.remove(placeId.toLowerCase(Locale.ROOT));
        if (s == null) return false;
        s.close();
        save();
        return true;
    }

    /** One line per stage: id, world, place, size. */
    public List<String[]> list() {
        List<String[]> out = new ArrayList<>();
        for (Stage s : stages.values()) {
            out.add(new String[]{s.id, s.world, fmt(s.x) + " " + fmt(s.y) + " " + fmt(s.z), fmt(s.width) + " x " + fmt(s.depth),
                    fmt(s.radius), String.valueOf(s.musicians.size())});
        }
        return out;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    public Set<String> ids() {
        return stages.keySet();
    }

    /** Song the stage of the player's place is playing to him, null when none. */
    public Song playing(Player player) {
        for (Stage s : stages.values()) {
            Jukebox.Broadcast b = s.broadcast;
            if (b != null && !b.done() && s.listeners.contains(player)) return b.song();
        }
        return null;
    }

    public void clear() {
        stages.values().forEach(Stage::close);
        stages.clear();
    }

    public void forget(Player player) {
        for (Stage s : stages.values()) s.viewers.remove(player.getUniqueId());
    }

    /** Runs every 2 ticks on the main thread. */
    public void tick() {
        ticks++;
        for (Stage s : stages.values()) s.tick(ticks);
    }

    // ---------------------------------------------------------------- one stage

    private static final class Musician {
        final Instrument instrument;
        Location at;
        final ModelBridge.Figure figure;
        String state = "";
        /** Notes per second lately (smoothed), and the speed his playing is shown at. */
        float rate, speed = 1f;

        Musician(Instrument instrument, Location at, ModelBridge.Figure figure) {
            this.instrument = instrument;
            this.at = at;
            this.figure = figure;
        }
    }

    private final class Stage implements Jukebox.Scene {
        final String id, world;
        final double x, y, z, width, depth, radius;
        final float yaw, scale;
        final List<Musician> musicians = new ArrayList<>();
        volatile Map<Instrument, double[]> sources = Map.of();
        /** Notes of each instrument since the last tick (chords count once), written by the clock thread. */
        final AtomicIntegerArray fresh = new AtomicIntegerArray(Instrument.values().length);
        final Set<UUID> viewers = new HashSet<>();
        /** Last note of each instrument (System.nanoTime) and its pitch, written by the clock thread. */
        final AtomicLongArray lastNote = new AtomicLongArray(Instrument.values().length);
        final AtomicIntegerArray pitch = new AtomicIntegerArray(Instrument.values().length);
        final long[] shownNote = new long[Instrument.values().length];
        volatile Collection<Player> listeners = List.of();
        /** Somebody is in the place: the band plays, even when nobody there wants to hear music. */
        boolean present;
        volatile Jukebox.Broadcast broadcast;
        Set<Instrument> cast = Set.of();
        final List<String> queue = new ArrayList<>();
        String last;
        long nextAt;
        boolean closed;

        Stage(String id, String world, double x, double y, double z, float yaw, double width, double depth,
              double radius, float scale) {
            this.id = id;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.width = width;
            this.depth = depth;
            this.radius = radius;
            this.scale = scale;
        }

        /**
         * Puts on the stage the musicians of this cast and only them, in the band's order (first ones in front), in
         * rows centred on the stage. A musician already there walks to his new place; the others come or go in a puff.
         */
        void stand(Set<Instrument> cast) {
            World w = Bukkit.getWorld(world);
            if (w == null || closed) return;
            List<Instrument> players = new ArrayList<>();
            for (Instrument i : band.keySet()) if (cast.contains(i)) players.add(i);
            Map<Instrument, Musician> former = new EnumMap<>(Instrument.class);
            for (Musician m : musicians) former.put(m.instrument, m);
            musicians.clear();
            double rad = Math.toRadians(yaw);
            double fx = -Math.sin(rad), fz = Math.cos(rad), rx = -Math.cos(rad), rz = -Math.sin(rad);
            int n = players.size();
            int most = Math.max(1, (int) Math.floor((width - 0.6) / SIDE_SPACING) + 1);
            int rows = Math.max(1, (n + most - 1) / most);
            int perRow = (n + rows - 1) / rows;   // rows of even length rather than a full one and a leftover
            double rowStep = rows <= 1 ? 0 : Math.min(ROW_SPACING, (depth - 1.6) / (rows - 1));
            Map<Instrument, double[]> places = new EnumMap<>(Instrument.class);
            for (int i = 0; i < n; i++) {
                int row = i / perRow, inRow = Math.min(perRow, n - row * perRow), col = i % perRow;
                double gap = inRow <= 1 ? 0 : Math.min(SIDE_SPACING_MAX, (width - 0.6) / (inRow - 1));
                double side = (col - (inRow - 1) / 2.0) * gap;
                double front = ((rows - 1) / 2.0 - row) * rowStep;
                Location at = new Location(w, x + rx * side + fx * front, y, z + rz * side + fz * front, yaw, 0);
                Instrument instrument = players.get(i);
                Musician m = former.remove(instrument);
                if (m != null) {
                    if (m.figure != null && m.at.distanceSquared(at) > 0.01) m.figure.move(at);
                    m.at = at;
                } else {
                    ModelBridge.Figure figure = null;
                    if (models) {
                        figure = ModelBridge.figure(band.get(instrument), at, scale, yaw);
                        if (figure == null) plugin.getLogger().warning("Stage " + id + ": no BetterModel model "
                                + band.get(instrument));
                    }
                    m = new Musician(instrument, at, figure);
                    if (figure != null) {
                        for (UUID viewer : viewers) {
                            Player p = Bukkit.getPlayer(viewer);
                            if (p != null) figure.show(p);
                        }
                        puff(at);
                    }
                }
                musicians.add(m);
                places.put(instrument, new double[]{at.getX(), at.getY() + 1.4 * scale, at.getZ()});
            }
            for (Musician gone : former.values()) {
                if (gone.figure == null) continue;
                puff(gone.at);
                gone.figure.close();
            }
            sources = places;
        }

        private void puff(Location at) {
            if (!viewers.isEmpty()) at.getWorld().spawnParticle(Particle.POOF, at.getX(), at.getY() + scale, at.getZ(),
                    12, 0.25, 0.6 * scale, 0.25, 0.01);
        }

        void close() {
            closed = true;
            Jukebox.Broadcast b = broadcast;
            if (b != null) b.cancel();
            for (Musician m : musicians) if (m.figure != null) m.figure.close();
            musicians.clear();
            viewers.clear();
            listeners = List.of();
        }

        void tick(long tick) {
            if (closed) return;
            if (tick % 10 == 1) {
                audience();
                program();
            }
            long now = System.nanoTime();
            boolean live = broadcast != null && !broadcast.done();
            World w = null;
            for (Musician m : musicians) {
                int i = m.instrument.ordinal();
                long note = lastNote.get(i);
                boolean playing = live && note != 0 && now - note < PLAYING_NANOS;
                String state = playing ? "playing" : live && cast.contains(m.instrument) ? "ready" : "idle";
                if (m.figure != null && !state.equals(m.state)) {
                    m.figure.loop(state);
                    m.state = state;
                }
                // pace of his notes -> speed of his playing, eased so it never jerks (a tick here is 0.1 s)
                int notes = fresh.getAndSet(i, 0);
                m.rate += (notes * 10f - m.rate) * 0.12f;
                Float gestures = GESTURES.get(m.instrument);
                float want = !playing || gestures == null ? 1f
                        : Math.max(SPEED_MIN, Math.min(SPEED_MAX, m.rate / gestures));
                m.speed += (want - m.speed) * 0.2f;
                if (m.figure != null) m.figure.speed(m.speed);
                // one note particle per new note, coloured by its pitch, at most every 4 ticks
                if (playing && note != shownNote[i] && tick % 2 == i % 2 && !viewers.isEmpty()) {
                    shownNote[i] = note;
                    if (w == null) w = m.at.getWorld();
                    double colour = (Math.log(pitch.get(i) / 1000.0) / Math.log(2) + 1) / 2;   // pitch 0.5..2 -> 0..1
                    w.spawnParticle(Particle.NOTE, m.at.getX(), m.at.getY() + 2.3 * scale, m.at.getZ(), 0,
                            Math.max(0, Math.min(1, colour)), 0, 0, 1);
                }
            }
        }

        /** Who hears the place's music, who sees the band. */
        private void audience() {
            Ambience.PlaceInfo info = ambience.place(id);
            List<Player> hear = new ArrayList<>();
            World w = Bukkit.getWorld(world);
            present = false;
            for (Player p : Bukkit.getOnlinePlayers()) {
                String in = p.getWorld().getName().toLowerCase(Locale.ROOT);
                if (info != null && info.worlds().contains(in)) {
                    present = true;
                    if (wants.test(p)) hear.add(p);   // music turned off in his settings: he sees the band, silent
                }
                boolean sees = w != null && p.getWorld() == w
                        && p.getLocation().distanceSquared(new Location(w, x, y, z)) < VIEW_DISTANCE * VIEW_DISTANCE;
                if (sees && viewers.add(p.getUniqueId())) {
                    for (Musician m : musicians) if (m.figure != null) m.figure.show(p);
                } else if (!sees && viewers.remove(p.getUniqueId())) {
                    for (Musician m : musicians) if (m.figure != null) m.figure.hide(p);
                }
            }
            listeners = List.copyOf(hear);
        }

        /** Starts the next song of the place when the former ended (after the place's pause), while somebody listens. */
        private void program() {
            Ambience.PlaceInfo info = ambience.place(id);
            if (info == null) return;
            Jukebox.Broadcast b = broadcast;
            long now = System.currentTimeMillis();
            if (b != null) {
                if (!b.done()) {
                    if (!present) b.cancel();   // nobody left: the band stops, a new song when one comes
                    return;
                }
                broadcast = null;
                nextAt = now + info.pauseMillis();
            }
            if (!present || now < nextAt) return;
            if (queue.isEmpty()) {
                List<String> shuffled = new ArrayList<>(info.songs());
                Collections.shuffle(shuffled);
                if (shuffled.size() > 1 && shuffled.get(0).equals(last)) Collections.swap(shuffled, 0, 1);
                queue.addAll(shuffled);
            }
            Song song = songs.apply(queue.remove(0));
            if (song == null) return;
            last = song.id();
            cast = song.cast();
            stand(cast);
            for (int i = 0; i < lastNote.length(); i++) lastNote.set(i, 0);
            broadcast = jukebox.broadcast(song, this);
        }

        /** Skips to the next song. */
        void skip() {
            Jukebox.Broadcast b = broadcast;
            if (b != null) b.cancel();
            nextAt = 0;
        }

        // ---- Jukebox.Scene (clock thread)

        public Collection<Player> listeners() {
            return listeners;
        }

        public String world() {
            return world;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y + 1;
        }

        public double z() {
            return z;
        }

        public double radius() {
            return radius;
        }

        public double[] source(Instrument instrument) {
            double[] at = sources.get(instrument);
            return at != null ? at : new double[]{x, y + 1.4, z};
        }

        public void played(Instrument instrument, float notePitch) {
            long now = System.nanoTime();
            if (now - lastNote.get(instrument.ordinal()) > 20_000_000L) fresh.incrementAndGet(instrument.ordinal());
            lastNote.set(instrument.ordinal(), now);
            pitch.set(instrument.ordinal(), Math.round(notePitch * 1000));
        }
    }

    /** Next song on the stage of a place; false when it has none. */
    public boolean skip(String placeId) {
        Stage s = stages.get(placeId.toLowerCase(Locale.ROOT));
        if (s == null) return false;
        s.skip();
        return true;
    }

    /** Id of the stage playing to this player, null when none. */
    public String stageOf(Player player) {
        for (Stage s : stages.values()) if (s.listeners.contains(player)) return s.id;
        return null;
    }
}
