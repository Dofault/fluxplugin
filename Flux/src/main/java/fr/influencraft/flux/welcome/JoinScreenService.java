package fr.influencraft.flux.welcome;

import fr.influencraft.flux.api.welcome.JoinScreens;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link JoinScreens}: per player, a queue of screens shown one at a time once his resource pack is loaded (or
 * {@link #PACK_TIMEOUT} seconds after joining). A screen that never says it is done is dropped after
 * {@link #SCREEN_TIMEOUT} seconds, so it cannot block the next ones forever.
 */
public final class JoinScreenService implements JoinScreens.Service, Listener {

    private static final long PACK_TIMEOUT = 30;
    private static final long SCREEN_TIMEOUT = 600;

    private record Queued(int order, long sequence, JoinScreens.Screen screen) {
    }

    private static final class State {
        final List<Queued> queue = new ArrayList<>();
        boolean ready;
        long current; // sequence of the screen shown, 0 = none
        long sequence;
    }

    private final Plugin flux;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public JoinScreenService(Plugin flux) {
        this.flux = flux;
    }

    private State state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), id -> new State());
    }

    @Override
    public void request(Player player, int order, JoinScreens.Screen screen) {
        State s = state(player);
        s.queue.add(new Queued(order, ++s.sequence, screen));
        s.queue.sort(Comparator.comparingInt(Queued::order).thenComparingLong(Queued::sequence));
        next(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        state(p).ready = false;
        Bukkit.getScheduler().runTaskLater(flux, () -> ready(p), PACK_TIMEOUT * 20L);
    }

    @EventHandler
    public void onPack(PlayerResourcePackStatusEvent e) {
        PlayerResourcePackStatusEvent.Status status = e.getStatus();
        if (status == PlayerResourcePackStatusEvent.Status.ACCEPTED || status == PlayerResourcePackStatusEvent.Status.DOWNLOADED) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(flux, () -> ready(p), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
    }

    private void ready(Player p) {
        if (!p.isOnline()) return;
        State s = state(p);
        if (s.ready) return;
        s.ready = true;
        next(p);
    }

    /** Shows the first screen of the queue when nothing is shown. */
    private void next(Player p) {
        State s = states.get(p.getUniqueId());
        if (s == null || !s.ready || s.current != 0 || s.queue.isEmpty() || !p.isOnline()) return;
        Queued q = s.queue.remove(0);
        s.current = q.sequence();
        Runnable done = () -> Bukkit.getScheduler().runTask(flux, () -> {
            State now = states.get(p.getUniqueId());
            if (now == null || now.current != q.sequence()) return;
            now.current = 0;
            next(p);
        });
        Bukkit.getScheduler().runTaskLater(flux, done, SCREEN_TIMEOUT * 20L);
        try {
            q.screen().show(p, done);
        } catch (RuntimeException ex) {
            flux.getLogger().warning("Join screen failed for " + p.getName() + ": " + ex);
            done.run();
        }
    }
}
