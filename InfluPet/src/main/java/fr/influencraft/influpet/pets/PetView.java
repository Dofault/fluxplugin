package fr.influencraft.influpet.pets;

import com.willfp.eco.core.config.interfaces.Config;
import fr.influencraft.influpet.CardRegistry;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Navigation dans les familiers d'un joueur, pour un menu construit ailleurs (InfluPet ne fournit aucun menu
 * de joueur) : filtre, page et familier consulté, gardés par joueur ; lus par les placeholders
 * %influpet_view_...%, changés par les sous-commandes de /pets (filter, page, select).
 *
 * Liste : familiers adoptés (du filtre en cours), du plus haut niveau au plus bas, pet-settings.view-page-size
 * par page. Filtre : la série de cartes d'un familier, ou sa rareté pour les séries de gui.filter.by-rarity
 * (ex. Prestige) ; seuls les filtres où le joueur a un familier sont proposés (repris de l'ancien menu /pets).
 */
public final class PetView {

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    /** Liste triée du joueur, recalculée au plus une fois par tick (un menu la lit pour chaque case). */
    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();

    private record Cached(int tick, String filter, List<Pet> pets) {
    }

    private static final class State {
        String filter;
        int page = 1;
        String selected;
    }

    private PetView() {
    }

    private static State state(Player player) {
        return STATES.computeIfAbsent(player.getUniqueId(), u -> new State());
    }

    static void forget(UUID uuid) {
        STATES.remove(uuid);
        CACHE.remove(uuid);
    }

    /** Rechargement des familiers : plus aucune liste en cache. */
    static void clearCache() {
        CACHE.clear();
    }

    /** Oublie la liste en cache (familier activé, rangé, filtre changé...). */
    public static void invalidate(Player player) {
        CACHE.remove(player.getUniqueId());
    }

    public static int pageSize() {
        return Math.max(1, Pets.settings().getInt("view-page-size"));
    }

    /** Retour à la première page, sans filtre (ouverture du menu). */
    public static void reset(Player player) {
        State s = state(player);
        s.filter = null;
        s.page = 1;
    }

    // ------------------------------------------------------------------ liste

    /** Familiers adoptés du filtre en cours, du plus haut niveau au plus bas. */
    public static List<Pet> list(Player player) {
        String filter = filter(player);
        int tick = org.bukkit.Bukkit.getCurrentTick();
        Cached cached = CACHE.get(player.getUniqueId());
        if (cached != null && cached.tick() == tick && java.util.Objects.equals(cached.filter(), filter)) {
            return cached.pets();
        }
        List<Pet> pets = new ArrayList<>();
        for (Pet pet : Pets.values()) {
            if (PetData.has(player, pet) && (filter == null || filter.equals(filterKey(pet)))) {
                pets.add(pet);
            }
        }
        pets.sort(Comparator.comparingInt((Pet p) -> PetData.level(player, p)).reversed());
        List<Pet> result = List.copyOf(pets);
        CACHE.put(player.getUniqueId(), new Cached(tick, filter, result));
        return result;
    }

    public static int pages(Player player) {
        return Math.max(1, (int) Math.ceil(list(player).size() / (double) pageSize()));
    }

    public static int page(Player player) {
        return Math.min(state(player).page, pages(player));
    }

    /** n-ième familier (1 = premier) de la page en cours, null au-delà. */
    public static Pet at(Player player, int n) {
        List<Pet> pets = list(player);
        int index = (page(player) - 1) * pageSize() + n - 1;
        return n >= 1 && n <= pageSize() && index < pets.size() ? pets.get(index) : null;
    }

    /** Page suivante (step 1) ou précédente (-1), sans boucler. */
    public static void stepPage(Player player, int step) {
        state(player).page = Math.max(1, Math.min(pages(player), page(player) + step));
    }

    // ------------------------------------------------------------------ familier consulté

    public static void select(Player player, Pet pet) {
        state(player).selected = pet.id();
    }

    public static Pet selected(Player player) {
        String id = state(player).selected;
        return id == null ? null : Pets.get(id);
    }

    // ------------------------------------------------------------------ filtres

    /**
     * Filtre d'un familier : la série de sa carte, ou « rarity:<id> » si cette série est filtrée par rareté
     * (gui.filter.by-rarity). null sans carte liée.
     */
    static String filterKey(Pet pet) {
        CardRegistry registry = Pets.plugin().registry();
        CardRegistry.PetEntry entry = registry.pet(pet.id());
        CardRegistry.Card card = entry == null ? null : registry.cardOf(entry);
        if (card == null || card.series() == null || card.series().isEmpty()) {
            return null;
        }
        for (String series : Pets.settings().getStrings("gui.filter.by-rarity")) {
            if (series.equalsIgnoreCase(card.series())) {
                return "rarity:" + entry.rarity().id();
            }
        }
        return card.series();
    }

    /** Filtres proposés au joueur (clé -> libellé) : ceux de ses familiers, raretés puis séries (ordre des cartes). */
    static Map<String, String> filters(Player player) {
        Config c = Pets.settings();
        CardRegistry registry = Pets.plugin().registry();
        Map<String, String> series = new LinkedHashMap<>();
        Map<Integer, String[]> rarities = new TreeMap<>();
        for (CardRegistry.Card card : registry.cards()) {
            Pet pet = Pets.get(card.pet());
            String key = pet == null || !PetData.has(player, pet) ? null : filterKey(pet);
            if (key == null) {
                continue;
            }
            if (key.startsWith("rarity:")) {
                CardRegistry.Rarity rarity = registry.pet(pet.id()).rarity();
                String path = "gui.filter.rarity-names." + rarity.id();
                rarities.put(rarity.order(), new String[]{key, c.has(path) ? c.getString(path) : rarity.tag()});
            } else {
                series.putIfAbsent(key, key);
            }
        }
        Map<String, String> filters = new LinkedHashMap<>();
        rarities.values().forEach(r -> filters.put(r[0], r[1]));
        filters.putAll(series);
        return filters;
    }

    /** Filtre en cours du joueur, null = tout afficher (aussi si le filtre n'a plus de familier). */
    public static String filter(Player player) {
        String filter = state(player).filter;
        return filter != null && filters(player).containsKey(filter) ? filter : null;
    }

    /** Libellé du filtre en cours (gui.filter.all sans filtre). */
    public static String filterName(Player player) {
        String filter = filter(player);
        return filter == null ? Pets.settings().getString("gui.filter.all") : filters(player).get(filter);
    }

    /** Filtre suivant (step 1) ou précédent (-1), en boucle : tout, puis les filtres du joueur. Page 1. */
    public static void stepFilter(Player player, int step) {
        List<String> keys = new ArrayList<>();
        keys.add(null);
        keys.addAll(filters(player).keySet());
        State s = state(player);
        s.filter = keys.get(Math.floorMod(keys.indexOf(filter(player)) + step, keys.size()));
        s.page = 1;
        invalidate(player);
    }

    /**
     * Lignes de la liste des filtres (gui.filter.selected / unselected, avec %filter%, %owned%, %total%) : tout,
     * puis les filtres proposés au joueur, le filtre en cours mis en avant.
     */
    public static List<String> filterLines(Player player) {
        Config c = Pets.settings().getSubsection("gui.filter");
        String current = filter(player);
        Map<String, int[]> counts = new HashMap<>();
        for (Pet pet : Pets.values()) {
            boolean owned = PetData.has(player, pet);
            for (String key : new String[]{null, filterKey(pet)}) {
                int[] count = counts.computeIfAbsent(key, k -> new int[2]);
                count[0] += owned ? 1 : 0;
                count[1]++;
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add(line(c, current == null, c.getString("all"), counts.get(null)));
        filters(player).forEach((key, label) -> lines.add(line(c, key.equals(current), label, counts.get(key))));
        return lines;
    }

    private static String line(Config c, boolean selected, String label, int[] count) {
        return c.getString(selected ? "selected" : "unselected").replace("%filter%", label)
                .replace("%owned%", String.valueOf(count == null ? 0 : count[0]))
                .replace("%total%", String.valueOf(count == null ? 0 : count[1]));
    }
}
