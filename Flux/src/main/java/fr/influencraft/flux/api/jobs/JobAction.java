package fr.influencraft.flux.api.jobs;

import java.util.List;

/**
 * One paid action of a job, as the job plugin defines it.
 *
 * @param trigger  what the player does ({@code mine_block}, {@code kill}, {@code catch_fish}...)
 * @param label    label given by the server for this line, or null (then the trigger names it)
 * @param targets  blocks, entity (one per line) or fish ids the action is limited to (empty: any)
 * @param xp       XP per action, or per health point of the victim when {@code health} is above 0
 * @param money    money per action (or per health point) at the player's level, or null when unknown
 * @param every    money given every N actions (1 = every action)
 * @param health   for a kill paid per health point of the victim: the mob's base health; 0 otherwise
 */
public record JobAction(String trigger, String label, List<String> targets, double xp, Double money, double every,
                        double health) {

    public JobAction(String trigger, String label, List<String> targets, double xp, Double money, double every) {
        this(trigger, label, targets, xp, money, every, 0);
    }

    /** Whether the action pays per health point of the killed mob. */
    public boolean perHealth() {
        return health > 0;
    }
}
