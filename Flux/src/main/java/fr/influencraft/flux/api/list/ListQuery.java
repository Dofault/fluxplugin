package fr.influencraft.flux.api.list;

import org.bukkit.entity.Player;

/**
 * What a player currently looks at in a list: chosen filter and sort (indexes into the provider's labels, 0 by
 * default) and search text ({@code ""} when none). Flux applies the search itself after the provider (on
 * {@link ListEntry#searchText()}) unless the provider handles it ({@link ListProvider#handlesSearch()}).
 */
public record ListQuery(Player viewer, String list, int filter, int sort, String search) {
}
