package fr.influencraft.fluxmascot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** The visits in progress, one per player at most. */
final class Greetings implements Listener {

    private final FluxMascot plugin;
    private final Map<UUID, Greeting> running = new HashMap<>();

    Greetings(FluxMascot plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    /** Starts a visit (a visit already running for him starts over); false when the model is missing. */
    boolean start(Player player) {
        stop(player);
        Greeting g = Greeting.start(player, plugin.settings());
        if (g == null) return false;
        running.put(player.getUniqueId(), g);
        return true;
    }

    boolean stop(Player player) {
        Greeting g = running.remove(player.getUniqueId());
        if (g != null) g.close();
        return g != null;
    }

    void stopAll() {
        running.values().forEach(Greeting::close);
        running.clear();
    }

    private void tick() {
        for (Iterator<Greeting> it = running.values().iterator(); it.hasNext(); ) {
            Greeting g = it.next();
            boolean alive;
            try {
                alive = g.tick();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Greeting of " + g.player().getName() + " stopped: " + e);
                alive = false;
            }
            if (!alive) {
                it.remove();
                g.close();
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stop(e.getPlayer());
    }
}
