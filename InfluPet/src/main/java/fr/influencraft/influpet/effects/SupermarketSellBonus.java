package fr.influencraft.influpet.effects;

import io.dofault.supermarket.api.SupermarketAPI;
import org.bukkit.plugin.Plugin;

/** Seule classe qui référence VaultSupermarket : chargée seulement s'il est actif. */
final class SupermarketSellBonus {

    private SupermarketSellBonus() {
    }

    static void register(Plugin plugin, MoneyBonus bonus) {
        SupermarketAPI.registerSellPriceModifier(plugin, (player, item) -> bonus.multiplier(player));
    }
}
