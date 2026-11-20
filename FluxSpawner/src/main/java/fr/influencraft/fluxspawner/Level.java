package fr.influencraft.fluxspawner;

import java.util.List;

/**
 * One level of a profile (levels.yml).
 *
 * @param efficiency   share of optimal play the spawner produces (0.05 = 5 %: a zombie spawner makes as many zombies
 *                     per hour as 5 % of an hour of optimal play is worth)
 * @param capacityHours most mobs waiting at the spawner, in hours of its production
 * @param tankHours    most fuel it holds, in hours of running
 * @param requirements goals to reach this level from the one below (empty for level 0)
 */
record Level(int number, double efficiency, double capacityHours, double tankHours, List<Requirement> requirements) {
}
