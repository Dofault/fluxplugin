package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import fr.influencraft.flux.api.client.Drivers;

/**
 * {@link Drivers} with PacketEvents: the registered driver is moved first in the passengers packet of his vehicle
 * (read after other plugins changed it, at the encoder).
 */
public final class DriverFirst extends PacketListenerAbstract {

    public DriverFirst() {
        super(PacketListenerPriority.HIGHEST);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.SET_PASSENGERS) return;
        WrapperPlayServerSetPassengers w = new WrapperPlayServerSetPassengers(event);
        int driver = Drivers.driver(w.getEntityId());
        if (driver < 0) return;
        int[] passengers = w.getPassengers();
        int at = -1;
        for (int i = 0; i < passengers.length; i++) if (passengers[i] == driver) at = i;
        if (at <= 0) return;
        int[] ordered = new int[passengers.length];
        ordered[0] = driver;
        for (int i = 0, k = 1; i < passengers.length; i++) if (i != at) ordered[k++] = passengers[i];
        w.setPassengers(ordered);
        event.markForReEncode(true);
    }
}
