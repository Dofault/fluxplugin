// COMPAT 2026-11-01: market keepers of the AuctionHouse plugin (persistent villagers riding an armour stand) are
// removed - remove when production has run FluxCommerce 1.0.0 and its old keepers were removed.
package fr.influencraft.fluxcommerce.compat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * AuctionHouse saved its keeper with the world: a villager marked {@code auctionhouse:auction_master} riding an armour
 * stand marked {@code auctionhouse:auction_stand}. Without AuctionHouse it would stand there doing nothing. The market
 * keeper is now a Typewriter NPC (FluxCommerce has none of its own): whenever such a villager is loaded, both entities
 * are removed and the spot is logged, so the staff can place the Typewriter NPC there.
 */
public final class LegacyKeepers implements Listener {

    private static final NamespacedKey MASTER = new NamespacedKey("auctionhouse", "auction_master");
    private static final NamespacedKey STAND = new NamespacedKey("auctionhouse", "auction_stand");

    private final Plugin plugin;

    public LegacyKeepers(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Keepers already loaded when the plugin starts. */
    public void scanLoaded() {
        for (World w : Bukkit.getWorlds()) remove(w.getEntities());
    }

    @EventHandler
    public void load(EntitiesLoadEvent e) {
        List<Entity> entities = e.getEntities();
        // next tick: the entities are in the world
        Bukkit.getScheduler().runTask(plugin, () -> remove(entities));
    }

    private void remove(List<Entity> entities) {
        for (Entity e : entities) {
            if (!e.isValid() || !e.getPersistentDataContainer().has(MASTER)) continue;
            Entity stand = e.getVehicle();
            Location at = e.getLocation();
            e.remove();
            if (stand != null && stand.getPersistentDataContainer().has(STAND)) stand.remove();
            plugin.getLogger().info("Keeper of the former AuctionHouse plugin removed at " + at.getWorld().getName() + " "
                    + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ() + " (place the Typewriter NPC there)");
        }
    }
}
