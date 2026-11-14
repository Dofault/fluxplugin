package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import fr.influencraft.flux.api.client.Sidebar;
import org.bukkit.entity.Player;

/**
 * {@link Sidebar} with PacketEvents: remembers which objective each player's sidebar shows and holds the packet back
 * while his sidebar is hidden.
 */
public final class SidebarFilter extends PacketListenerAbstract implements Sidebar.Service {

    /** Display slot of the sidebar in the display objective packet. */
    private static final int SIDEBAR = 1;

    public SidebarFilter() {
        super(PacketListenerPriority.HIGH);
    }

    @Override
    public void show(Player player, String objective) {
        // silently: our own packet must not be read as the drawing plugin's
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, new WrapperPlayServerDisplayScoreboard(SIDEBAR, objective));
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.DISPLAY_SCOREBOARD) return;
        if (!(event.getPlayer() instanceof Player p)) return;
        WrapperPlayServerDisplayScoreboard w = new WrapperPlayServerDisplayScoreboard(event);
        if (w.getPosition() != SIDEBAR) return;
        Sidebar.told(p.getUniqueId(), w.getScoreName());
        if (Sidebar.hidden(p.getUniqueId()) && w.getScoreName() != null && !w.getScoreName().isEmpty()) event.setCancelled(true);
    }
}
