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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the HUD's boss bars below every other one: the client draws boss bars in the order they were added, so when
 * another bar is added for a player, the HUD's are added again on the next tick.
 *
 * <p>The HUD's own bars are the only ones whose title uses the font {@code flux:hud}: their added packets are found
 * by that name in their bytes (the title itself is not decoded: it holds sprites and heads). Nothing can loop: at
 * most one reordering per player and second. (2026-11-13: recognising them by an id read through reflection let
 * the HUD take its own bars for others when the read failed, and re-add them on every tick: the HUD blinked.)</p>
 */
public final class BossBarOrder extends PacketListenerAbstract {

    private static final int ADD = 0;
    private static final byte[] MARK = "flux:hud".getBytes(StandardCharsets.US_ASCII);
    private static final long GAP_MS = 1000;

    private final Plugin flux;
    private final HudService hud;
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();

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
        byte[] rest;
        try {
            PacketWrapper<?> w = new PacketWrapper<>(event, false);
            w.readUUID();
            if (w.readVarInt() != ADD) return;
            rest = w.readBytes(ByteBufHelper.readableBytes(buf));
        } catch (RuntimeException e) {
            return;
        } finally {
            ByteBufHelper.readerIndex(buf, at);
        }
        if (contains(rest, MARK)) return;
        long now = System.currentTimeMillis();
        Long before = last.get(p.getUniqueId());
        if (before != null && now - before < GAP_MS) return;
        last.put(p.getUniqueId(), now);
        if (!flux.isEnabled()) return;
        Bukkit.getScheduler().runTask(flux, () -> hud.putLast(p));
    }

    private static boolean contains(byte[] data, byte[] part) {
        outer:
        for (int i = 0; i + part.length <= data.length; i++) {
            for (int j = 0; j < part.length; j++) if (data[i + j] != part[j]) continue outer;
            return true;
        }
        return false;
    }
}
