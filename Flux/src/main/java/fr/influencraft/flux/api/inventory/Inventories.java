package fr.influencraft.flux.api.inventory;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Inventory checks shared by the suite. The rule of the suite: nothing a plugin hands out ever falls on the
 * ground; check {@link #fits} first, then {@link #give}.
 *
 * <p>Only the 36 storage slots (hotbar and main inventory) count: armour, off-hand and cursor are never used.</p>
 */
public final class Inventories {

    private Inventories() {
    }

    /** Empty storage slots. */
    public static int freeSlots(Player player) {
        int free = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType().isAir()) free++;
        }
        return free;
    }

    /** Whether all these items fit together (stacking with what the player already has). */
    public static boolean fits(Player player, Collection<ItemStack> items) {
        return missing(player, items) == 0;
    }

    public static boolean fits(Player player, ItemStack... items) {
        return fits(player, List.of(items));
    }

    /** How many of these items (stacks) would not fit, on a copy of the inventory: nothing is changed. */
    public static int missing(Player player, Collection<ItemStack> items) {
        Inventory copy = copyOfStorage(player);
        int missing = 0;
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            Map<Integer, ItemStack> left = copy.addItem(item.clone());
            if (!left.isEmpty()) missing++;
        }
        return missing;
    }

    /**
     * Gives the items. Returns what did not fit (empty list when everything was given); nothing is dropped.
     * Use {@link #fits} first when a partial gift is not acceptable.
     */
    public static List<ItemStack> give(Player player, Collection<ItemStack> items) {
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            left.addAll(player.getInventory().addItem(item.clone()).values());
        }
        return left;
    }

    /** Gives and drops at the player's feet what does not fit (instant pick-up): last resort only. */
    public static void giveOrDrop(Player player, Collection<ItemStack> items) {
        for (ItemStack left : give(player, items)) {
            player.getWorld().dropItemNaturally(player.getLocation(), left, drop -> drop.setPickupDelay(0));
        }
    }

    /** Number of items matching in the storage slots. */
    public static int count(Player player, Predicate<ItemStack> matches) {
        int n = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && !item.getType().isAir() && matches.test(item)) n += item.getAmount();
        }
        return n;
    }

    public static int count(Player player, Material material) {
        return count(player, i -> i.getType() == material);
    }

    /** Removes {@code amount} matching items; false (nothing removed) when the player has fewer. */
    public static boolean take(Player player, Predicate<ItemStack> matches, int amount) {
        if (count(player, matches) < amount) return false;
        int left = amount;
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length && left > 0; slot++) {
            ItemStack item = storage[slot];
            if (item == null || item.getType().isAir() || !matches.test(item)) continue;
            int used = Math.min(left, item.getAmount());
            left -= used;
            if (used == item.getAmount()) {
                player.getInventory().setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - used);
            }
        }
        return true;
    }

    /** A detached 36-slot copy of the storage slots, for dry runs. */
    public static Inventory copyOfStorage(Player player) {
        Inventory copy = Bukkit.createInventory(null, 36);
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            if (storage[slot] != null) copy.setItem(slot, storage[slot].clone());
        }
        return copy;
    }
}
