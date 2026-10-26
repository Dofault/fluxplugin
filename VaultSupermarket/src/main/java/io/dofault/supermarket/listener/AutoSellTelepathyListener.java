package io.dofault.supermarket.listener;

import io.dofault.supermarket.autosell.AutoSellFilter;
import io.dofault.supermarket.autosell.AutoSellItemManager;
import io.dofault.supermarket.integration.SellPriceResolver;
import io.dofault.supermarket.managers.PriceManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Closes the one gap {@link AutoSellPickupListener} can't cover: AdvancedEnchantments' "TP_DROPS"
 * effect (used by the "Télépathie" enchant) hands the block's drops straight to the player's
 * inventory ({@code ASManager.giveItem}) and has its own {@code BlockDropItemEvent} handler
 * ({@code MiningTrigger.onBlockItemDrop} → {@code DropTracker.consumeForDrop}) swallow the item
 * entities that would otherwise duplicate them — so no pickup ever happens and
 * {@link org.bukkit.event.entity.EntityPickupItemEvent} never fires.
 * <p>
 * AE has no public API for "here's what this effect is about to give", so rather than replicating
 * its drop computation (silk touch, smelt, drop multipliers, mcMMO bonuses...), which would drift
 * the moment AE's internals change, this takes a snapshot of the inventory before AE gets a say,
 * then diffs it one tick later — once AE has finished giving the items — and sells whatever was
 * added.
 * <p>
 * ⚠️ The snapshot must be taken at {@link EventPriority#LOWEST} on the player's own
 * {@link BlockBreakEvent}: AE's mining trigger runs at {@code HIGH} by default
 * ({@code AdvancedEnchantments/config.yml} → {@code mining-trigger-block-break-event-priority})
 * and gives the items synchronously from there, so anything later already sees them in the
 * inventory and diffs to nothing.
 * <p>
 * ⚠️ Do NOT gate this on the {@code "breakblock-effect"} block metadata AE sets around the
 * synthetic {@code BlockBreakEvent} it re-fires for effect-broken blocks (confirmed by decompiling
 * {@code DropsHandler.breakBlock()}): that re-fire deliberately EXCLUDES the block of the original
 * event, so a plain Télépathie break never carries it and nothing was ever sold. Extra blocks
 * (mine-aire, tunnel...) do fire it, but they land in the same tick as the original break and are
 * covered by the same snapshot anyway.
 * <p>
 * Consequence of diffing the whole inventory: anything else that lands in it during that one tick
 * (a command give, a crate reward) is treated as farmed and sold too. Accepted tradeoff — the
 * window is a single tick of a block the player broke themselves, and the alternative couples this
 * plugin to AE's drop computation.
 */
public class AutoSellTelepathyListener implements Listener {

    private final JavaPlugin plugin;
    private final AutoSellItemManager autoSellItemManager;
    private final AutoSellFilter autoSellFilter;
    private final PriceManager priceManager;
    private final Economy econ;

    // Un seul diff programmé par joueur et par tick, même si plusieurs blocs sont cassés
    // d'un coup (mine-aire + télépathie par ex.) : le premier bloc du tick pose l'instantané
    // "avant", les suivants n'ont rien à ajouter avant que la tâche ne s'exécute.
    private final Map<UUID, ItemStack[]> pendingBeforeSnapshot = new ConcurrentHashMap<>();

    public AutoSellTelepathyListener(JavaPlugin plugin, AutoSellItemManager autoSellItemManager,
            AutoSellFilter autoSellFilter, PriceManager priceManager, Economy econ) {
        this.plugin = plugin;
        this.autoSellItemManager = autoSellItemManager;
        this.autoSellFilter = autoSellFilter;
        this.priceManager = priceManager;
        this.econ = econ;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!autoSellItemManager.hasActiveIn(player)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        if (pendingBeforeSnapshot.putIfAbsent(uuid, snapshotStorage(player)) != null) {
            return; // un diff est déjà programmé pour ce joueur sur ce tick
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack[] before = pendingBeforeSnapshot.remove(uuid);
            if (before != null && player.isOnline()) {
                sellNewlyAddedItems(player, before);
            }
        });
    }

    private ItemStack[] snapshotStorage(Player player) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    private void sellNewlyAddedItems(Player player, ItemStack[] before) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] after = inventory.getStorageContents();
        double total = 0.0;

        for (int i = 0; i < after.length && i < before.length; i++) {
            ItemStack afterStack = after[i];
            if (afterStack == null || afterStack.getType().isAir()) {
                continue;
            }
            if (autoSellItemManager.isAutoSellItem(afterStack)) {
                continue; // jamais l'objet de vente auto lui-même
            }
            if (!autoSellFilter.isAutoSellable(player, afterStack)) {
                continue; // équipement / objet rare : le joueur le garde
            }

            ItemStack beforeStack = before[i];
            int beforeAmount = beforeStack != null && beforeStack.isSimilar(afterStack) ? beforeStack.getAmount() : 0;
            int delta = afterStack.getAmount() - beforeAmount;
            if (delta <= 0) {
                continue;
            }

            ItemStack deltaStack = afterStack.clone();
            deltaStack.setAmount(delta);
            double price = SellPriceResolver.resolveTotalPrice(priceManager, player, deltaStack);
            if (price <= 0) {
                continue; // pas de prix connu : on laisse le joueur garder ce qu'il a reçu
            }

            total += price;
            int remaining = afterStack.getAmount() - delta;
            inventory.setItem(i, remaining > 0 ? withAmount(afterStack, remaining) : null);
        }

        if (total > 0) {
            econ.depositPlayer(player, total);
        }
    }

    private ItemStack withAmount(ItemStack stack, int amount) {
        ItemStack copy = stack.clone();
        copy.setAmount(amount);
        return copy;
    }
}
