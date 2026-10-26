package fr.influencraft.influpet.effects;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.MangrovePropagule;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Souche : poudre d'os sur une pousse, l'arbre pousse d'un seul coup, comme si on avait cliqué autant de fois
 * qu'il le fallait, pour une seule poudre d'os. Block#applyBoneMeal rejoue la poudre d'os vanilla (arbres 2x2,
 * StructureGrowEvent pour les protections, place nécessaire) jusqu'à ce que la pousse change, MAX_TRIES fois au
 * plus : une pousse sans la place de pousser ne coûte que MAX_TRIES essais par clic. Une poudre d'os consommée,
 * comme en vanilla, même si l'arbre n'a pas eu la place.
 */
final class InstantBoneMeal extends PetEffect {

    private static final int MAX_TRIES = 16;

    InstantBoneMeal(PetEffects manager) {
        super(manager, "instant-bone-meal");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.useInteractedBlock() == Event.Result.DENY || event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.BONE_MEAL) {
            return;
        }
        Block block = event.getClickedBlock();
        Material sapling = block.getType();
        if (!Tag.SAPLINGS.isTagged(sapling)
                || (block.getBlockData() instanceof MangrovePropagule propagule && propagule.isHanging())) {
            return;
        }
        Player player = event.getPlayer();
        if (!roll(level(player))) {
            return;
        }
        // la poudre d'os vanilla ne s'applique pas en plus
        event.setCancelled(true);
        for (int i = 0; i < MAX_TRIES && block.getType() == sapling; i++) {
            block.applyBoneMeal(event.getBlockFace());
        }
        EquipmentSlot hand = event.getHand() == null ? EquipmentSlot.HAND : event.getHand();
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemStack held = player.getInventory().getItem(hand);
            if (held.getType() == Material.BONE_MEAL) {
                held.setAmount(held.getAmount() - 1);
                player.getInventory().setItem(hand, held.getAmount() <= 0 ? null : held);
            }
        }
        player.swingHand(hand);
        block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 0.5, 0.5),
                15, 0.4, 0.4, 0.4, 0);
    }
}
