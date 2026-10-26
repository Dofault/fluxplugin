package fr.influencraft.influpet.effects;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Pot de Peinture : la poudre de béton posée devient du béton aussitôt. Changée au tick suivant (après les
 * protections et les autres plugins), et seulement si la case contient encore la même poudre : la chute d'une
 * poudre posée en l'air n'est programmée que 2 ticks après la pose (FallingBlock), donc elle n'a pas encore pu
 * tomber ; si elle a disparu entre-temps, rien n'est créé (pas de duplication).
 */
final class InstantConcrete extends PetEffect {

    InstantConcrete(PetEffects manager) {
        super(manager, "instant-concrete");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Material powder = event.getBlockPlaced().getType();
        if (!powder.name().endsWith("_CONCRETE_POWDER")) {
            return;
        }
        Material concrete = Material.getMaterial(powder.name().replace("_POWDER", ""));
        if (concrete == null || !roll(level(event.getPlayer()))) {
            return;
        }
        Block block = event.getBlockPlaced();
        Bukkit.getScheduler().runTask(manager.plugin(), () -> {
            if (block.getType() == powder) {
                block.setType(concrete);
            }
        });
    }
}
