package fr.influencraft.influbridge;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.plugin.Plugin;

/**
 * Le menu natif "/jobs" d'EcoJobs reste accessible en interne même une fois /jobs et /job
 * redirigés vers notre menu (commands.yml) : le bouton "retour" du menu de progression d'un
 * métier (JobLevelGUI, ouvert par notre clic gauche) appelle en dur JobsGUI.open(player) côté
 * EcoJobs quand il n'y a pas de page précédente réelle (confirmé sur le vrai code source,
 * github.com/Auxilor/EcoJobs, JobLevelGUI.kt) — aucun hook de config pour ça.
 *
 * Un premier réflexe aurait été d'ajouter "item-inactive" au bouton "page précédente" dans
 * config.yml pour neutraliser ce fallback (ça fonctionne structurellement, confirmé en lisant
 * LayeredComponents.getSlotAt du framework eco — le calque le plus haut avec un slot non-null
 * gagne). Mais EcoJobs (EcoUpdatableConfig.kt, github.com/Auxilor/eco) resynchronise son
 * config.yml sur le template embarqué dans son jar à CHAQUE chargement du plugin, et supprime
 * automatiquement toute clé absente de ce template — "item-inactive" n'existe pas par défaut
 * sur "prev-page", donc toute tentative de l'ajouter en config est effacée au redémarrage
 * suivant. D'où cette interception côté Java à la place, qui ne touche à aucune config et
 * n'est donc pas concernée par ce nettoyage automatique.
 *
 * On intercepte l'ouverture de CE menu natif précis (titre reconnu depuis gui.title de la
 * config EcoJobs, tronqué au premier placeholder pour rester correct si le titre change) et on
 * la redirige vers notre menu — couvre ce retour de progression, et tout futur appel interne à
 * JobsGUI.open() qu'on n'aurait pas prévu. Redirige vers le menu DeluxeMenus /métiers (commande
 * "metiers"), point d'entrée canonique du système de métiers.
 */
public final class NativeJobsMenuRedirectListener implements Listener {

    private final Plugin plugin;
    private final String nativeTitlePrefix;

    public NativeJobsMenuRedirectListener(Plugin plugin) {
        this.plugin = plugin;
        this.nativeTitlePrefix = computeNativeTitlePrefix();
    }

    private static String computeNativeTitlePrefix() {
        String raw = EcoJobsConfig.read().getString("gui.title", "Jobs");
        String stripped = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', raw));
        int placeholderIndex = stripped.indexOf('%');
        return placeholderIndex >= 0 ? stripped.substring(0, placeholderIndex) : stripped;
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (nativeTitlePrefix.isBlank() || !(event.getPlayer() instanceof Player player)) {
            return;
        }

        String title = ChatColor.stripColor(event.getView().getTitle());
        if (title == null || !title.startsWith(nativeTitlePrefix)) {
            return;
        }

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(player, "metiers"));
    }
}
