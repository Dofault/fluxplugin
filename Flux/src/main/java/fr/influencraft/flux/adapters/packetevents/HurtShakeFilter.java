package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDamageEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import fr.influencraft.flux.api.client.HurtShake;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drops the hurt packets about a player sent to himself sooner than his {@link HurtShake} gap: his screen shakes at
 * most that often. The same packet plays his hurt sound on his side, so a dropped one plays the sound instead.
 */
public final class HurtShakeFilter extends PacketListenerAbstract {

    private final Plugin flux;
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();

    public HurtShakeFilter(Plugin flux) {
        super(PacketListenerPriority.NORMAL);
        this.flux = flux;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        boolean damage = event.getPacketType() == PacketType.Play.Server.DAMAGE_EVENT;
        if (!damage && event.getPacketType() != PacketType.Play.Server.HURT_ANIMATION) return;
        if (!(event.getPlayer() instanceof Player p)) return;
        long gap = HurtShake.gap(p.getUniqueId());
        if (gap <= 0) return;
        int entity = damage ? new WrapperPlayServerDamageEvent(event).getEntityId() : new WrapperPlayServerHurtAnimation(event).getEntityId();
        if (entity != p.getEntityId()) return;
        long now = System.currentTimeMillis();
        Long before = last.get(p.getUniqueId());
        if (before == null || now - before >= gap) {
            last.put(p.getUniqueId(), now);
            return;
        }
        event.setCancelled(true);
        if (damage && flux.isEnabled()) {
            float pitch = 0.8f + ThreadLocalRandom.current().nextFloat() * 0.4f;
            Bukkit.getScheduler().runTask(flux, () -> {
                if (p.isOnline()) p.playSound(p, Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1f, pitch);
            });
        }
    }
}
