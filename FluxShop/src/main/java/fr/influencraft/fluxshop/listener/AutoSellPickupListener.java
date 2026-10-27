package fr.influencraft.fluxshop.listener;

import fr.influencraft.fluxshop.autosell.AutoSellFilter;
import fr.influencraft.fluxshop.autosell.AutoSellItemManager;
import fr.influencraft.fluxshop.integration.SellPriceResolver;
import fr.influencraft.fluxshop.managers.PriceManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;

/**
 * While a player carries an active "Vente Automatique" item, anything they pick up off the
 * ground (farming, mining, fishing, mob loot) that has a configured sell price is sold on the
 * spot instead of entering their inventory.
 * <p>
 * Deliberately hooks {@link EntityPickupItemEvent} rather than any inventory-add event:
 * that event only fires for a dropped item entity being vacuumed up from the world, never for
 * items moved between inventories (chest sorting, shift-clicking, hoppers) — so tidying up
 * storage is never mistaken for "farming" and never triggers a sale.
 * <p>
 * Deliberately sends no chat message per sale (farming fast would spam the chat into
 * uselessness) — {@code econ.depositPlayer(...)} goes through Vault → CMI like every other
 * deposit, so InfluBridge's {@code MoneyGainActionBarListener} already picks it up and merges
 * it into the same cumulative "+X BitBlock" action bar it shows for EcoJobs earnings, with no
 * changes needed on either side.
 * <p>
 * Cancelling the pickup also silently swallows vanilla's item-pickup "pop" sound (it's tied to
 * the pickup actually completing), so it's replayed manually here. {@link AutoSellTelepathyListener}
 * must NOT do the same: AdvancedEnchantments' "Télépathie" effect already plays that exact sound
 * itself ({@code PLAY_SOUND:ENTITY_ITEM_PICKUP} in enchantments.yml) for the items it delivers.
 */
public class AutoSellPickupListener implements Listener {

    private final AutoSellItemManager autoSellItemManager;
    private final AutoSellFilter autoSellFilter;
    private final PriceManager priceManager;
    private final Economy econ;

    public AutoSellPickupListener(AutoSellItemManager autoSellItemManager, AutoSellFilter autoSellFilter,
            PriceManager priceManager, Economy econ) {
        this.autoSellItemManager = autoSellItemManager;
        this.autoSellFilter = autoSellFilter;
        this.priceManager = priceManager;
        this.econ = econ;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!autoSellItemManager.hasActiveIn(player)) {
            return;
        }

        ItemStack picked = event.getItem().getItemStack();
        if (autoSellItemManager.isAutoSellItem(picked)) {
            return; // ne jamais vendre l'objet de vente auto lui-même
        }
        if (!autoSellFilter.isAutoSellable(player, picked)) {
            return; // équipement / objet rare : ramassage normal, il se vendra manuellement
        }

        double price = SellPriceResolver.resolveTotalPrice(priceManager, player, picked);
        if (price <= 0) {
            return; // pas de prix connu : ramassage normal, comme si l'objet n'existait pas
        }

        event.setCancelled(true);
        event.getItem().remove();
        econ.depositPlayer(player, price);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.2f, 1.5f);
    }
}
