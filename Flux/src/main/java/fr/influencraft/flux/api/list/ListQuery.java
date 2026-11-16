package fr.influencraft.flux.api.list;

import org.bukkit.entity.Player;

/**
 * What a player currently looks at in a list: chosen filter, kind and sort (indexes into the provider's labels, 0 by
 * default) and search text ({@code ""} when none). Flux applies the search itself after the provider (on
 * {@link ListEntry#searchText()}) unless the provider handles it ({@link ListProvider#handlesSearch()}).
 * {@code kind} is a second filter, for lists filtered two ways (the dungeon shop: by job and by kind of item).
 */
public record ListQuery(Player viewer, String list, int filter, int sort, String search, int kind) {

    public ListQuery(Player viewer, String list, int filter, int sort, String search) {
        this(viewer, list, filter, sort, search, 0);
    }
}
