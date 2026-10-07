package fr.influencraft.influcard;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Mise à jour des cartes et boosters déjà distribués, sur le modèle des plugins eco : chaque objet
 * porte l'empreinte de la configuration qui l'a construit ; dès qu'elle ne correspond plus (nom,
 * description, texture, rareté changés), l'objet est reconstruit en gardant sa quantité.
 * Vérifié à la connexion (inventaire + coffre de l'Ender), au ramassage, à l'ouverture d'un
 * inventaire (coffres, menus) et pour tous les joueurs connectés après un rechargement.
 */
public final class ItemUpdater implements Listener {

    private final InfluCard plugin;

    public ItemUpdater(InfluCard plugin) {
        this.plugin = plugin;
    }

    /** Met à jour tout un inventaire. Renvoie le nombre d'objets reconstruits. */
    public int refresh(Inventory inventory) {
        return refresh(inventory, null);
    }

    /** viewer : joueur pour qui les boosters affichent ✔ / ✘ devant chaque familier. */
    public int refresh(Inventory inventory, Player viewer) {
        int changed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack fresh = plugin.registry().refreshed(contents[i], viewer);
            if (fresh != null) {
                inventory.setItem(i, fresh);
                changed++;
            }
        }
        return changed;
    }

    public void refresh(Player player) {
        refresh(player.getInventory(), player);
        refresh(player.getEnderChest(), player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        ItemStack fresh = plugin.registry().refreshed(event.getItem().getItemStack(),
                event.getEntity() instanceof Player p ? p : null);
        if (fresh != null) {
            event.getItem().setItemStack(fresh);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getHolder(false) instanceof fr.influencraft.influcard.gui.Menu) {
            return;
        }
        Player viewer = event.getPlayer() instanceof Player p ? p : null;
        refresh(event.getInventory(), viewer);
        if (viewer != null) {
            refresh(viewer.getInventory(), viewer);
        }
    }
}
