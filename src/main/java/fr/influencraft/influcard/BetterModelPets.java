package fr.influencraft.influcard;

import com.willfp.ecopets.pets.Pet;
import com.willfp.ecopets.pets.entity.PetEntity;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlot;

import java.util.logging.Logger;

/**
 * Familiers EcoPets affichés avec un modèle BetterModel : dans le fichier du familier,
 * entity-texture: "bettermodel:<nom du .bbmodel>". EcoPets ne reçoit qu'une ancre invisible qu'il colle à la
 * caméra du joueur ; le modèle est porté par un autre porte-armure que PetFollowers fait vraiment suivre le joueur.
 * N'est chargée que si EcoPets et BetterModel sont actifs.
 */
final class BetterModelPets {

    private BetterModelPets() {
    }

    private static PetFollowers followers;

    static void register(InfluCard plugin) {
        followers = new PetFollowers(plugin);
        Logger logger = plugin.getLogger();
        PetEntity.registerPetEntity("bettermodel", (pet, model) -> new ModelPet(pet, model, logger));
        logger.info("Familiers BetterModel activés (entity-texture: bettermodel:<modèle>).");
    }

    static void reload() {
        if (followers != null) {
            followers.reload();
        }
    }

    static void shutdown() {
        if (followers != null) {
            followers.shutdown();
        }
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

    private static final class ModelPet extends PetEntity {
        private final String model;
        private final Logger logger;

        ModelPet(Pet pet, String model, Logger logger) {
            super(pet);
            this.model = model;
            this.logger = logger;
        }

        @Override
        public Entity spawn(Location location) {
            // ancre sans modèle : EcoPets la déplace (collée à la caméra), le modèle suit le joueur à part
            ArmorStand anchor = PetFollowers.stand(location);
            org.bukkit.entity.Player owner = PetFollowers.ownerOf(location, getPet().getId());
            if (owner != null && followers != null) {
                followers.attach(owner, getPet().getId(), model, anchor);
                return anchor;
            }
            // propriétaire introuvable : ancien comportement, le modèle sur l'ancre
            var renderer = BetterModel.modelOrNull(model);
            if (renderer == null) {
                logger.warning("Modèle BetterModel introuvable pour le familier " + getPet().getId() + " : " + model);
                return anchor;
            }
            renderer.getOrCreate(BukkitAdapter.adapt(anchor));
            return anchor;
        }
    }
}
