package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerRotation;
import fr.influencraft.flux.api.client.Camera;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link Camera} with PacketEvents: camera and rotation packets, the invisible flag of a player's own entity added to
 * the metadata sent to himself, and clicks on watched entities read before the server checks their distance.
 */
public final class PacketEventsCamera extends PacketListenerAbstract implements Camera.Service {

    private static final byte INVISIBLE = 0x20;

    private final Plugin flux;
    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
    /** Tick of a player's last right click: the client sends two packets (at, then plain) for one click. */
    private final Map<UUID, Integer> lastUse = new ConcurrentHashMap<>();

    public PacketEventsCamera(Plugin flux) {
        super(PacketListenerPriority.HIGH);
        this.flux = flux;
    }

    @Override
    public void view(Player player, Entity camera) {
        int id = camera == null ? player.getEntityId() : camera.getEntityId();
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerCamera(id));
    }

    @Override
    public void turn(Player player, float yaw, boolean relativeYaw, float pitch, boolean relativePitch) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerPlayerRotation(yaw, relativeYaw, pitch, relativePitch));
    }

    @Override
    public void hideSelf(Player player, boolean hide) {
        if (hide) hidden.add(player.getUniqueId());
        else hidden.remove(player.getUniqueId());
        // the flags as they are now, with or without the invisible bit
        List<EntityData<?>> data = new ArrayList<>();
        data.add(new EntityData<>(0, EntityDataTypes.BYTE, flags(player, hide)));
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerEntityMetadata(player.getEntityId(), data));
    }

    private static byte flags(Player p, boolean invisible) {
        int f = 0;
        if (p.getFireTicks() > 0) f |= 0x01;
        if (p.isSneaking()) f |= 0x02;
        if (p.isSprinting()) f |= 0x08;
        if (p.isSwimming()) f |= 0x10;
        if (invisible || p.isInvisible() || p.hasPotionEffect(PotionEffectType.INVISIBILITY)) f |= INVISIBLE;
        if (p.isGlowing()) f |= 0x40;
        if (p.isGliding()) f |= (byte) 0x80;
        return (byte) f;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onPacketSend(PacketSendEvent event) {
        if (hidden.isEmpty() || event.getPacketType() != PacketType.Play.Server.ENTITY_METADATA) return;
        if (!(event.getPlayer() instanceof Player p) || !hidden.contains(p.getUniqueId())) return;
        WrapperPlayServerEntityMetadata w = new WrapperPlayServerEntityMetadata(event);
        if (w.getEntityId() != p.getEntityId()) return;
        boolean changed = false;
        for (EntityData<?> d : w.getEntityMetadata()) {
            if (d.getIndex() == 0 && d.getValue() instanceof Byte b && (b & INVISIBLE) == 0) {
                ((EntityData<Byte>) d).setValue((byte) (b | INVISIBLE));
                changed = true;
            }
        }
        if (changed) event.markForReEncode(true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
        WrapperPlayClientInteractEntity w = new WrapperPlayClientInteractEntity(event);
        Camera.Click click = Camera.target(w.getEntityId());
        if (click == null || !(event.getPlayer() instanceof Player p)) return;
        event.setCancelled(true);
        boolean left = w.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
        Bukkit.getScheduler().runTask(flux, () -> {
            if (!p.isOnline()) return;
            if (!left) {
                int tick = Bukkit.getCurrentTick();
                Integer before = lastUse.put(p.getUniqueId(), tick);
                if (before != null && before == tick) return;
            }
            click.clicked(p, left);
        });
    }
}
