package fr.influencraft.fluxdungeons.dungeon;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * What the dungeons ask the rpg universe (docs/technique/rpg.md): dungeons are played with the rpg profile (class,
 * stats, rpg items), not with the vanilla gear. Set by FluxDungeons once its rpg module runs; without it, the former
 * vanilla-gear dungeons (launch screen gear, vault, gear locked on death) are kept.
 */
public interface RpgBridge {

    /** May he play: he has an rpg class, or he is outside the rpg universe (he chooses one on arriving). */
    boolean mayPlay(Player player);

    /** Rpg experience (monsters killed, victory). */
    void giveXp(Player player, double xp);

    /** Where a player leaving a dungeon goes: the rpg lobby. */
    Location lobby();
}
