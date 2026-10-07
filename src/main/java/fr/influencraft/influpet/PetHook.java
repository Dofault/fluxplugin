package fr.influencraft.influpet;

import fr.influencraft.influpet.pets.Pet;
import fr.influencraft.influpet.pets.PetData;
import fr.influencraft.influpet.pets.PetItems;
import fr.influencraft.influpet.pets.Pets;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Façade des familiers pour les cartes, boosters et effets (système de familiers d'InfluPet, paquet pets, qui
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

    /** Id du familier actif du joueur, null sans familier. */
    public static String activeId(OfflinePlayer player) {
        Pet pet = available() ? PetData.active(player) : null;
        return pet == null ? null : pet.id();
    }

    /** Niveau du familier id s'il est le familier actif du joueur, sinon 0. */
    public static int activeLevel(OfflinePlayer player, String id) {
        Pet pet = available() ? PetData.active(player) : null;
        return pet == null || !pet.id().equals(id) ? 0 : PetData.level(player, pet);
    }

    /** Icône du familier (celle du menu des familiers), papier si indisponible. */
    public static ItemStack icon(Player player, String id) {
        Pet pet = available() ? Pets.get(id) : null;
        return pet == null ? new ItemStack(Material.PAPER) : PetItems.icon(player, pet);
    }

    /** Adopte le familier (événement d'adoption, niveau 1). */
    public static void unlock(OfflinePlayer player, String id) {
        Pet pet = available() ? Pets.get(id) : null;
        if (pet != null) {
            PetData.adopt(player, pet);
        }
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
