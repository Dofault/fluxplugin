package fr.influencraft.fluxshop.listener;

import fr.influencraft.flux.api.world.DirectDropsEvent;
import fr.influencraft.fluxshop.autosell.AutoSellFilter;
import fr.influencraft.fluxshop.autosell.AutoSellItemManager;
import fr.influencraft.fluxshop.integration.SellPriceResolver;
import fr.influencraft.fluxshop.managers.PriceManager;
import fr.influencraft.fluxshop.managers.Money;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Telekinesis of an enchantment: drops never become item entities, so {@link AutoSellPickupListener} never sees them.
 * Flux's {@link DirectDropsEvent} (eco's drop queue today) hands them before they enter the inventory: sellable items
 * are taken out and paid instead. Covers every telekinetic drop (blocks, mob loot, shears, fishing).
 */
public class AutoSellEcoTelekinesisListener implements Listener {

    private final AutoSellItemManager autoSellItemManager;
    private final AutoSellFilter autoSellFilter;
    private final PriceManager priceManager;

    public AutoSellEcoTelekinesisListener(AutoSellItemManager autoSellItemManager, AutoSellFilter autoSellFilter,
            PriceManager priceManager) {
        this.autoSellItemManager = autoSellItemManager;
        this.autoSellFilter = autoSellFilter;
        this.priceManager = priceManager;
    }

    @EventHandler
    public void onDirectDrops(DirectDropsEvent event) {
        Player player = event.player();
        if (!autoSellItemManager.hasActiveIn(player)) {
            return;
        }

        List<ItemStack> kept = new ArrayList<>();
        double total = 0.0;

        for (ItemStack item : event.items()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (autoSellItemManager.isAutoSellItem(item) || !autoSellFilter.isAutoSellable(player, item)) {
                kept.add(item); // objet de vente auto / équipement / objet rare : le joueur le garde
                continue;
            }
            double price = SellPriceResolver.resolveTotalPrice(priceManager, player, item);
            if (price <= 0) {
                kept.add(item); // pas de prix connu : livré normalement
                continue;
            }
            total += price;
        }

        if (total > 0) {
            event.items().clear();
            event.items().addAll(kept);
            Money.give(player, total);
        }
    }
}
