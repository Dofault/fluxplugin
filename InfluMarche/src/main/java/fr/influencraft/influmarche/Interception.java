package fr.influencraft.influmarche;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Remplace les menus d'AuctionHouse : {@code /ah}, {@code /hdv}, {@code /marche} sans argument
 * ouvrent le marché unifié ; {@code history}, {@code average}, {@code search}, {@code view},
 * {@code sell} sans prix... sont traités par InfluMarche ({@link Commande#entree}). Tout le reste
 * ({@code sell <prix>}, {@code bid <prix>}, administration) passe à AuctionHouse : une commande
 * {@code /hdv} ou {@code /marche} non traitée est réécrite en {@code /ah} (pas besoin d'alias
 * dans commands.yml).
 */
final class Interception implements Listener {

    private final InfluMarche plugin;
    private Set<String> ah = Set.of(), alias = Set.of();

    Interception(InfluMarche plugin) {
        this.plugin = plugin;
        recharger();
    }

    void recharger() {
        ah = minuscules(plugin.getConfig().getStringList("interception.ah"), "ah", "auctionhouse:ah");
        alias = minuscules(plugin.getConfig().getStringList("interception.alias"), "hdv", "marche");
    }

    private static Set<String> minuscules(java.util.List<String> l, String... defaut) {
        Set<String> s = new HashSet<>();
        for (String x : l.isEmpty() ? Arrays.asList(defaut) : l) s.add(x.toLowerCase(Locale.ROOT));
        return s;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void commande(PlayerCommandPreprocessEvent e) {
        if (!plugin.getConfig().getBoolean("interception.actif", true)) return;
        String msg = e.getMessage().substring(1).trim();
        String[] mots = msg.split("\\s+");
        if (mots.length == 0 || mots[0].isEmpty()) return;
        String label = mots[0].toLowerCase(Locale.ROOT);
        boolean estAh = ah.contains(label);
        boolean estAlias = alias.contains(label) || alias.contains(label.replaceFirst("^influmarche:", ""));
        if (!estAh && !estAlias) return;
        String[] args = Arrays.copyOfRange(mots, 1, mots.length);
        if (plugin.commande().entree(e.getPlayer(), args)) {
            e.setCancelled(true);
        } else if (estAlias) {
            e.setMessage("/ah" + (args.length == 0 ? "" : " " + String.join(" ", args)));
        }
    }
}
