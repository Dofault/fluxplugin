package fr.influencraft.fluxpets.effects;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * « Propriétaire » d'un four (four, haut fourneau, fumoir) pour les familiers de Fondeur : le dernier joueur qui
 * a cliqué dans son inventaire (y mettre ou en retirer quelque chose). En mémoire seulement : écrire dans le
 * bloc pendant le clic (PDC + update) risquerait de réécrire le contenu du four avec un état périmé. Après un
 * redémarrage, le four n'a plus de propriétaire jusqu'au prochain clic.
 */
final class FurnaceOwners implements Listener {

    private record Pos(UUID world, int x, int y, int z) {
        static Pos of(Block b) {
            return new Pos(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }

    private final Map<Pos, UUID> owners = new HashMap<>();

    /** Propriétaire connecté du four, ou null. */
    Player owner(Block furnace) {
        UUID uuid = owners.get(Pos.of(furnace));
        return uuid == null ? null : Bukkit.getPlayer(uuid);
    }

    private void claim(Inventory top, Object who) {
        if (top instanceof FurnaceInventory inv && inv.getHolder() instanceof Furnace furnace && who instanceof Player p) {
            owners.put(Pos.of(furnace.getBlock()), p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        claim(event.getView().getTopInventory(), event.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        claim(event.getView().getTopInventory(), event.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!owners.isEmpty()) {
            owners.remove(Pos.of(event.getBlock()));
        }
    }
}
