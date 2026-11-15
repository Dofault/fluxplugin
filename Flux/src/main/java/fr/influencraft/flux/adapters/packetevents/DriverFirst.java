package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import fr.influencraft.flux.api.client.Drivers;
import org.bukkit.entity.Player;

/**
 * {@link Drivers} with PacketEvents: every passengers packet is rewritten as Drivers says (read after other plugins
 * changed it, at the encoder), and Drivers' own packets are sent silently (already as they must be).
 */
public final class DriverFirst extends PacketListenerAbstract implements Drivers.Sender {

    public DriverFirst() {
        super(PacketListenerPriority.HIGHEST);
    }

    @Override
    public void send(Player viewer, int vehicle, int[] passengers) {
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, new WrapperPlayServerSetPassengers(vehicle, passengers));
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.SET_PASSENGERS) return;
        WrapperPlayServerSetPassengers w = new WrapperPlayServerSetPassengers(event);
        int[] rewritten = Drivers.rewrite(w.getEntityId(), w.getPassengers());
        if (rewritten == null) return;
        w.setPassengers(rewritten);
        event.markForReEncode(true);
    }
}
