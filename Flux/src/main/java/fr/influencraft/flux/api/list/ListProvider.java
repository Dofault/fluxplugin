package fr.influencraft.flux.api.list;

import java.util.List;

/**
 * Elements of a list displayed by Flux menus ({@code lists.<id>.source} in a menu file), registered with
 * {@link Lists#register}. Flux handles pages, the player's filter, sort and search, and the buttons.
 */
public interface ListProvider {

    /** Elements for this query, already filtered and sorted. Main thread; kept for one second per player. */
    List<ListEntry> entries(ListQuery query);

    /** Labels of the filters ({@code %flux_list_<id>_filter%}), none by default. */
    default List<String> filters() {
        return List.of();
    }

    /** Labels of the sort orders ({@code %flux_list_<id>_sort%}), none by default. */
    default List<String> sorts() {
        return List.of();
    }

    /** True when {@link #entries} already applies {@link ListQuery#search()}. */
    default boolean handlesSearch() {
        return false;
    }
}
