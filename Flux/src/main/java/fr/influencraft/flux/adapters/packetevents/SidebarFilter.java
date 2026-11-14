package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import fr.influencraft.flux.api.client.ChatHold;
import fr.influencraft.flux.api.client.Sidebar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link Sidebar} and {@link ChatHold} with PacketEvents: remembers which objective each player's sidebar shows and
 * holds the packet back while his sidebar is hidden; keeps the chat messages sent to a player whose chat is held.
 * Also gives the sidebar's title and lines a text shadow (config {@code sidebar-shadow}), whoever draws it.
 */
public final class SidebarFilter extends PacketListenerAbstract implements Sidebar.Service {

    /** Display slot of the sidebar in the display objective packet. */
    private static final int SIDEBAR = 1;

    /** An objective's look as last sent, while it is not in the sidebar (its title is shaded once it gets there). */
    private record Objective(Component title, WrapperPlayServerScoreboardObjective.RenderType render, ScoreFormat format) {
    }

    private final Map<UUID, Map<String, Objective>> objectives = new ConcurrentHashMap<>();
    private volatile ShadowColor shadow;

    public SidebarFilter() {
        super(PacketListenerPriority.HIGH);
    }

    /** Shadow of the sidebar texts, {@code null} for none. */
    public void shadow(ShadowColor shadow) {
        this.shadow = shadow;
    }

    /** Reads {@code #RRGGBB} / {@code #AARRGGBB}; blank or wrong = none. */
    public static ShadowColor parseShadow(String hex) {
        if (hex == null || hex.isBlank()) return null;
        String h = hex.trim().replace("#", "");
        try {
            long v = Long.parseLong(h, 16);
            return ShadowColor.shadowColor((int) (h.length() <= 6 ? 0xFF000000L | v : v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void forget(UUID player) {
        objectives.remove(player);
    }

    @Override
    public void show(Player player, String objective) {
        // silently: our own packet must not be read as the drawing plugin's
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, new WrapperPlayServerDisplayScoreboard(SIDEBAR, objective));
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        var type = event.getPacketType();
        if (type == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) {
            // a held chat (api.client.ChatHold): the message is kept for later; action bar texts pass
            if (!(event.getPlayer() instanceof Player reader) || !ChatHold.held(reader.getUniqueId())) return;
            var chat = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage(event);
            if (chat.isOverlay()) return;
            ChatHold.keep(reader.getUniqueId(), chat.getMessage());
            event.setCancelled(true);
            return;
        }
        if (type == PacketType.Play.Server.SCOREBOARD_OBJECTIVE) {
            objective(event);
            return;
        }
        if (type == PacketType.Play.Server.UPDATE_SCORE) {
            score(event);
            return;
        }
        if (type != PacketType.Play.Server.DISPLAY_SCOREBOARD) return;
        if (!(event.getPlayer() instanceof Player p)) return;
        WrapperPlayServerDisplayScoreboard w = new WrapperPlayServerDisplayScoreboard(event);
        if (w.getPosition() != SIDEBAR) return;
        Sidebar.told(p.getUniqueId(), w.getScoreName());
        if (Sidebar.hidden(p.getUniqueId()) && w.getScoreName() != null && !w.getScoreName().isEmpty()) {
            event.setCancelled(true);
            return;
        }
        // the objective was created before being shown here (Typewriter does): its title is shaded now
        ShadowColor s = shadow;
        Map<String, Objective> known = objectives.get(p.getUniqueId());
        Objective o = known == null || w.getScoreName() == null ? null : known.remove(w.getScoreName());
        if (s != null && o != null && o.title() != null) {
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(p, new WrapperPlayServerScoreboardObjective(w.getScoreName(),
                    WrapperPlayServerScoreboardObjective.ObjectiveMode.UPDATE, shade(o.title(), s), o.render(), o.format()));
        }
    }

    private void objective(PacketSendEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        ShadowColor s = shadow;
        if (s == null) return;
        WrapperPlayServerScoreboardObjective w = new WrapperPlayServerScoreboardObjective(event);
        UUID id = p.getUniqueId();
        if (w.getMode() == WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE) {
            Map<String, Objective> known = objectives.get(id);
            if (known != null) known.remove(w.getName());
            return;
        }
        if (w.getName() != null && w.getName().equals(Sidebar.shown(id))) {
            if (w.getDisplayName() == null) return;
            w.setDisplayName(shade(w.getDisplayName(), s));
            event.markForReEncode(true);
        } else {
            // not (yet) in the sidebar: left as is, remembered in case it gets there
            objectives.computeIfAbsent(id, k -> new ConcurrentHashMap<>())
                    .put(w.getName(), new Objective(w.getDisplayName(), w.getRenderType(), w.getScoreFormat()));
        }
    }

    private void score(PacketSendEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        ShadowColor s = shadow;
        if (s == null) return;
        WrapperPlayServerUpdateScore w = new WrapperPlayServerUpdateScore(event);
        if (w.getObjectiveName() == null || !w.getObjectiveName().equals(Sidebar.shown(p.getUniqueId()))) return;
        if (w.getAction() == WrapperPlayServerUpdateScore.Action.REMOVE_ITEM) return; // before 1.20.3 only
        Component line = w.getEntityDisplayName();
        // without a display name the client shows the entry's name: the same text, shaded
        w.setEntityDisplayName(shade(line != null ? line : Component.text(w.getEntityName()), s));
        event.markForReEncode(true);
    }

    /** The text with this shadow, its own parts keeping theirs (a part may ask for none). */
    private static Component shade(Component text, ShadowColor s) {
        return text.shadowColor() != null ? text : text.shadowColor(s);
    }
}
