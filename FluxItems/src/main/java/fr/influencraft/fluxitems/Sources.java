package fr.influencraft.fluxitems;

import fr.influencraft.fluxmenu.api.FluxMenuApi;
import org.bukkit.inventory.ItemStack;

/** Source d'objet FluxMenu {@code source:fluxitems:main} : l'outil tenu en main, tel quel. */
final class Sources {

    private Sources() {
    }

    static void inscrire(FluxItems plugin) {
        FluxMenuApi.registerItemSource("fluxitems", (p, arg) -> {
            ItemStack main = p.getInventory().getItemInMainHand();
            return plugin.outil(main) == null ? null : main.clone();
        });
    }

    static void retirer() {
        FluxMenuApi.unregisterItemSource("fluxitems");
    }
}
