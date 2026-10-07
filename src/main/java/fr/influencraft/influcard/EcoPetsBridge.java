package fr.influencraft.influcard;

import com.willfp.ecopets.api.EcoPetsAPI;
import com.willfp.ecopets.pets.Pet;
import com.willfp.ecopets.pets.Pets;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Seule classe qui référence EcoPets : n'est chargée que via PetHook, si EcoPets est actif. */
final class EcoPetsBridge {

    private EcoPetsBridge() {
    }

    static List<PetHook.PetInfo> pets() {
        List<PetHook.PetInfo> result = new ArrayList<>();
        for (Pet pet : Pets.values()) {
            result.add(new PetHook.PetInfo(pet.getId(), legacy(pet.getName())));
        }
        return result;
    }

    static PetHook.PetInfo pet(String id) {
        Pet pet = Pets.getByID(id);
        return pet == null ? null : new PetHook.PetInfo(pet.getId(), legacy(pet.getName()));
    }

    static boolean has(OfflinePlayer player, String id) {
        Pet pet = Pets.getByID(id);
        return pet != null && EcoPetsAPI.getInstance().hasPet(player, pet);
    }

    /** Id du familier actif, null sans familier. */
    static String activeId(OfflinePlayer player) {
        Pet pet = EcoPetsAPI.getInstance().getActivePet(player);
        return pet == null ? null : pet.getId();
    }

    /** Niveau du familier id s'il est le familier actif du joueur, sinon 0. */
    static int activeLevel(OfflinePlayer player, String id) {
        Pet pet = EcoPetsAPI.getInstance().getActivePet(player);
        return pet == null || !pet.getId().equals(id) ? 0 : EcoPetsAPI.getInstance().getPetLevel(player, pet);
    }

    static ItemStack icon(Player player, String id) {
        Pet pet = Pets.getByID(id);
        return pet == null ? null : pet.getIcon(player).clone();
    }

    private static final java.util.regex.Pattern SECTION_HEX =
            java.util.regex.Pattern.compile("§x((?:§[0-9a-fA-F]){6})");

    /**
     * Nom EcoPets déjà colorisé en codes § (hex au format §x§R§R§G§G§B§B) -> codes & lisibles par
     * Texts (&#RRGGBB). Sans ça, seul le dernier code (souvent §0, noir) était appliqué.
     */
    static String legacy(String name) {
        if (name == null) {
            return "";
        }
        var m = SECTION_HEX.matcher(name);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, "&#" + m.group(1).replace("§", ""));
        }
        m.appendTail(out);
        return out.toString().replace('§', '&');
    }
}
