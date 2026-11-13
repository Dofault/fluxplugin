package fr.influencraft.fluxcrate;

import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.fluxcrate.compat.EcoCratesKeys;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;

/** Crate keys: items carrying {@code fluxcrate:key = <crate id>} (and the former EcoCrates keys, see compat). */
final class Keys {

    static final NamespacedKey KEY = new NamespacedKey("fluxcrate", "key");

    private Keys() {
    }

    static ItemStack create(Crate crate, int amount) {
        ItemStack item = Items.of(crate.keyItem(), crate.keyName(), crate.keyLore(), null);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, crate.id());
            item.setItemMeta(meta);
        }
        item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), amount)));
        return item;
    }

    /** Crate id of a key, null when the item is not a key. */
    static String crateOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        return id != null ? id : EcoCratesKeys.crateOf(item);
    }

    /** Keys of this crate the player carries. */
    static int count(Player p, String crate) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (crate.equals(crateOf(it))) n += it.getAmount();
        ItemStack off = p.getInventory().getItemInOffHand();
        if (crate.equals(crateOf(off))) n += off.getAmount();
        return n;
    }

    /** Takes {@code amount} keys of this crate (hand first); false, and nothing taken, when he has fewer. */
    static boolean take(Player p, String crate, int amount) {
        if (count(p, crate) < amount) return false;
        PlayerInventory inv = p.getInventory();
        int left = amount;
        ItemStack hand = inv.getItemInMainHand();
        if (crate.equals(crateOf(hand))) left = consume(hand, left);
        for (int slot = 0; slot < inv.getSize() && left > 0; slot++) {
            ItemStack it = inv.getItem(slot);
            if (crate.equals(crateOf(it))) left = consume(it, left);
        }
        return true;
    }

    private static int consume(ItemStack it, int left) {
        int used = Math.min(left, it.getAmount());
        it.setAmount(it.getAmount() - used);
        return left - used;
    }

    /** Gives keys (stacks as big as the item allows); what does not fit is kept in /recompenses by Flux. */
    static void give(Player p, Crate crate, int amount) {
        int left = amount;
        while (left > 0) {
            ItemStack stack = create(crate, left);
            left -= stack.getAmount();
            fr.influencraft.flux.api.inventory.Inventories.giveOrDrop(p, java.util.List.of(stack));
        }
    }
}
