package fr.influencraft.fluxenchant;

import net.kyori.adventure.sound.Sound;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Beehive;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * "Cisaille" (EcoEnchants id {@code cisaille}, defined in its enchants folder): a hoe that also does what shears do.
 * Breaking a block drops what shears would (leaves, grass, vines, cobweb...), shearing an animal works (sheep,
 * mooshroom, snow golem, bogged), a pumpkin is carved and a full beehive gives its honeycomb. The behaviour lives
 * here because no libreforge effect gives a block the drops of another tool.
 */
final class Shears implements Listener {

    private static final NamespacedKey KEY = NamespacedKey.minecraft("cisaille");
    private static final ItemStack SHEARS = new ItemStack(Material.SHEARS);

    private static boolean shearing(ItemStack hand) {
        if (hand == null || !hand.getType().name().endsWith("_HOE")) return false;
        Enchantment e = Registry.ENCHANTMENT.get(KEY);
        return e != null && hand.getEnchantmentLevel(e) > 0;
    }

    /** Drops of shears for the blocks being broken, read before the block is gone, applied when it drops. */
    private final java.util.Map<Block, Collection<ItemStack>> pending = new java.util.HashMap<>();

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || !event.isDropItems()) return;
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!shearing(hand)) return;
        Block block = event.getBlock();
        Collection<ItemStack> sheared = block.getDrops(SHEARS, player);
        if (sameItems(sheared, block.getDrops(hand, player))) return;
        pending.put(block, sheared);
    }

    /**
     * The drops are swapped in the drop event itself, first of all, with entities the server spawns afterwards: every
     * plugin that reads drops (Télépathie, auto-sell) sees the drops of the shears as if they were the normal ones.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(org.bukkit.event.block.BlockDropItemEvent event) {
        Collection<ItemStack> sheared = pending.remove(event.getBlock());
        if (sheared == null) return;
        Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        event.getItems().clear();
        for (ItemStack drop : sheared) {
            org.bukkit.entity.Item item = event.getBlock().getWorld().createEntity(at, org.bukkit.entity.Item.class);
            item.setItemStack(drop);
            event.getItems().add(item);
        }
    }

    /** A break cancelled after we read it never drops: forget it at the end of the tick. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakDone(BlockBreakEvent event) {
        if (event.isCancelled() || !event.isDropItems()) pending.remove(event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!shearing(player.getInventory().getItemInMainHand())) return;
        if (!(event.getRightClicked() instanceof io.papermc.paper.entity.Shearable target) || !target.readyToBeSheared()) return;
        event.setCancelled(true);
        target.shear(Sound.Source.PLAYER);
        player.damageItemStack(EquipmentSlot.HAND, 1);
        player.swingMainHand();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        if (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return;
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null || !shearing(player.getInventory().getItemInMainHand())) return;
        Location at = block.getLocation().add(0.5, 1, 0.5);
        if (block.getType() == Material.PUMPKIN) {
            BlockFace face = event.getBlockFace().getModY() != 0 ? player.getFacing().getOppositeFace() : event.getBlockFace();
            block.setType(Material.CARVED_PUMPKIN);
            if (block.getBlockData() instanceof Directional d) {
                d.setFacing(face);
                block.setBlockData(d);
            }
            block.getWorld().dropItemNaturally(at, new ItemStack(Material.PUMPKIN_SEEDS, 4));
            block.getWorld().playSound(block.getLocation(), org.bukkit.Sound.BLOCK_PUMPKIN_CARVE, 1f, 1f);
        } else if (block.getBlockData() instanceof Beehive hive && hive.getHoneyLevel() >= hive.getMaximumHoneyLevel()) {
            hive.setHoneyLevel(0);
            block.setBlockData(hive);
            block.getWorld().dropItemNaturally(at, new ItemStack(Material.HONEYCOMB, 3));
            block.getWorld().playSound(block.getLocation(), org.bukkit.Sound.BLOCK_BEEHIVE_SHEAR, 1f, 1f);
        } else {
            return;
        }
        event.setCancelled(true);
        player.damageItemStack(EquipmentSlot.HAND, 1);
        player.swingMainHand();
    }

    private static boolean sameItems(Collection<ItemStack> a, Collection<ItemStack> b) {
        List<ItemStack> left = new ArrayList<>(a);
        for (ItemStack x : b) {
            if (!left.removeIf(y -> y.isSimilar(x) && y.getAmount() == x.getAmount())) return false;
        }
        return left.isEmpty();
    }
}
