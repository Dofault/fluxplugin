package fr.influencraft.flux.api.jobs;

import java.util.List;

/**
 * One paid action of a job, as the job plugin defines it.
 *
 * @param trigger  what the player does ({@code mine_block}, {@code kill}, {@code catch_fish}...)
 * @param label    label given by the server for this line, or null (then the trigger names it)
 * @param targets  blocks, entity (one per line) or fish ids the action is limited to (empty: any)
 * @param xp       XP per action, or per health point of the victim when {@code perHealth}
 * @param money    money per action (or per health point) at the player's level, or null when unknown
 * @param every    money given every N actions (1 = every action)
 * @param perHealth a kill paid per health point of the victim (the trigger's value), whatever the mob
 */
public record JobAction(String trigger, String label, List<String> targets, double xp, Double money, double every,
                        boolean perHealth) {

    public JobAction(String trigger, String label, List<String> targets, double xp, Double money, double every) {
        this(trigger, label, targets, xp, money, every, false);
    }
}
