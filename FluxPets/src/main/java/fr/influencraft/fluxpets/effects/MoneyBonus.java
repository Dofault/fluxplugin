package fr.influencraft.fluxpets.effects;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Licorne Astrale : prix de vente au magasin en plus (value du niveau, en %). Passe par l'API de
 * VaultSupermarket (SupermarketAPI, bonus de prix de vente) : FluxPets s'y inscrit s'il est là, le magasin ne
 * dépend pas d'FluxPets et vend au prix normal sans lui. Vaut pour le bloc de vente et la vente automatique.
 * Le multiplicateur est aussi exposé par %influpet_money_multiplier% (affichage).
 */
final class MoneyBonus extends PetEffect {

    MoneyBonus(PetEffects manager) {
        super(manager, "money-bonus");
        if (Bukkit.getPluginManager().isPluginEnabled("VaultSupermarket")) {
            try {
                SupermarketSellBonus.register(manager.plugin(), this);
            } catch (Throwable t) {
                manager.plugin().getLogger().warning("Sell price pet effect unavailable (VaultSupermarket API): " + t);
            }
        } else {
            manager.plugin().getLogger().info("VaultSupermarket not found: money-bonus pet effect has no effect.");
        }
    }

    double multiplier(Player player) {
        int level = level(player);
        return level <= 0 ? 1 : 1 + value(level) / 100;
    }
}
