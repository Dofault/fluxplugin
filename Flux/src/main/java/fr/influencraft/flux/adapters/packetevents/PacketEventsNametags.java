package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import fr.influencraft.flux.tab.Nametags;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Nametags and the line below the name as packets, one client at a time (like the TAB plugin): nothing is written to
 * the server's scoreboards, so the teams and objectives of other plugins are never touched.
 */
public final class PacketEventsNametags implements Nametags {

    private static final String OBJECTIVE = "flux_below";
    /** Display slot of the line below the name. */
    private static final int BELOW_NAME = 2;

    private static void send(Player viewer, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    private static WrapperPlayServerTeams.ScoreBoardTeamInfo info(Component prefix, Component suffix, boolean collision) {
        return new WrapperPlayServerTeams.ScoreBoardTeamInfo(Component.empty(), prefix, suffix,
                WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
                collision ? WrapperPlayServerTeams.CollisionRule.ALWAYS : WrapperPlayServerTeams.CollisionRule.NEVER,
                NamedTextColor.WHITE, WrapperPlayServerTeams.OptionData.NONE);
    }

    @Override
    public void createTeam(Player viewer, String team, String entry, Component prefix, Component suffix, boolean collision) {
        send(viewer, new WrapperPlayServerTeams(team, WrapperPlayServerTeams.TeamMode.CREATE, info(prefix, suffix, collision), entry));
    }

    @Override
    public void updateTeam(Player viewer, String team, Component prefix, Component suffix, boolean collision) {
        send(viewer, new WrapperPlayServerTeams(team, WrapperPlayServerTeams.TeamMode.UPDATE, Optional.of(info(prefix, suffix, collision))));
    }

    @Override
    public void removeTeam(Player viewer, String team) {
        send(viewer, new WrapperPlayServerTeams(team, WrapperPlayServerTeams.TeamMode.REMOVE, Optional.empty()));
    }

    @Override
    public void createBelowName(Player viewer, Component title) {
        send(viewer, new WrapperPlayServerScoreboardObjective(OBJECTIVE, WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE, title,
                WrapperPlayServerScoreboardObjective.RenderType.INTEGER));
        send(viewer, new WrapperPlayServerDisplayScoreboard(BELOW_NAME, OBJECTIVE));
    }

    @Override
    public void removeBelowName(Player viewer) {
        send(viewer, new WrapperPlayServerScoreboardObjective(OBJECTIVE, WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
                Component.empty(), WrapperPlayServerScoreboardObjective.RenderType.INTEGER));
    }

    @Override
    public void score(Player viewer, String entry, int value, Component shown) {
        send(viewer, new WrapperPlayServerUpdateScore(entry, WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM, OBJECTIVE, value,
                null, shown == null ? null : ScoreFormat.fixedScore(shown)));
    }

    @Override
    public void removeScore(Player viewer, String entry) {
        send(viewer, new WrapperPlayServerUpdateScore(entry, WrapperPlayServerUpdateScore.Action.REMOVE_ITEM, OBJECTIVE, Optional.empty()));
    }
}
