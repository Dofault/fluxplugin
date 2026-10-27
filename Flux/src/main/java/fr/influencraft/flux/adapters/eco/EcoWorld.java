package fr.influencraft.flux.adapters.eco;

import com.willfp.eco.core.events.DropQueuePushEvent;
import com.willfp.eco.util.BlockUtils;
import fr.influencraft.flux.api.world.DirectDropsEvent;
import fr.influencraft.flux.api.world.PlacedBlocks;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;

/**
 * eco behind {@link PlacedBlocks} (eco tracks player placed blocks) and {@link DirectDropsEvent} (eco delivers the
 * drops of telekinesis through its drop queue, two ticks after the break, as a cancellable event).
 */
public final class EcoWorld implements PlacedBlocks.Provider, Listener {

    @Override
    public boolean playerPlaced(Block block) {
        return BlockUtils.isPlayerPlaced(block);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropQueuePush(DropQueuePushEvent event) {
        if (!event.isTelekinetic()) return; // dropped on the ground: picked up as usual
        DirectDropsEvent flux = new DirectDropsEvent(event.getPlayer(), List.copyOf(event.getItems()));
        Bukkit.getPluginManager().callEvent(flux);
        if (flux.items().size() != event.getItems().size() || !flux.items().containsAll(event.getItems())) {
            event.setItems(flux.items());
        }
    }
}
