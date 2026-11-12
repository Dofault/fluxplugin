package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import fr.influencraft.flux.hud.HudService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the HUD's boss bars below every other one: the client draws boss bars in the order they were added, so when
 * another bar is added for a player, the HUD's are added again on the next tick. Only the bar's id and the action
 * are read (a title may hold sprites or heads, which are not decoded here).
 */
public final class BossBarOrder extends PacketListenerAbstract {

    private static final int ADD = 0;

    private final Plugin flux;
    private final HudService hud;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public BossBarOrder(Plugin flux, HudService hud) {
        super(PacketListenerPriority.MONITOR);
        this.flux = flux;
        this.hud = hud;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.BOSS_BAR || event.isCancelled()) return;
        if (!(event.getPlayer() instanceof Player p)) return;
        Object buf = event.getByteBuf();
        int at = ByteBufHelper.readerIndex(buf);
        UUID id;
        int action;
        try {
            PacketWrapper<?> w = new PacketWrapper<>(event, false);
            id = w.readUUID();
            action = w.readVarInt();
        } catch (RuntimeException e) {
            return;
        } finally {
            ByteBufHelper.readerIndex(buf, at);
        }
        if (action != ADD || HudService.BarIds.ours(id)) return;
        if (!pending.add(p.getUniqueId()) || !flux.isEnabled()) return;
        Bukkit.getScheduler().runTask(flux, () -> {
            pending.remove(p.getUniqueId());
            hud.putLast(p);
        });
    }
}
