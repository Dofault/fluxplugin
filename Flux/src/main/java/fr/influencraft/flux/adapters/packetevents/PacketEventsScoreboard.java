package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import fr.influencraft.flux.scoreboard.ScoreboardService;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Flux's scoreboard as packets, one client at a time: an objective of its own, lines as scores whose text is their
 * display name and whose number is hidden. Nothing is written to the server's scoreboards.
 */
public final class PacketEventsScoreboard implements ScoreboardService.Packets {

    /** Display slot of the sidebar. */
    private static final int SIDEBAR = 1;

    private static void send(Player viewer, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    @Override
    public void create(Player player, String objective, Component title) {
        send(player, new WrapperPlayServerScoreboardObjective(objective, WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE, title,
                WrapperPlayServerScoreboardObjective.RenderType.INTEGER, ScoreFormat.blankScore()));
        // not silently: the sidebar filter must see it (Sidebar.told, hidden sidebars)
        send(player, new WrapperPlayServerDisplayScoreboard(SIDEBAR, objective));
    }

    @Override
    public void title(Player player, String objective, Component title) {
        send(player, new WrapperPlayServerScoreboardObjective(objective, WrapperPlayServerScoreboardObjective.ObjectiveMode.UPDATE, title,
                WrapperPlayServerScoreboardObjective.RenderType.INTEGER, ScoreFormat.blankScore()));
    }

    @Override
    public void remove(Player player, String objective) {
        send(player, new WrapperPlayServerScoreboardObjective(objective, WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
                Component.empty(), WrapperPlayServerScoreboardObjective.RenderType.INTEGER));
    }

    @Override
    public void line(Player player, String objective, String entry, int score, Component text) {
        send(player, new WrapperPlayServerUpdateScore(entry, WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM, objective, score,
                text, ScoreFormat.blankScore()));
    }

    @Override
    public void removeLine(Player player, String objective, String entry) {
        send(player, new WrapperPlayServerResetScore(entry, objective));
    }
}
