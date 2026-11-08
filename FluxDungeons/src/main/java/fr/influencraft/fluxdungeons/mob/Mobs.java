package fr.influencraft.fluxdungeons.mob;

import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.fluxdungeons.mob.MobTemplate.Ability;
import fr.influencraft.fluxdungeons.script.Script;
import fr.influencraft.fluxdungeons.script.ScriptEngine;
import fr.influencraft.fluxdungeons.script.ScriptHost;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Monsters of the dungeons: spawned with their numbers and equipment, marked with their template, run and objective,
 * driven every half second (target, abilities, leash), running their scripts on their events ({@link ScriptEngine}),
 * changing phase below a share of their life, changing blocks around their spawn point when they appear and
 * disappear (doors of imported dungeons). A boss wearing a model plays its animations.
 */
public final class Mobs {

    /** What the dungeon is told when a monster is gone for good. */
    public interface DeathHandler {
        void dead(LivingEntity mob, Player killer, MobTemplate template, String objective, Set<UUID> contributors);
    }

    /** A monster alive in a run. */
    private final class Alive implements ScriptHost {
        final LivingEntity entity;
        final MobTemplate template;
        final String run;
        final Location spawn;
        final Map<Integer, Long> lastCast = new HashMap<>();
        final ModelBridge.Handle model;
        final Powers.Kit kit;
        final List<Ability> abilities;
        final Set<String> tags = new HashSet<>();
        final Map<String, Long> cooldowns = new HashMap<>();
        long globalCooldown;
        List<MobTemplate.Phase> phases;
        int nextPhase;
        String objective;
        long lastHurt;
        /** Damage dealt by each player: loot goes to those who did at least a tenth of its life (EliteMobs). */
        final Map<UUID, Double> damagers = new HashMap<>();
        /** Last time it had a player to fight (leaves combat after a while without one). */
        long lastEngaged;
        boolean dying, inCombat, switching;
        /** Name, level and life bar floating above it (a text display riding it), null when off. */
        org.bukkit.entity.TextDisplay label;
        /** Bar step and health shown on the label (redrawn only when they change). */
        int labelStep = -1;
        double labelHealth = -1;

        Alive(LivingEntity entity, MobTemplate template, String run, Location spawn, ModelBridge.Handle model) {
            this.entity = entity;
            this.template = template;
            this.run = run;
            this.spawn = spawn.clone();
            this.model = model;
            this.kit = Powers.of(template.extra().powers());
            this.abilities = new ArrayList<>(template.abilities());
            abilities.addAll(kit.abilities());
            this.phases = template.extra().phases();
        }

        public LivingEntity entity() {
            return entity;
        }

        public Location spawn() {
            return spawn;
        }

        public String run() {
            return run;
        }

        public Map<String, Script> scripts() {
            return template.extra().scripts();
        }

        public Set<String> tags() {
            return tags;
        }

        public Map<String, Long> cooldowns() {
            return cooldowns;
        }

        public long globalCooldownUntil() {
            return globalCooldown;
        }

        public void globalCooldownUntil(long tick) {
            globalCooldown = tick;
        }

        public double baseDamage() {
            AttributeInstance a = entity.getAttribute(Attribute.ATTACK_DAMAGE);
            return a == null ? template.damage() : a.getValue();
        }

        public void animate(String animation) {
            if (model != null && animation != null && !animation.isBlank()) model.play(animation);
        }
    }

    /** Scoreboard tag of every dungeon monster: other plugins leave tagged mobs alone (FluxStack never stacks them). */
    public static final String TAG = "fluxdungeons";
    /** Out-of-combat speed cap and share of sight kept (EliteMobs: 0.12 and a third). */
    private static final double CALM_SPEED = 0.12, CALM_SIGHT = 1 / 3.0;
    /** A monster without a player to fight for this long leaves combat. */
    private static final long COMBAT_TIMEOUT_MS = 10_000;
    /** Highest a flying monster stays above its target, or above its spawn point without one (blocks). */
    private static final double FLYING_ABOVE_TARGET = 3, FLYING_ABOVE_SPAWN = 4;
    private final NamespacedKey calmKey;

    private final Plugin plugin;
    private final NamespacedKey mobKey, runKey, objectiveKey, spawnKey;
    /** True while a hit computed here (ability, power) is dealt. */
    private boolean dealing;
    private final Map<String, MobTemplate> templates = new LinkedHashMap<>();
    private final Map<UUID, Alive> alive = new HashMap<>();
    private final boolean models;
    private final ScriptEngine engine;
    private BiConsumerLiving onSummon = (parent, child) -> {
    };
    private DeathHandler onDeath = (mob, killer, template, objective, contributors) -> {
    };
    private int ticks;

    @FunctionalInterface
    public interface BiConsumerLiving {
        void accept(LivingEntity parent, LivingEntity child);
    }

    public Mobs(Plugin plugin) {
        this.plugin = plugin;
        this.mobKey = new NamespacedKey(plugin, "mob");
        this.runKey = new NamespacedKey(plugin, "run");
        this.objectiveKey = new NamespacedKey(plugin, "objective");
        this.spawnKey = new NamespacedKey(plugin, "spawn");
        this.calmKey = new NamespacedKey(plugin, "calm");
        this.models = Bukkit.getPluginManager().isPluginEnabled("BetterModel");
        this.engine = new ScriptEngine(plugin, new ScriptEngine.Services() {
            public ScriptHost host(Entity entity) {
                return alive.get(entity.getUniqueId());
            }

            public LivingEntity summon(ScriptHost by, String template, Location at) {
                MobTemplate t = templates.get(template);
                if (t == null) return null;
                LivingEntity child = spawn(t, at, by.run());
                if (child != null) onSummon.accept(by.entity(), child);
                return child;
            }

            public boolean fighting(Player player) {
                return Mobs.fighting(player);
            }

            public void damage(ScriptHost by, Player target, double multiplier) {
                deal(target, by.entity(), hitOn(by, target) * multiplier);
            }
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    /** Loads every monster file (mobs.yml first, then each file of mobs/). */
    public void load(List<ConfigurationSection> files) {
        templates.clear();
        for (ConfigurationSection file : files) {
            if (file == null) continue;
            for (String id : file.getKeys(false)) {
                ConfigurationSection c = file.getConfigurationSection(id);
                if (c == null) continue;
                try {
                    templates.put(id, MobTemplate.read(id, c));
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Monster " + id + " could not be read: " + e);
                }
            }
        }
    }

    /** Ids of every monster known (completion). */
    public List<String> templateIds() {
        return List.copyOf(templates.keySet());
    }

    public MobTemplate template(String id) {
        return templates.get(id);
    }

    public ScriptEngine engine() {
        return engine;
    }

    public void onSummon(BiConsumerLiving handler) {
        this.onSummon = handler;
    }

    public void onDeath(DeathHandler handler) {
        this.onDeath = handler;
    }

    /** Template id of a dungeon monster, null for anything else. */
    public String templateOf(Entity entity) {
        return entity.getPersistentDataContainer().get(mobKey, PersistentDataType.STRING);
    }

    /** Run a dungeon monster belongs to, null for anything else. */
    public String runOf(Entity entity) {
        return entity.getPersistentDataContainer().get(runKey, PersistentDataType.STRING);
    }

    public MobTemplate templateOfEntity(Entity entity) {
        String id = templateOf(entity);
        return id == null ? null : templates.get(id);
    }

    /** Marks a monster as an objective of its run (its last phase dying completes it). */
    public void objective(LivingEntity entity, String objective) {
        entity.getPersistentDataContainer().set(objectiveKey, PersistentDataType.STRING, objective);
        Alive a = alive.get(entity.getUniqueId());
        if (a != null) a.objective = objective;
    }

    // ---------------------------------------------------------------- spawning

    public LivingEntity spawn(MobTemplate t, Location at, String run) {
        Class<? extends Entity> type = t.type().getEntityClass();
        if (type == null || !LivingEntity.class.isAssignableFrom(type)) return null;
        MobTemplate.Extra x = t.extra();
        // imported monsters (a level): the vanilla creature's numbers times their multipliers; others: absolute numbers
        boolean imported = x.level() > 0;
        Entity spawned = at.getWorld().spawn(at, type, CreatureSpawnEvent.SpawnReason.CUSTOM, e -> {
            LivingEntity le = (LivingEntity) e;
            le.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, t.id());
            le.getPersistentDataContainer().set(runKey, PersistentDataType.STRING, run);
            le.getPersistentDataContainer().set(spawnKey, PersistentDataType.STRING, at.getX() + "," + at.getY() + "," + at.getZ());
            le.addScoreboardTag(TAG);
            le.customName(Texts.parse(t.name()));
            // the name shows on the life bar above it (label); without the bar, on the vanilla name tag
            le.setCustomNameVisible(!t.boss() && !healthBar);
            le.setRemoveWhenFarAway(false);
            le.setPersistent(true);
            le.setCanPickupItems(false);
            double health = imported ? vanilla(le, Attribute.MAX_HEALTH, 20) * x.healthMultiplier() : t.health();
            base(le, Attribute.MAX_HEALTH, health);
            base(le, Attribute.ATTACK_DAMAGE, imported ? vanilla(le, Attribute.ATTACK_DAMAGE, 2) * x.damageMultiplier() : t.damage());
            if (t.speed() > 0) base(le, Attribute.MOVEMENT_SPEED, t.speed());
            if (!imported) base(le, Attribute.ARMOR, t.armor());
            base(le, Attribute.SCALE, t.scale());
            base(le, Attribute.FOLLOW_RANGE, x.followRange());
            le.setHealth(le.getAttribute(Attribute.MAX_HEALTH).getValue());
            EntityEquipment eq = le.getEquipment();
            if (eq != null) {
                eq.clear();
                t.equipment().forEach((slot, material) -> {
                    ItemStack item = new ItemStack(material);
                    switch (slot) {
                        case "head" -> eq.setHelmet(item);
                        case "chest" -> eq.setChestplate(item);
                        case "legs" -> eq.setLeggings(item);
                        case "feet" -> eq.setBoots(item);
                        case "offhand" -> eq.setItemInOffHand(item);
                        default -> eq.setItemInMainHand(item);
                    }
                });
                eq.setHelmetDropChance(0);
                eq.setChestplateDropChance(0);
                eq.setLeggingsDropChance(0);
                eq.setBootsDropChance(0);
                eq.setItemInMainHandDropChance(0);
                eq.setItemInOffHandDropChance(0);
            }
            if (le instanceof Zombie z) z.setShouldBurnInDay(false);
            if (le instanceof org.bukkit.entity.AbstractSkeleton s) s.setShouldBurnInDay(false);
            if (le instanceof Ageable ag) {
                if (x.baby()) ag.setBaby();
                else ag.setAdult();
            }
            if (x.frozen()) le.setAI(false);
        });
        LivingEntity le = (LivingEntity) spawned;
        ModelBridge.Handle model = null;
        if (t.model() != null && models) {
            try {
                model = ModelBridge.attach(le, t.model());
                if (model != null) le.setInvisible(true);
                else plugin.getLogger().warning("BetterModel model not found: " + t.model());
            } catch (LinkageError | RuntimeException e) {
                plugin.getLogger().warning("Could not put model " + t.model() + " on " + t.id() + ": " + e);
            }
        }
        Alive a = new Alive(le, t, run, at, model);
        if (a.kit.noKnockback()) base(le, Attribute.KNOCKBACK_RESISTANCE, 1);
        else if (t.boss()) base(le, Attribute.KNOCKBACK_RESISTANCE, 1);
        for (PotionEffect pe : a.kit.permanent()) le.addPotionEffect(pe);
        alive.put(le.getUniqueId(), a);
        label(a);
        calm(a, true);
        blocks(a, x.spawnBlocks());
        if (x.mount() != null) mount(a, x.mount());
        engine.fire(a, "EliteMobSpawnEvent", null, null);
        return le;
    }

    /**
     * A dungeon monster loaded back with its chunk (players went away, then came back): tracked again, without its
     * spawn scripts nor block states. False when its run is over (the caller removes it).
     */
    public boolean adopt(LivingEntity le) {
        if (alive.containsKey(le.getUniqueId())) return true;
        MobTemplate t = templateOfEntity(le);
        String run = runOf(le);
        if (t == null || run == null) return false;
        Location spawn = le.getLocation();
        String sp = le.getPersistentDataContainer().get(spawnKey, PersistentDataType.STRING);
        if (sp != null) {
            String[] p = sp.split(",");
            try {
                spawn = new Location(le.getWorld(), Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
            } catch (RuntimeException ignored) {
                // keeps its current place
            }
        }
        ModelBridge.Handle model = null;
        if (t.model() != null && models) {
            try {
                model = ModelBridge.attach(le, t.model());
            } catch (LinkageError | RuntimeException ignored) {
                // shown as its vanilla mob
            }
        }
        Alive a = new Alive(le, t, run, spawn, model);
        a.objective = le.getPersistentDataContainer().get(objectiveKey, PersistentDataType.STRING);
        alive.put(le.getUniqueId(), a);
        label(a);
        calm(a, true);
        return true;
    }

    private void mount(Alive rider, String mount) {
        MobTemplate t = templates.get(mount.replace(".yml", ""));
        LivingEntity horse = null;
        if (t != null) {
            horse = spawn(t, rider.spawn.clone(), rider.run);
            if (horse != null) onSummon.accept(rider.entity, horse);
        } else {
            try {
                EntityType type = EntityType.valueOf(mount.toUpperCase(java.util.Locale.ROOT));
                if (type.getEntityClass() != null && LivingEntity.class.isAssignableFrom(type.getEntityClass())) {
                    // tagged: a creature without the tag is refused in a dungeon world (DungeonListener)
                    horse = (LivingEntity) rider.spawn.getWorld().spawn(rider.spawn, type.getEntityClass(),
                            CreatureSpawnEvent.SpawnReason.CUSTOM, e -> e.addScoreboardTag(TAG));
                }
            } catch (IllegalArgumentException ignored) {
                // unknown mount
            }
        }
        if (horse != null) horse.addPassenger(rider.entity);
    }

    /** Applies block states relative to the monster's spawn point (doors, arena walls). */
    private void blocks(Alive a, List<MobTemplate.BlockState> states) {
        for (MobTemplate.BlockState s : states) {
            try {
                Location l = a.spawn.clone().add(s.offset());
                l.getBlock().setBlockData(Bukkit.createBlockData(s.data()), false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Bad block state " + s.data() + " of " + a.template.id());
            }
        }
    }

    /**
     * Out of combat, a dungeon monster is slowed and short-sighted (as EliteMobs does for its dungeon bosses): it
     * only notices a player who comes close and in sight, so a room is fought a few monsters at a time instead of
     * the whole map rushing in. Full speed and sight come back as soon as it fights.
     */
    private void calm(Alive a, boolean on) {
        for (Attribute attribute : List.of(Attribute.MOVEMENT_SPEED, Attribute.FOLLOW_RANGE)) {
            AttributeInstance inst = a.entity.getAttribute(attribute);
            if (inst == null) continue;
            inst.removeModifier(calmKey);
            if (!on) continue;
            double factor = attribute == Attribute.FOLLOW_RANGE ? CALM_SIGHT - 1
                    : inst.getBaseValue() > CALM_SPEED ? CALM_SPEED / inst.getBaseValue() - 1 : 0;
            if (factor != 0) inst.addModifier(new org.bukkit.attribute.AttributeModifier(calmKey, factor,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_SCALAR, org.bukkit.inventory.EquipmentSlotGroup.ANY));
        }
    }

    private void enterCombat(Alive a, Player player, boolean event) {
        a.lastEngaged = System.currentTimeMillis();
        if (a.inCombat) return;
        a.inCombat = true;
        calm(a, false);
        if (event) engine.fire(a, "EliteMobEnterCombatEvent", player, null);
    }

    private static void base(LivingEntity e, Attribute attribute, double value) {
        AttributeInstance inst = e.getAttribute(attribute);
        if (inst != null) inst.setBaseValue(value);
    }

    /** Removes every monster of a run (end of run). */
    public void removeRun(String run) {
        Iterator<Alive> it = alive.values().iterator();
        while (it.hasNext()) {
            Alive a = it.next();
            if (!a.run.equals(run)) continue;
            close(a);
            a.entity.remove();
            it.remove();
        }
    }

    public void removeAll() {
        for (Alive a : alive.values()) {
            close(a);
            a.entity.remove();
        }
        alive.clear();
    }

    /** Chunk tickets are released by the run (whole map world unloaded, or arena slot released), not per monster. */
    private void close(Alive a) {
        if (a.model != null) a.model.close();
        if (a.label != null) {
            a.label.remove();
            a.label = null;
        }
        engine.forget(a);
    }

    // ---------------------------------------------------------------- events from the listener

    /** A dungeon monster was hit by a player. */
    public void damagedByPlayer(LivingEntity entity, Player player, EntityDamageEvent event) {
        Alive a = alive.get(entity.getUniqueId());
        if (a == null) return;
        enterCombat(a, player, true);
        a.damagers.merge(player.getUniqueId(), Math.min(event.getFinalDamage(), entity.getHealth()), Double::sum);
        engine.fire(a, "EliteMobDamagedByPlayerEvent", player, event);
        if (!a.template.extra().hurtMessages().isEmpty() && ThreadLocalRandom.current().nextDouble() < 0.15) say(a, a.template.extra().hurtMessages());
    }

    /** A dungeon monster took damage of any kind; returns true when the damage must be cancelled (immunity). */
    public boolean damaged(LivingEntity entity, EntityDamageEvent event) {
        Alive a = alive.get(entity.getUniqueId());
        if (a == null) return false;
        if (a.kit.immune().contains(event.getCause())) return true;
        engine.fire(a, "EliteMobDamagedEvent", null, event);
        hurt(a);
        if (!a.phases.isEmpty() && a.nextPhase < a.phases.size()) {
            Bukkit.getScheduler().runTask(plugin, () -> checkPhase(a));
        }
        return false;
    }

    /** A dungeon monster hit a player. */
    public void hitPlayer(LivingEntity entity, Player player, EntityDamageEvent event) {
        Alive a = alive.get(entity.getUniqueId());
        if (a == null) return;
        dealing = true;
        try {
            for (Powers.OnHit h : a.kit.onHit()) Powers.hit(h, player, entity, hitOn(a, player));
        } finally {
            dealing = false;
        }
        engine.fire(a, "PlayerDamagedByEliteMobEvent", player, event);
        if (!a.template.extra().hitMessages().isEmpty() && ThreadLocalRandom.current().nextDouble() < 0.2) say(a, a.template.extra().hitMessages());
    }

    /** A dungeon monster chose a player as target. */
    public void targeted(LivingEntity entity, Player player) {
        Alive a = alive.get(entity.getUniqueId());
        if (a == null) return;
        enterCombat(a, player, true);
        engine.fire(a, "EliteMobTargetPlayerEvent", player, null);
    }

    private void hurt(Alive a) {
        if (a.model == null || a.dying) return;
        long now = System.currentTimeMillis();
        if (now - a.lastHurt < 500) return;
        a.lastHurt = now;
        a.model.play("hurt");
    }

    /** Below the share of life of its next phase, the monster becomes the next one, life share kept. */
    private void checkPhase(Alive a) {
        if (a.switching || a.dying || !a.entity.isValid() || a.nextPhase >= a.phases.size()) return;
        double ratio = a.entity.getHealth() / Math.max(1, maxHealth(a.entity));
        MobTemplate.Phase phase = a.phases.get(a.nextPhase);
        if (ratio > phase.below()) return;
        MobTemplate next = templates.get(phase.mob());
        if (next == null) {
            a.nextPhase = a.phases.size();
            return;
        }
        a.switching = true;
        Location at = a.entity.getLocation();
        blocks(a, a.template.extra().removeBlocks());
        alive.remove(a.entity.getUniqueId());
        close(a);
        a.entity.remove();
        LivingEntity child = spawnPhase(next, at, a, ratio);
        if (child != null) onSummon.accept(a.entity, child);
    }

    private LivingEntity spawnPhase(MobTemplate next, Location at, Alive from, double ratio) {
        LivingEntity child = spawn(next, at, from.run);
        if (child == null) return null;
        Alive c = alive.get(child.getUniqueId());
        c.phases = from.phases;
        c.nextPhase = from.nextPhase + 1;
        enterCombat(c, null, false);
        c.tags.addAll(from.tags);
        c.damagers.putAll(from.damagers);
        if (from.objective != null) objective(child, from.objective);
        child.setHealth(Math.max(1, maxHealth(child) * ratio));
        engine.fire(c, "ElitePhaseSwitchEvent", null, null);
        return child;
    }

    /**
     * A hit would kill this monster. A boss wearing a model with a {@code death} animation does not die at once: it
     * stops, plays the animation, then disappears and counts as killed (returns true: cancel the hit).
     */
    public boolean lethal(LivingEntity entity, Player killer) {
        Alive a = alive.get(entity.getUniqueId());
        if (a == null) return false;
        if (a.dying) return true;
        if (a.model == null || !a.template.boss() || !a.model.play("death")) return false;
        a.dying = true;
        entity.setInvulnerable(true);
        if (entity instanceof Mob m) {
            m.setAI(false);
            m.setTarget(null);
        }
        entity.setHealth(Math.max(1, entity.getHealth()));
        World w = entity.getWorld();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, entity.getLocation().add(0, 1, 0), 20, 0.8, 0.4, 0.8, 0.02);
            w.playSound(entity.getLocation(), Sound.BLOCK_ANVIL_LAND, 1f, 0.5f);
        }, 26L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            finish(a, killer);
            entity.remove();
        }, 36L);
        return true;
    }

    /** A dungeon monster died the vanilla way. */
    public void died(LivingEntity entity, Player killer) {
        Alive a = alive.get(entity.getUniqueId());
        if (a == null) return;
        finish(a, killer);
    }

    private void finish(Alive a, Player killer) {
        if (!alive.containsKey(a.entity.getUniqueId())) return;
        engine.fire(a, "EliteMobDeathEvent", killer, null);
        blocks(a, a.template.extra().removeBlocks());
        if (!a.template.extra().deathMessages().isEmpty()) {
            for (Player p : a.entity.getWorld().getPlayers()) for (String line : a.template.extra().deathMessages()) p.sendMessage(Texts.parse(line.replace("$players", killer == null ? "" : killer.getName())));
        }
        Set<UUID> contributors = contributors(a.entity);
        alive.remove(a.entity.getUniqueId());
        close(a);
        onDeath.dead(a.entity, killer, a.template, a.objective, contributors);
    }

    private void say(Alive a, List<String> lines) {
        String line = lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
        for (Entity e : a.entity.getNearbyEntities(16, 8, 16)) if (e instanceof Player p) p.sendMessage(Texts.parse(a.template.name() + "&f : " + line));
    }

    // ---------------------------------------------------------------- behaviour

    private void tick() {
        ticks++;
        long now = System.currentTimeMillis();
        // abilities and zone scripts run after the loop: they may add monsters to the map being walked
        List<Runnable> later = new ArrayList<>();
        Iterator<Alive> it = alive.values().iterator();
        while (it.hasNext()) {
            Alive a = it.next();
            if (!a.entity.isValid()) {
                if (!a.dying && !a.entity.isDead()) {
                    close(a);
                    it.remove();
                }
                continue;
            }
            if (a.dying) continue;
            updateLabel(a);
            double leash = a.template.extra().leash();
            if (leash > 0 && a.entity.getLocation().distanceSquared(a.spawn) > leash * leash) move(a.entity, a.spawn);
            if (!a.template.extra().scripts().isEmpty()) later.add(() -> engine.pollZones(a));
            if (!a.entity.hasAI()) continue;
            Player target = target(a);
            keepLow(a, target);
            if (target == null) {
                if (a.inCombat && now - a.lastEngaged > COMBAT_TIMEOUT_MS) {
                    a.inCombat = false;
                    calm(a, true);
                    if (a.entity instanceof Mob m) m.setTarget(null);
                    later.add(() -> engine.fire(a, "EliteMobExitCombatEvent", null, null));
                }
                continue;
            }
            a.lastEngaged = now;
            double life = 100 * a.entity.getHealth() / Math.max(1, maxHealth(a.entity));
            for (int i = 0; i < a.abilities.size(); i++) {
                Ability ab = a.abilities.get(i);
                if (life > ab.below()) continue;
                long last = a.lastCast.getOrDefault(i, now - (long) (ab.every() * 500));
                if (now - last < ab.every() * 1000) continue;
                a.lastCast.put(i, now);
                later.add(() -> cast(a, ab, target));
                break;
            }
        }
        later.forEach(Runnable::run);
    }

    /**
     * Flying monsters (wither, blaze, ghast...) stay within reach: vanilla withers climb high above their target or,
     * without one, wander upwards, out of reach of melee classes. Pushed down beyond a few blocks above their target
     * (in combat) or above their spawn point.
     */
    private static void keepLow(Alive a, Player target) {
        if (!(a.entity instanceof org.bukkit.entity.Flying) && !(a.entity instanceof org.bukkit.entity.Wither)
                && !(a.entity instanceof org.bukkit.entity.Blaze) && !(a.entity instanceof org.bukkit.entity.Vex)) return;
        double ceiling = target != null && target.getWorld() == a.entity.getWorld() ? target.getLocation().getY() + FLYING_ABOVE_TARGET
                : a.spawn.getY() + FLYING_ABOVE_SPAWN;
        double over = a.entity.getLocation().getY() - ceiling;
        if (over <= 0) return;
        org.bukkit.util.Vector v = a.entity.getVelocity();
        a.entity.setVelocity(v.setY(Math.min(v.getY(), -Math.min(0.6, 0.15 + over * 0.1))));
    }

    private static Player target(Alive a) {
        if (!(a.entity instanceof Mob m)) return null;
        if (m.getTarget() instanceof Player p && fighting(p)) return p;
        // out of combat, only vanilla sight (shortened by calm) picks a target: never through walls
        if (!a.inCombat) return null;
        Player best = null;
        double range = a.template.extra().followRange();
        double bestD = Math.min(24, range) * Math.min(24, range);
        for (Player p : a.entity.getWorld().getPlayers()) {
            if (!fighting(p)) continue;
            double d = p.getLocation().distanceSquared(a.entity.getLocation());
            if (d < bestD && (a.template.boss() || a.entity.hasLineOfSight(p))) {
                bestD = d;
                best = p;
            }
        }
        if (best != null) m.setTarget(best);
        return best;
    }

    static boolean fighting(Player p) {
        return !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE);
    }

    private static double maxHealth(LivingEntity e) {
        AttributeInstance inst = e.getAttribute(Attribute.MAX_HEALTH);
        return inst == null ? 20 : inst.getValue();
    }

    /** A model worn by an entity that is not a dungeon monster (pet of a player). */
    public interface Worn {
        /** Plays an animation once; false when the model has none of that name. */
        boolean play(String animation);

        void close();
    }

    /**
     * Puts a BetterModel model on an entity that is not a dungeon monster (a pet of a player) and hides the entity
     * under it. Null when BetterModel or the model is missing.
     */
    public Worn dress(LivingEntity entity, String model) {
        if (!models || model == null || model.isBlank()) return null;
        try {
            ModelBridge.Handle h = ModelBridge.attach(entity, model);
            if (h == null) return null;
            entity.setInvisible(true);
            return new Worn() {
                public boolean play(String animation) {
                    try {
                        return h.play(animation);
                    } catch (RuntimeException e) {
                        return false;
                    }
                }

                public void close() {
                    h.close();
                }
            };
        } catch (LinkageError | RuntimeException e) {
            plugin.getLogger().warning("Could not put model " + model + " on " + entity.getType() + ": " + e);
            return null;
        }
    }

    // ---------------------------------------------------------------- life bar above the monsters

    private boolean healthBar = true;
    private String barGlyph = "fluxafk:bar_{step}";
    private int barSteps = 20;

    /** {@code health-bar} of the config: the textured bar of FluxAFK and FluxTools, one picture per step. */
    public void healthBar(boolean enabled, String glyph, int steps) {
        healthBar = enabled;
        barGlyph = glyph == null || glyph.isBlank() ? "fluxafk:bar_{step}" : glyph;
        barSteps = Math.max(1, steps);
    }

    /**
     * Teleports a dungeon monster with what rides it (its life bar): a plain teleport refuses to move an entity that
     * has passengers.
     */
    public static void move(Entity e, Location to) {
        e.teleport(to, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    /** Puts the floating name, level and life bar on a monster. */
    private void label(Alive a) {
        if (!healthBar || a.label != null) return;
        LivingEntity le = a.entity;
        a.label = le.getWorld().spawn(le.getLocation(), org.bukkit.entity.TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            d.setDefaultBackground(false);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(true);
            d.setSeeThrough(false);
            d.setViewRange(0.45f);
            d.setLineWidth(400);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(0, 0.25f, 0),
                    new org.joml.AxisAngle4f(), new org.joml.Vector3f(1, 1, 1), new org.joml.AxisAngle4f()));
            d.addScoreboardTag(TAG + "_label");
        });
        le.addPassenger(a.label);
        a.labelStep = -1;
        updateLabel(a);
    }

    /** Draws the label again when the monster's life changed. */
    private void updateLabel(Alive a) {
        if (a.label == null) return;
        if (!a.label.isValid() || !a.entity.getPassengers().contains(a.label)) {
            // lost (chunk saved without it, dismounted): put a new one
            a.label.remove();
            a.label = null;
            label(a);
            return;
        }
        double max = Math.max(1, maxHealth(a.entity));
        double health = Math.max(0, a.entity.getHealth());
        double ratio = health / max;
        int step = (int) Math.floor(Math.min(1, ratio) * barSteps);
        if (step == a.labelStep && Math.abs(health - a.labelHealth) < 0.5) return;
        a.labelStep = step;
        a.labelHealth = health;
        int level = Math.max(0, a.template.extra().level());
        String head = a.template.name() + (level > 0 ? " &7Nv. &f" + level : "");
        String glyph = fr.influencraft.flux.api.items.CustomItems.bar(barGlyph, barSteps, ratio);
        String bar = glyph != null ? "&f" + glyph
                : fr.influencraft.flux.api.util.Format.bar(ratio, 1.0, barSteps, "&#FF5C5C", "&8");
        a.label.text(Texts.parse(head + "\n" + bar));
    }

    /** The monster's life just changed (hit, heal): its bar is redrawn on the next tick, once the change is applied. */
    public void lifeChanged(LivingEntity mob) {
        Alive a = alive.get(mob.getUniqueId());
        if (a == null || a.label == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (a.entity.isValid()) updateLabel(a);
        });
    }

    /** Default value of an attribute for this kind of creature (what vanilla gives it). */
    private static double vanilla(LivingEntity le, Attribute attribute, double fallback) {
        AttributeInstance inst = le.getAttribute(attribute);
        return inst == null ? fallback : inst.getDefaultValue();
    }

    /** A dungeon monster's normal hit (its attack damage, vanilla armour of the target applies). */
    private double hitOn(ScriptHost host, Player target) {
        Alive a = host instanceof Alive al ? al : alive.get(host.entity().getUniqueId());
        return a == null ? host.baseDamage() : a.baseDamage();
    }

    /** Level of a dungeon monster (its template's, shown to players; 0 when it has none). */
    public int level(LivingEntity mob) {
        Alive a = alive.get(mob.getUniqueId());
        return a == null ? 0 : Math.max(0, a.template.extra().level());
    }

    /** Players who dealt at least a tenth of the monster's life (they share its loot). */
    public Set<UUID> contributors(LivingEntity mob) {
        Alive a = alive.get(mob.getUniqueId());
        if (a == null) return Set.of();
        double tenth = maxHealth(mob) * 0.1;
        Set<UUID> out = new HashSet<>();
        a.damagers.forEach((u, d) -> {
            if (d >= tenth) out.add(u);
        });
        return out;
    }

    /** True while a hit computed here is being dealt (the listener leaves its damage as it is). */
    public boolean dealing() {
        return dealing;
    }

    private void deal(Player target, LivingEntity by, double amount) {
        dealing = true;
        try {
            target.damage(amount, by);
        } finally {
            dealing = false;
        }
    }

    /** An ability hit: its {@code power} is absolute, or a multiple of the monster's hit (built-in powers, {@code relative}). */
    private void strike(Alive a, Player p, Ability ab, double fallback) {
        double v = ab.number("power", fallback);
        boolean relative = ab.number("relative", 0) > 0 || !a.template.abilities().contains(ab);
        deal(p, a.entity, relative ? v * hitOn(a, p) : v);
    }

    private void cast(Alive a, Ability ab, Player target) {
        LivingEntity e = a.entity;
        if (!e.isValid()) return;
        World w = e.getWorld();
        Location at = e.getLocation();
        switch (ab.type()) {
            case "leap" -> {
                Vector v = target.getLocation().toVector().subtract(at.toVector());
                if (v.lengthSquared() > 400 || v.lengthSquared() < 4) return;
                e.setVelocity(v.multiply(0.18).setY(0.55));
                w.playSound(at, Sound.ENTITY_RAVAGER_STEP, 0.8f, 1.4f);
            }
            case "fireball" -> {
                Vector dir = target.getEyeLocation().toVector().subtract(e.getEyeLocation().toVector()).normalize();
                SmallFireball ball = w.spawn(e.getEyeLocation().add(dir), SmallFireball.class, f -> {
                    f.setShooter(e);
                    f.setIsIncendiary(false);
                });
                ball.setVelocity(dir.multiply(ab.number("speed", 1.2)));
                w.playSound(at, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 1f);
            }
            case "arrow", "volley" -> {
                int count = ab.type().equals("arrow") ? 1 : (int) ab.number("count", 5);
                double dmg = a.baseDamage() * ab.number("power", 1);
                Vector base = target.getEyeLocation().toVector().subtract(e.getEyeLocation().toVector()).normalize();
                for (int i = 0; i < count; i++) {
                    Vector dir = base.clone().add(new Vector((Math.random() - 0.5) * 0.25 * (count > 1 ? 1 : 0), (Math.random() - 0.3) * 0.1, (Math.random() - 0.5) * 0.25 * (count > 1 ? 1 : 0)));
                    Arrow arrow = w.spawn(e.getEyeLocation().add(dir), Arrow.class, ar -> {
                        ar.setShooter(e);
                        ar.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                        ar.setDamage(dmg / 2);
                    });
                    arrow.setVelocity(dir.normalize().multiply(1.8));
                }
                w.playSound(at, Sound.ENTITY_SKELETON_SHOOT, 1f, 1f);
            }
            case "cone" -> {
                double range = ab.number("range", 6);
                Vector dir = target.getLocation().toVector().subtract(at.toVector()).setY(0).normalize();
                boolean fire = ab.number("fire", 0) > 0;
                for (double d = 1; d <= range; d += 0.8) {
                    w.spawnParticle(fire ? Particle.FLAME : Particle.SNOWFLAKE, at.clone().add(0, 1.2, 0).add(dir.clone().multiply(d)), 6, d * 0.12, 0.2, d * 0.12, 0.01);
                }
                w.playSound(at, fire ? Sound.ENTITY_BLAZE_SHOOT : Sound.BLOCK_GLASS_BREAK, 1f, 0.8f);
                for (Player p : w.getPlayers()) {
                    if (!fighting(p)) continue;
                    Vector to = p.getLocation().toVector().subtract(at.toVector());
                    if (to.length() > range || to.clone().setY(0).normalize().dot(dir) < 0.7) continue;
                    strike(a, p, ab, 0.6);
                    if (fire) p.setFireTicks(60);
                    else p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2));
                }
            }
            case "taze" -> {
                w.spawnParticle(Particle.ELECTRIC_SPARK, target.getLocation().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.1);
                w.playSound(target.getLocation(), Sound.ENTITY_GUARDIAN_ATTACK, 1f, 1.6f);
                strike(a, target, ab, 0.8);
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 4));
            }
            case "lightning" -> {
                int count = (int) ab.number("count", 3);
                List<Player> near = new ArrayList<>();
                for (Player p : w.getPlayers()) if (fighting(p) && p.getLocation().distanceSquared(at) < 400) near.add(p);
                for (int i = 0; i < count && !near.isEmpty(); i++) {
                    Player p = near.get(ThreadLocalRandom.current().nextInt(near.size()));
                    Location spot = p.getLocation().clone();
                    w.spawnParticle(Particle.ELECTRIC_SPARK, spot, 20, 0.5, 0.1, 0.5, 0.05);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        w.strikeLightningEffect(spot);
                        for (Player q : w.getPlayers()) if (fighting(q) && q.getLocation().distanceSquared(spot) < 4) strike(a, q, ab, 1);
                    }, 20L + i * 10L);
                }
            }
            case "implosion" -> {
                double radius = ab.number("radius", 8);
                w.spawnParticle(Particle.PORTAL, at.clone().add(0, 1, 0), 80, radius / 2, 1, radius / 2, 0.5);
                w.playSound(at, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1f, 0.6f);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!e.isValid()) return;
                    Location c = e.getLocation();
                    for (Player p : w.getPlayers()) {
                        if (!fighting(p) || p.getLocation().distanceSquared(c) > radius * radius) continue;
                        p.setVelocity(c.toVector().subtract(p.getLocation().toVector()).normalize().multiply(1.2).setY(0.3));
                        strike(a, p, ab, 0.8);
                    }
                }, 20L);
            }
            case "summon" -> {
                MobTemplate child = templates.get(ab.text("mob", ""));
                if (child == null) return;
                int count = (int) ab.number("count", 2);
                w.playSound(at, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1f, 0.9f);
                for (int i = 0; i < count; i++) {
                    double angle = 2 * Math.PI * i / count;
                    Location l = at.clone().add(Math.cos(angle) * 2, 0, Math.sin(angle) * 2);
                    if (!l.getBlock().isPassable()) l = at.clone();
                    w.spawnParticle(Particle.LARGE_SMOKE, l, 10, 0.3, 0.5, 0.3, 0.02);
                    LivingEntity spawned = spawn(child, l, a.run);
                    if (spawned != null) onSummon.accept(e, spawned);
                }
            }
            case "summon_vanilla" -> {
                try {
                    EntityType type = EntityType.valueOf(ab.text("entity", "SKELETON"));
                    MobTemplate copy = new MobTemplate(a.template.id() + "_summon", type, "&7" + type.name().toLowerCase(), false, null,
                            maxHealth(e) * 0.15, a.baseDamage() * 0.6, 0.25, 0, 1, 0, Map.of(), List.of(),
                            new MobTemplate.Extra(1, 0.15, 0.6, false, false, 32, 0, List.of(), List.of(), null, List.of(), List.of(), List.of(),
                                    List.of(), List.of(), List.of(), Map.of(), "TRASH"));
                    for (int i = 0; i < (int) ab.number("count", 2); i++) {
                        LivingEntity s = spawn(copy, at.clone().add(ThreadLocalRandom.current().nextDouble(-2, 2), 0, ThreadLocalRandom.current().nextDouble(-2, 2)), a.run);
                        if (s != null) onSummon.accept(e, s);
                    }
                } catch (IllegalArgumentException ignored) {
                    // unknown entity
                }
            }
            case "slam" -> {
                String animation = ab.text("animation", "attack");
                long impact = (long) ab.number("impact", a.model == null ? 12 : 16);
                if (a.model != null) a.model.play(animation);
                double radius = ab.number("radius", 4);
                w.spawnParticle(Particle.DUST, at, 30, radius / 2, 0.1, radius / 2, 0, new Particle.DustOptions(org.bukkit.Color.RED, 1.5f));
                if (e instanceof Mob m) m.setAI(false);
                w.playSound(at, Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.6f);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (e instanceof Mob m) m.setAI(true);
                    if (!e.isValid() || a.dying) return;
                    Location hit = e.getLocation().add(e.getLocation().getDirection().setY(0).normalize().multiply(a.model == null ? 0 : radius * 0.5));
                    w.spawnParticle(Particle.EXPLOSION, hit, 2, 0.5, 0.1, 0.5, 0);
                    w.spawnParticle(Particle.LAVA, hit, 12, radius / 2, 0.2, radius / 2, 0);
                    w.playSound(hit, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 0.7f);
                    for (Player p : w.getPlayers()) {
                        if (!fighting(p) || p.getLocation().distanceSquared(hit) > radius * radius) continue;
                        strike(a, p, ab, 8);
                        p.setVelocity(p.getLocation().toVector().subtract(hit.toVector()).setY(0).normalize().multiply(0.7).setY(0.6));
                    }
                }, impact);
            }
            case "heal" -> {
                double max = maxHealth(e);
                e.setHealth(Math.min(max, e.getHealth() + max * ab.number("percent", 10) / 100));
                w.spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 1.5, 0), 12, 0.6, 0.6, 0.6, 0);
            }
            case "shield" -> {
                e.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, (int) (ab.number("duration", 4) * 20), 2));
                w.spawnParticle(Particle.ENCHANTED_HIT, at.clone().add(0, 1, 0), 20, 0.6, 0.8, 0.6, 0.1);
                w.playSound(at, Sound.ITEM_SHIELD_BLOCK, 1f, 0.8f);
            }
            case "enrage" -> {
                e.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, (int) (ab.number("duration", 8) * 20), 1));
                e.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, (int) (ab.number("duration", 8) * 20), 0));
                w.spawnParticle(Particle.FLAME, at.clone().add(0, 1, 0), 30, 0.6, 1, 0.6, 0.05);
                w.playSound(at, Sound.ENTITY_WITHER_AMBIENT, 0.6f, 1.6f);
            }
            default -> plugin.getLogger().warning("Unknown monster ability: " + ab.type());
        }
    }
}
