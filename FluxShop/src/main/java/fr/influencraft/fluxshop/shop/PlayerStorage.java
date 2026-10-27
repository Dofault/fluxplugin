package fr.influencraft.fluxshop.shop;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Opérations sur l'inventaire de stockage du joueur uniquement (barre rapide + 27 cases) : ni
 * armure, ni seconde main, qu'aucun achat ne doit remplir.
 * <p>
 * Une pile n'est complétée que par un objet strictement identique au modèle (même nom, lore,
 * données de plugin...).
 */
final class PlayerStorage {

    private PlayerStorage() {
    }

    /** @return combien d'exemplaires de ce modèle tiennent encore dans le stockage. */
    static int space(PlayerInventory inventory, ItemStack model) {
        int max = model.getMaxStackSize();
        ItemStack[] storage = inventory.getStorageContents();
        int space = 0;
        for (ItemStack stack : storage) {
            if (stack == null || stack.getType().isAir()) {
                space += max;
            } else if (stack.isSimilar(model)) {
                space += Math.max(0, max - stack.getAmount());
            }
        }
        return space;
    }

    /** @return le nombre d'exemplaires identiques au modèle dans le stockage. */
    static int count(PlayerInventory inventory, ItemStack model) {
        int count = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null && stack.isSimilar(model)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /**
     * Range {@code amount} exemplaires : piles entamées d'abord, puis cases vides.
     *
     * @return ce qui n'a pas pu être rangé (0 si {@link #space} a été vérifié juste avant)
     */
    static int add(PlayerInventory inventory, ItemStack model, int amount) {
        int max = model.getMaxStackSize();
        ItemStack[] storage = inventory.getStorageContents();
        int remaining = amount;

        for (int slot = 0; slot < storage.length && remaining > 0; slot++) {
            ItemStack stack = storage[slot];
            if (stack != null && stack.isSimilar(model) && stack.getAmount() < max) {
                int added = Math.min(remaining, max - stack.getAmount());
                stack.setAmount(stack.getAmount() + added);
                remaining -= added;
            }
        }
        for (int slot = 0; slot < storage.length && remaining > 0; slot++) {
            ItemStack stack = storage[slot];
            if (stack == null || stack.getType().isAir()) {
                int added = Math.min(remaining, max);
                ItemStack copy = model.clone();
                copy.setAmount(added);
                storage[slot] = copy;
                remaining -= added;
            }
        }
        inventory.setStorageContents(storage);
        return remaining;
    }
}
