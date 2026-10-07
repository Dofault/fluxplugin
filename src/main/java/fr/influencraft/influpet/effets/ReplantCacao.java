package fr.influencraft.influpet.effets;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Cocoa;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Citrouillard : une cabosse de cacao mûre cassée est replantée (même orientation, stade 0) contre une fève :
 * prise dans le butin de la cabosse, sinon dans l'inventaire du joueur, gratuite en créatif ; sans fève, rien.
 * replant_crops de libreforge n'a pas de chance par récolte (effet permanent), d'où ce code.
 *
 * BlockBreakEvent (MONITOR, casse acceptée par les protections) décide et mémorise la cabosse ;
 * BlockDropItemEvent, levé juste après dans le même tick s'il y a un butin, y prélève la fève ; la cabosse est
 * reposée au tick suivant si la case est restée vide et que le tronc de jungle est toujours là. La première
 * version ne passait que par BlockDropItemEvent : rien en créatif (pas de butin, pas d'événement).
 *
 * Avec le familier actif, une cabosse au stade 0 (tout juste plantée) ne se casse pas : on récolte une rangée
 * sans détruire les cabosses replantées.
 */
final class ReplantCacao extends PetEffect {

    private static final class Pending {
        final Player player;
        final Cocoa cocoa;
        boolean paid;

        Pending(Player player, Cocoa cocoa) {
            this.player = player;
            this.cocoa = cocoa;
        }
    }

    private final Map<Block, Pending> pending = new HashMap<>();

    ReplantCacao(PetEffects manager) {
        super(manager, "replant-cacao");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void protectYoung(BlockBreakEvent event) {
        if (event.getBlock().getBlockData() instanceof Cocoa cocoa && cocoa.getAge() == 0
                && level(event.getPlayer()) > 0) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block.getBlockData() instanceof Cocoa cocoa) || cocoa.getAge() < cocoa.getMaximumAge()
                || !roll(level(event.getPlayer()))) {
            return;
        }
        Pending p = new Pending(event.getPlayer(), (Cocoa) cocoa.clone());
        p.paid = event.getPlayer().getGameMode() == GameMode.CREATIVE;
        pending.put(block, p);
        Bukkit.getScheduler().runTask(manager.plugin(), () -> replant(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent event) {
        Pending p = pending.get(event.getBlock());
        if (p == null || p.paid) {
            return;
        }
        for (Iterator<Item> it = event.getItems().iterator(); it.hasNext(); ) {
            Item item = it.next();
            ItemStack stack = item.getItemStack();
            if (stack.getType() == Material.COCOA_BEANS) {
                if (stack.getAmount() <= 1) {
                    it.remove();
                } else {
                    stack.setAmount(stack.getAmount() - 1);
                    item.setItemStack(stack);
                }
                p.paid = true;
                return;
            }
        }
    }

    private void replant(Block block) {
        Pending p = pending.remove(block);
        if (p == null || !block.getType().isAir()
                || !Tag.JUNGLE_LOGS.isTagged(block.getRelative(p.cocoa.getFacing()).getType())) {
            return;
        }
        if (!p.paid) {
            if (!p.player.isOnline() || !p.player.getInventory().containsAtLeast(new ItemStack(Material.COCOA_BEANS), 1)) {
                return;
            }
            p.player.getInventory().removeItem(new ItemStack(Material.COCOA_BEANS, 1));
        }
        Cocoa replant = (Cocoa) Material.COCOA.createBlockData();
        replant.setFacing(p.cocoa.getFacing());
        replant.setAge(0);
        block.setBlockData(replant);
    }
}
