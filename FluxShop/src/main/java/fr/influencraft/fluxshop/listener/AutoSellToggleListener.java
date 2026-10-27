package fr.influencraft.fluxshop.listener;

import fr.influencraft.fluxshop.autosell.AutoSellItemManager;
import fr.influencraft.fluxshop.autosell.AutoSellMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * A right-click with the "Vente Automatique" item in hand opens its management menu
 * ({@link AutoSellMenu}: exceptions, on/off); a sneaking right-click toggles it straight
 * away, like the old behaviour. Either way it never dyes a sheep/wolf/sign, since we always
 * cancel the event once we recognise the item. The toggle cooldown lives in
 * {@link AutoSellMenu#toggle}, shared by both paths.
 */
public class AutoSellToggleListener implements Listener {

    private final AutoSellItemManager autoSellItemManager;
    private final AutoSellMenu autoSellMenu;

    public AutoSellToggleListener(AutoSellItemManager autoSellItemManager, AutoSellMenu autoSellMenu) {
        this.autoSellItemManager = autoSellItemManager;
        this.autoSellMenu = autoSellMenu;
    }

    // Pas de ignoreCancelled=true ici : un clic droit dans le vide (RIGHT_CLICK_AIR) est
    // fréquemment déjà annulé par un autre plugin (anti-cheat, CMI...) avant que ce listener ne
    // s'exécute, alors que RIGHT_CLICK_BLOCK ne l'est pas — ignorer les events déjà annulés
    // faisait que le clic droit ne marchait que sur un bloc visé. On traite l'event dans tous
    // les cas et on réaffirme nous-mêmes l'annulation.
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // évite un double déclenchement (l'event arrive aussi pour l'off-hand)
        }
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            handleIfHoldingItem(event.getPlayer(), event.getItem(), event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        handleIfHoldingItem(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand(), event);
    }

    private void handleIfHoldingItem(Player player, ItemStack item, Cancellable event) {
        if (!autoSellItemManager.isAutoSellItem(item)) {
            return;
        }
        event.setCancelled(true);

        if (player.isSneaking()) {
            autoSellMenu.toggle(player);
        } else {
            autoSellMenu.open(player);
        }
    }
}
