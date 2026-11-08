package fr.influencraft.fluxdungeons;

import fr.influencraft.fluxdungeons.dungeon.Arena;
import fr.influencraft.fluxdungeons.dungeon.Runs;
import fr.influencraft.fluxdungeons.hero.Heroes;
import fr.influencraft.fluxdungeons.mob.Mobs;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityKnockbackEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Everything happening in the dungeon world, and the players coming and going. */
final class DungeonListener implements Listener {

    private final FluxDungeons plugin;
    private final Arena arena;
    private final Runs runs;
    private final Heroes heroes;
    private final Mobs mobs;
    private final Loadouts loadouts;
    private static final int OUT_OF_COMBAT_TICKS = 100;
    /** Tick of the last monster hit taken by each player (grace against chained hits). */
    private final java.util.Map<java.util.UUID, Integer> lastHit = new java.util.HashMap<>();

    DungeonListener(FluxDungeons plugin, Arena arena, Runs runs, Heroes heroes, Mobs mobs, Loadouts loadouts) {
        this.plugin = plugin;
        this.arena = arena;
        this.runs = runs;
        this.heroes = heroes;
        this.mobs = mobs;
        this.loadouts = loadouts;
        Bukkit.getScheduler().runTaskTimer(plugin, this::regenerate, 20L, 20L);
    }

    // ---------------------------------------------------------------- coming and going

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean inArena = arena.isArena(p.getWorld());
        if (!loadouts.hasVault(p) && !inArena) {
            heroes.cleanLeftovers(p);
            return;
        }
        // left a dungeon without getting his things back (disconnect during a crash): give them now
        heroes.cleanLeftovers(p);
        Location back = loadouts.recover(p);
        Location to = back != null ? back : Bukkit.getWorlds().get(0).getSpawnLocation();
        if (inArena || back != null) Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) p.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        runs.leave(e.getPlayer());
        runs.endVisit(e.getPlayer());
        lastHit.remove(e.getPlayer().getUniqueId());
        plugin.parties().leave(e.getPlayer().getUniqueId());
    }

    /** No way out of a run but /dungeon leave: other teleports (homes, spawn, warps) are refused. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (runs.teleporting(p) || runs.runOf(p) == null) return;
        if (e.getTo().getWorld() == e.getFrom().getWorld()) return;
        e.setCancelled(true);
        plugin.messages().send(p, "run.no-teleport");
    }

    /** Safety net: a player of a run found outside the dungeon world leaves his run (and gets his things back). */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (runs.runOf(p) != null && !arena.isArena(p.getWorld()) && !runs.teleporting(p)) runs.leave(p);
    }

    // ---------------------------------------------------------------- abilities

    /** Selecting an ability slot casts it; the weapon stays in hand. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        if (heroes.hero(p) == null) return;
        if (heroes.castSlot(p, e.getNewSlot(), runs.context(p))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (heroes.isAbilityItem(e.getItem())) {
            e.setCancelled(true);
            if (heroes.hero(p) != null && (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)) {
                heroes.castSlot(p, p.getInventory().getHeldItemSlot(), runs.context(p));
            }
            return;
        }
        // containers and switches of the dungeon are decor
        if (arena.isArena(p.getWorld()) && e.getClickedBlock() != null && !p.hasPermission("fluxdungeons.admin")
                && e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock().getType().isInteractable()) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (heroes.isAbilityItem(e.getMainHandItem()) || heroes.isAbilityItem(e.getOffHandItem())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (heroes.isAbilityItem(e.getItemDrop().getItemStack()) || runs.runOf(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || runs.runOf(p) == null) return;
        InventoryType t = e.getInventory().getType();
        if (t != InventoryType.PLAYER && t != InventoryType.CRAFTING && t != InventoryType.CREATIVE) e.setCancelled(true);
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        Projectile projectile = e.getEntity();
        if (projectile.getShooter() instanceof Player p && runs.runOf(p) != null) {
            heroes.abilities().onProjectileHit(projectile, runs.context(p));
        }
    }

    // ---------------------------------------------------------------- combat

    /**
     * Combat is vanilla: players fight with their own gear, so armour, enchantments and libreforge effects apply as
     * anywhere else. Here only: no friendly fire (players and their pets), class abilities, monster scripts.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent e) {
        Player attacker = attacker(e.getDamager());
        Entity victim = e.getEntity();
        // no friendly fire inside a run, pets included
        if (attacker != null && (victim instanceof Player || runs.isPet(victim))) {
            java.util.UUID petOwner = runs.petOwner(victim);
            Player hurt = victim instanceof Player vp ? vp : petOwner == null ? null : Bukkit.getPlayer(petOwner);
            if (hurt == null || runs.runOf(hurt) != null && (runs.sameRun(attacker, hurt) || attacker.equals(hurt))) {
                e.setCancelled(true);
                return;
            }
        }
        if (e.getDamager() instanceof Projectile projectile) {
            double power = heroes.abilities().projectilePower(projectile);
            if (power >= 0) {
                if (attacker == null || !runs.context(attacker).enemy(victim)) {
                    e.setCancelled(true);
                    return;
                }
                e.setDamage(power);
            }
        }
        if (victim instanceof LivingEntity mob && mobs.templateOf(mob) != null) {
            if (mobs.damaged(mob, e)) {
                e.setCancelled(true);
                return;
            }
            if (attacker != null) mobs.damagedByPlayer(mob, attacker, e);
            if (e.getFinalDamage() >= mob.getHealth() && mobs.lethal(mob, attacker)) e.setCancelled(true);
            return;
        }
        // a dungeon monster (or its projectile) hit a player
        if (victim instanceof Player hurt) {
            Entity source = e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity sh ? sh
                    : e.getDamager() instanceof org.bukkit.entity.EvokerFangs f && f.getOwner() != null ? f.getOwner() : e.getDamager();
            if (source instanceof LivingEntity mob && mobs.templateOf(mob) != null) {
                lastHit.put(hurt.getUniqueId(), Bukkit.getCurrentTick());
                mobs.hitPlayer(mob, hurt, e);
            }
        }
    }

    /** Players do not order their dungeon pets around (sitting...). */
    @EventHandler(ignoreCancelled = true)
    public void onPetClick(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (runs.isPet(e.getRightClicked())) e.setCancelled(true);
    }

    /**
     * In a run world only dungeon monsters and pets appear: creatures summoned by something else (enchantments that
     * call golems or blazes...) would fight the bosses for the players.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(org.bukkit.event.entity.CreatureSpawnEvent e) {
        org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r != org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM && r != org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT
                && r != org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM
                && r != org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN) return;
        if (!runs.isDungeonWorld(e.getLocation().getWorld())) return;
        LivingEntity le = e.getEntity();
        if (le.getScoreboardTags().contains(Mobs.TAG) || runs.isPet(le)) return;
        e.setCancelled(true);
    }

    /** A dungeon creeper keeps its potion effects when it blows up (vanilla would leave them in an endless cloud). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(org.bukkit.event.entity.ExplosionPrimeEvent e) {
        if (e.getEntity() instanceof org.bukkit.entity.Creeper c && mobs.templateOf(c) != null) {
            for (org.bukkit.potion.PotionEffect pe : c.getActivePotionEffects()) c.removePotionEffect(pe.getType());
        }
    }

    /** Players never burn in a dungeon, whatever sets them on fire (monster power, script, lava, fire block). */
    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (e.getEntity() instanceof Player p && runs.runOf(p) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerFire(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || runs.runOf(p) == null) return;
        EntityDamageEvent.DamageCause c = e.getCause();
        if (c == EntityDamageEvent.DamageCause.FIRE || c == EntityDamageEvent.DamageCause.FIRE_TICK) {
            e.setCancelled(true);
            p.setFireTicks(0);
        }
    }

    /**
     * No knockback in a dungeon, neither on players nor on dungeon monsters: hits never push. Movements cast on purpose
     * (charge, leap, slam, implosion) set the velocity directly and still work.
     */
    @EventHandler(ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent e) {
        Entity target = e.getEntity();
        if (target instanceof Player p ? runs.runOf(p) != null : mobs.templateOf(target) != null) e.setCancelled(true);
    }

    /**
     * No hunger in a dungeon. Saturation is not refilled: vanilla healing stays slow during a fight; out of combat
     * ({@link #regenerate}) it is fast, as in EliteMobs, so a party recovers between two rooms.
     */
    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p) || runs.runOf(p) == null) return;
        if (e.getFoodLevel() >= p.getFoodLevel()) return;
        e.setCancelled(true);
        p.setFoodLevel(20);
    }

    /** Every second: players of a run who took no monster hit for a while heal quickly (one tick of saturation). */
    void regenerate() {
        int now = Bukkit.getCurrentTick();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (runs.runOf(p) == null || p.isDead()) continue;
            if (p.getFoodLevel() < 20) p.setFoodLevel(20);
            Integer last = lastHit.get(p.getUniqueId());
            if (last != null && now - last < OUT_OF_COMBAT_TICKS) continue;
            if (p.getHealth() < p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()) {
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SATURATION, 1, 0, false, false, false));
            }
        }
    }

    /** Nothing ever lies on the ground in a dungeon world (monster loot, equipment, overflow). */
    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (runs.isDungeonWorld(e.getEntity().getWorld())) e.setCancelled(true);
    }

    /** Lethal damage of any other cause (fire, fall) still plays a boss's death animation. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent) return;
        if (!(e.getEntity() instanceof LivingEntity mob) || mobs.templateOf(mob) == null) return;
        if (mobs.damaged(mob, e)) {
            e.setCancelled(true);
            return;
        }
        if (e.getFinalDamage() >= mob.getHealth() && mobs.lethal(mob, mob.getKiller())) e.setCancelled(true);
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        if (e instanceof PlayerDeathEvent) return;
        LivingEntity mob = e.getEntity();
        if (mobs.templateOf(mob) == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        mobs.died(mob, mob.getKiller());
    }

    /** Dungeon monsters only fight players (never each other). */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (mobs.templateOf(e.getEntity()) == null || e.getTarget() == null) return;
        if (!(e.getTarget() instanceof Player p)) {
            e.setCancelled(true);
            return;
        }
        if (e.getEntity() instanceof LivingEntity mob) mobs.targeted(mob, p);
    }

    /** A chunk of a dungeon loaded back: its monsters are tracked again (or removed when their run is over). */
    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent e) {
        for (Entity entity : e.getEntities()) {
            if (!(entity instanceof LivingEntity le) || mobs.templateOf(le) == null) continue;
            if (!runs.adopt(le)) le.remove();
        }
    }

    /** Slimes of the dungeons do not split (their pieces would belong to no run). */
    @EventHandler(ignoreCancelled = true)
    public void onSplit(org.bukkit.event.entity.SlimeSplitEvent e) {
        if (mobs.templateOf(e.getEntity()) != null) e.setCancelled(true);
    }

    /** Falling blocks of scripts are only a show: they never become blocks. */
    @EventHandler(ignoreCancelled = true)
    public void onFallingBlock(org.bukkit.event.entity.EntityChangeBlockEvent e) {
        if (mobs.engine().ownsFallingBlock(e.getEntity())) {
            e.setCancelled(true);
            e.getEntity().remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        if (runs.runOf(p) == null) return;
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.getDrops().clear();
        e.setDroppedExp(0);
        runs.died(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        Location at = runs.respawn(e.getPlayer());
        if (at != null) e.setRespawnLocation(at);
    }

    // ---------------------------------------------------------------- the dungeon world is not built in

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (arena.isArena(e.getBlock().getWorld()) && !e.getPlayer().hasPermission("fluxdungeons.admin")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (arena.isArena(e.getBlock().getWorld()) && !e.getPlayer().hasPermission("fluxdungeons.admin")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (arena.isArena(e.getEntity().getWorld())) e.blockList().clear();
    }

    /** The player behind a hit: himself, his projectile or his dungeon pet. */
    private Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        java.util.UUID owner = runs.petOwner(damager);
        return owner == null ? null : Bukkit.getPlayer(owner);
    }
}
