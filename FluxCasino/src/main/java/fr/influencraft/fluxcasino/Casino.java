package fr.influencraft.fluxcasino;

import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Origin;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Chip API of the casino games (FluxCasino's own games, or another plugin's). A game takes the bet with
 * {@link #placeBet}, then pays the gain with {@link #payout} (0 = nothing). Whole chips, kept by Flux (currency
 * {@code chips}): every bet and gain lands in the player's account history, merged per game. Main thread only.
 */
public final class Casino {

    private static FluxCasino plugin;

    private Casino() {
    }

    static void init(FluxCasino instance) {
        plugin = instance;
    }

    /** Flux's chips currency (null when Flux's config declares none). */
    static Currency currency() {
        return Economy.get("chips");
    }

    public static boolean available() {
        return plugin != null && plugin.isEnabled() && currency() != null && currency().available();
    }

    public static long chips(OfflinePlayer player) {
        Currency c = currency();
        return c == null ? 0 : (long) c.balance(player);
    }

    /** The player's bet, within the current limits of the config. */
    public static long bet(OfflinePlayer player) {
        return plugin.clampBet(plugin.store().bet(player.getUniqueId()));
    }

    /** Takes the player's bet for a round of {@code game}: the bet taken, or -1 when the balance is too low. */
    public static long placeBet(Player player, String game) {
        long bet = bet(player);
        Currency c = currency();
        return c != null && c.take(player, bet, Origin.of(plugin, game + "_bet").verb("bet")) ? bet : -1;
    }

    /** Pays a gain (chips) of a round of {@code game}; detail = readable result (e.g. "x1.5"), for the log only. */
    public static void payout(Player player, long amount, String game, String detail) {
        Currency c = currency();
        if (amount > 0 && c != null) c.give(player, amount, Origin.of(plugin, game + "_win").verb("won"));
    }
}
