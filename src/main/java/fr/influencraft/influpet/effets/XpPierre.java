package fr.influencraft.influpet.effets;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Caillou : chance de gagner de l'XP vanilla par bloc de pierre miné. Chaque bloc cassé par la Tranchée lève
 * son propre BlockBreakEvent (mine_radius en keep_events), d'où un tirage par bloc du 3x3, ce que libreforge
 * (mine_block non relancé pour ces blocs) ne permet pas. Pierre posée par un joueur exclue.
 */
final class XpPierre extends PetEffect {

    private final PlacedBlocks placed;
    private final Set<Material> blocks = EnumSet.noneOf(Material.class);
    private int xp = 1;

    XpPierre(PetEffects manager, PlacedBlocks placed) {
        super(manager, "xp-pierre");
        this.placed = placed;
    }

    @Override
    protected void onLoad() {
        blocks.clear();
        xp = getInt("xp", 1);
        if (cfg != null) {
            for (String id : cfg.getStringList("blocs")) {
                Material m = Material.matchMaterial(id.toUpperCase(Locale.ROOT));
                if (m != null) {
                    blocks.add(m);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!blocks.contains(event.getBlock().getType()) || placed.wasPlaced(event)) {
            return;
        }
        Player player = event.getPlayer();
        if (roll(level(player))) {
            player.giveExp(xp, true);
        }
    }
}
