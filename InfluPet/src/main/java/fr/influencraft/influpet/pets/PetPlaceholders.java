package fr.influencraft.influpet.pets;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Placeholders %influpet_...%, mêmes noms que ceux d'EcoPets (%ecopets_...%) :
 * pet, pet_id (premier familier actif), total_pets, active_pets (nombre), max_active_pets, active_pet_level,
 * active_pet_level_numeral, active_pet_current_xp, active_pet_required_xp, active_pet_percentage_progress, active_pet_description, et pour chaque familier
 * <id> (niveau), <id>_level, <id>_current_xp, <id>_required_xp, <id>_percentage_progress, <id>_name,
 * <id>_can_activate. money_multiplier : multiplicateur de prix de vente du familier actif (effet codé
 * money-bonus), « 1.0000 » sans bonus. Chargée seulement si PlaceholderAPI est actif.
 *
 * Pour construire le menu des familiers dans un plugin de menus (InfluPet n'en fournit aucun, cf. PetView) :
 * - view_<n> : familier n de la page en cours (1 = premier, vide au-delà), view_<n>_<champ> ;
 *   view_page, view_pages, view_count, view_page_size, view_filter (libellé), view_filter_line_<k> (k-ième
 *   ligne de la liste des filtres, vide au-delà) ;
 * - slot_<n> : familier actif de l'emplacement n (1 à 5, vide si libre), slot_<n>_state (active, free, locked),
 *   slot_<n>_grade (gui.pet-slots.locked.grades), slot_<n>_<champ> ;
 * - selected : familier consulté (/pets select), selected_<champ> ;
 * - hidden : true si le joueur a caché ses familiers ; pets_count : nombre de familiers existants (chargés).
 * Champs d'un familier : id, name, level, max_level, maxed, active, can_activate, current_xp, required_xp,
 * progress (pourcentage vers le niveau suivant), description, effects_<k> (k-ième ligne de ses effets au niveau
 * actuel), level_<L> (état du niveau L : unlocked, current, locked), rewards_<L>_<k> (k-ième ligne des effets
 * du niveau L). Vide sans familier ou au-delà des lignes.
 */
final class PetPlaceholders extends PlaceholderExpansion {

    private final fr.influencraft.influpet.InfluPet plugin;

    PetPlaceholders(fr.influencraft.influpet.InfluPet plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "influpet";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Influencraft";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equals("money_multiplier")) {
            double multiplier = player instanceof Player online ? plugin.moneyMultiplier(online) : 1;
            return String.format(java.util.Locale.ROOT, "%.4f", multiplier);
        }
        if (player == null || !Pets.enabled()) {
            return "";
        }
        if (player instanceof Player online) {
            String view = view(online, params);
            if (view != null) {
                return view;
            }
        }
        Pet active = PetData.active(player);
        switch (params) {
            case "pet":
                return active == null ? "" : active.name();
            case "pet_id":
                return active == null ? "" : active.id();
            case "total_pets":
                return String.valueOf(PetData.owned(player));
            case "pets_count":
                return String.valueOf(Pets.values().size());
            case "active_pets":
                return String.valueOf(PetData.actives(player).size());
            case "max_active_pets":
                return player instanceof Player online ? String.valueOf(PetData.maxActive(online)) : "";
            case "active_pet_level":
                return active == null ? "" : String.valueOf(PetData.level(player, active));
            case "active_pet_level_numeral":
                return active == null ? "" : com.willfp.eco.util.NumberUtils.toNumeral(PetData.level(player, active));
            case "active_pet_current_xp":
                return active == null ? "" : Pet.nice(PetData.xp(player, active));
            case "active_pet_required_xp":
                return active == null ? "" : Pet.nice(active.xpForLevel(PetData.level(player, active) + 1));
            case "active_pet_percentage_progress":
                return active == null ? "" : Pet.nice(Math.round(PetData.progress(player, active) * 10000) / 100.0);
            case "active_pet_description":
                return active == null ? "" : active.description();
            default:
                break;
        }
        for (Pet pet : Pets.values()) {
            String id = pet.id();
            if (!params.startsWith(id)) {
                continue;
            }
            String rest = params.substring(id.length());
            switch (rest) {
                case "", "_level":
                    return String.valueOf(PetData.level(player, pet));
                case "_current_xp":
                    return Pet.nice(PetData.xp(player, pet));
                case "_required_xp":
                    return Pet.nice(pet.xpForLevel(PetData.level(player, pet) + 1));
                case "_percentage_progress":
                    return Pet.nice(Math.round(PetData.progress(player, pet) * 10000) / 100.0);
                case "_name":
                    return pet.name();
                case "_can_activate":
                    return String.valueOf(player instanceof Player online && pet.canActivate(online));
                default:
                    break;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ menu des familiers (PetView)

    private static final java.util.regex.Pattern INDEXED = java.util.regex.Pattern.compile("^(view|slot)_(\\d+)(?:_(.+))?$");

    /** Placeholders de navigation (view_, slot_, selected, hidden) ; null si params n'en est pas un. */
    private static String view(Player player, String params) {
        switch (params) {
            case "view_page":
                return String.valueOf(PetView.page(player));
            case "view_pages":
                return String.valueOf(PetView.pages(player));
            case "view_count":
                return String.valueOf(PetView.list(player).size());
            case "view_page_size":
                return String.valueOf(PetView.pageSize());
            case "view_filter":
                return PetView.filterName(player);
            case "hidden":
                return String.valueOf(PetData.hidden(player));
            case "selected":
                Pet selected = PetView.selected(player);
                return selected == null ? "" : selected.id();
            default:
                break;
        }
        if (params.startsWith("view_filter_line_")) {
            List<String> lines = PetView.filterLines(player);
            int k = parse(params.substring("view_filter_line_".length()));
            return k >= 1 && k <= lines.size() ? lines.get(k - 1) : "";
        }
        if (params.startsWith("selected_")) {
            return field(player, PetView.selected(player), params.substring("selected_".length()));
        }
        java.util.regex.Matcher m = INDEXED.matcher(params);
        if (!m.matches()) {
            return null;
        }
        int n = parse(m.group(2));
        String champ = m.group(3);
        if (m.group(1).equals("view")) {
            Pet pet = PetView.at(player, n);
            return champ == null ? (pet == null ? "" : pet.id()) : field(player, pet, champ);
        }
        List<Pet> actives = PetData.actives(player);
        Pet pet = n >= 1 && n <= actives.size() ? actives.get(n - 1) : null;
        if (champ == null) {
            return pet == null ? "" : pet.id();
        }
        if (champ.equals("state")) {
            return pet != null ? "active" : n > PetData.maxActive(player) ? "locked" : "free";
        }
        if (champ.equals("grade")) {
            String path = "gui.pet-slots.locked.grades." + n;
            String grade = Pets.settings().has(path) ? Pets.settings().getString(path) : "";
            return org.bukkit.Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
                    ? me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, grade) : grade;
        }
        return field(player, pet, champ);
    }

    /** Champ d'un familier pour ce joueur (cf. en-tête), vide sans familier. */
    private static String field(Player player, Pet pet, String champ) {
        if (pet == null) {
            return "";
        }
        int level = PetData.level(player, pet);
        switch (champ) {
            case "id":
                return pet.id();
            case "name":
                return pet.name();
            case "level":
                return String.valueOf(level);
            case "max_level":
                return String.valueOf(pet.maxLevel());
            case "maxed":
                return String.valueOf(level >= pet.maxLevel());
            case "active":
                return String.valueOf(PetData.isActive(player, pet));
            case "can_activate":
                return String.valueOf(pet.canActivate(player));
            case "current_xp":
                return Pet.nice(PetData.xp(player, pet));
            case "required_xp":
                return Pet.nice(pet.xpForLevel(level + 1));
            case "progress":
                return level >= pet.maxLevel() ? "100" : Pet.nice(Math.round(PetData.progress(player, pet) * 10000) / 100.0);
            case "description":
                return pet.description();
            default:
                break;
        }
        if (champ.startsWith("effects_")) {
            return line(pet.effectsDescription(level), parse(champ.substring("effects_".length())));
        }
        if (champ.startsWith("level_")) {
            int target = parse(champ.substring("level_".length()));
            return target <= level ? "unlocked" : target == level + 1 ? "current" : "locked";
        }
        if (champ.startsWith("rewards_")) {
            String[] parts = champ.substring("rewards_".length()).split("_");
            if (parts.length == 2) {
                int target = parse(parts[0]);
                return target >= 1 && target <= pet.maxLevel() ? line(pet.rewardsDescription(target), parse(parts[1])) : "";
            }
        }
        return "";
    }

    private static String line(List<String> lines, int k) {
        return lines != null && k >= 1 && k <= lines.size() ? lines.get(k - 1) : "";
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
