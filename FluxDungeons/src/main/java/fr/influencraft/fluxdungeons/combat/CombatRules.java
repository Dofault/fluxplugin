package fr.influencraft.fluxdungeons.combat;

import fr.influencraft.fluxdungeons.FluxDungeons;

import fr.influencraft.fluxdungeons.dungeon.Runs;
import fr.influencraft.fluxdungeons.dungeon.Arena;
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

/**
 * Every combat rule of the dungeons in one place: no friendly fire, no vanilla invulnerability (limits per source),
 * each monster's own melee pace, the exact damage of monsters (their file, never their held items), ranged
 * resistance, the players' reaction to hits (push along the
 * ground one hit in four, stagger, frost), knockback off, fire off, lethal hits of bosses with a death animation.
 */
public final class CombatRules implements Listener {

    private final FluxDungeons plugin;
    private final Runs runs;
    private final Heroes heroes;
    private final Mobs mobs;
    /** Tick of the last monster hit taken by each player (healing out of combat waits for it). */
    private final java.util.Map<java.util.UUID, Integer> lastHit = new java.util.HashMap<>();

    public CombatRules(FluxDungeons plugin, Runs runs, Heroes heroes, Mobs mobs) {
        this.plugin = plugin;
        this.runs = runs;
        this.heroes = heroes;
        this.mobs = mobs;
        Bukkit.getScheduler().runTaskTimer(plugin, this::cleanup, 20L, 20L);
    }

    /** Tick of the last monster hit this player took, null when none. */
    public Integer lastHit(java.util.UUID player) {
        return lastHit.get(player);
    }

    public void forget(java.util.UUID player) {
        lastHit.remove(player);
        meleeTaken.remove(player);
    }

    private void cleanup() {
        int now = Bukkit.getCurrentTick();
        lastSourceHit.values().removeIf(t -> now - t > 40);
        meleeTaken.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
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
            // melee: one hit of the same attacker (player, pet) every MELEE_GAP ticks at most
            if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && heroes.abilities().striking() < 0
                    && tooSoon(mob, e.getDamager().getUniqueId().toString(), MELEE_GAP)) {
                e.setCancelled(true);
                return;
            }
            if (mobs.damaged(mob, e)) {
                e.setCancelled(true);
                return;
            }
            // some monsters shrug off part of what is shot at them (ranged-resistance): melee is the way
            if (e.getDamager() instanceof Projectile) {
                var t = mobs.template(mobs.templateOf(mob));
                if (t != null && t.extra().rangedResistance() > 0) e.setDamage(e.getDamage() * (1 - t.extra().rangedResistance()));
            }
            if (attacker != null) {
                mobs.damagedByPlayer(mob, attacker, e);
                if (!runs.isPet(e.getDamager())) runs.petFocus(attacker, mob);
            }
            if (e.getFinalDamage() >= mob.getHealth() && mobs.lethal(mob, attacker)) e.setCancelled(true);
            return;
        }
        // a dungeon monster (or its projectile) hit a player
        if (victim instanceof Player hurt) {
            Entity source = e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity sh ? sh
                    : e.getDamager() instanceof org.bukkit.entity.EvokerFangs f && f.getOwner() != null ? f.getOwner() : e.getDamager();
            if (source instanceof LivingEntity mob && mobs.templateOf(mob) != null) {
                // players take every hit (no invulnerability): what limits them is each monster's own melee pace
                if (melee(e) && !mobs.meleeReady(mob)) {
                    e.setCancelled(true);
                    return;
                }
                // a melee hit, or an arrow it shot with its bow, is exactly the monster's attack (the armour still applies)
                if (melee(e) || e.getDamager() instanceof Projectile pr && mobs.ownShot(pr)) {
                    double hit = mobs.meleeDamage(mob);
                    if (hit >= 0) e.setDamage(hit);
                }
                lastHit.put(hurt.getUniqueId(), Bukkit.getCurrentTick());
                mobs.hitPlayer(mob, hurt, e);
                runs.petFocus(hurt, mob);
            }
        }
    }

    /**
     * No vanilla invulnerability after a hit, neither for dungeon monsters nor for players in a run (it swallowed the
     * hits of a party, its pets, its abilities and enchantments landing together). Monsters: melee of the same attacker
     * (player, pet) {@link #MELEE_GAP} ticks apart at least. Players: limited only by each monster's own pace
     * ({@link Mobs#meleeReady}). Both: each environmental cause (lava, cactus...) {@link #ENVIRONMENT_GAP} ticks apart,
     * else lava would hit every tick. Projectiles, abilities, enchantment effects and explosions always land.
     */
    private static final int MELEE_GAP = 5, ENVIRONMENT_GAP = 10;
    /** Tick of the last hit of each source on each monster ({@code <monster>|<source>}). */
    private final java.util.Map<String, Integer> lastSourceHit = new java.util.HashMap<>();

    private boolean tooSoon(LivingEntity mob, String source, int gap) {
        int now = Bukkit.getCurrentTick();
        String key = mob.getUniqueId() + "|" + source;
        Integer last = lastSourceHit.get(key);
        // the same tick always passes: enchantment effects hit along with the swing that triggered them
        if (last != null && now == last) return false;
        if (last != null && now - last < gap) return true;
        lastSourceHit.put(key, now);
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerFire(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || runs.runOf(p) == null) return;
        EntityDamageEvent.DamageCause c = e.getCause();
        if (c == EntityDamageEvent.DamageCause.FIRE || c == EntityDamageEvent.DamageCause.FIRE_TICK) {
            e.setCancelled(true);
            p.setFireTicks(0);
            return;
        }
        // no invulnerability in a run: lava, cactus, suffocation... hit once per cause every ENVIRONMENT_GAP ticks
        if (!(e instanceof EntityDamageByEntityEvent) && c != EntityDamageEvent.DamageCause.CUSTOM
                && c != EntityDamageEvent.DamageCause.MAGIC && c != EntityDamageEvent.DamageCause.POISON
                && c != EntityDamageEvent.DamageCause.WITHER && tooSoon(p, c.name(), ENVIRONMENT_GAP)) {
            e.setCancelled(true);
        }
    }

    /** A monster's own melee hit (not an ability nor a projectile). */
    private boolean melee(EntityDamageByEntityEvent e) {
        return !mobs.dealing() && !(e.getDamager() instanceof Projectile)
                && (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK || e.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK);
    }

    /** Blocks a monster's melee hit pushes a player ({@code hit-reaction.melee-push}), one hit out of {@link #pushEvery}. */
    private double meleePush = 2;
    private int pushEvery = 4;
    /** A hit from afar stops the player's momentum for an instant ({@code hit-reaction.ranged-stagger}). */
    private boolean stagger = true;
    /** Frost added by each frost arrow, in freezing ticks (vanilla: 140 = fully frozen). */
    private int frostPerHit = 30;
    /** Minimum gap between two shakes of a player's screen in a run (he takes many small hits). */
    private long shakeGap = 1000;
    /** Melee hits each player took from monsters (every {@link #pushEvery}th one pushes him). */
    private final java.util.Map<java.util.UUID, Integer> meleeTaken = new java.util.HashMap<>();

    public void hitReaction(org.bukkit.configuration.ConfigurationSection c) {
        meleePush = c == null ? 2 : Math.max(0, c.getDouble("melee-push", 2));
        pushEvery = c == null ? 4 : Math.max(1, c.getInt("melee-push-every", 4));
        stagger = c == null || c.getBoolean("ranged-stagger", true);
        frostPerHit = c == null ? 30 : Math.max(0, c.getInt("frost-per-hit", 30));
        shakeGap = c == null ? 1000 : Math.max(0, c.getLong("screen-shake-ms", 1000));
        runs.shakeGap(shakeGap);
    }

    /**
     * Frost on a player, progressive: each frost hit adds {@code amount} freezing ticks, never quite up to fully frozen
     * (no freezing damage). Vanilla slows a player more the more frozen he is, and thaws him by itself. For any monster
     * whose hits should chill.
     */
    public void chill(Player p, int amount) {
        if (amount <= 0) return;
        int cap = p.getMaxFreezeTicks() - 10;
        p.setFreezeTicks(Math.min(cap, Math.max(p.getFreezeTicks(), 0) + amount));
        p.getWorld().spawnParticle(org.bukkit.Particle.SNOWFLAKE, p.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.02);
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.BLOCK_POWDER_SNOW_STEP, 0.8f, 0.9f);
    }

    /** An arrow shot by a dungeon monster: aimed, damage set, more shots to come (see {@link Mobs#shot}). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(org.bukkit.event.entity.EntityShootBowEvent e) {
        if (mobs.templateOf(e.getEntity()) == null || !(e.getProjectile() instanceof org.bukkit.entity.AbstractArrow arrow)) return;
        mobs.shot(e.getEntity(), arrow);
    }

    /**
     * How a player reacts to a monster's hit that landed: a melee hit pushes him back {@code melee-push} blocks along
     * the ground (never up: he keeps his footing; already in the air, his fall goes on); a hit from afar (arrow,
     * ability) only slows him a moment, so he can still reach whoever shoots at him.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player hurt) || runs.runOf(hurt) == null) return;
        Entity source = e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity sh ? sh
                : e.getDamager() instanceof org.bukkit.entity.EvokerFangs f && f.getOwner() != null ? f.getOwner() : e.getDamager();
        if (!(source instanceof LivingEntity mob) || mobs.templateOf(mob) == null) return;
        if (melee(e)) {
            if (meleePush <= 0 || meleeTaken.merge(hurt.getUniqueId(), 1, Integer::sum) % pushEvery != 0) return;
            org.bukkit.util.Vector away = hurt.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1.0E-4) away = mob.getLocation().getDirection().setY(0);
            if (away.lengthSquared() < 1.0E-4) return;
            boolean ground = hurt.isOnGround();
            // speed that slides this far: friction keeps 54.6 % of the speed each tick on the ground, 91 % in the air
            double speed = meleePush * (ground ? 1 - 0.546 : 1 - 0.91);
            org.bukkit.util.Vector v = away.normalize().multiply(speed);
            v.setY(ground ? -0.0784 : hurt.getVelocity().getY());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (hurt.isOnline() && !hurt.isDead()) hurt.setVelocity(v);
            });
        } else {
            // a hit from afar: the player's run stops for an instant (no potion: no lasting slow, no change of view)
            if (stagger && hurt.isOnGround()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (hurt.isOnline() && !hurt.isDead() && hurt.isOnGround()) hurt.setVelocity(new org.bukkit.util.Vector(0, -0.0784, 0));
                });
            }
            // frost archers: each arrow chills a little more
            if (e.getDamager() instanceof Projectile && mobs.frostArrows(mob)) chill(hurt, frostPerHit);
        }
    }

    /**
     * Vanilla knockback is off in a dungeon: monsters are never pushed by hits, players are pushed by
     * {@link #onPlayerHit} only (along the ground). Movements cast on purpose (charge, leap, slam, implosion) set the
     * velocity directly and still work.
     */
    @EventHandler(ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent e) {
        Entity target = e.getEntity();
        if (target instanceof Player p) {
            if (runs.runOf(p) != null) e.setCancelled(true);
            return;
        }
        if (mobs.templateOf(target) == null) return;
        // a boss is never pushed, whatever the hit (its place belongs to its animations and abilities)
        if (target.getScoreboardTags().contains(fr.influencraft.fluxdungeons.mob.Mobs.BOSS_TAG)) {
            e.setCancelled(true);
            return;
        }
        // monsters are never pushed by hits, except by a weapon enchanted with Knockback (the enchantment keeps its use)
        if (e instanceof org.bukkit.event.entity.EntityKnockbackByEntityEvent byEntity && attacker(byEntity.getSourceEntity()) instanceof Player hitter
                && hitter.getInventory().getItemInMainHand().getEnchantmentLevel(org.bukkit.enchantments.Enchantment.KNOCKBACK) > 0) return;
        e.setCancelled(true);
    }

    /** Lethal damage of any other cause (fire, fall) still plays a boss's death animation. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent) return;
        if (!(e.getEntity() instanceof LivingEntity mob) || mobs.templateOf(mob) == null) return;
        // fire, lava, cactus...: one hit of each cause every ENVIRONMENT_GAP ticks (monsters have no invulnerability)
        if (e.getCause() != EntityDamageEvent.DamageCause.CUSTOM && e.getCause() != EntityDamageEvent.DamageCause.MAGIC
                && e.getCause() != EntityDamageEvent.DamageCause.POISON && e.getCause() != EntityDamageEvent.DamageCause.WITHER
                && tooSoon(mob, e.getCause().name(), ENVIRONMENT_GAP)) {
            e.setCancelled(true);
            return;
        }
        if (mobs.damaged(mob, e)) {
            e.setCancelled(true);
            return;
        }
        if (e.getFinalDamage() >= mob.getHealth() && mobs.lethal(mob, mob.getKiller())) e.setCancelled(true);
    }

    /** The player behind a hit: himself, his projectile or his dungeon pet. */
    private Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        java.util.UUID owner = runs.petOwner(damager);
        return owner == null ? null : Bukkit.getPlayer(owner);
    }
}
