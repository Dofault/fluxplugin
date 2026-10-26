package fr.influencraft.fluxitems;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Met à jour la description des outils déjà distribués après un changement de config (couleurs,
 * textes, barèmes) : un outil rendu avec une autre empreinte que la config chargée est réécrit une
 * fois, à la connexion, à l'ouverture d'un coffre ou quand il est pris en main. Pas de traitement des
 * paquets : le coût est nul en jeu courant (une lecture de marqueur sur les seuls matériaux d'outils).
 */
final class Rafraichissement implements Listener {

    private final FluxItems plugin;

    Rafraichissement(FluxItems plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void connexion(PlayerJoinEvent ev) {
        inventaire(plugin, ev.getPlayer().getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void ouverture(InventoryOpenEvent ev) {
        inventaire(plugin, ev.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void enMain(PlayerItemHeldEvent ev) {
        Player p = ev.getPlayer();
        ItemStack item = p.getInventory().getItem(ev.getNewSlot());
        if (rafraichir(plugin, item)) p.getInventory().setItem(ev.getNewSlot(), item);
    }

    static void inventaire(FluxItems plugin, Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (rafraichir(plugin, item)) inv.setItem(i, item);
        }
    }

    /** Réécrit la description si elle date d'une autre config ; vrai si l'objet a changé. */
    static boolean rafraichir(FluxItems plugin, ItemStack item) {
        if (item == null || item.isEmpty() || !plugin.materiau(item.getType().getKey().getKey())) return false;
        Outil o = plugin.outil(item);
        if (o == null || Etat.rendu(item) == Rendu.version) return false;
        Rendu.maj(item, o, Etat.lire(item), true);
        return true;
    }
}
