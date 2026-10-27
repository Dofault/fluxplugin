package fr.influencraft.fluxshop.integration;

import dev.lone.itemsadder.api.CustomBlock;
import dev.lone.itemsadder.api.ItemsAdder;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;

/**
 * Lecture de l'identifiant ItemsAdder d'un bloc posé, pour qu'un bloc custom puisse servir de
 * magasin ({@code itemsadder:<namespace>:<id>} dans {@code shop.block-types}). ItemsAdder n'est
 * qu'une dépendance douce : sans lui, aucun bloc n'est reconnu comme bloc ItemsAdder.
 * <p>
 * Seuls les vrais blocs ItemsAdder sont couverts. Les meubles (furniture) sont des entités, pas
 * des blocs : ils ne peuvent pas servir de magasin.
 */
public final class ItemsAdderHook {

    private ItemsAdderHook() {
    }

    public static boolean isEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("ItemsAdder");
    }

    /** @return {@code namespace:id} du bloc ItemsAdder posé ici, ou {@code null}. */
    public static String blockId(Block block) {
        if (!isEnabled()) {
            return null;
        }
        try {
            CustomBlock custom = CustomBlock.byAlreadyPlaced(block);
            return custom == null ? null : custom.getNamespacedID();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** ItemsAdder charge ses blocs après le démarrage : rien ne peut être posé avant. */
    public static boolean areItemsLoaded() {
        if (!isEnabled()) {
            return false;
        }
        try {
            return ItemsAdder.areItemsLoaded();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** @return {@code true} si le bloc ItemsAdder {@code namespace:id} a été posé. */
    public static boolean place(String namespacedId, Block block) {
        if (!isEnabled()) {
            return false;
        }
        try {
            return CustomBlock.place(namespacedId, block.getLocation()) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
