package fr.influencraft.fluxlands;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.UUID;

/**
 * Instantanés des échoppes et adresses, indépendants des classes d'InfluEchoppe et de
 * InfluPW : les placeholders et menus les lisent sans dépendre de la présence de
 * ces plugins.
 */
final class Fiches {

    private Fiches() {
    }

    /** Échoppe InfluEchoppe vue au dernier passage de l'index. */
    record Echoppe(long id, Location lieu, UUID proprio, String proprioNom, String icone, String objet,
                   int quantite, double prix, boolean rachat, int stock, String villeId, String villeNom) {

        /**
         * Active = encore en mesure de vendre (stock) ou de racheter (place libre). Une échoppe vide
         * ou un rachat plein (dernier stock connu à 0) est caché des listes publiques ; stock jamais
         * relevé (-1) : affichée par défaut.
         */
        boolean active() {
            return stock != 0;
        }
    }

    /** Adresse InfluPW vue au dernier passage de l'index. */
    record Adresse(String id, String nom, String titre, UUID proprio, String proprioNom, Location lieu,
                   String villeId, String villeNom, int visites, float note, int prix, String icone,
                   String categorie, boolean ouverte, boolean motDePasse, String description, long creation) {
    }

    /** Matériau Flux d'un objet : {@code itemsadder:ns:id} pour un objet ItemsAdder, sinon vanilla. */
    static String materiau(ItemStack item) {
        if (item == null || item.getType().isAir()) return "minecraft:chest";
        if (Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            try {
                dev.lone.itemsadder.api.CustomStack cs = dev.lone.itemsadder.api.CustomStack.byItemStack(item);
                if (cs != null) return "itemsadder:" + cs.getNamespacedID();
            } catch (Throwable ignored) {
                // API ItemsAdder pas encore prête : objet vanilla de repli
            }
        }
        return item.getType().getKey().toString();
    }

    /**
     * Nom à afficher : nom personnalisé de l'objet (couleurs &amp;), sinon « lang:clé » que
     * Flux remplace par le nom Minecraft traduit dans la langue du joueur.
     */
    static String nomObjet(ItemStack item) {
        if (item == null) return "?";
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return LegacyComponentSerializer.legacyAmpersand().serialize(meta.displayName());
        }
        return "lang:" + item.getType().translationKey();
    }

    /** Étoiles d'une note sur 5 (or pour les pleines, gris pour les vides). */
    static String etoiles(float note) {
        int pleines = Math.max(0, Math.min(5, Math.round(note)));
        return "&#FFD94D" + "★".repeat(pleines) + "&8" + "★".repeat(5 - pleines);
    }

    static String coord(Location l) {
        if (l == null) return "?";
        return l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }
}
