package fr.influencraft.influoffrande.data;

import fr.influencraft.influoffrande.model.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerDataListener implements Listener {

    private final JavaPlugin plugin;
    private final DataManager dataManager;

    public PlayerDataListener(JavaPlugin plugin, DataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> dataManager.loadIntoCache(player.getUniqueId(), player.getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerData data = dataManager.get(player);
        dataManager.saveAsync(data);
        dataManager.unload(player.getUniqueId());
    }
}
