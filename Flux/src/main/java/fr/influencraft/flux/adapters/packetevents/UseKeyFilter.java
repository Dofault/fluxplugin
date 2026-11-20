package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import fr.influencraft.flux.api.client.UseKey;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link UseKey} with PacketEvents: a claimed player's right clicks (use item, use on a block, use on an entity) never
 * reach the server; the action runs once per click on the server thread (a client sends up to three packets for one).
 */
public final class UseKeyFilter extends PacketListenerAbstract {

    private final Plugin flux;
    /** Tick of each player's last action: the packets of one click come in the same tick. */
    private final Map<UUID, Integer> last = new ConcurrentHashMap<>();

    public UseKeyFilter(Plugin flux) {
        // before the camera's entity clicks (HIGH) and the server
        super(PacketListenerPriority.HIGHEST);
        this.flux = flux;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        var type = event.getPacketType();
        boolean use = type == PacketType.Play.Client.USE_ITEM || type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT;
        if (!use && type != PacketType.Play.Client.INTERACT_ENTITY) return;
        UUID id = event.getUser().getUUID();
        if (id == null) return;
        Runnable claimed = UseKey.claimed(id);
        if (claimed == null) return;
        if (!use && new WrapperPlayClientInteractEntity(event).getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(flux, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null) return;
            int tick = Bukkit.getCurrentTick();
            Integer before = last.put(id, tick);
            if (before != null && before == tick) return;
            claimed.run();
        });
    }
}
