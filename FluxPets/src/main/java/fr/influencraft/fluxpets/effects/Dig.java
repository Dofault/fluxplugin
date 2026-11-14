package fr.influencraft.fluxpets.effects;

import fr.influencraft.flux.api.claims.Claims;
import fr.influencraft.flux.api.inventory.Inventories;
import fr.influencraft.fluxpets.pets.PetMessages;
import fr.influencraft.fluxpets.pets.event.PetMountMoveEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code dig}: ridden from its {@code from-level} (the max level by default), the pet digs its way: every block of a
 * {@code size} x {@code size} face in front of it (or under it when the rider looks down) is broken as it moves,
 * drops in the rider's inventory. Never in the worlds of {@code blocked-worlds}, in a claim the rider is not a member
 * of, nor blocks harder than {@code max-hardness}, unbreakable or holding data (containers, spawners, signs...).
 * Broken without a block break event on purpose: jobs do not pay for them (25 blocks at a time would flood the job
 * economy), the drops are the reward. The pet is slowed down ({@code speed-factor}) while it digs.
 */
final class Dig extends PetEffect {

    private final Map<UUID, Long> last = new HashMap<>();

    Dig(PetEffects manager) {
        super(manager, "dig");
    }

    @EventHandler
    public void onMove(PetMountMoveEvent event) {
        if (petId == null || !event.getPet().id().equals(petId)) return;
        Player player = event.getPlayer();
        int level = level(player);
        if (level <= 0 || level < getInt("from-level", manager.maxLevel())) return;
        long now = org.bukkit.Bukkit.getCurrentTick();
        Long previous = last.get(player.getUniqueId());
        if (previous != null && now - previous < getInt("interval-ticks", 3)) {
            event.setSpeedFactor(getDouble("speed-factor", 0.5));
            return;
        }
        Location at = event.getAt();
        World world = at.getWorld();
        if (blockedWorld(world)) return;
        int size = Math.max(1, getInt("size", 5));
        int half = size / 2;
        Vector dir = event.getDirection();
        boolean down = player.getLocation().getPitch() > getDouble("down-pitch", 50);
        List<Block> blocks = new ArrayList<>();
        if (down) {
            int y = (int) Math.floor(at.getY() - 0.5);
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    blocks.add(world.getBlockAt((int) Math.floor(at.getX()) + dx, y, (int) Math.floor(at.getZ()) + dz));
                }
            }
        } else {
            double reach = getDouble("reach", 1.6);
            Vector right = new Vector(-dir.getZ(), 0, dir.getX());
            Location front = at.clone().add(dir.clone().multiply(reach));
            int base = (int) Math.floor(at.getY() + 0.01);
            for (int w = -half; w <= half; w++) {
                for (int h = 0; h < size; h++) {
                    Location l = front.clone().add(right.clone().multiply(w));
                    blocks.add(world.getBlockAt(l.getBlockX(), base + h, l.getBlockZ()));
                }
            }
        }
        int broken = 0;
        boolean refused = false;
        List<ItemStack> drops = new ArrayList<>();
        for (Block block : blocks) {
            if (!diggable(block)) continue;
            if (!allowed(block, player)) {
                refused = true;
                continue;
            }
            drops.addAll(block.getDrops(tool(block), player));
            world.spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3,
                    block.getBlockData());
            block.setType(Material.AIR, false);
            broken++;
        }
        if (refused && broken == 0) {
            actionBarOnce(player);
        }
        if (broken == 0) return;
        last.put(player.getUniqueId(), now);
        event.setSpeedFactor(getDouble("speed-factor", 0.5));
        world.playSound(at, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.9f, 0.8f);
        for (ItemStack left : Inventories.give(player, drops)) {
            world.dropItemNaturally(player.getLocation(), left);
        }
    }

    private final Map<UUID, Long> warned = new HashMap<>();

    private void actionBarOnce(Player player) {
        long now = System.currentTimeMillis();
        Long before = warned.get(player.getUniqueId());
        if (before != null && now - before < 5000) return;
        warned.put(player.getUniqueId(), now);
        PetMessages.send(player, "dig-blocked");
    }

    private boolean blockedWorld(World world) {
        for (String name : cfg == null ? List.<String>of() : cfg.getStringList("blocked-worlds")) {
            if (name.equalsIgnoreCase(world.getName())) return true;
        }
        return false;
    }

    private boolean diggable(Block block) {
        Material type = block.getType();
        if (type.isAir() || block.isLiquid()) return false;
        float hardness = type.getHardness();
        if (hardness < 0 || hardness > getDouble("max-hardness", 50)) return false;
        return !(block.getState(false) instanceof TileState);
    }

    /** Wild land, or a claim the rider belongs to. */
    private static boolean allowed(Block block, Player player) {
        if (!Claims.available()) return true;
        Location l = block.getLocation();
        return Claims.claimId(l) == null || Claims.member(l, player.getUniqueId());
    }

    /** The right tool for the block, so it drops what a player mining it would get (no enchantment). */
    private static ItemStack tool(Block block) {
        Material type = block.getType();
        if (Tag.MINEABLE_PICKAXE.isTagged(type)) return new ItemStack(Material.NETHERITE_PICKAXE);
        if (Tag.MINEABLE_SHOVEL.isTagged(type)) return new ItemStack(Material.NETHERITE_SHOVEL);
        if (Tag.MINEABLE_AXE.isTagged(type)) return new ItemStack(Material.NETHERITE_AXE);
        if (Tag.MINEABLE_HOE.isTagged(type)) return new ItemStack(Material.NETHERITE_HOE);
        return new ItemStack(Material.AIR);
    }

    @Override
    protected void onLoad() {
        last.clear();
        warned.clear();
    }
}
