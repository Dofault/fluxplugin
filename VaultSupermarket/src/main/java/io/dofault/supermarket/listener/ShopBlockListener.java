package io.dofault.supermarket.listener;

import io.dofault.supermarket.managers.LangManager;
import io.dofault.supermarket.managers.SellManager;
import io.dofault.supermarket.shop.Shop;
import io.dofault.supermarket.shop.ShopEditor;
import io.dofault.supermarket.shop.ShopMenu;
import io.dofault.supermarket.shop.ShopRegistry;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;

/**
 * Clic droit sur un coffre magasin : ouvre son menu d'achat à la place du bloc lui-même (un
 * coffre magasin ne s'ouvre jamais) ; accroupi, un admin ouvre l'éditeur du coffre. Clic sur un bloc de vente : menu de vente (droit) ou
 * consultation des prix (gauche). Les deux sont assignés par commande, à une position précise.
 * <p>
 * Priorité LOWEST : le clic est traité et annulé avant les protections de zone. WorldGuard
 * (décompilé : {@code EventAbstractionListener.onPlayerInteract}) saute sa vérification quand
 * {@code useInteractedBlock} vaut déjà DENY, donc il n'affiche plus « you can't open that here » à
 * chaque ouverture du magasin. L'annulation empêche aussi le bloc d'agir (coffre qui s'ouvre, bloc
 * posé contre...), et le menu s'ouvre quelle que soit la protection.
 * <p>
 * Un bloc assigné est indestructible : il faut d'abord le désassigner
 * ({@code /supermarket removechest} ou {@code removesellblock}).
 */
public class ShopBlockListener implements Listener {

    private final LangManager lang;
    private final ShopRegistry registry;
    private final ShopMenu shopMenu;
    private final ShopEditor shopEditor;
    private final SellManager sellManager;

    public ShopBlockListener(LangManager lang, ShopRegistry registry, ShopMenu shopMenu, ShopEditor shopEditor,
            SellManager sellManager) {
        this.lang = lang;
        this.registry = registry;
        this.shopMenu = shopMenu;
        this.shopEditor = shopEditor;
        this.sellManager = sellManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Player player = event.getPlayer();
        Action action = event.getAction();

        Shop shop = registry.getShop(block);
        if (shop != null) {
            if (action == Action.RIGHT_CLICK_BLOCK) {
                event.setCancelled(true);
                // Bloc remplacé depuis l'enregistrement (ex. coffre cassé au WorldEdit puis
                // reposé autrement) : on n'ouvre rien, l'admin doit le réenregistrer.
                if (player.isSneaking() && player.hasPermission("supermarket.admin")) {
                    shopEditor.open(player, shop); // accroupi + clic droit : éditeur admin
                } else if (registry.isAllowedBlock(block)) {
                    shopMenu.open(player, shop);
                }
            }
            return;
        }

        if (registry.isSellBlock(block)) {
            if (action == Action.RIGHT_CLICK_BLOCK) {
                event.setCancelled(true);
                sellManager.openSellMenu(player);
            } else if (action == Action.LEFT_CLICK_BLOCK) {
                event.setCancelled(true);
                sellManager.openPriceList(player);
            }
        }
    }

    // ------------------------------------------------------------------
    // Protection des blocs magasins
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (registry.isAssigned(event.getBlock())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(lang.get(event.getPlayer().hasPermission("supermarket.admin")
                    ? "shop-block-protected-admin"
                    : "shop-block-protected"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> registry.isAssigned(block));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> registry.isAssigned(block));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (registry.isAssigned(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (registry.isAssigned(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (containsShop(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (containsShop(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    private boolean containsShop(List<Block> blocks) {
        if (registry.isEmpty()) {
            return false;
        }
        for (Block block : blocks) {
            if (registry.isAssigned(block)) {
                return true;
            }
        }
        return false;
    }
}
