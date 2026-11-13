package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerRotation;
import fr.influencraft.flux.api.client.Camera;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link Camera} with PacketEvents: camera and rotation packets, the mouse read as soon as it moves, entities moved on
 * one player's screen, and clicks on watched entities read before the server checks their distance.
 */
public final class PacketEventsCamera extends PacketListenerAbstract implements Camera.Service {

    private final Plugin flux;
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
    public void place(Player viewer, Entity entity, double x, double y, double z) {
        var data = new com.github.retrooper.packetevents.protocol.entity.EntityPositionData(
                new com.github.retrooper.packetevents.util.Vector3d(x, y, z), new com.github.retrooper.packetevents.util.Vector3d(0, 0, 0),
                entity.getYaw(), entity.getPitch());
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync(entity.getEntityId(), data, false));
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        var type = event.getPacketType();
        if (type == PacketType.Play.Client.PLAYER_ROTATION || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            if (event.getPlayer() instanceof Player p) {
                Camera.Turn turn = Camera.turn(p.getUniqueId());
                if (turn != null) {
                    var w = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying(event);
                    if (w.hasRotationChanged()) turn.turned(p, w.getLocation().getYaw(), w.getLocation().getPitch());
                }
            }
            return;
        }
        // since 26.1 an attack is a packet of its own; before, an action of the interact packet
        boolean attack = event.getPacketType() == PacketType.Play.Client.ATTACK;
        if (!attack && event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
        int entity;
        boolean left;
        if (attack) {
            entity = new WrapperPlayClientAttack(event).getEntityId();
            left = true;
        } else {
            WrapperPlayClientInteractEntity w = new WrapperPlayClientInteractEntity(event);
            entity = w.getEntityId();
            left = w.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
        }
        Camera.Click click = Camera.target(entity);
        if (click == null || !(event.getPlayer() instanceof Player p)) return;
        event.setCancelled(true);
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
