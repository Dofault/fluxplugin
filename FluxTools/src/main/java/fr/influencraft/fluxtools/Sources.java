package fr.influencraft.fluxtools;

import fr.influencraft.flux.api.FluxApi;
import org.bukkit.inventory.ItemStack;

/** Source d'objet Flux {@code source:fluxitems:main} : l'outil tenu en main, tel quel. */
final class Sources {

    private Sources() {
    }

    static void inscrire(FluxTools plugin) {
        FluxApi.registerItemSource(plugin, "fluxitems", (p, arg) -> {
            ItemStack main = p.getInventory().getItemInMainHand();
            return plugin.outil(main) == null ? null : main.clone();
        });
    }

    static void retirer() {
        FluxApi.unregisterItemSource("fluxitems");
    }
}
