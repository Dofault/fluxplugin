package fr.influencraft.influpet;

import com.willfp.eco.core.items.CustomItem;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Boosters exposés au système d'objets d'eco : « influpet:booster_<id> » dans la config de n'importe quel plugin
 * eco (aperçu d'une caisse EcoCrates, récompense, recette...) désigne le vrai booster, avec son nom, sa
 * description et sa texture. Rien n'est recopié ailleurs : changer un booster ici le change partout.
 *
 * L'objet est construit à la demande (pas à l'enregistrement) : ItemsAdder peut ne pas avoir fini de charger
 * quand InfluPet démarre, et un /influpet reload doit se voir sans réenregistrer. C'est la version sans joueur
 * (familiers à adopter marqués « » », ni ✔ ni ✘).
 */
final class EcoItemLookup {

    private EcoItemLookup() {
    }

    /** Appelé à chaque chargement du registre : un objet par booster (réenregistrer remplace). */
    static void register(InfluPet plugin, CardRegistry registry) {
        for (CardRegistry.Booster booster : registry.boosters()) {
            String id = booster.id();
            NamespacedKey key = NamespacedKey.fromString("influpet:booster_" + id);
            if (key == null) {
                plugin.getLogger().warning("Booster " + id + " not exposed to eco item lookup (invalid id).");
                continue;
            }
            new CustomItem(key, item -> isBooster(plugin, item, id), new ItemStack(Material.PAPER)) {
                @Override
                public ItemStack getItem() {
                    CardRegistry.Booster current = registry.booster(id);
                    return current == null ? new ItemStack(Material.PAPER) : registry.boosterItem(current);
                }
            }.register();
        }
    }

    private static boolean isBooster(InfluPet plugin, ItemStack item, String id) {
        return item != null && item.hasItemMeta() && id.equals(item.getItemMeta().getPersistentDataContainer()
                .get(plugin.boosterKey(), PersistentDataType.STRING));
    }
}
