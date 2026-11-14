package fr.influencraft.fluxmascot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** The robots in the world, one per player at most. */
final class Visits implements Listener {

    private final FluxMascot plugin;
    private final Map<UUID, Visit> running = new HashMap<>();

    Visits(FluxMascot plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    /** The robot comes in front of the player and speaks (his robot, wherever it was, starts over); false when the model is missing. */
    boolean greet(Player player) {
        stop(player);
        return put(player, Visit.greet(plugin, player));
    }

    /** The robot strolls near the player, without speech; nothing changes when he already has his robot. */
    boolean stroll(Player player) {
        if (running.containsKey(player.getUniqueId())) return true;
        return put(player, Visit.stroll(plugin, player));
    }

    private boolean put(Player player, Visit v) {
        if (v == null) return false;
        running.put(player.getUniqueId(), v);
        return true;
    }

    boolean stop(Player player) {
        Visit v = running.remove(player.getUniqueId());
        if (v != null) v.close();
        return v != null;
    }

    void stopAll() {
        running.values().forEach(Visit::close);
        running.clear();
    }

    private void tick() {
        for (Iterator<Visit> it = running.values().iterator(); it.hasNext(); ) {
            Visit v = it.next();
            boolean alive;
            try {
                alive = v.tick();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Robot of " + v.player().getName() + " stopped: " + e);
                alive = false;
            }
            if (!alive) {
                it.remove();
                v.close();
            }
        }
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Visit v = running.get(e.getPlayer().getUniqueId());
        if (v != null && v.owns(e.getRightClicked())) {
            e.setCancelled(true);
            v.clicked();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stop(e.getPlayer());
    }
}
