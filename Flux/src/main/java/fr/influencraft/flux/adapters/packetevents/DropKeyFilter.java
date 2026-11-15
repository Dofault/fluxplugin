package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import fr.influencraft.flux.api.client.DropKey;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@link DropKey} with PacketEvents: a claimed drop key never reaches the server (nothing is dropped); the action
 * runs on the server thread and the player gets his inventory again (his client took the item out by itself).
 */
public final class DropKeyFilter extends PacketListenerAbstract {

    private final Plugin flux;

    public DropKeyFilter(Plugin flux) {
        super(PacketListenerPriority.HIGH);
        this.flux = flux;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_DIGGING || event.getUser().getUUID() == null) return;
        DiggingAction action = new WrapperPlayClientPlayerDigging(event).getAction();
        if (action != DiggingAction.DROP_ITEM && action != DiggingAction.DROP_ITEM_STACK) return;
        Runnable claimed = DropKey.claimed(event.getUser().getUUID());
        if (claimed == null) return;
        event.setCancelled(true);
        java.util.UUID id = event.getUser().getUUID();
        Bukkit.getScheduler().runTask(flux, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null) return;
            claimed.run();
            player.updateInventory();
        });
    }
}
