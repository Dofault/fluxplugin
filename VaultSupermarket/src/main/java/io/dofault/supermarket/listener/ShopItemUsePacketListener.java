package io.dofault.supermarket.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import io.dofault.supermarket.integration.AdvancedEnchantmentsHook;
import io.dofault.supermarket.managers.PlayerListManager;
import io.dofault.supermarket.managers.ShopManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Blocks the client's "use item" packet before Bukkit even builds a {@code PlayerInteractEvent}
 * for it, when the player is in the shop (or standing right on the entry block, about to walk
 * in) and holding an AdvancedEnchantments item.
 * <p>
 * Needed specifically for {@code Action.RIGHT_CLICK_AIR} (right-clicking with nothing in
 * reach): AdvancedEnchantments' own interact listener (decompiled from
 * {@code InteractEvent.class} in AdvancedEnchantments-9.23.4.jar) starts with
 * {@code if (action != RIGHT_CLICK_AIR && event.isCancelled()) return;} — it deliberately
 * IGNORES Bukkit event cancellation for an air-click, so {@link ShopRestrictionListener}
 * cancelling {@code PlayerInteractEvent} (even at {@code EventPriority.LOWEST}) has zero
 * effect for that specific action. {@code RIGHT_CLICK_BLOCK} remains handled correctly by
 * that Bukkit-event-based listener; this packet listener only needs to cover the air-click
 * gap it can't reach.
 * <p>
 * The entry glass is thin enough that a right-click aimed at it very often misses its hitbox
 * and registers as {@code RIGHT_CLICK_AIR} instead of {@code RIGHT_CLICK_BLOCK} — the exact
 * click meant to walk into the shop. At that instant {@code isInShop()} is still false (that's
 * the whole point of the click), so it also needs the {@code shopManager.isEntry(...)} check
 * below to be covered, not just {@code isInShop()}.
 */
public final class ShopItemUsePacketListener extends PacketAdapter {

    private final PlayerListManager playerListManager;
    private final ShopManager shopManager;

    public ShopItemUsePacketListener(JavaPlugin plugin, PlayerListManager playerListManager,
            ShopManager shopManager) {
        super(plugin, ListenerPriority.HIGHEST, PacketType.Play.Client.USE_ITEM);
        this.playerListManager = playerListManager;
        this.shopManager = shopManager;
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        Player player = event.getPlayer();
        boolean inShop = playerListManager.isInShop(player.getUniqueId());
        if (!inShop && !shopManager.isEntry(player.getLocation().getBlock())) {
            return;
        }
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (AdvancedEnchantmentsHook.isAnyAeItem(mainHand) || AdvancedEnchantmentsHook.isAnyAeItem(offHand)) {
            event.setCancelled(true);
        }
    }
}
