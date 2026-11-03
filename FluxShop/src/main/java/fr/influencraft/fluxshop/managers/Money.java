package fr.influencraft.fluxshop.managers;

import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Origin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/** Payments of the shop, in the main Flux currency (Vault on our servers). */
public final class Money {

    private Money() {
    }

    /** Origin of the shop's movements in the account history ({@code FluxShop:<key>}: buy, sell, autosell, refund). */
    public static Origin origin(String key) {
        return Origin.of("FluxShop", key);
    }

    /** Pays a sale; false (and a warning) when no currency is available or the payment was refused. */
    public static boolean give(OfflinePlayer player, double amount, String originKey) {
        Currency money = Economy.main();
        if (money != null && money.give(player, amount, origin(originKey))) return true;
        Bukkit.getLogger().warning("[FluxShop] Payment of " + amount + " to " + player.getName() + " failed (no currency or refused)");
        return false;
    }
}
