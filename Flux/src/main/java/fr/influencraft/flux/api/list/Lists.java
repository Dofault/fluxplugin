package fr.influencraft.flux.api.list;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lists shown in Flux menus. A plugin registers a {@link ListProvider} under an id; a menu shows it with a
 * {@code lists.<id>} section (button template repeated on the given slots). Flux keeps, per player and list, the
 * page, filter, sort and search, changed by the {@code [list] <id> ...} action:
 *
 * <pre>
 *   [list] shops next | prev | first
 *   [list] shops filter | filter-prev | filter 2      next filter, previous, or by index
 *   [list] shops sort | sort-prev | sort 1
 *   [list] shops search            opens a search field; [list] shops search diamond sets it; [list] shops clear
 *   [list] shops reset             back to page 1, default filter and sort, no search
 * </pre>
 * Placeholders: {@code %flux_list_<id>_page%}, {@code _pages}, {@code _count} (elements after filter and
 * search), {@code _filter}, {@code _sort} (labels), {@code _search}, {@code _has_next}, {@code _has_prev}
 * (true/false).
 */
public final class Lists {

    private record Owned(Plugin owner, ListProvider provider) {
    }

    /** What a player looks at in one list. */
    public static final class State {
        int page;
        int filter;
        int sort;
        String search = "";
        long cachedAt;
        String cachedKey;
        List<ListEntry> cached;

        public int page() {
            return page;
        }

        public int filter() {
            return filter;
        }

        public int sort() {
            return sort;
        }

        public String search() {
            return search;
        }
    }

    private static final Map<String, Owned> PROVIDERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, State>> STATES = new ConcurrentHashMap<>();
    private static final long CACHE_MS = 1000;

    private Lists() {
    }

    public static void register(Plugin owner, String id, ListProvider provider) {
        PROVIDERS.put(id.toLowerCase(Locale.ROOT), new Owned(owner, provider));
    }

    public static void unregisterAll(Plugin owner) {
        PROVIDERS.values().removeIf(o -> o.owner() == owner);
    }

    public static ListProvider provider(String id) {
        Owned o = PROVIDERS.get(id.toLowerCase(Locale.ROOT));
        return o == null ? null : o.provider();
    }

    public static State state(Player player, String id) {
        return STATES.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(id.toLowerCase(Locale.ROOT), k -> new State());
    }

    /** Drops the cached elements of a list for everyone (data changed): the next render asks the provider again. */
    public static void invalidate(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        for (Map<String, State> states : STATES.values()) {
            State s = states.get(key);
            if (s != null) s.cached = null;
        }
    }

    /** Drops the cached elements of one player (e.g. after an action of his). */
    public static void invalidate(Player player, String id) {
        state(player, id).cached = null;
    }

    /** Back to page 1, default filter and sort, no search. */
    public static void reset(Player player, String id) {
        State s = state(player, id);
        s.page = 0;
        s.filter = 0;
        s.sort = 0;
        s.search = "";
        s.cached = null;
    }

    public static void forget(Player player) {
        STATES.remove(player.getUniqueId());
    }

    /** Elements after filter, sort and search, from the one-second cache. */
    public static List<ListEntry> entries(Player player, String id) {
        ListProvider provider = provider(id);
        if (provider == null) return List.of();
        State s = state(player, id);
        String key = s.filter + "|" + s.sort + "|" + s.search;
        long now = System.currentTimeMillis();
        if (s.cached != null && key.equals(s.cachedKey) && now - s.cachedAt < CACHE_MS) return s.cached;
        List<ListEntry> all = provider.entries(new ListQuery(player, id, s.filter, s.sort, s.search));
        if (!s.search.isEmpty() && !provider.handlesSearch()) {
            String needle = s.search.toLowerCase(Locale.ROOT);
            List<ListEntry> found = new ArrayList<>();
            for (ListEntry e : all) if (e.searchText().contains(needle)) found.add(e);
            all = found;
        }
        s.cached = List.copyOf(all);
        s.cachedKey = key;
        s.cachedAt = now;
        return s.cached;
    }

    /** Number of pages for a page size (at least 1). */
    public static int pages(Player player, String id, int pageSize) {
        int count = entries(player, id).size();
        return Math.max(1, (count + Math.max(1, pageSize) - 1) / Math.max(1, pageSize));
    }

    /** Applies a {@code [list] <id> <command>} action. Returns true when a search field must be opened. */
    public static boolean act(Player player, String id, String command, int pageSize) {
        ListProvider provider = provider(id);
        State s = state(player, id);
        String[] parts = command.trim().split("\\s+", 2);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        int filters = provider == null ? 0 : provider.filters().size();
        int sorts = provider == null ? 0 : provider.sorts().size();
        switch (verb) {
            case "next" -> s.page = Math.min(s.page + 1, pages(player, id, pageSize) - 1);
            case "prev" -> s.page = Math.max(0, s.page - 1);
            case "first" -> s.page = 0;
            case "filter" -> s.filter = cycle(s.filter, filters, arg, 1);
            case "filter-prev" -> s.filter = cycle(s.filter, filters, "", -1);
            case "sort" -> s.sort = cycle(s.sort, sorts, arg, 1);
            case "sort-prev" -> s.sort = cycle(s.sort, sorts, "", -1);
            case "search" -> {
                if (arg.isEmpty()) return true;
                s.search = arg;
            }
            case "clear" -> s.search = "";
            case "reset" -> {
                reset(player, id);
                return false;
            }
            default -> {
                return false;
            }
        }
        if (!verb.equals("next") && !verb.equals("prev") && !verb.equals("first")) s.page = 0;
        s.cached = null;
        return false;
    }

    private static int cycle(int current, int size, String index, int step) {
        if (size <= 0) return 0;
        if (!index.isEmpty()) {
            try {
                return Math.floorMod(Integer.parseInt(index), size);
            } catch (NumberFormatException e) {
                return current;
            }
        }
        return Math.floorMod(current + step, size);
    }

    /** {@code %flux_list_<id>_<field>%} for a page size (Flux knows it from the open menu). */
    public static String placeholder(Player player, String id, String field, int pageSize) {
        ListProvider provider = provider(id);
        if (provider == null) return "";
        State s = state(player, id);
        int pages = pages(player, id, pageSize);
        if (s.page >= pages) s.page = pages - 1;
        return switch (field) {
            case "page" -> String.valueOf(s.page + 1);
            case "pages" -> String.valueOf(pages);
            case "count" -> String.valueOf(entries(player, id).size());
            case "filter" -> label(provider.filters(), s.filter);
            case "sort" -> label(provider.sorts(), s.sort);
            case "search" -> s.search;
            case "has_next" -> String.valueOf(s.page + 1 < pages);
            case "has_prev" -> String.valueOf(s.page > 0);
            default -> null;
        };
    }

    private static String label(List<String> labels, int index) {
        return labels.isEmpty() ? "" : labels.get(Math.floorMod(index, labels.size()));
    }
}
