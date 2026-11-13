package fr.influencraft.fluxcrate;

import fr.influencraft.flux.api.music.Music;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Background music while a player opens crates (Flux's {@link Music}, config {@code music}): it starts at his first
 * opening, loops while he goes on opening, and stops after {@code idle-seconds} without an opening or once he walked
 * {@code radius} blocks away. Only the music started here is ever stopped.
 */
final class CrateMusic {

    private record Session(String song, Location where, long lastOpening) {
    }

    private final FluxCrate plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    CrateMusic(FluxCrate plugin) {
        this.plugin = plugin;
    }

    private String song() {
        return plugin.getConfig().getString("music.song", "caisses");
    }

    /** A crate was opened by this player (main thread). Never fails: music is only a bonus of the opening. */
    void opened(Player p) {
        String song = song();
        if (song == null || song.isBlank()) return;
        try {
            if (!song.equalsIgnoreCase(Music.playing(p)) && !Music.loop(p, song)) return;
        } catch (LinkageError e) {
            return; // a Flux older than the music API
        }
        sessions.put(p.getUniqueId(), new Session(song, p.getLocation(), System.currentTimeMillis()));
    }

    /** Every second: ends the music of players who stopped opening crates or walked away. */
    void tick() {
        long idle = Math.max(1, plugin.getConfig().getLong("music.idle-seconds", 20)) * 1000L;
        double radius = plugin.getConfig().getDouble("music.radius", 12);
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Session>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Session> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            Session s = e.getValue();
            if (p == null) {
                it.remove();
                continue;
            }
            Location at = p.getLocation();
            boolean away = at.getWorld() != s.where().getWorld() || at.distanceSquared(s.where()) > radius * radius;
            if (!away && now - s.lastOpening() < idle) continue;
            if (s.song().equalsIgnoreCase(Music.playing(p))) Music.stop(p);
            it.remove();
        }
    }

    void quit(UUID player) {
        sessions.remove(player);
    }

    void stopAll() {
        for (UUID id : sessions.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && sessions.get(id).song().equalsIgnoreCase(Music.playing(p))) Music.stop(p);
        }
        sessions.clear();
    }
}
