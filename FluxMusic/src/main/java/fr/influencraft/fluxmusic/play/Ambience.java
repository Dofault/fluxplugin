package fr.influencraft.fluxmusic.play;

import fr.influencraft.fluxmusic.song.Song;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Ambient music of places: while a player stands in a world listed in {@code ambience} of config.yml, the songs of
 * that place play one after another (shuffled, a pause between two), and stop when he leaves. A place with a single
 * song plays it with its own loop (a looping song's intro is heard once, then the loop without any pause). A place may
 * name a {@code first} song, heard first when arriving there (the theme of a town), the shuffled songs after it.
 *
 * <p>Ambience never covers other music: when another plugin plays something (crate opening, dungeon boss), the
 * ambience waits for it to end, then comes back. Leaving the place only stops a song the ambience started itself.</p>
 */
public final class Ambience {

    /** One place: its worlds, its songs, the song heard first on arrival (null = none), the pause between two. */
    private record Place(String id, List<String> worlds, List<String> songs, String first, long pauseMillis) {}

    /** What the ambience does for one player. */
    private static final class State {
        Place place;
        String started;          // song the ambience started, null when none
        long nextAt;             // no new song before this time (pause between songs)
        String last;             // last song the ambience played
        final List<String> queue = new ArrayList<>();
    }

    private final Jukebox jukebox;
    private final Function<String, Song> songs;
    private final Predicate<Player> wants;
    private final Map<UUID, State> states = new HashMap<>();
    private List<Place> places = List.of();

    public Ambience(Jukebox jukebox, Function<String, Song> songs, Predicate<Player> wants) {
        this.jukebox = jukebox;
        this.songs = songs;
        this.wants = wants;
    }

    /** Reads the places of {@code ambience} (config.yml). */
    public void load(ConfigurationSection section, java.util.logging.Logger log) {
        List<Place> loaded = new ArrayList<>();
        if (section != null) for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) continue;
            List<String> worlds = s.getStringList("worlds").stream().map(w -> w.toLowerCase(Locale.ROOT)).toList();
            List<String> list = new ArrayList<>();
            for (String song : s.getStringList("songs")) {
                if (songs.apply(song) == null) log.warning("Ambience " + id + ": unknown song " + song);
                else list.add(song.toLowerCase(Locale.ROOT));
            }
            String first = s.getString("first", "").toLowerCase(Locale.ROOT);
            if (!first.isEmpty() && songs.apply(first) == null) {
                log.warning("Ambience " + id + ": unknown first song " + first);
                first = "";
            }
            if (worlds.isEmpty() || (list.isEmpty() && first.isEmpty())) continue;
            if (list.isEmpty()) list.add(first);
            loaded.add(new Place(id, worlds, List.copyOf(list), first.isEmpty() ? null : first,
                    Math.max(0, s.getLong("pause-seconds", 6)) * 1000));
        }
        places = List.copyOf(loaded);
        // songs may have changed: every player starts over with the new lists
        states.keySet().forEach(this::leave);
        states.clear();
    }

    private Place placeOf(Player player) {
        String world = player.getWorld().getName().toLowerCase(Locale.ROOT);
        for (Place p : places) if (p.worlds().contains(world)) return p;
        return null;
    }

    /** Runs every second on the main thread. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Place place = placeOf(player);
            State state = states.get(id);
            if (state != null && state.place != place) {
                leave(id);
                states.remove(id);
                state = null;
            }
            if (place == null || !wants.test(player)) continue;
            if (state == null) {
                state = new State();
                state.place = place;
                if (place.first() != null) state.queue.add(place.first());
                states.put(id, state);
            }
            Song current = jukebox.playing(id);
            if (current != null) {
                if (!current.id().equals(state.started)) state.started = null; // someone else's music
                continue;
            }
            if (state.started != null) {   // our song just ended: pause before the next one
                state.started = null;
                state.nextAt = now + place.pauseMillis();
            }
            if (now < state.nextAt) continue;
            Song next = next(state);
            if (next == null) continue;
            jukebox.play(player, next, place.songs().size() == 1 && next.loop());
            state.started = next.id();
            state.last = next.id();
        }
    }

    /** Next song of the shuffled list, never the one just heard twice in a row. */
    private Song next(State state) {
        if (state.queue.isEmpty()) {
            List<String> shuffled = new ArrayList<>(state.place.songs());
            Collections.shuffle(shuffled);
            if (shuffled.size() > 1 && shuffled.get(0).equals(state.last)) Collections.swap(shuffled, 0, 1);
            state.queue.addAll(shuffled);
        }
        return songs.apply(state.queue.remove(0));
    }

    /** Stops the song the ambience started for this player, if it still plays. */
    private void leave(UUID player) {
        State state = states.get(player);
        if (state == null || state.started == null) return;
        Song current = jukebox.playing(player);
        if (current != null && current.id().equals(state.started)) jukebox.stop(player);
    }

    public void forget(UUID player) {
        states.remove(player);
    }
}
