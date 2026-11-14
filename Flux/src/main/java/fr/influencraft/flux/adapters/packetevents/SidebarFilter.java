package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import fr.influencraft.flux.api.client.ChatHold;
import fr.influencraft.flux.api.client.Sidebar;
import org.bukkit.entity.Player;

/**
 * {@link Sidebar} and {@link ChatHold} with PacketEvents: remembers which objective each player's sidebar shows and
 * holds the packet back while his sidebar is hidden; keeps the chat messages sent to a player whose chat is held.
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
        if (event.getPacketType() == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) {
            // a held chat (api.client.ChatHold): the message is kept for later; action bar texts pass
            if (!(event.getPlayer() instanceof Player reader) || !ChatHold.held(reader.getUniqueId())) return;
            var chat = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage(event);
            if (chat.isOverlay()) return;
            ChatHold.keep(reader.getUniqueId(), chat.getMessage());
            event.setCancelled(true);
            return;
        }
        if (event.getPacketType() != PacketType.Play.Server.DISPLAY_SCOREBOARD) return;
        if (!(event.getPlayer() instanceof Player p)) return;
        WrapperPlayServerDisplayScoreboard w = new WrapperPlayServerDisplayScoreboard(event);
        if (w.getPosition() != SIDEBAR) return;
        Sidebar.told(p.getUniqueId(), w.getScoreName());
        if (Sidebar.hidden(p.getUniqueId()) && w.getScoreName() != null && !w.getScoreName().isEmpty()) event.setCancelled(true);
    }
}
