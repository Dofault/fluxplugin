package fr.influencraft.influlands;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remplace les menus natifs de Lands par ceux d'InfluLands : {@code /lands}, {@code /v},
 * {@code /ville}... sans argument (ou {@code menu}) ouvrent Ma ville, {@code bank} la Banque,
 * {@code rental list} et {@code menu rentals} l'Agence, {@code area} et
 * {@code menu land areas} les Parcelles ; {@code /nations} et {@code /n} (ou {@code menu}) ouvrent
 * Mon pays. Toutes les autres sous-commandes passent telles
 * quelles. Le bouton « Menu complet » d'InfluLands ({@code /influlands natif}) laisse passer
 * une seule commande pour atteindre les menus natifs non refaits.
 */
final class Interception implements Listener {

    private final InfluLands plugin;
    private final Map<UUID, Long> passe = new ConcurrentHashMap<>();
    private Set<String> alias = Set.of();
    private Set<String> aliasPays = Set.of();
    private boolean actif;

    Interception(InfluLands plugin) {
        this.plugin = plugin;
        recharger();
    }

    void recharger() {
        actif = plugin.getConfig().getBoolean("menus-lands.remplacer", true);
        Set<String> s = new HashSet<>();
        for (String a : plugin.getConfig().getStringList("menus-lands.alias")) s.add(a.toLowerCase(Locale.ROOT));
        if (s.isEmpty()) s.addAll(List.of("lands", "v", "ville", "villes"));
        alias = s;
        Set<String> n = new HashSet<>();
        for (String a : plugin.getConfig().getStringList("menus-lands.alias-pays")) n.add(a.toLowerCase(Locale.ROOT));
        if (n.isEmpty()) n.addAll(List.of("nations", "n"));
        aliasPays = n;
    }

    /** La prochaine commande Lands du joueur (dans la seconde) atteint le menu natif. */
    void laisserPasser(Player p) {
        passe.put(p.getUniqueId(), System.currentTimeMillis() + 1000);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void commande(PlayerCommandPreprocessEvent e) {
        if (!actif) return;
        Player p = e.getPlayer();
        Long jusqua = passe.remove(p.getUniqueId());
        if (jusqua != null && jusqua >= System.currentTimeMillis()) return;
        String[] mots = e.getMessage().substring(1).trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (mots.length == 0) return;
        String label = mots[0].startsWith("lands:") ? mots[0].substring(6) : mots[0];
        String reste = String.join(" ", java.util.Arrays.copyOfRange(mots, 1, mots.length));
        if (aliasPays.contains(label)) {
            String cible = switch (reste) {
                case "", "menu", "menu nation", "menu nations" -> "pays";
                case "menu nation lands" -> "pays_villes";
                default -> null;
            };
            if (cible == null) return;
            e.setCancelled(true);
            p.performCommand("influlands ouvrir " + cible);
            return;
        }
        if (!alias.contains(label)) return;
        String cible = switch (reste) {
            case "", "menu", "menu land", "menu here" -> "ville";
            case "bank" -> "banque";
            case "rental list", "rentals", "rentlist", "menu rentals" -> "agence";
            case "area", "area menu", "menu land areas" -> "parcelles";
            default -> null;
        };
        if (cible == null) return;
        e.setCancelled(true);
        p.performCommand("influlands ouvrir " + cible);
    }
}
