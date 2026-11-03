// COMPAT 2026-11-03: feathers made by ItemsAdder (influrewards:plume_vol_*) already handed out in production; they ran
// « powerfly addflytime » when drunk - remove when no such item is left in the game.
package fr.influencraft.fluxfly.compat;

import fr.influencraft.flux.api.items.ItemUses;
import fr.influencraft.fluxfly.FluxFly;

public final class ItemsAdderFeathers {

    private ItemsAdderFeathers() {
    }

    public static void register(FluxFly plugin) {
        ItemUses.register(plugin, "influrewards:plume_vol_commune", (player, item, id) -> plugin.useFeather(player, "10m"));
        ItemUses.register(plugin, "influrewards:plume_vol_unique", (player, item, id) -> plugin.useFeather(player, "30m"));
    }
}
