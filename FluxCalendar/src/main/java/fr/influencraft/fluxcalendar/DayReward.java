package fr.influencraft.fluxcalendar;

import java.util.List;

/**
 * The reward of a date of the month (config {@code days.<1..31>}).
 *
 * @param day      date in the month (1 to 31)
 * @param name     title shown in the menu
 * @param lore     lines shown in the menu (colours and placeholders allowed)
 * @param commands console commands run on claim ({@code %player%} = player name)
 * @param slots    inventory slots the commands need (items given)
 * @param big      a big reward: its own mark in the calendar before it is claimed
 */
public record DayReward(int day, String name, List<String> lore, List<String> commands, int slots, boolean big) {
}
