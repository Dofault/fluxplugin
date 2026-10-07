package fr.influencraft.influpet;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import org.bukkit.Location;


/**
 * Modèle BetterModel d'un familier montré lors d'une adoption (révélation d'un booster). Le familier qui suit son
 * maître est géré par pets.PetDisplay. N'est chargée que si BetterModel est actif.
 */
final class BetterModelPets {

    private BetterModelPets() {
    }

    /**
     * Fait apparaître le modèle à cet endroit pour ce joueur seul et joue l'animation.
     * Renvoie de quoi le retirer, ou null si le modèle n'existe pas.
     */
    static Runnable showcase(org.bukkit.entity.Player player, String model, Location location, String animation) {
        var renderer = BetterModel.modelOrNull(model);
        if (renderer == null) {
            return null;
        }
        var tracker = renderer.create(BukkitAdapter.adapt(location));
        // visible par le joueur et par tous ceux qui sont autour (64 blocs, même monde)
        tracker.spawn(BukkitAdapter.adapt(player));
        for (var other : location.getWorld().getPlayers()) {
            if (other != player && other.getLocation().distanceSquared(location) < 64 * 64) {
                tracker.spawn(BukkitAdapter.adapt(other));
            }
        }
        tracker.animate(animation);
        return () -> {
            if (!tracker.isClosed()) {
                tracker.close();
            }
        };
    }
}
