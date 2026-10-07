package fr.influencraft.influpet;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Accès aux familiers EcoPets. Sans EcoPets, tout renvoie « aucun familier » et InfluPet
 * fonctionne avec les cartes seules (le code EcoPets est isolé dans EcoPetsBridge).
 */
public final class PetHook {

    public record PetInfo(String id, String name) {
    }

    private PetHook() {
    }

    public static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("EcoPets");
    }

    public static List<PetInfo> pets() {
        return available() ? EcoPetsBridge.pets() : List.of();
    }

    public static PetInfo pet(String id) {
        return available() ? EcoPetsBridge.pet(id) : null;
    }

    public static boolean has(OfflinePlayer player, String id) {
        return available() && EcoPetsBridge.has(player, id);
    }

    /** Id du familier actif du joueur, null sans familier ou sans EcoPets. */
    public static String activeId(OfflinePlayer player) {
        return available() ? EcoPetsBridge.activeId(player) : null;
    }

    /** Niveau du familier id s'il est le familier actif du joueur, sinon 0 (sans EcoPets : 0). */
    public static int activeLevel(OfflinePlayer player, String id) {
        return available() ? EcoPetsBridge.activeLevel(player, id) : 0;
    }

    /** Icône du familier (celle du menu EcoPets), papier si indisponible. */
    public static ItemStack icon(Player player, String id) {
        ItemStack icon = available() ? EcoPetsBridge.icon(player, id) : null;
        return icon == null ? new ItemStack(Material.PAPER) : icon;
    }

    /** Débloque le familier via la commande EcoPets (qui lève l'événement d'adoption). */
    public static void unlock(OfflinePlayer player, String id) {
        if (available() && player.getName() != null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ecopets give " + player.getName() + " " + id);
        }
    }

    public static void giveXp(OfflinePlayer player, String id, double amount) {
        if (available() && player.getName() != null && amount > 0) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "ecopets givexp " + player.getName() + " " + id + " " + amount);
        }
    }
}
