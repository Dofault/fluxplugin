package fr.influencraft.casino;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * API des jeux de casino (appelée par FluxMenu par réflexion : aucune dépendance de compilation).
 * Un jeu prend la mise avec {@link #placeBet}, puis verse le gain avec {@link #payout} (0 = rien).
 * Montants en jetons entiers. Fil principal uniquement.
 */
public final class Casino {

    private static InfluCasino plugin;

    private Casino() {
    }

    static void init(InfluCasino instance) {
        plugin = instance;
    }

    public static boolean available() {
        return plugin != null && plugin.isEnabled();
    }

    public static long chips(OfflinePlayer player) {
        return plugin.store().chips(player.getUniqueId());
    }

    /** Mise du joueur, bornée aux limites actuelles de la config. */
    public static long bet(OfflinePlayer player) {
        return plugin.clampBet(plugin.store().bet(player.getUniqueId()));
    }

    /** Prend la mise du joueur pour une partie de game : la mise prise, ou -1 si son solde ne suffit pas. */
    public static long placeBet(Player player, String game) {
        long bet = bet(player);
        return plugin.store().add(player.getUniqueId(), player.getName(), -bet, "mise " + game) ? bet : -1;
    }

    /** Verse un gain (jetons) d'une partie de game ; detail = résultat lisible (ex. « x1.5 »). */
    public static void payout(Player player, long amount, String game, String detail) {
        if (amount > 0) plugin.store().add(player.getUniqueId(), player.getName(), amount, "gain " + game + " " + detail);
    }
}
