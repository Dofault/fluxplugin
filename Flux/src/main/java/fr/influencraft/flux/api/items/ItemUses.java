package fr.influencraft.flux.api.items;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What happens when a player right-clicks with an item, whoever made the item: the item's plugin declares it here
 * instead of listening to clicks and recognising items by itself. The click is cancelled for a matching item.
 */
public final class ItemUses {

    /** The player used the item {@code id}. True when one item is consumed. */
    @FunctionalInterface
    public interface Use {
        boolean use(Player player, ItemStack item, String id);
    }

    private record Owned(Plugin owner, String pattern, Use use) {
    }

    private static final List<Owned> USES = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean LISTENING = new AtomicBoolean();

    private ItemUses() {
    }

    /** {@code pattern}: an item id, or a prefix ending with {@code *} ({@link CustomItems#matches}). */
    public static void register(Plugin owner, String pattern, Use use) {
        USES.add(new Owned(owner, pattern, use));
        Plugin flux = Bukkit.getPluginManager().getPlugin("Flux");
        if (flux != null && LISTENING.compareAndSet(false, true)) Bukkit.getPluginManager().registerEvents(new Events(), flux);
    }

    /** Called by Flux when a plugin stops. */
    public static void unregisterAll(Plugin owner) {
        USES.removeIf(o -> o.owner() == owner);
    }

    private static final class Events implements Listener {

        @EventHandler(priority = EventPriority.LOW)
        public void interact(PlayerInteractEvent event) {
            if (USES.isEmpty() || event.getItem() == null) return;
            if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
            String id = CustomItems.id(event.getItem());
            if (id == null) return;
            for (Owned o : USES) {
                if (!CustomItems.matches(o.pattern(), id)) continue;
                event.setCancelled(true);
                Player player = event.getPlayer();
                EquipmentSlot hand = event.getHand() == null ? EquipmentSlot.HAND : event.getHand();
                ItemStack held = player.getInventory().getItem(hand);
                if (held.isEmpty() || !o.use().use(player, held, id)) return;
                held.setAmount(held.getAmount() - 1);
                player.getInventory().setItem(hand, held.isEmpty() ? null : held);
                return;
            }
        }
    }
}
