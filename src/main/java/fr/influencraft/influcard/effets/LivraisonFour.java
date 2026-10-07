package fr.influencraft.influcard.effets;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Fournaise : l'objet qui vient de cuire part directement dans l'inventaire du propriétaire du four (dernier
 * joueur à y avoir cliqué) s'il est connecté, dans le même monde et à « rayon » blocs au plus.
 *
 * Déplacé au tick suivant depuis la case de résultat (l'événement a lieu avant que le résultat n'y soit
 * ajouté) : on ne retire que ce qui y est vraiment et ressemble au résultat, et seulement ce que l'inventaire
 * du joueur a accepté (le reste reste dans le four), donc ni perte ni duplication. L'XP de cuisson reste
 * stockée dans le four, comme en vanilla : elle est donnée au prochain retrait de la case de résultat.
 */
final class LivraisonFour extends PetEffect {

    private final FurnaceOwners owners;

    LivraisonFour(PetEffects manager, FurnaceOwners owners) {
        super(manager, "livraison-four");
        this.owners = owners;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmelt(FurnaceSmeltEvent event) {
        Block block = event.getBlock();
        Player owner = owners.owner(block);
        if (owner == null || owner.getWorld() != block.getWorld()) {
            return;
        }
        double radius = getDouble("rayon", 16);
        if (owner.getLocation().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5)) > radius * radius
                || !roll(level(owner))) {
            return;
        }
        ItemStack result = event.getResult().clone();
        Bukkit.getScheduler().runTask(manager.plugin(), () -> deliver(block, owner, result));
    }

    private void deliver(Block block, Player owner, ItemStack result) {
        if (!owner.isOnline() || !(block.getState(false) instanceof Furnace furnace)) {
            return;
        }
        FurnaceInventory inv = furnace.getInventory();
        ItemStack out = inv.getResult();
        if (out == null || !out.isSimilar(result)) {
            return;
        }
        int amount = Math.min(result.getAmount(), out.getAmount());
        ItemStack moving = out.clone();
        moving.setAmount(amount);
        Map<Integer, ItemStack> left = owner.getInventory().addItem(moving);
        int moved = amount - left.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (moved <= 0) {
            return;
        }
        out.setAmount(out.getAmount() - moved);
        inv.setResult(out.getAmount() <= 0 ? null : out);
    }
}
