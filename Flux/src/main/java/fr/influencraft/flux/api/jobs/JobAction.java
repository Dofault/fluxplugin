package fr.influencraft.flux.api.jobs;

import java.util.List;

/**
 * One paid action of a job, as the job plugin defines it.
 *
 * @param trigger  what the player does ({@code mine_block}, {@code kill}, {@code catch_fish}...)
 * @param label    label given by the server for this line, or null (then the trigger names it)
 * @param targets  blocks, entities or items the action is limited to (empty: any)
 * @param xp       XP per action
 * @param money    money per action at the player's level, or null when unknown
 * @param every    money given every N actions (1 = every action)
 */
public record JobAction(String trigger, String label, List<String> targets, double xp, Double money, double every) {
}
