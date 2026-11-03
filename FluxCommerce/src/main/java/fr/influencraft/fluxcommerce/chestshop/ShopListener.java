package fr.influencraft.fluxcommerce.chestshop;

import fr.influencraft.flux.api.shops.ChestShops;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * What happens around a shop's container:
 * <ul>
 *   <li>right click on the container or its sign: the owner and his friends open it (to fill it), even where the claim would refuse a passer-by;
 *       anyone else trades in Flux's trade window. Sneak + right click by the owner (or the staff): settings;</li>
 *   <li>sneak + right click on a container that is no shop, with an item in hand: creation window, where the player
 *       may open that container (claim) and the protection plugins did not refuse the click;</li>
 *   <li>breaking: owner, staff, or whoever another plugin allows ({@link ChestShops#allowRemoval}, FluxLands' town
 *       rule); the shop goes, its content drops as for any chest. Explosions, endermen and hoppers never touch it,
 *       and no one else can turn it into a double chest;</li>
 *   <li>stock counted when the container is closed and when its chunk loads; displays follow the chunks.</li>
 * </ul>
 */
final class ShopListener implements Listener {

    private final ChestShopModule plugin;

    ShopListener(ChestShopModule plugin) {
        this.plugin = plugin;
    }

    /** Clicks on a shop or its sign, before claim plugins (they would refuse a passer-by or a friend). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void clickShop(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block block = e.getClickedBlock();
        Shop shop = plugin.registry().at(block);
        if (shop == null) shop = plugin.signs().at(block);
        if (shop == null) return;
        Player p = e.getPlayer();
        e.setCancelled(true);
        if (p.isSneaking() && plugin.mayManage(p, shop)) {
            plugin.forms().settings(p, shop);
            return;
        }
        if (shop.mayStock(p.getUniqueId())) {
            var inv = Containers.inventory(shop.block());
            if (inv != null) p.openInventory(inv);
            return;
        }
        if (!p.hasPermission(ChestShopModule.USE)) {
            plugin.messages().send(p, "no-permission");
            return;
        }
        plugin.trades().open(p, shop);
    }

    /** Sneak + right click with an item on a container that is no shop: creation (after the protections spoke). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void clickCreate(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Block block = e.getClickedBlock();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!p.isSneaking() || held.getType().isAir() || block == null || !plugin.isContainer(block)) return;
        if (plugin.registry().at(block) != null || !p.hasPermission(ChestShopModule.CREATE)) return;
        if (!plugin.allowedWorld(block.getWorld())) return;
        // a protection plugin refused to let him use this block: not his to sell from
        if (e.useInteractedBlock() == Event.Result.DENY) return;
        e.setCancelled(true);
        plugin.forms().create(p, block, held);
    }

    /** The sign of a shop stays: the shop is removed from its settings, or by breaking the container. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void breakSign(BlockBreakEvent e) {
        if (plugin.signs().at(e.getBlock()) == null) return;
        e.setCancelled(true);
        plugin.messages().send(e.getPlayer(), "sign-protected");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void breakShop(BlockBreakEvent e) {
        Shop shop = plugin.registry().at(e.getBlock());
        if (shop == null) return;
        Player p = e.getPlayer();
        if (plugin.mayManage(p, shop) || ChestShops.mayRemove(shop.view(), p.getUniqueId())) return;
        e.setCancelled(true);
        plugin.messages().send(p, "not-yours", "%owner%", shop.ownerName == null ? "?" : shop.ownerName);
        Sounds.cue(p, "error");
    }

    /** The break went through: the shop goes when its own block broke (the other half of a double chest keeps it). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void brokenShop(BlockBreakEvent e) {
        Block b = e.getBlock();
        Shop shop = plugin.registry().at(b);
        if (shop == null) return;
        if (shop.x == b.getX() && shop.y == b.getY() && shop.z == b.getZ() && shop.world.equals(b.getWorld().getName())) {
            plugin.removeShop(shop, e.getPlayer());
        } else {
            plugin.host().getServer().getScheduler().runTask(plugin.host(), () -> {
                plugin.registry().count(shop);
                plugin.displays().update(shop);
            });
        }
    }

    /** A chest placed beside a shop chest would join it: only its owner and friends may. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void placeBeside(BlockPlaceEvent e) {
        Material type = e.getBlockPlaced().getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) return;
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Block next = e.getBlockPlaced().getRelative(face);
            if (next.getType() != type) continue;
            Shop shop = plugin.registry().at(next);
            if (shop != null && !shop.mayStock(e.getPlayer().getUniqueId()) && !e.getPlayer().hasPermission(ChestShopModule.ADMIN)) {
                e.setCancelled(true);
                plugin.messages().send(e.getPlayer(), "no-double-chest");
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void explosion(EntityExplodeEvent e) {
        e.blockList().removeIf(this::protectedBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void explosion(BlockExplodeEvent e) {
        e.blockList().removeIf(this::protectedBlock);
    }

    /** A piston would push the sign off (it pops) or move the container. */
    @EventHandler(ignoreCancelled = true)
    public void piston(org.bukkit.event.block.BlockPistonExtendEvent e) {
        if (e.getBlocks().stream().anyMatch(this::protectedBlock)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void piston(org.bukkit.event.block.BlockPistonRetractEvent e) {
        if (e.getBlocks().stream().anyMatch(this::protectedBlock)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void entityChange(EntityChangeBlockEvent e) {
        if (protectedBlock(e.getBlock())) e.setCancelled(true);
    }

    /** A shop's container or sign. */
    private boolean protectedBlock(Block b) {
        return plugin.isContainer(b) && plugin.registry().at(b) != null || plugin.signs().at(b) != null;
    }

    /** Hoppers (and hopper minecarts) never take from a shop: that would empty it without paying. */
    @EventHandler(ignoreCancelled = true)
    public void hopper(InventoryMoveItemEvent e) {
        for (Block b : Containers.blocks(e.getSource().getHolder(false))) {
            if (plugin.registry().at(b) != null) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** The owner or a friend filled or emptied the container: stock counted again. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void close(InventoryCloseEvent e) {
        for (Block b : Containers.blocks(e.getInventory().getHolder(false))) {
            Shop shop = plugin.registry().at(b);
            if (shop == null) continue;
            int before = shop.stock;
            plugin.registry().count(shop);
            if ((before == 0) != (shop.stock == 0)) plugin.displays().update(shop);
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void chunkLoad(ChunkLoadEvent e) {
        plugin.displays().chunkLoaded(e.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void chunkUnload(ChunkUnloadEvent e) {
        plugin.displays().chunkUnloaded(e.getChunk());
    }
}
