package fr.influencraft.flux;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Accès à FluxCasino par réflexion ({@code fr.influencraft.fluxcasino.Casino}), sans dépendance de compilation.
 * Partagé par les jeux du casino (Plinko, machine à sous) : la mise est prise au lancer, le gain versé à
 * l'arrivée. Sans FluxCasino, {@link #available()} est faux et les jeux sont gratuits.
 */
final class CasinoLink {

    private final Flux plugin;
    private Method available, placeBet, payout;
    private Object pluginRef;

    CasinoLink(Flux plugin) {
        this.plugin = plugin;
    }

    private boolean resolve() {
        org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin("FluxCasino");
        if (p == null || !p.isEnabled()) return false;
        if (p == pluginRef) return true;
        try {
            Class<?> c = Class.forName("fr.influencraft.fluxcasino.Casino", true, p.getClass().getClassLoader());
            available = c.getMethod("available");
            placeBet = c.getMethod("placeBet", Player.class, String.class);
            payout = c.getMethod("payout", Player.class, long.class, String.class, String.class);
            pluginRef = p;
            return true;
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("FluxCasino présent mais API introuvable : " + e);
            return false;
        }
    }

    boolean available() {
        try {
            return resolve() && (boolean) available.invoke(null);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** Prend la mise du joueur : renvoie son montant, ou -1 si le solde ne suffit pas. */
    long placeBet(Player player, String game) {
        try {
            return (long) placeBet.invoke(null, player, game);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("Mise refusée (FluxCasino) : " + e);
            return -1;
        }
    }

    /** Verse un gain. En cas d'échec, l'erreur est journalisée avec tout le détail pour un remboursement à la main. */
    void payout(Player player, long amount, String game, String detail) {
        try {
            payout.invoke(null, player, amount, game, detail);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().severe("Gain NON versé à " + player.getName() + " (" + amount + " jetons, " + game + " "
                    + detail + ") : " + e);
        }
    }
}
