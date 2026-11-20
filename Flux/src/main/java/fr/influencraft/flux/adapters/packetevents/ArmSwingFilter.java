package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import fr.influencraft.flux.api.client.ArmSwing;

/**
 * {@link ArmSwing} with PacketEvents: the swing a client sends back when the server swung its player's arm never
 * reaches the server, so it is never read as a left click.
 */
public final class ArmSwingFilter extends PacketListenerAbstract {

    public ArmSwingFilter() {
        super(PacketListenerPriority.LOWEST);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.ANIMATION || event.getUser().getUUID() == null) return;
        if (ArmSwing.echo(event.getUser().getUUID())) event.setCancelled(true);
    }
}
