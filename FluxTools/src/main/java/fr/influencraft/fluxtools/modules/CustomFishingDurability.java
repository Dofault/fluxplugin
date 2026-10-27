package fr.influencraft.fluxtools.modules;

import fr.influencraft.flux.api.fishing.Fishing;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Rods with their own durability (custom fishing plugin): the vanilla repair only fills the bar, the real counter is
 * reset through the fishing service of Flux.
 */
final class CustomFishingDurability {

    private CustomFishingDurability() {
    }

    static boolean isCustomDurabilityItem(ItemStack item) {
        return Fishing.hasCustomDurability(item);
    }

    static boolean isDamaged(ItemStack item) {
        return Fishing.damaged(item);
    }

    static boolean restore(Player player, ItemStack item) {
        return Fishing.repair(player, item);
    }
}
