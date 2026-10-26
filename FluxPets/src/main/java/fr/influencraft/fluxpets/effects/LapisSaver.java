package fr.influencraft.fluxpets.effects;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.inventory.EnchantingInventory;
import org.bukkit.inventory.ItemStack;

/**
 * Orbe d'Expérience : chance qu'un enchantement à la table ne coûte pas de lapis. La table retire le lapis
 * juste après l'événement ; le tick suivant, on rend exactement ce qui a disparu de la case (rien en créatif,
 * où la table n'en consomme pas). dont_consume_lapis_chance de libreforge rendait « bouton + 1 » lapis quoi
 * qu'il arrive : en créatif, enchanter au niveau 30 avec 1 lapis en donnait 3 de plus.
 * Si la table a été fermée entre-temps (le lapis restant est revenu au joueur), le remboursement va dans
 * l'inventaire du joueur (au sol s'il est plein).
 */
final class LapisSaver extends PetEffect {

    LapisSaver(PetEffects manager) {
        super(manager, "lapis-saver");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Player player = event.getEnchanter();
        if (player.getGameMode() == GameMode.CREATIVE || !(event.getInventory() instanceof EnchantingInventory inv)
                || !roll(level(player))) {
            return;
        }
        int before = lapis(inv.getSecondary());
        Bukkit.getScheduler().runTask(manager.plugin(), () -> {
            boolean open = player.getOpenInventory().getTopInventory() == inv;
            int after = open ? lapis(inv.getSecondary()) : 0;
            int consumed = open ? before - after : Math.min(before, event.whichButton() + 1);
            if (consumed <= 0 || !player.isOnline()) {
                return;
            }
            if (open) {
                ItemStack stack = new ItemStack(Material.LAPIS_LAZULI, Math.min(64, after + consumed));
                inv.setSecondary(stack);
            } else {
                player.getInventory().addItem(new ItemStack(Material.LAPIS_LAZULI, consumed)).values()
                        .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
            }
        });
    }

    private static int lapis(ItemStack stack) {
        return stack != null && stack.getType() == Material.LAPIS_LAZULI ? stack.getAmount() : 0;
    }
}
