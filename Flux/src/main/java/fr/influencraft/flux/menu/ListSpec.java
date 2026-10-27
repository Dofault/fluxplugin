package fr.influencraft.flux.menu;

import org.bukkit.configuration.ConfigurationSection;

/**
 * A list shown in a menu or page ({@code lists.<key>}): the elements of the list provider {@code id} (the
 * {@code source} key, else the section key), one per slot, drawn from the {@code item} button template;
 * {@code empty} (optional) is shown on the first slot when there is nothing to list. Several lists may share one
 * source (same elements and page, different slots and templates: an item and its buttons side by side).
 */
public record ListSpec(String id, int[] slots, ConfigurationSection template, ConfigurationSection empty, String where) {

    public int pageSize() {
        return slots.length;
    }
}
