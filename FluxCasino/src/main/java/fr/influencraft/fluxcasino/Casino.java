package fr.influencraft.fluxcasino;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Chip API of the casino games (FluxCasino's own games, or another plugin's). A game takes the bet with
 * {@link #placeBet}, then pays the gain with {@link #payout} (0 = nothing). Whole chips. Main thread only.
 */
public final class Casino {

    private static FluxCasino plugin;

    private Casino() {
    }

    static void init(FluxCasino instance) {
        plugin = instance;
    }

    public static boolean available() {
        return plugin != null && plugin.isEnabled();
    }

    public static long chips(OfflinePlayer player) {
        return plugin.store().chips(player.getUniqueId());
    }

    /** The player's bet, within the current limits of the config. */
    public static long bet(OfflinePlayer player) {
        return plugin.clampBet(plugin.store().bet(player.getUniqueId()));
    }

    /** Takes the player's bet for a round of {@code game}: the bet taken, or -1 when the balance is too low. */
    public static long placeBet(Player player, String game) {
        long bet = bet(player);
        return plugin.store().add(player.getUniqueId(), player.getName(), -bet, "bet " + game) ? bet : -1;
    }

    /** Pays a gain (chips) of a round of {@code game}; detail = readable result (e.g. "x1.5"). */
    public static void payout(Player player, long amount, String game, String detail) {
        if (amount > 0) plugin.store().add(player.getUniqueId(), player.getName(), amount, "gain " + game + " " + detail);
    }
}
