package fr.influencraft.fluxshop.managers;

import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/** Payments of the shop, in the main Flux currency (Vault on our servers). */
public final class Money {

    private Money() {
    }

    /** Pays a sale; false (and a warning) when no currency is available or the payment was refused. */
    public static boolean give(OfflinePlayer player, double amount) {
        Currency money = Economy.main();
        if (money != null && money.give(player, amount)) return true;
        Bukkit.getLogger().warning("[FluxShop] Payment of " + amount + " to " + player.getName() + " failed (no currency or refused)");
        return false;
    }
}
