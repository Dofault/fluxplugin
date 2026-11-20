package fr.influencraft.fluxspawner;

import com.destroystokyo.paper.event.entity.PreSpawnerSpawnEvent;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityEnterLoveModeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * What happens to spawners and their mobs in the world: placing, breaking, opening, deaths (kill goals, vanilla
 * experience switch), and the rules that keep a spawner's mobs for players only (no machine kills them, they do not
 * burn, teleport, turn into another mob, split or breed). Kills are counted here on purpose: only a death event knows
 * which spawner made the mob, {@code Effects.count} does not give the entity.
 */
final class SpawnerListener implements Listener {

    private final FluxSpawner plugin;

    SpawnerListener(FluxSpawner plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- blocks

    /** Last word before the placement is final: a protection plugin refusing it has already spoken. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (plugin.items().typeOf(item) == null) return;
        if (!plugin.place(event.getPlayer(), event.getBlockPlaced(), item)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        if (b.getType() != Material.SPAWNER) return;
        Player p = event.getPlayer();
        Spawner s = plugin.spawners().at(b);
        if (s == null) {
            natural(event);
            return;
        }
        if (!plugin.mayManage(p, s)) {
            event.setCancelled(true);
            plugin.send(p, "not-yours", "%owner%", s.ownerName == null ? "?" : s.ownerName);
            Sounds.cue(p, "error");
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
        plugin.pickup(s, p);
    }

    /** A spawner of the world (dungeon, mineshaft): taken as a level 0 spawner with Silk Touch when capture is on. */
    private void natural(BlockBreakEvent event) {
        if (!plugin.settings().vanillaCapture || !(event.getBlock().getState() instanceof CreatureSpawner cs)) return;
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        if (tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) <= 0 || cs.getSpawnedType() == null) return;
        SpawnerType type = null;
        for (SpawnerType t : plugin.settings().types.values()) if (t.entity() == cs.getSpawnedType()) type = t;
        if (type == null) return;
        event.setExpToDrop(0);
        plugin.giveNew(event.getPlayer(), type, 0, 1);
        plugin.send(event.getPlayer(), "captured", "%type%", type.displayName());
    }

    /** Not skipped when cancelled: a claim plugin may refuse clicks on blocks, never on our own spawner's menu. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block b = event.getClickedBlock();
        if (b == null || b.getType() != Material.SPAWNER) return;
        Spawner s = plugin.spawners().at(b);
        if (s == null) return;
        Player p = event.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        // sneaking with a block: let it be placed against the spawner (never a spawn egg: it would change the mob)
        if (p.isSneaking() && hand.getType().isBlock() && !hand.isEmpty()) return;
        event.setCancelled(true);
        if (plugin.mayManage(p, s)) plugin.menus().open(p, s);
        else plugin.send(p, "info-other", plugin.vars(s, plugin.settings().type(s.type)));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> plugin.spawners().at(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> plugin.spawners().at(b) != null);
    }

    // ---------------------------------------------------------------- vanilla spawns

    /** Our blocks never spawn by themselves; natural spawners stay off in the worlds of vanilla.disabled-worlds. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreSpawn(PreSpawnerSpawnEvent event) {
        Block b = event.getSpawnerLocation().getBlock();
        if (plugin.spawners().at(b) != null || vanillaOff(b)) {
            event.setCancelled(true);
            event.setShouldAbortSpawn(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(SpawnerSpawnEvent event) {
        if (event.getSpawner() == null) return;
        Block b = event.getSpawner().getBlock();
        if (plugin.spawners().at(b) != null || vanillaOff(b)) event.setCancelled(true);
    }

    private boolean vanillaOff(Block b) {
        return plugin.settings().vanillaDisabledWorlds.contains(b.getWorld().getName().toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- the spawner's mobs

    private Spawner spawnerOf(Entity e) {
        UUID id = plugin.production().spawnerOf(e);
        return id == null ? null : plugin.spawners().get(id);
    }

    private boolean ours(Entity e) {
        return plugin.production().spawnerOf(e) != null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!ours(dead)) return;
        Spawner s = spawnerOf(dead);
        SpawnerType type = s == null ? null : plugin.settings().type(s.type);
        Player killer = dead.getKiller();
        if (type != null && !type.vanillaXp()) event.setDroppedExp(0);
        if (type != null && !type.loot()) event.getDrops().clear();
        if (killer == null) {
            // not a player: nothing earned (protection off, /kill...)
            event.setDroppedExp(0);
            event.getDrops().clear();
            return;
        }
        if (s != null) plugin.killed(s, type);
    }

    /** Only players hurt a spawner's mobs (and the void, /kill): no machine, lava or cactus kills them. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.settings().protect || !ours(event.getEntity())) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.KILL || cause == EntityDamageEvent.DamageCause.VOID) return;
        if (event instanceof EntityDamageByEntityEvent by && byPlayer(by.getDamager())) return;
        event.setCancelled(true);
    }

    private static boolean byPlayer(Entity damager) {
        if (damager instanceof Player) return true;
        return damager instanceof Projectile pr && pr.getShooter() instanceof Player;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (!(event instanceof EntityCombustByEntityEvent) && ours(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(EntityTeleportEvent event) {
        if (ours(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (ours(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSplit(SlimeSplitEvent event) {
        if (ours(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLove(EntityEnterLoveModeEvent event) {
        if (ours(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (ours(event.getMother()) || ours(event.getFather())) event.setCancelled(true);
    }

    // ---------------------------------------------------------------- players

    /** A player who lost a rank keeps only as many spawners on as his rank now allows (the newest go off). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            List<Spawner> mine = plugin.spawners().placedBy(p.getUniqueId());
            int limit = plugin.settings().activeLimit(p);
            int on = 0;
            int stopped = 0;
            for (Spawner s : mine) {
                if (!p.getName().equals(s.ownerName)) {
                    s.ownerName = p.getName();
                    s.dirty = true;
                }
                if (!s.active) continue;
                if (++on > limit) {
                    s.active = false;
                    plugin.store().save(s);
                    stopped++;
                }
            }
            if (stopped > 0) plugin.send(p, "limit-lowered", "%count%", String.valueOf(stopped), "%limit%", String.valueOf(limit));
        }, 40L);
    }
}
