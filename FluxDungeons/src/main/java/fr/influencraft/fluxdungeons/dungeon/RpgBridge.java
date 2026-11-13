package fr.influencraft.fluxdungeons.dungeon;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * What the dungeons ask the rpg universe (docs/technique/rpg.md): dungeons are played with the rpg profile (class,
 * stats, rpg items), never with things of the semi-rp world. Set by FluxDungeons once its rpg module runs; without it
 * no dungeon can be started.
 */
public interface RpgBridge {

    /** May he play: he has an rpg class, or he is outside the rpg universe (he chooses one on arriving). */
    boolean mayPlay(Player player);

    /** Rpg experience (monsters killed, victory). */
    void giveXp(Player player, double xp);

    /** Rpg experience of a dungeon monster killed (balance.yml: its rank at the party's level). */
    double killXp(org.bukkit.entity.LivingEntity mob, fr.influencraft.fluxdungeons.mob.MobTemplate template);

    /** Rpg experience of a victory (a mini-boss of the party's level), before the difficulty's reward multiplier. */
    double victoryXp(org.bukkit.World world);

    /** His class and level as shown on the launch screen ("Guerrier 5"), null when he has none yet. */
    String classLine(Player player);

    /** Where a player leaving a dungeon goes: the rpg lobby. */
    Location lobby();
}
