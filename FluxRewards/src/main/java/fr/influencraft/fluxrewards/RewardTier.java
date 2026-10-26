package fr.influencraft.fluxrewards;

import java.util.List;

/**
 * A reward of the config: one day of the login cycle, or one playtime tier.
 *
 * @param threshold day number (login) or playtime needed in seconds
 * @param name      title shown in the menu
 * @param lore      lines shown in the menu (colours and placeholders allowed)
 * @param commands  console commands run on claim ({@code %player%} = player name)
 * @param slots     inventory slots the commands need (items given)
 */
public record RewardTier(long threshold, String name, List<String> lore, List<String> commands, int slots) {
}
