package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.universe.Universes;
import fr.influencraft.fluxdungeons.rpg.core.CombatMath;
import fr.influencraft.fluxdungeons.rpg.core.Modifier;
import fr.influencraft.fluxdungeons.rpg.core.StatSheet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The one authority over life in the rpg universe (docs/technique/rpg.md §5). Every Bukkit damage event touching a
 * player or a creature of that universe is cancelled at the lowest priority, then turned into a hit of the engine:
 * vanilla armour, enchantments, potion effects and other plugins never count, and nothing can count twice.
 *
 * <p>Life belongs to the engine (players and creatures may have far more than the game's 1024 cap); the game's own
 * health only mirrors its share (hearts, boss phases "below half", death).</p>
 */
public final class CombatEngine implements Listener {

    /**
     * Level, rank and family of a creature; the plugin owning it says (dungeon monsters), else level 1 normal.
     * {@code unit}: the game damage of its plain hit (a dungeon monster's melee), so that an ability dealing twice
     * that is a hit of coefficient 2; 0 = every hit of it counts as coefficient 1.
     */
    public record MobProfile(int level, String rank, String family, List<Modifier> extra, double unit) {
        public static final MobProfile DEFAULT = new MobProfile(1, "normal", null, List.of(), 0);
    }

    private static final class MobState {
        final StatSheet stats;
        final int level;
        final String family;
        final double unit;
        double hp;

        MobState(StatSheet stats, int level, String family, double unit) {
            this.stats = stats;
            this.level = level;
            this.family = family;
            this.unit = unit;
            this.hp = stats.get("max_health");
        }
    }

    /** Ticks between two environment hits on the same creature (lava, fire, cactus fire every tick once cancelled). */
    private static final int ENVIRONMENT_GAP = 10;

    private final JavaPlugin plugin;
    private final Supplier<RpgConfig.Set> config;
    private final Function<Player, RpgPlayer> players;
    private Function<LivingEntity, MobProfile> profiles = e -> MobProfile.DEFAULT;
    /** Creatures that never die (training dummies): their life comes back full instead. */
    private java.util.function.Predicate<LivingEntity> immortal = e -> false;

    public void immortal(java.util.function.Predicate<LivingEntity> immortal) {
        this.immortal = immortal;
    }
    private final Map<UUID, MobState> mobs = new HashMap<>();
    /** cast id + target -> tick it was hit: a cast hits each target once. */
    private final Map<String, Long> hits = new HashMap<>();
    private final Map<UUID, Long> environment = new HashMap<>();
    /** Life of each rpg player (the engine's, not the game's). */
    private final Map<UUID, Double> life = new HashMap<>();
    /** True while the engine itself kills: its own death call passes through. */
    private boolean killing;
    /** True while the engine asks the others about a hit (the event it fires is not a hit to turn into one). */
    private boolean asking;
    /** Told after a monster's hit on a player was turned into an engine hit (dungeon on-hit powers, frost arrows). */
    private java.util.function.Consumer<EntityDamageByEntityEvent> afterMonsterHit = e -> { };
    /** Owner of a creature fighting for a player (dungeon pets), null for any other. */
    private Function<Entity, Player> petOwner = e -> null;

    public void afterMonsterHit(java.util.function.Consumer<EntityDamageByEntityEvent> hook) {
        this.afterMonsterHit = hook;
    }

    public void petOwner(Function<Entity, Player> owner) {
        this.petOwner = owner;
    }

    CombatEngine(JavaPlugin plugin, Supplier<RpgConfig.Set> config, Function<Player, RpgPlayer> players) {
        this.plugin = plugin;
        this.config = config;
        this.players = players;
        Bukkit.getScheduler().runTaskTimer(plugin, this::forgetOld, 100L, 100L);
    }

    /** Who decides the level, rank and family of creatures (FluxDungeons' monsters). */
    public void profiles(Function<LivingEntity, MobProfile> profiles) {
        this.profiles = profiles;
    }

    static long now() {
        return Bukkit.getCurrentTick();
    }

    public boolean rpg(Entity e) {
        if (e instanceof Player p) return RpgWorld.UNIVERSE.equals(Universes.of(p)) && !Universes.transitioning(p);
        return RpgWorld.UNIVERSE.equals(Universes.of(e.getWorld()));
    }

    // ------------------------------------------------------------------ stats and life of anyone

    public StatSheet stats(LivingEntity e) {
        if (e instanceof Player p) {
            RpgPlayer r = players.apply(p);
            return r == null ? null : r.stats();
        }
        MobState m = mob(e);
        return m == null ? null : m.stats;
    }

    public int level(LivingEntity e) {
        if (e instanceof Player p) {
            RpgPlayer r = players.apply(p);
            return r == null ? 1 : r.level();
        }
        MobState m = mob(e);
        return m == null ? 1 : m.level;
    }

    private MobState mob(LivingEntity e) {
        if (e instanceof Player || e.isDead()) return null;
        MobState m = mobs.get(e.getUniqueId());
        if (m != null) return m;
        RpgConfig.Set set = config.get();
        if (set == null) return null;
        MobProfile p = profiles.apply(e);
        if (p == null) return null;
        List<Modifier> mods = new ArrayList<>(set.balance().monster(p.level(), p.rank()));
        mods.addAll(p.extra());
        m = new MobState(StatSheet.compute(set.stats(), mods), p.level(), p.family(), p.unit());
        mobs.put(e.getUniqueId(), m);
        mirror(e, m.hp, m.stats.get("max_health"));
        return m;
    }

    public double health(LivingEntity e) {
        if (e instanceof Player p) return life.getOrDefault(p.getUniqueId(), 0.0);
        MobState m = mob(e);
        return m == null ? e.getHealth() : m.hp;
    }

    public double maxHealth(LivingEntity e) {
        StatSheet s = stats(e);
        return s == null ? 20 : s.get("max_health");
    }

    /** Sets a player's life (join, stats change); his hearts follow. */
    void life(Player p, double hp) {
        double max = maxHealth(p);
        double v = Math.max(0, Math.min(max, hp));
        life.put(p.getUniqueId(), v);
        mirror(p, v, max);
    }

    void forget(Player p) {
        life.remove(p.getUniqueId());
    }

    /**
     * The game's health of a creature shows the engine's share of life (boss phases "below half", its bar; never 0
     * while alive: death is the engine's call). A player's stays full: his hearts are covered by the hud's life bar,
     * full hearts never shake nor blink out from under it.
     */
    private void mirror(LivingEntity e, double hp, double max) {
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        double vanillaMax = a == null ? 20 : a.getValue();
        if (e instanceof Player) {
            if (hp > 0 && e.getHealth() != vanillaMax) e.setHealth(vanillaMax);
            return;
        }
        double v = max <= 0 ? vanillaMax : vanillaMax * hp / max;
        if (hp > 0) v = Math.max(Math.min(0.5, vanillaMax), v);
        e.setHealth(Math.max(0, Math.min(vanillaMax, v)));
    }

    // ------------------------------------------------------------------ hits

    /**
     * A hit of {@code attacker} on {@code target} through the damage chain. {@code cast}: id of the cast or attack it
     * belongs to (null = always counts): the same cast never hits the same target twice. Null when it did not land
     * (invalid, friendly, already hit, a side not ready).
     */
    public CombatMath.Outcome strike(LivingEntity attacker, LivingEntity target, CombatMath.Hit hit, String cast) {
        RpgConfig.Set set = config.get();
        if (set == null || attacker == null || target == null || attacker.equals(target)) return null;
        if (target.isDead() || attacker.isDead() || target.getWorld() != attacker.getWorld()) return null;
        if (!rpg(attacker) || !rpg(target)) return null;
        boolean fromPlayer = attacker instanceof Player, toPlayer = target instanceof Player;
        if (fromPlayer == toPlayer) return null;          // no player versus player, no monster versus monster
        if (target instanceof Player tp && tp.getGameMode() != org.bukkit.GameMode.SURVIVAL && tp.getGameMode() != org.bukkit.GameMode.ADVENTURE) return null;
        if (cast != null) {
            String key = cast + "|" + target.getUniqueId();
            if (hits.containsKey(key)) return null;
            hits.put(key, now());
        }
        StatSheet a = stats(attacker), d = stats(target);
        if (a == null || d == null) return null;
        if (fromPlayer && !allowed((Player) attacker, target, cast)) return null;
        MobState targetMob = toPlayer ? null : mob(target);
        String family = targetMob == null ? null : targetMob.family;
        CombatMath.Outcome o = CombatMath.hit(set.balance(), a, level(attacker), d, family, hit,
                () -> ThreadLocalRandom.current().nextDouble());
        if (o.dodged()) {
            indicator(target, Component.text("Esquive", TextColor.color(0xB376E5)));
            return o;
        }
        damage(target, o.damage(), attacker, o.crit());
        if (attacker instanceof Player p) {
            RpgPlayer r = players.apply(p);
            if (r != null) {
                r.attacked(now());
                if (o.lifesteal() > 0) heal(p, o.lifesteal(), false);
                if (o.manasteal() > 0) r.mana(r.mana() + o.manasteal());
            }
        }
        return o;
    }

    /**
     * A player's hit on a creature, told to the other plugins as a Bukkit hit first: the dungeons may refuse it (a
     * boss between two phases, a melee too soon) and react to it (aggro, scripts, boss reactions, pets). Only the
     * engine decides how much it takes.
     */
    private boolean allowed(Player attacker, LivingEntity target, String cast) {
        boolean melee = cast != null && cast.contains(":basic:");
        org.bukkit.damage.DamageSource source = org.bukkit.damage.DamageSource.builder(melee
                        ? org.bukkit.damage.DamageType.PLAYER_ATTACK : org.bukkit.damage.DamageType.MAGIC)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        EntityDamageByEntityEvent ask = new EntityDamageByEntityEvent(attacker, target, melee
                ? EntityDamageEvent.DamageCause.ENTITY_ATTACK : EntityDamageEvent.DamageCause.MAGIC, source, 1);
        asking = true;
        try {
            Bukkit.getPluginManager().callEvent(ask);
        } finally {
            asking = false;
        }
        return !ask.isCancelled();
    }

    /** Takes life from a creature or a player, with the hurt look and the number. */
    public void damage(LivingEntity target, double amount, LivingEntity source, boolean crit) {
        if (amount <= 0 || target.isDead()) return;
        double left = amount;
        if (target instanceof Player p) {
            RpgPlayer r = players.apply(p);
            if (r == null) return;
            r.hurt(now());
            double hp = life.getOrDefault(p.getUniqueId(), maxHealth(p)) - left;
            life.put(p.getUniqueId(), Math.max(0, hp));
            p.playHurtAnimation(0);
            hurtSound(p, org.bukkit.SoundCategory.PLAYERS);
            if (hp <= 0) kill(p, source);
            else mirror(p, hp, maxHealth(p));
        } else {
            MobState m = mob(target);
            if (m == null) return;
            m.hp -= amount;
            target.playHurtAnimation(0);
            hurtSound(target, org.bukkit.SoundCategory.HOSTILE);
            if (source instanceof Player p && target instanceof org.bukkit.entity.Mob mob && mob.getTarget() == null) mob.setTarget(p);
            if (m.hp <= 0 && immortal.test(target)) m.hp = m.stats.get("max_health");
            if (m.hp <= 0) kill(target, source);
            else mirror(target, m.hp, m.stats.get("max_health"));
        }
        indicator(target, Component.text((crit ? "✦ " : "") + format(amount),
                TextColor.color(target instanceof Player ? 0xFF5C5C : crit ? 0xFFD94D : 0xFFFFFF)));
    }

    /**
     * The creature's own hurt sound: the game plays it with the damage it applies, and the engine cancels that damage
     * (players heard nothing when hit).
     */
    private static void hurtSound(LivingEntity target, org.bukkit.SoundCategory category) {
        org.bukkit.Sound s = target.getHurtSound();
        if (s == null) return;
        float pitch = 0.8f + ThreadLocalRandom.current().nextFloat() * 0.4f;
        target.getWorld().playSound(target.getLocation(), s, category, 1f, pitch);
    }

    private void kill(LivingEntity target, LivingEntity source) {
        killing = true;
        try {
            if (source instanceof Player p && !(target instanceof Player)) target.setKiller(p);
            target.setHealth(0);
        } finally {
            killing = false;
        }
    }

    /** Gives life back to a player ({@code show}: a green number above him). */
    public void heal(Player p, double amount, boolean show) {
        if (amount <= 0 || p.isDead() || !life.containsKey(p.getUniqueId())) return;
        double max = maxHealth(p);
        double hp = Math.min(max, life.get(p.getUniqueId()) + amount);
        life.put(p.getUniqueId(), hp);
        mirror(p, hp, max);
        if (show) indicator(p, Component.text("+" + format(amount), TextColor.color(0x2ECF7B)));
    }

    private static String format(double v) {
        return v >= 100 || v == Math.floor(v) ? String.valueOf(Math.round(v)) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** A number rising above a creature for a moment. */
    private void indicator(LivingEntity target, Component text) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = target.getEyeLocation().add(r.nextDouble(-0.4, 0.4), 0.3 + r.nextDouble(0, 0.3), r.nextDouble(-0.4, 0.4));
        TextDisplay d = target.getWorld().spawn(at, TextDisplay.class, t -> {
            t.text(text);
            t.setBillboard(Display.Billboard.CENTER);
            t.setPersistent(false);
            t.setShadowed(true);
            t.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            t.setTeleportDuration(10);
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (d.isValid()) d.teleport(d.getLocation().add(0, 0.6, 0));
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, d::remove, 16L);
    }

    // ------------------------------------------------------------------ every Bukkit damage becomes an engine hit

    /**
     * After the other plugins (the dungeons pace their monsters and give them their power, and may refuse a hit):
     * what is left becomes an engine hit, and the game's own damage never happens.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (killing || asking || !(e.getEntity() instanceof LivingEntity target) || !rpg(target)) return;
        e.setCancelled(true);
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID || e.getCause() == EntityDamageEvent.DamageCause.KILL) {
            if (e.getCause() == EntityDamageEvent.DamageCause.KILL) kill(target, null);
            return;   // the void is handled by the world (back to the platform)
        }
        if (e instanceof EntityDamageByEntityEvent by) {
            Entity damager = by.getDamager();
            LivingEntity source = damager instanceof Projectile pr && pr.getShooter() instanceof LivingEntity sh ? sh
                    : damager instanceof LivingEntity le ? le : null;
            // a player's hits come through his controls (basic attack, skills), never through vanilla melee
            if (source == null || source instanceof Player) return;
            Player owner = petOwner.apply(source);
            if (owner != null) {
                // a pet fighting for its owner: a light hit of the owner
                if (!(target instanceof Player)) strike(owner, target, CombatMath.Hit.physical(0.6), null);
                return;
            }
            if (!(target instanceof Player)) return;
            MobState m = mob(source);
            double unit = m == null ? 0 : m.unit;
            double coefficient = unit > 0 ? Math.max(0.1, by.getDamage() / unit)
                    : by.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION ? 1.5 : 1;
            if (strike(source, target, CombatMath.Hit.physical(coefficient), null) != null) afterMonsterHit.accept(by);
            return;
        }
        // the environment: its vanilla damage read as a share of 20 points of life (2 = a tenth of the life)
        Long last = environment.get(target.getUniqueId());
        if (last != null && now() - last < ENVIRONMENT_GAP) return;
        environment.put(target.getUniqueId(), now());
        double share = Math.min(1, e.getDamage() / 20);
        RpgConfig.Set set = config.get();
        if (set == null) return;
        StatSheet s = stats(target);
        if (s == null) return;
        double amount = CombatMath.hit(set.balance(), s, 1, s, null, CombatMath.Hit.pure(share * s.get("max_health")), () -> 1).damage();
        damage(target, amount, null, false);
    }

    /** No vanilla healing (food, potions, beacons) in the rpg universe: life comes back through the engine only. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent e) {
        if (rpg(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p) || !rpg(p)) return;
        e.setCancelled(true);
        p.setFoodLevel(20);
    }

    /** Vanilla experience never counts there: the bar shows the rpg level. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onExp(PlayerExpChangeEvent e) {
        if (rpg(e.getPlayer())) e.setAmount(0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        mobs.remove(e.getEntity().getUniqueId());
        environment.remove(e.getEntity().getUniqueId());
    }

    private void forgetOld() {
        long t = now();
        for (Iterator<Map.Entry<String, Long>> it = hits.entrySet().iterator(); it.hasNext(); ) {
            if (t - it.next().getValue() > 200) it.remove();
        }
        mobs.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
        environment.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
    }
}
