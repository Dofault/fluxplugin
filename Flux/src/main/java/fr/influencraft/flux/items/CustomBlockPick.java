package fr.influencraft.flux.items;

import fr.influencraft.flux.api.items.CustomItems;
import io.papermc.paper.event.player.PlayerPickBlockEvent;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Middle click on a custom block (ItemsAdder, through {@link CustomItems}) picks that block's item, not the vanilla block
 * it is drawn with (a note block for the RPG blocks of the dungeons). Paper does not let the picked item be replaced,
 * so the pick is done here, as the game does it: the item already in the hotbar is selected, one elsewhere in the
 * inventory comes to the hotbar, and in creative a missing one is created in the slot the game chose.
 */
public final class CustomBlockPick implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPick(PlayerPickBlockEvent e) {
        String id = CustomItems.blockId(e.getBlock());
        if (id == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        PlayerInventory inv = p.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (!id.equals(CustomItems.id(inv.getItem(slot)))) continue;
            if (slot < 9) {
                inv.setHeldItemSlot(slot);
            } else {
                int target = e.getTargetSlot() >= 0 && e.getTargetSlot() < 9 ? e.getTargetSlot() : inv.getHeldItemSlot();
                ItemStack moved = inv.getItem(slot);
                inv.setItem(slot, inv.getItem(target));
                inv.setItem(target, moved);
                inv.setHeldItemSlot(target);
            }
            return;
        }
        if (p.getGameMode() != GameMode.CREATIVE) return;
        ItemStack item = CustomItems.item(id);
        if (item == null) return;
        int target = e.getTargetSlot() >= 0 && e.getTargetSlot() < 9 ? e.getTargetSlot() : inv.getHeldItemSlot();
        inv.setItem(target, item);
        inv.setHeldItemSlot(target);
    }
}
