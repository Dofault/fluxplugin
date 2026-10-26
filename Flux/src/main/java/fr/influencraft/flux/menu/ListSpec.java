package fr.influencraft.flux.menu;

import org.bukkit.configuration.ConfigurationSection;

/**
 * A list shown in a menu or page ({@code lists.<id>}): the elements of the list provider {@code id}, one per slot,
 * drawn from the {@code item} button template; {@code empty} (optional) is shown on the first slot when there is
 * nothing to list.
 */
public record ListSpec(String id, int[] slots, ConfigurationSection template, ConfigurationSection empty, String where) {

    public int pageSize() {
        return slots.length;
    }
}
