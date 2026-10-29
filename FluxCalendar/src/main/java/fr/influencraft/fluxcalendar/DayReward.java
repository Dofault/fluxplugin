package fr.influencraft.fluxcalendar;

import java.util.List;

/**
 * A reward of the calendar: every day ({@code daily}), a weekday ({@code weekdays.<day>}), a date
 * ({@code days.<1..31>}) or the whole month claimed ({@code complete}).
 *
 * @param name     title shown in the menu
 * @param lore     lines shown in the menu (colours and placeholders allowed)
 * @param commands console commands run on claim ({@code %player%} = player name)
 * @param slots    inventory slots the commands need (items given)
 * @param big      a big reward: its own mark in the calendar before it is claimed
 */
public record DayReward(String name, List<String> lore, List<String> commands, int slots, boolean big) {
}
