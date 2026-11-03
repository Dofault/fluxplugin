package fr.influencraft.fluxcommerce.chestshop;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** The container of a shop and its content: counting, room, taking and adding the traded item. */
final class Containers {

    private Containers() {
    }

    /** Live inventory of a container block (both halves of a double chest), or null when the block is not one. */
    static Inventory inventory(Block block) {
        if (block == null) return null;
        BlockState state = block.getState(false);
        return state instanceof Container c ? c.getInventory() : null;
    }

    /** The block and, for a double chest, its other half. */
    static List<Block> halves(Block block) {
        List<Block> out = new ArrayList<>(2);
        out.add(block);
        if (block.getState(false) instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder(false);
            if (holder instanceof DoubleChest dc) {
                for (InventoryHolder side : new InventoryHolder[]{dc.getLeftSide(false), dc.getRightSide(false)}) {
                    if (side instanceof Chest c && !c.getBlock().equals(block)) out.add(c.getBlock());
                }
            }
        }
        return out;
    }

    /** Container blocks of an inventory's holder (a chest, both halves of a double chest, a barrel...). */
    static List<Block> blocks(InventoryHolder holder) {
        List<Block> out = new ArrayList<>(2);
        if (holder instanceof DoubleChest dc) {
            if (dc.getLeftSide(false) instanceof Chest l) out.add(l.getBlock());
            if (dc.getRightSide(false) instanceof Chest r) out.add(r.getBlock());
        } else if (holder instanceof BlockState state) {
            out.add(state.getBlock());
        }
        return out;
    }

    static int count(Inventory inv, Shop shop) {
        int n = 0;
        for (ItemStack i : inv.getStorageContents()) if (shop.matches(i)) n += i.getAmount();
        return n;
    }

    /** How many more of the shop's item the inventory can take. */
    static int room(Inventory inv, Shop shop) {
        int max = shop.item.getMaxStackSize();
        int n = 0;
        for (ItemStack i : inv.getStorageContents()) {
            if (i == null || i.getType().isAir()) n += max;
            else if (shop.matches(i)) n += Math.max(0, max - i.getAmount());
        }
        return n;
    }

    /** Removes {@code amount} matching items (counted before: there are enough). */
    static void take(Inventory inv, Shop shop, int amount) {
        int left = amount;
        ItemStack[] contents = inv.getStorageContents();
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack i = contents[slot];
            if (!shop.matches(i)) continue;
            int used = Math.min(left, i.getAmount());
            left -= used;
            if (used == i.getAmount()) inv.setItem(slot, null);
            else i.setAmount(i.getAmount() - used);
        }
    }

    /** {@code amount} items of the model, in full stacks. */
    static List<ItemStack> stacks(ItemStack model, int amount) {
        List<ItemStack> out = new ArrayList<>();
        int max = Math.max(1, model.getMaxStackSize());
        while (amount > 0) {
            ItemStack s = model.clone();
            s.setAmount(Math.min(max, amount));
            amount -= s.getAmount();
            out.add(s);
        }
        return out;
    }

    /** Puts the items in; returns what did not fit. */
    static List<ItemStack> add(Inventory inv, List<ItemStack> items) {
        return new ArrayList<>(inv.addItem(items.toArray(new ItemStack[0])).values());
    }
}
