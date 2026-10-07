package fr.influencraft.influpet.effets;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;

/**
 * Lingotin : cuisson plus rapide (+value % de vitesse : durée divisée par 1 + value / 100) dans les fours dont
 * le propriétaire (dernier joueur à y avoir cliqué) est connecté avec ce familier actif. Durée fixée au début
 * de chaque cuisson : retirer le familier en cours ne change que les cuissons suivantes.
 */
final class CuissonRapide extends PetEffect {

    private final FurnaceOwners owners;

    CuissonRapide(PetEffects manager, FurnaceOwners owners) {
        super(manager, "cuisson-rapide");
        this.owners = owners;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onStart(FurnaceStartSmeltEvent event) {
        Player owner = owners.owner(event.getBlock());
        int level = level(owner);
        if (level <= 0) {
            return;
        }
        int total = event.getTotalCookTime();
        event.setTotalCookTime(Math.max(1, (int) Math.round(total / (1 + value(level) / 100))));
    }
}
