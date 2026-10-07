package fr.influencraft.influpet.pets;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Placeholders %influpet_...%, mêmes noms que ceux d'EcoPets (%ecopets_...%) :
 * pet, pet_id, total_pets, active_pet_level, active_pet_level_numeral, active_pet_current_xp,
 * active_pet_required_xp, active_pet_percentage_progress, active_pet_description, et pour chaque familier
 * <id> (niveau), <id>_level, <id>_current_xp, <id>_required_xp, <id>_percentage_progress, <id>_name,
 * <id>_can_activate. Chargée seulement si PlaceholderAPI est actif.
 */
final class PetPlaceholders extends PlaceholderExpansion {

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
        return Pets.plugin().getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || !Pets.enabled()) {
            return "";
        }
        Pet active = PetData.active(player);
        switch (params) {
            case "pet":
                return active == null ? "" : active.name();
            case "pet_id":
                return active == null ? "" : active.id();
            case "total_pets":
                return String.valueOf(PetData.owned(player));
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
}
