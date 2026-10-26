package fr.influencraft.fluxitems;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Accroupi + clic droit en tenant un outil FluxItems : l'interface de l'outil s'ouvre. L'action
 * normale du clic (labourer, écorcer, tirer à l'arc...) est annulée.
 */
final class Ouverture implements Listener {

    private final FluxItems plugin;

    Ouverture(FluxItems plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void clic(PlayerInteractEvent ev) {
        if (ev.getHand() != EquipmentSlot.HAND) return;
        if (ev.getAction() != Action.RIGHT_CLICK_AIR && ev.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = ev.getPlayer();
        if (!p.isSneaking() || plugin.outil(p.getInventory().getItemInMainHand()) == null) return;
        ev.setCancelled(true);
        plugin.ouvrir(p);
    }
}
