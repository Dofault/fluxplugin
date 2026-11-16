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
 * {@link JoinScreens}: per player, a queue of screens shown one at a time once his resource pack is loaded and his
 * client has left the "loading terrain" screen (or {@link #PACK_TIMEOUT} seconds after joining). The world wait is
 * needed: a menu opened while that screen shows is closed by the client when the screen goes away (the calendar
 * played its opening sound but never showed, 2026-11-16). A screen that never says it is done is dropped after
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
        boolean pack;  // resource pack loaded (or failed for good)
        boolean world; // client finished loading the world
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
        State s = state(p);
        s.ready = false;
        // A pack applied before joining (ItemsAdder before_join) is answered during the configuration phase, where
        // Paper fires no PlayerResourcePackStatusEvent: it only keeps the status on the connection. Without this
        // check, such a player always waited the whole PACK_TIMEOUT.
        if (finished(packStatus(p))) s.pack = true;
        Bukkit.getScheduler().runTaskLater(flux, () -> ready(p), PACK_TIMEOUT * 20L);
    }

    @EventHandler
    public void onPack(PlayerResourcePackStatusEvent e) {
        if (!finished(e.getStatus())) return;
        state(e.getPlayer()).pack = true;
        whenBoth(e.getPlayer());
    }

    /** Sent by the client when the "loading terrain" screen closes (or by the server after its own timeout). */
    @EventHandler
    public void onWorldLoaded(io.papermc.paper.event.player.PlayerClientLoadedWorldEvent e) {
        state(e.getPlayer()).world = true;
        whenBoth(e.getPlayer());
    }

    private void whenBoth(Player p) {
        State s = state(p);
        if (s.ready || !s.pack || !s.world) return;
        Bukkit.getScheduler().runTaskLater(flux, () -> ready(p), 10L);
    }

    /** Loaded, or failed for good: nothing more to wait for. Null (no pack sent yet), accepted, downloaded: wait. */
    private static boolean finished(PlayerResourcePackStatusEvent.Status status) {
        return status != null && status != PlayerResourcePackStatusEvent.Status.ACCEPTED
                && status != PlayerResourcePackStatusEvent.Status.DOWNLOADED;
    }

    @SuppressWarnings("deprecation")
    private static PlayerResourcePackStatusEvent.Status packStatus(Player p) {
        try {
            return p.getResourcePackStatus();
        } catch (RuntimeException ex) {
            return null;
        }
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
