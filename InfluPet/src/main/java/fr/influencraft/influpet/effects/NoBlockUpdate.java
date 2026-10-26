package fr.influencraft.influpet.effects;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Piston;
import org.bukkit.block.data.type.PistonHead;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Motte : creuser sans mise à jour de bloc autour (le sable et le gravier au-dessus ne tombent pas, l'eau ne
 * coule pas dans le trou, etc.).
 *
 * Paper lève BlockPhysicsEvent aux deux endroits où un bloc cassé réveille ses voisins (décompilé, 26.2) :
 * NeighborUpdater.executeUpdate (voisin = bloc de l'événement, source = bloc cassé) et Level.setBlock avant
 * updateNeighbourShapes (bloc et source = bloc cassé). Annuler les événements dont la source est un bloc cassé
 * ce tick-ci par un joueur chanceux suffit, sans toucher à la casse elle-même (butin, XP, métiers, protections
 * gardés : rien n'est annulé dans BlockBreakEvent).
 *
 * Un seul tirage par joueur et par tick : les blocs cassés par la Tranchée pendant le même coup suivent le
 * tirage du bloc frappé. Exclus : blocs en deux parties (portes, plantes hautes, lits, coffres doubles,
 * pistons), dont l'autre moitié n'est retirée que par cette mise à jour : la laisser en place donnerait des
 * demi-blocs fantômes.
 */
final class NoBlockUpdate extends PetEffect {

    private record Pos(UUID world, int x, int y, int z) {
        static Pos of(Block b) {
            return new Pos(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }

    private final Set<Pos> suppressed = new HashSet<>();
    /** joueur -> {tick, 1 si le tirage de ce tick a réussi} */
    private final Map<UUID, long[]> rolls = new HashMap<>();
    private boolean clearScheduled;

    NoBlockUpdate(PetEffects manager) {
        super(manager, "no-block-update");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        int level = level(player);
        if (level <= 0 || twoParts(event.getBlock().getBlockData())) {
            return;
        }
        long tick = Bukkit.getCurrentTick();
        long[] last = rolls.get(player.getUniqueId());
        boolean lucky;
        if (last != null && last[0] == tick) {
            lucky = last[1] == 1;
        } else {
            lucky = roll(level);
            rolls.put(player.getUniqueId(), new long[]{tick, lucky ? 1 : 0});
        }
        if (!lucky) {
            return;
        }
        suppressed.add(Pos.of(event.getBlock()));
        if (!clearScheduled) {
            clearScheduled = true;
            // la casse et ses mises à jour ont lieu dans le même tick que l'événement
            Bukkit.getScheduler().runTask(manager.plugin(), () -> {
                suppressed.clear();
                rolls.clear();
                clearScheduled = false;
            });
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhysics(BlockPhysicsEvent event) {
        // appelé pour chaque mise à jour de bloc du serveur : sortie immédiate dans le cas courant
        if (suppressed.isEmpty()) {
            return;
        }
        if (suppressed.contains(Pos.of(event.getSourceBlock()))) {
            event.setCancelled(true);
        }
    }

    private static boolean twoParts(BlockData data) {
        return data instanceof Bisected || data instanceof Bed || data instanceof Piston || data instanceof PistonHead
                || (data instanceof Chest chest && chest.getType() != Chest.Type.SINGLE);
    }
}
