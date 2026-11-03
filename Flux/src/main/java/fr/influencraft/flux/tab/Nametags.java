package fr.influencraft.flux.tab;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Per-client nametag teams and the line below the name (packets, see the packetevents adapter). */
public interface Nametags {

    void createTeam(Player viewer, String team, String entry, Component prefix, Component suffix, boolean collision);

    void updateTeam(Player viewer, String team, Component prefix, Component suffix, boolean collision);

    void removeTeam(Player viewer, String team);

    void createBelowName(Player viewer, Component title);

    void removeBelowName(Player viewer);

    /** {@code shown} replaces the number (fixed format), null = the number itself. */
    void score(Player viewer, String entry, int value, Component shown);

    void removeScore(Player viewer, String entry);
}
