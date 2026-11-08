package fr.influencraft.fluxpets;

import fr.influencraft.fluxpets.pets.Pet;
import fr.influencraft.fluxpets.pets.PetData;
import fr.influencraft.fluxpets.pets.PetItems;
import fr.influencraft.fluxpets.pets.Pets;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Façade des familiers pour les cartes, boosters et effets (système de familiers de FluxPets, paquet pets, qui
 * remplace EcoPets). Système inactif (libreforge absent, EcoPets encore là) : tout renvoie « aucun familier » et
 * le plugin tourne avec les cartes seules.
 */
public final class PetHook {

    public record PetInfo(String id, String name) {
    }

    private PetHook() {
    }

    public static boolean available() {
        return Pets.enabled();
    }

    public static List<PetInfo> pets() {
        return available() ? Pets.values().stream().map(p -> new PetInfo(p.id(), p.name())).toList() : List.of();
    }

    public static PetInfo pet(String id) {
        Pet pet = available() ? Pets.get(id) : null;
        return pet == null ? null : new PetInfo(pet.id(), pet.name());
    }

    public static boolean has(OfflinePlayer player, String id) {
        Pet pet = available() ? Pets.get(id) : null;
        return pet != null && PetData.has(player, pet);
    }

    /** Id du premier familier actif du joueur, null sans familier. */
    public static String activeId(OfflinePlayer player) {
        Pet pet = available() ? PetData.active(player) : null;
        return pet == null ? null : pet.id();
    }

    /** Niveau du familier id s'il est parmi les familiers actifs du joueur, sinon 0. */
    public static int activeLevel(OfflinePlayer player, String id) {
        Pet pet = available() ? Pets.get(id) : null;
        return pet == null || !PetData.isActive(player, pet) ? 0 : PetData.level(player, pet);
    }

    /** Icône du familier (son objet « icon », à son nom), papier si indisponible. */
    public static ItemStack icon(Player player, String id) {
        Pet pet = available() ? Pets.get(id) : null;
        return pet == null ? new ItemStack(Material.PAPER) : PetItems.named(pet.baseItem(), pet.name(), null);
    }

    /** Adopte le familier (événement d'adoption, niveau 1). */
    public static void unlock(OfflinePlayer player, String id) {
        Pet pet = available() ? Pets.get(id) : null;
        if (pet != null) {
            PetData.adopt(player, pet);
        }
    }

    /** Pets seen by the other plugins of the suite through Flux ({@code api.pets.Companions}). */
    static void provideCompanions() {
        fr.influencraft.flux.api.pets.Companions.provide(new fr.influencraft.flux.api.pets.Companions.Provider() {
            public List<fr.influencraft.flux.api.pets.Companions.Companion> owned(Player player) {
                if (!available()) return List.of();
                List<Pet> actives = PetData.actives(player);
                List<fr.influencraft.flux.api.pets.Companions.Companion> out = new java.util.ArrayList<>();
                for (Pet pet : actives) out.add(companion(player, pet));
                for (Pet pet : Pets.values()) {
                    if (!actives.contains(pet) && PetData.has(player, pet)) out.add(companion(player, pet));
                }
                return out;
            }

            public List<String> active(Player player) {
                return available() ? PetData.actives(player).stream().map(Pet::id).toList() : List.of();
            }

            public void suppress(java.util.UUID player, boolean hidden) {
                if (hidden) fr.influencraft.fluxpets.pets.PetDisplay.SUPPRESSED.add(player);
                else fr.influencraft.fluxpets.pets.PetDisplay.SUPPRESSED.remove(player);
            }
        });
    }

    private static fr.influencraft.flux.api.pets.Companions.Companion companion(Player player, Pet pet) {
        return new fr.influencraft.flux.api.pets.Companions.Companion(pet.id(), pet.name(),
                PetItems.named(pet.baseItem(), pet.name(), null), pet.model(), pet.flying(), PetData.level(player, pet));
    }

    /** XP de cartes : montant exact, sans multiplicateur de permission (une carte vaut toujours son XP). */
    public static void giveXp(OfflinePlayer player, String id, double amount) {
        Pet pet = available() ? Pets.get(id) : null;
        if (pet == null || amount <= 0) {
            return;
        }
        if (player instanceof Player online) {
            PetData.giveXp(online, pet, amount, true);
        } else {
            PetData.setXp(player, pet, PetData.xp(player, pet) + amount);
        }
    }
}
