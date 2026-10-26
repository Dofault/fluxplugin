package fr.influencraft.flux.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Fournit l'objet réel d'un bouton : matériau {@code source:<id>:<argument>} (placeholders résolus
 * avant). Sert à afficher un objet complet (enchantements, nom, lore, objet ItemsAdder...) qu'un
 * matériau ne peut pas décrire, ex : les objets en vente du marché (FluxAuctionHouse).
 */
@FunctionalInterface
public interface ItemSource {

    /** Objet à afficher (copié par Flux), ou null pour une case vide. Fil principal. */
    ItemStack item(Player viewer, String argument);
}
