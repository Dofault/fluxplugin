package fr.influencraft.influcard.effets;

import com.willfp.eco.util.BlockUtils;
import fr.influencraft.influcard.PetHook;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Bloc posé par un joueur (suivi d'eco, comme le filtre player_placed de libreforge), relevé au tout début de
 * la casse : eco l'oublie pendant le traitement de l'événement. Évite le farm pose / casse.
 * Les casses imbriquées (Tranchée : blocs voisins cassés pendant l'événement du bloc frappé) ont chacune leur
 * entrée, d'où un ensemble par identité d'événement, vidé au tick suivant.
 */
final class PlacedBlocks implements Listener {

    private final Plugin plugin;
    private final Set<BlockBreakEvent> placed = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean clearScheduled;

    PlacedBlocks(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void mark(BlockBreakEvent event) {
        // seuls les joueurs avec un familier actif peuvent en avoir besoin
        if (PetHook.activeId(event.getPlayer()) == null || !BlockUtils.isPlayerPlaced(event.getBlock())) {
            return;
        }
        placed.add(event);
        if (!clearScheduled) {
            clearScheduled = true;
            Bukkit.getScheduler().runTask(plugin, () -> {
                placed.clear();
                clearScheduled = false;
            });
        }
    }

    /** Valable pendant tout le traitement de l'événement (MONITOR compris). */
    boolean wasPlaced(BlockBreakEvent event) {
        return placed.contains(event);
    }
}
