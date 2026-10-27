package fr.influencraft.fluxshop.listener;

import com.willfp.eco.core.events.DropQueuePushEvent;
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
 * Same gap as {@link AutoSellTelepathyListener}, for EcoEnchants' "Télépathie" (libreforge
 * {@code telekinesis} effect) instead of AdvancedEnchantments': drops never become item entities,
 * so {@link AutoSellPickupListener} never sees them.
 * <p>
 * The inventory diff trick doesn't work here: libreforge clears the {@code BlockDropItemEvent}
 * items and pushes them into an eco {@code DropQueue}, and with {@code use-fast-collated-drops}
 * (eco {@code config.yml}) that queue is drained by a 1-tick timer which itself schedules the
 * delivery one more tick later (confirmed by decompiling {@code CollatedRunnable}) — the items land
 * 2 ticks after the break, after the 1-tick diff has already run on an unchanged inventory.
 * <p>
 * eco exposes the final delivery as a public, cancellable {@link DropQueuePushEvent} whose item list
 * can be replaced, fired right before {@code player.getInventory().addItem(...)}: sellable items are
 * taken out of the list and paid instead. Covers every telekinetic drop (blocks, mob loot, shears,
 * fishing), consistent with the pickup listener which also sells mob loot. Non-telekinetic pushes
 * are ignored: those items are dropped on the ground and go through the pickup listener.
 * <p>
 * Only registered when eco is enabled (this class links against eco's API).
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropQueuePush(DropQueuePushEvent event) {
        if (!event.isTelekinetic()) {
            return; // items posés au sol : AutoSellPickupListener s'en charge au ramassage
        }
        Player player = event.getPlayer();
        if (!autoSellItemManager.hasActiveIn(player)) {
            return;
        }

        List<ItemStack> kept = new ArrayList<>();
        double total = 0.0;

        for (ItemStack item : event.getItems()) {
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
            event.setItems(kept);
            Money.give(player, total);
        }
    }
}
