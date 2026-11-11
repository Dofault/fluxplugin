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
        /** Its file; replaced at a reload (monsters already out take the new values). */
        MobTemplate template;
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
        boolean inCombat, switching;
        /** Asleep, waiting for a scene (no AI, no ability, cannot be hurt): {@link #sleep}. */
        boolean dormant;
        /** Moves it in its water instead of walking ({@code swim} of its file), null for a walker. */
        Swimmer swimmer;
        /** Tick of its last melee hit on a player (vanilla's or the longer reach's). */
        int lastMelee = -1000;
        /** No ability before this time (cast-gap after the last one). */
        long nextCast;
        /** Server tick until which an ability keeps it still (hold), extended by later holds. */
        int holdUntil;
        /** Abilities cast once and done ({@code once: true}), and when each one became allowed by its life share. */
        final Set<Integer> spent = new HashSet<>();
        final Map<Integer, Long> allowedSince = new HashMap<>();
        /** Where it stood at the last cue tick, distance walked since its last step sound, ticks standing still. */
        Location lastPos;
        double walked;
        int still;
        /**
         * The player it fights, kept by FluxDungeons (not the vanilla target, which any plugin may cancel or clear:
         * every decision of the monster, abilities and melee, reads this one).
         */
        UUID target;

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
            if (model != null && animation != null && !animation.isBlank() && model.play(animation)) cue(this, animation);
        }
    }

    // ---------------------------------------------------------------- cues: sounds and effects of the model

    /**
     * Sounds and particles tied to an animation ({@code cues.<animation>} of the monster's file): a list of
     * {@code {tick, sound, volume, pitch, particle, count, height, spread}}, played from the moment the animation
     * starts. The model itself cannot carry sounds (BetterModel ignores sound keyframes).
     */
    private void cue(Alive a, String animation) {
        Object list = a.template.extra().cues().get(animation);
        if (!(list instanceof List<?> entries)) return;
        for (Object o : entries) {
            if (!(o instanceof Map<?, ?> m)) continue;
            long tick = m.get("tick") instanceof Number n ? n.longValue() : 0;
            Runnable play = () -> {
                if (!a.entity.isValid()) return;
                play(a.entity.getLocation(), m);
            };
            if (tick <= 0) play.run();
            else Bukkit.getScheduler().runTaskLater(plugin, play, tick);
        }
    }

    private static void play(Location at, Map<?, ?> m) {
        World w = at.getWorld();
        double height = m.get("height") instanceof Number n ? n.doubleValue() : 1;
        if (m.get("sound") != null) {
            float volume = m.get("volume") instanceof Number n ? n.floatValue() : 1f;
            float pitch = m.get("pitch") instanceof Number n ? n.floatValue() : 1f;
            w.playSound(at, String.valueOf(m.get("sound")), org.bukkit.SoundCategory.HOSTILE, volume, pitch);
        }
        if (m.get("particle") != null) {
            try {
                Particle p = Particle.valueOf(String.valueOf(m.get("particle")).toUpperCase(java.util.Locale.ROOT));
                int count = m.get("count") instanceof Number n ? n.intValue() : 10;
                double spread = m.get("spread") instanceof Number n ? n.doubleValue() : 0.5;
                Location l = at.clone().add(0, height, 0);
                if (p.getDataType() == Particle.DustOptions.class) {
                    w.spawnParticle(p, l, count, spread, spread * 0.6, spread, 0, new Particle.DustOptions(org.bukkit.Color.fromRGB(0xFFD94D), 1.2f));
                } else if (p.getDataType() == Void.class) {
                    w.spawnParticle(p, l, count, spread, spread * 0.6, spread, 0.02);
                }
            } catch (IllegalArgumentException ignored) {
                // unknown particle
            }
        }
    }

    /**
     * Every 2 ticks: step sounds of a walking monster ({@code cues.steps: {sound, stride, volume, pitch}}: one sound
     * every {@code stride} blocks walked) and, standing still out of any ability, now and then a little animation or
     * sound of its own ({@code cues.ambient: {every, chance, animations, sounds}}).
     */
    private void cueTick() {
        for (Alive a : alive.values()) {
            if (!a.entity.isValid()) continue;
            Map<String, Object> cues = a.template.extra().cues();
            if (cues.isEmpty()) continue;
            Location now = a.entity.getLocation();
            double moved = a.lastPos != null && a.lastPos.getWorld() == now.getWorld()
                    ? Math.hypot(now.getX() - a.lastPos.getX(), now.getZ() - a.lastPos.getZ()) : 0;
            a.lastPos = now;
            if (moved > 0.02) {
                a.still = 0;
                if (cues.get("steps") instanceof Map<?, ?> steps) {
                    double stride = steps.get("stride") instanceof Number n ? n.doubleValue() : 0.6;
                    a.walked += moved;
                    if (a.walked >= stride) {
                        a.walked = 0;
                        play(now, steps);
                    }
                }
                continue;
            }
            a.still += 2;
            if (!(cues.get("ambient") instanceof Map<?, ?> amb) || Bukkit.getCurrentTick() < a.holdUntil) continue;
            int every = amb.get("every") instanceof Number n ? n.intValue() : 120;
            if (a.still < every) continue;
            a.still = 0;
            // never in the middle of a fight
            Player near = fightingTarget(a);
            if (near != null && near.getWorld() == now.getWorld() && near.getLocation().distanceSquared(now) < 64) continue;
            double chance = amb.get("chance") instanceof Number n ? n.doubleValue() : 0.5;
            ThreadLocalRandom r = ThreadLocalRandom.current();
            if (r.nextDouble() > chance) continue;
            List<?> anims = amb.get("animations") instanceof List<?> l ? l : List.of();
            List<?> sounds = amb.get("sounds") instanceof List<?> l ? l : List.of();
            if (!anims.isEmpty() && (sounds.isEmpty() || r.nextBoolean())) {
                a.animate(String.valueOf(anims.get(r.nextInt(anims.size()))));
            } else if (!sounds.isEmpty()) {
                a.entity.getWorld().playSound(now, String.valueOf(sounds.get(r.nextInt(sounds.size()))),
                        org.bukkit.SoundCategory.HOSTILE, 1.2f, (float) r.nextDouble(0.9, 1.3));
            }
        }
    }

    /** Scoreboard tag of every dungeon monster: other plugins leave tagged mobs alone (FluxStack never stacks them). */
    public static final String TAG = "fluxdungeons";
    /** Scoreboard tag of a boss: nothing pushes it (hits, Knockback, class abilities). */
    public static final String BOSS_TAG = "fluxdungeons_boss";
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
        this.shotKey = new NamespacedKey(plugin, "mob_shot");
        this.healthScaleKey = new NamespacedKey(plugin, "health_scale");
        this.pilotKey = com.destroystokyo.paper.entity.ai.GoalKey.of(Mob.class, new NamespacedKey(plugin, "pilot"));
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
        Bukkit.getScheduler().runTaskTimer(plugin, this::reachTick, 2L, 2L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::cueTick, 2L, 2L);
        this.rock = new RockPowers(plugin);
        this.fx = new Fx(plugin);
        this.harvest = new HarvestPowers(plugin, fx);
        this.copper = new CopperPowers(plugin, fx);
        this.mole = new MolePowers(plugin, fx);
        this.tide = new TidePowers(plugin);
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
        refreshAlive();
    }

    /**
     * After a reload, monsters already out take their new file: abilities (their clocks start again), attack, walking
     * speed, name and life bar. Their life is left as it is (a fight in progress is not healed).
     */
    private void refreshAlive() {
        for (Alive a : alive.values()) {
            MobTemplate t = templates.get(a.template.id());
            if (t == null || !a.entity.isValid()) continue;
            a.template = t;
            a.abilities.clear();
            a.abilities.addAll(t.abilities());
            a.abilities.addAll(a.kit.abilities());
            a.lastCast.clear();
            a.nextCast = 0;
            boolean imported = t.extra().level() > 0;
            if (!imported) base(a.entity, Attribute.ATTACK_DAMAGE, t.damage() * scaling.apply(a.run)[1]);
            if (t.speed() > 0) base(a.entity, Attribute.MOVEMENT_SPEED, t.speed());
            if (t.walkSpeed() > 0) base(a.entity, Attribute.MOVEMENT_SPEED, Math.sqrt(t.walkSpeed() / 44.0));
            a.entity.customName(Texts.parse(t.name()));
            bar(a);
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

    /** Whether this monster is moved by its {@link Swimmer} (it puts itself back in place every tick). */
    public boolean swims(Entity entity) {
        Alive a = alive.get(entity.getUniqueId());
        return a != null && a.swimmer != null;
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
            if (t.boss()) {
                le.addScoreboardTag(BOSS_TAG);
                // nothing pushes a boss, not even players walking into it
                le.setCollidable(false);
            }
            le.customName(Texts.parse(t.name()));
            // the name shows on the life bar above it; without the bar, on the vanilla name tag
            le.setCustomNameVisible(!t.boss() && (bars == null || !bars.enabled()));
            le.setRemoveWhenFarAway(false);
            le.setPersistent(true);
            le.setCanPickupItems(false);
            // no vanilla invulnerability after a hit: every source lands (limits per source in DungeonListener)
            le.setMaximumNoDamageTicks(0);
            double[] level = run == null ? NO_SCALE : scaling.apply(run);
            double health = (imported ? vanilla(le, Attribute.MAX_HEALTH, 20) * x.healthMultiplier() : t.health()) * level[0];
            maxHealth(le, health);
            base(le, Attribute.ATTACK_DAMAGE, (imported ? vanilla(le, Attribute.ATTACK_DAMAGE, 2) * x.damageMultiplier() : t.damage()) * level[1]);
            if (t.speed() > 0) base(le, Attribute.MOVEMENT_SPEED, t.speed());
            // walk-speed: blocks per second on flat ground. A mob's walk grows with the SQUARE of its speed attribute (the
            // game uses it both as its input and as its acceleration): about 44 × speed² blocks a second (zombie 0.23 =
            // 2.3 blocks a second), so speed = sqrt(blocks a second / 44)
            if (t.walkSpeed() > 0) base(le, Attribute.MOVEMENT_SPEED, Math.sqrt(t.walkSpeed() / 44.0));
            if (!imported) base(le, Attribute.ARMOR, t.armor());
            base(le, Attribute.SCALE, t.scale());
            base(le, Attribute.FOLLOW_RANGE, x.followRange());
            le.setHealth(le.getAttribute(Attribute.MAX_HEALTH).getValue());
            EntityEquipment eq = le.getEquipment();
            if (eq != null) {
                eq.clear();
                t.equipment().forEach((slot, material) -> {
                    ItemStack item = new ItemStack(material);
                    // looks only: a weapon or an armour piece never adds its own damage or armour to the monster
                    item.setData(io.papermc.paper.datacomponent.DataComponentTypes.ATTRIBUTE_MODIFIERS,
                            io.papermc.paper.datacomponent.item.ItemAttributeModifiers.itemAttributes().build());
                    org.bukkit.Color dye = x.dyes().get(slot);
                    if (dye != null) item.editMeta(m -> {
                        if (m instanceof org.bukkit.inventory.meta.LeatherArmorMeta lm) lm.setColor(dye);
                    });
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
        control(a);
        swim(a);
        bar(a);
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
        le.setMaximumNoDamageTicks(0);
        if (t.boss()) {
            le.addScoreboardTag(BOSS_TAG);
            le.setCollidable(false);
        }
        Alive a = new Alive(le, t, run, spawn, model);
        a.objective = le.getPersistentDataContainer().get(objectiveKey, PersistentDataType.STRING);
        alive.put(le.getUniqueId(), a);
        control(a);
        swim(a);
        bar(a);
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

    /**
     * The monster fights this player from now on (it saw him, he hit it, its pack warned it): remembered as its own
     * target, vanilla target set too (only for its look and path), its calm pack alerted.
     */
    private void engage(Alive a, Player player) {
        if (player == null || !fighting(player)) return;
        boolean wasCalm = !a.inCombat;
        a.target = player.getUniqueId();
        if (a.entity instanceof Mob m && m.getTarget() != player) m.setTarget(player);
        enterCombat(a, player, true);
        if (wasCalm) alert(a, player);
    }

    /** The player a monster fights now (its own target), null when none or when he is out of reach. */
    private static Player fightingTarget(Alive a) {
        if (a.target == null) return null;
        Player p = Bukkit.getPlayer(a.target);
        if (p == null || !fighting(p) || p.getWorld() != a.entity.getWorld()
                || p.getLocation().distanceSquared(a.entity.getLocation()) > 48 * 48) {
            a.target = null;
            return null;
        }
        return p;
    }

    private void enterCombat(Alive a, Player player, boolean event) {
        a.lastEngaged = System.currentTimeMillis();
        if (a.inCombat) return;
        a.inCombat = true;
        calm(a, false);
        if (event) engine.fire(a, "EliteMobEnterCombatEvent", player, null);
    }

    // ---------------------------------------------------------------- swimmers

    /** A monster with a {@code swim} section swims in the water around it (see {@link Swimmer}). */
    private void swim(Alive a) {
        if (a.template.extra().swim().isEmpty()) return;
        a.swimmer = Swimmer.start(plugin, new Swimmer.Host() {
            public LivingEntity entity() {
                return a.entity;
            }

            public boolean over() {
                return a.switching || !alive.containsKey(a.entity.getUniqueId());
            }

            public Player target() {
                Player t = fightingTarget(a);
                if (t != null) return t;
                Player best = null;
                double bd = 40 * 40;
                for (Player p : a.entity.getWorld().getPlayers()) {
                    if (!fighting(p)) continue;
                    double d = p.getLocation().distanceSquared(a.entity.getLocation());
                    if (d < bd) {
                        bd = d;
                        best = p;
                    }
                }
                if (best != null) engage(a, best);
                return best;
            }

            public void touch(Player p, double power) {
                deal(p, a.entity, power * scaling.apply(a.run)[1]);
            }
        }, a.template.extra().swim());
        if (a.swimmer == null) plugin.getLogger().warning("Monster " + a.template.id() + " should swim but has no water around it.");
    }

    // ---------------------------------------------------------------- controlled monsters

    private final com.destroystokyo.paper.entity.ai.GoalKey<Mob> pilotKey;

    /**
     * A {@code controlled} monster loses every vanilla behaviour (some creatures rewrite their own speed each tick, as
     * the ravager does) and gets {@link Pilot} instead: FluxDungeons alone decides where it goes and how fast (its
     * {@code speed}), and its melee hits come from {@link #reachTick}.
     */
    private void control(Alive a) {
        if (!a.template.extra().controlled() || !(a.entity instanceof Mob m)) return;
        if (a.template.extra().silent()) m.setSilent(true);
        Bukkit.getMobGoals().removeAllGoals(m);
        Bukkit.getMobGoals().addGoal(m, 0, new Pilot(a));
    }

    /** The whole behaviour of a controlled monster: find a player, walk to him, face him. */
    private final class Pilot implements com.destroystokyo.paper.entity.ai.Goal<Mob> {
        private final Alive a;
        private int ticks;

        Pilot(Alive a) {
            this.a = a;
        }

        @Override
        public boolean shouldActivate() {
            return true;
        }

        @Override
        public void tick() {
            if (!(a.entity instanceof Mob m)) return;
            if (a.swimmer != null || a.dormant) return;
            ticks++;
            // the vanilla float goal went with the rest of the AI: without it a piloted monster sinks to the bottom
            if (m.isInWater() && ThreadLocalRandom.current().nextFloat() < 0.8f) m.setJumping(true);
            Player target = fightingTarget(a);
            if (target == null && ticks % 10 == 0) {
                // calm: sees players within its follow range (shortened by calm, but a boss sees as far as its file
                // says: it guards its room), never through walls
                AttributeInstance sight = m.getAttribute(Attribute.FOLLOW_RANGE);
                double range = a.template.boss() ? a.template.extra().followRange() : sight == null ? 16 : sight.getValue();
                double best = range * range;
                for (Player p : m.getWorld().getPlayers()) {
                    if (!fighting(p)) continue;
                    double d = p.getLocation().distanceSquared(m.getLocation());
                    if (d < best && m.hasLineOfSight(p)) {
                        best = d;
                        target = p;
                    }
                }
                if (target != null) engage(a, target);
            }
            if (target == null) {
                m.getPathfinder().stopPathfinding();
                return;
            }
            m.lookAt(target);
            if (a.template.extra().stationary()) {
                m.getPathfinder().stopPathfinding();
                return;
            }
            org.bukkit.util.BoundingBox reach = m.getBoundingBox().expand(VANILLA_REACH + reachBonus - 0.4, 0, VANILLA_REACH + reachBonus - 0.4);
            if (reach.overlaps(target.getBoundingBox())) {
                m.getPathfinder().stopPathfinding();
            } else if (ticks % 10 == 0 || !m.getPathfinder().hasPath()) {
                m.getPathfinder().moveTo(target, 1.0);
            }
        }

        @Override
        public com.destroystokyo.paper.entity.ai.GoalKey<Mob> getKey() {
            return pilotKey;
        }

        @Override
        public java.util.EnumSet<com.destroystokyo.paper.entity.ai.GoalType> getTypes() {
            // MOVE and LOOK only: with TARGET, Paper puts the goal among the target goals, whose ticks never walk the body
            return java.util.EnumSet.of(com.destroystokyo.paper.entity.ai.GoalType.MOVE, com.destroystokyo.paper.entity.ai.GoalType.LOOK);
        }
    }

    /**
     * {@code invulnerable} on an ability (its ultimate): {@code true} = nothing hurts the monster while it holds for
     * that ability ({@code hold} ticks), a number = that many ticks. Its life bar shows it is out of reach.
     */
    private void shield(Alive a, Ability ab) {
        Object v = ab.params().get("invulnerable");
        int ticks = Boolean.TRUE.equals(v) ? (int) ab.number("hold", 0) : v instanceof Number n ? n.intValue() : 0;
        if (ticks <= 0) return;
        LivingEntity e = a.entity;
        e.setInvulnerable(true);
        e.getWorld().playSound(e.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.5f, 0.6f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // a monster changing phase keeps the state its scene gave it
            if (e.isValid() && !a.switching) e.setInvulnerable(false);
        }, ticks);
    }

    // ---------------------------------------------------------------- charge

    /**
     * A rush in a straight line ({@code type: charge}): the animation starts, a line on the ground shows the way during
     * {@code impact} ticks (the direction is taken at the end of it, towards where the target stands then: a player
     * may step aside), then the monster moves {@code distance} blocks at most in {@code duration} ticks, slowing at the
     * end, stopped by a wall. Every player within {@code width} on its way is hit once and thrown ({@code knockback}).
     * {@code return: true} brings it back to where it started in {@code return-ticks}. Still and without gravity all
     * along: the monster is moved by teleports, its model plays the rush.
     */
    private void charge(Alive a, Ability ab, Player target) {
        LivingEntity e = a.entity;
        World w = e.getWorld();
        int windup = (int) Math.max(1, ab.number("impact", 10));
        int dash = (int) Math.max(1, ab.number("duration", 12));
        double distance = ab.number("distance", 8), width = ab.number("width", 2.5), knock = ab.number("knockback", 1);
        boolean back = ab.flag("return", false);
        int backTicks = back ? (int) Math.max(1, ab.number("return-ticks", 20)) : 0;
        int total = (int) Math.max(windup + dash + backTicks, ab.number("hold", 0));
        org.bukkit.Color color = org.bukkit.Color.fromRGB(Integer.parseInt(ab.text("color", "#43B7B0").replace("#", ""), 16));
        a.animate(ab.text("animation", ""));
        if (e instanceof Mob m) m.setAI(false);
        e.setGravity(false);
        Location start = e.getLocation().clone();
        Set<UUID> hit = new HashSet<>();
        new org.bukkit.scheduler.BukkitRunnable() {
            int t;
            Vector dir;
            double length;
            Location from, reached;

            @Override
            public void run() {
                if (!e.isValid() || a.switching) {
                    end();
                    return;
                }
                t++;
                if (t <= windup) {
                    // the way it will rush, drawn on the ground towards the target, brighter as it comes
                    Vector to = target.getLocation().toVector().subtract(e.getLocation().toVector()).setY(0);
                    if (to.lengthSquared() > 0.01 && t % 2 == 0) {
                        double len = Math.min(distance, to.length() + 1.5);
                        Vector step = to.normalize();
                        for (double d = 1; d <= len; d += 0.6) {
                            w.spawnParticle(Particle.DUST, e.getLocation().add(step.clone().multiply(d)).add(0, 0.15, 0), 1,
                                    0.15, 0.02, 0.15, 0, new Particle.DustOptions(color, 1.2f + (float) t / windup));
                        }
                    }
                    if (t == windup) {
                        Vector to2 = target.getLocation().toVector().subtract(e.getLocation().toVector()).setY(0);
                        dir = to2.lengthSquared() < 0.01 ? e.getLocation().getDirection().setY(0) : to2;
                        if (dir.lengthSquared() < 0.01) dir = new Vector(0, 0, 1);
                        dir.normalize();
                        length = Math.min(distance, to2.length() + 2);
                        from = e.getLocation().clone();
                        from.setDirection(dir);
                        move(e, from);
                        w.playSound(from, e.isInWater() ? Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED : Sound.ENTITY_RAVAGER_ATTACK, 1.5f, 0.8f);
                    }
                    return;
                }
                if (t <= windup + dash) {
                    double u = (double) (t - windup) / dash;
                    // fast, then braking over the last third
                    double f = u < 0.66 ? u / 0.66 * 0.8 : 0.8 + 0.2 * Math.sin((u - 0.66) / 0.34 * Math.PI / 2);
                    Location next = from.clone().add(dir.clone().multiply(length * f));
                    if (blocked(next)) {
                        reached = e.getLocation().clone();
                        t = windup + dash;
                    } else {
                        move(e, next);
                        reached = next;
                    }
                    Location at = e.getLocation();
                    w.spawnParticle(e.isInWater() ? Particle.SPLASH : Particle.CLOUD, at.clone().add(0, 0.3, 0), 12, 0.5, 0.2, 0.5, 0.1);
                    if (e.isInWater()) w.spawnParticle(Particle.BUBBLE, at, 8, 0.4, 0.3, 0.4, 0.05);
                    for (Player p : w.getPlayers()) {
                        if (!fighting(p) || hit.contains(p.getUniqueId())) continue;
                        Location pl = p.getLocation();
                        if (Math.hypot(pl.getX() - at.getX(), pl.getZ() - at.getZ()) > width / 2 + 0.6 || Math.abs(pl.getY() - at.getY()) > 2.2) continue;
                        hit.add(p.getUniqueId());
                        strike(a, p, ab, 6);
                        Vector side = pl.toVector().subtract(at.toVector()).setY(0);
                        Vector push = dir.clone().multiply(0.8).add(side.lengthSquared() > 0.01 ? side.normalize().multiply(0.5) : new Vector());
                        p.setVelocity(push.multiply(knock).setY(0.45 * knock));
                        w.playSound(pl, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.2f, 0.7f);
                    }
                    return;
                }
                if (t <= windup + dash + backTicks && reached != null) {
                    // back to its place, facing where it goes, then facing the fight again
                    double u = (double) (t - windup - dash) / backTicks;
                    double f = u * u * (3 - 2 * u);
                    Location l = reached.clone().add(start.toVector().subtract(reached.toVector()).multiply(f));
                    l.setDirection(start.toVector().subtract(reached.toVector()).setY(0).lengthSquared() > 0.01
                            ? start.toVector().subtract(reached.toVector()).setY(0) : dir);
                    if (u >= 1) l.setDirection(dir);
                    move(e, l);
                    return;
                }
                if (t >= total) end();
            }

            private void end() {
                cancel();
                if (!e.isValid()) return;
                e.setGravity(true);
                if (e instanceof Mob m && !a.switching) m.setAI(true);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Whether a body of about two blocks high cannot stand at this spot (a solid block at its feet or head). */
    private static boolean blocked(Location at) {
        org.bukkit.block.Block feet = at.getBlock(), head = feet.getRelative(org.bukkit.block.BlockFace.UP);
        return (feet.getType().isSolid() && !feet.isPassable()) || (head.getType().isSolid() && !head.isPassable());
    }

    // ---------------------------------------------------------------- pack alert

    /** Monsters this close to one that spots a player join the fight (aggro.alert-radius). */
    private double alertRadius = 10;
    /** True while a pack is being alerted: the monsters it wakes do not alert their own neighbours (no chain). */
    private boolean alerting;

    public void alertRadius(double blocks) {
        alertRadius = Math.max(0, blocks);
    }

    /**
     * A monster just spotted (or was hit by) a player: the calm monsters of its run around it that see it turn on that
     * player too, as a pack (a skeleton that sees you warns those behind it). Only its direct neighbours, never further
     * in a chain, so one pull never wakes the whole map.
     */
    private void alert(Alive a, Player player) {
        if (alerting || alertRadius <= 0 || !fighting(player)) return;
        alerting = true;
        try {
            double r2 = alertRadius * alertRadius;
            for (Alive o : List.copyOf(alive.values())) {
                if (o == a || o.inCombat || !o.run.equals(a.run) || !(o.entity instanceof Mob m) || !m.hasAI()) continue;
                if (o.entity.getWorld() != a.entity.getWorld() || o.entity.getLocation().distanceSquared(a.entity.getLocation()) > r2) continue;
                if (!a.entity.hasLineOfSight(o.entity)) continue;
                o.target = player.getUniqueId();
                m.setTarget(player);
                enterCombat(o, player, true);
            }
        } finally {
            alerting = false;
        }
    }

    private static void base(LivingEntity e, Attribute attribute, double value) {
        AttributeInstance inst = e.getAttribute(attribute);
        if (inst != null) inst.setBaseValue(value);
    }

    /** Removes every monster of a run (end of run). */
    // ---------------------------------------------------------------- scenes (dungeon features driving a monster)

    /**
     * Puts a monster to sleep for a scene (true: no AI, no ability, cannot be hurt, stays where it is) or wakes it up
     * (false). The boss of a lair waits asleep in its nest until the players find it.
     */
    public void sleep(LivingEntity e, boolean asleep) {
        Alive a = alive.get(e.getUniqueId());
        if (a == null) return;
        a.dormant = asleep;
        e.setInvulnerable(asleep);
        if (e instanceof Mob m) {
            m.setAI(!asleep);
            if (asleep) m.getPathfinder().stopPathfinding();
        }
    }

    /** A new home for a monster (where it is pulled back, where its abilities think its arena is). */
    public void rehome(LivingEntity e, Location home) {
        Alive a = alive.get(e.getUniqueId());
        if (a == null) return;
        a.spawn.setWorld(home.getWorld());
        a.spawn.setX(home.getX());
        a.spawn.setY(home.getY());
        a.spawn.setZ(home.getZ());
    }

    /** Plays an animation of a monster's model (with its cues), false when it has none. */
    public boolean play(LivingEntity e, String animation) {
        Alive a = alive.get(e.getUniqueId());
        if (a == null || a.model == null) return false;
        a.animate(animation);
        return true;
    }

    /** Plays an animation of a monster's model in a loop, or stops it. */
    public void loop(LivingEntity e, String animation, boolean on) {
        Alive a = alive.get(e.getUniqueId());
        if (a == null || a.model == null) return;
        if (on) a.model.loop(animation);
        else a.model.stop(animation);
    }

    /** Shows or hides bones of a monster's model. */
    public void parts(LivingEntity e, boolean visible, java.util.Collection<String> bones) {
        Alive a = alive.get(e.getUniqueId());
        if (a != null && a.model != null) a.model.parts(visible, bones);
    }

    /** Effects shared by every boss (cracks, eruptions, collapses...), for dungeon features. */
    public Fx fx() {
        return fx;
    }

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

    /** Moving blocks of the rock abilities. */
    private RockPowers rock;
    /** Abilities of the water (geysers, whirlpool, bubbles, tidal breach). */
    private TidePowers tide;
    private HarvestPowers harvest;
    /** Abilities of the Copper T-Rex (Overcharge, bite, tail sweep, drill charge, resonance, veins). */
    private CopperPowers copper;
    /** Abilities of the Field Mole (ram, burrow and surge, drill sweep, seismic drill). */
    private MolePowers mole;
    private Fx fx;

    public void removeAll() {
        if (rock != null) rock.clear();
        if (tide != null) tide.clear();
        if (harvest != null) harvest.clear();
        if (copper != null) copper.clear();
        if (mole != null) mole.clear();
        if (fx != null) fx.clear();
        for (Alive a : alive.values()) {
            close(a);
            a.entity.remove();
        }
        alive.clear();
    }

    /** Chunk tickets are released by the run (whole map world unloaded, or arena slot released), not per monster. */
    private void close(Alive a) {
        if (a.model != null) a.model.close();
        if (bars != null) bars.remove(a.entity);
        engine.forget(a);
    }

    // ---------------------------------------------------------------- events from the listener

    /** A dungeon monster was hit by a player. */
    public void damagedByPlayer(LivingEntity entity, Player player, EntityDamageEvent event) {
        Alive a = alive.get(entity.getUniqueId());
        if (a == null) return;
        engage(a, player);
        a.damagers.merge(player.getUniqueId(), Math.min(event.getFinalDamage(), entity.getHealth()), Double::sum);
        engine.fire(a, "EliteMobDamagedByPlayerEvent", player, event);
        counter(a, player, event);
        if (!a.template.extra().hurtMessages().isEmpty() && ThreadLocalRandom.current().nextDouble() < 0.15) say(a, a.template.extra().hurtMessages());
    }

    /**
     * Reflexes against players who shoot from afar ({@code counter_shot} abilities): hit by a projectile of a player
     * at least {@code min-range} blocks away, the monster answers at once, even in the middle of another ability,
     * at most every {@code every} seconds. Never drawn among the other abilities.
     */
    private void counter(Alive a, Player shooter, EntityDamageEvent event) {
        if (!(event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent ee)
                || !(ee.getDamager() instanceof org.bukkit.entity.Projectile) || !castsAbilities(a)) return;
        long now = System.currentTimeMillis();
        double dist = shooter.getLocation().distance(a.entity.getLocation());
        for (int i = 0; i < a.abilities.size(); i++) {
            Ability ab = a.abilities.get(i);
            if (!"counter_shot".equals(ab.type()) || dist < ab.number("min-range", 8)) continue;
            double life = 100 * a.entity.getHealth() / Math.max(1, maxHealth(a.entity));
            if (life > ab.below()) continue;
            Long last = a.lastCast.get(i);
            if (last != null && now - last < ab.every() * 1000) continue;
            a.lastCast.put(i, now);
            Bukkit.getScheduler().runTask(plugin, () -> cast(a, ab, shooter));
            return;
        }
    }

    /** Highest max health Minecraft allows a creature (the attribute is clamped there). */
    private static final double HEALTH_CAP = 1024;

    /**
     * Sets a monster's max health, even beyond what Minecraft allows (1024): above it the monster keeps 1024 and every
     * hit it takes is divided by the same factor ({@code health_scale} in its data), so it lasts as long as asked.
     */
    public void maxHealth(LivingEntity le, double health) {
        double scale = health > HEALTH_CAP ? health / HEALTH_CAP : 1;
        base(le, Attribute.MAX_HEALTH, Math.min(health, HEALTH_CAP));
        if (scale > 1) le.getPersistentDataContainer().set(healthScaleKey, PersistentDataType.DOUBLE, scale);
        else le.getPersistentDataContainer().remove(healthScaleKey);
    }

    /** How much a monster's life is stretched beyond the 1024 cap (1 = not). */
    public double healthScale(LivingEntity le) {
        Double v = le.getPersistentDataContainer().get(healthScaleKey, PersistentDataType.DOUBLE);
        return v == null || v < 1 ? 1 : v;
    }

    /** A dungeon monster took damage of any kind; returns true when the damage must be cancelled (immunity). */
    public boolean damaged(LivingEntity entity, EntityDamageEvent event) {
        Alive a = alive.get(entity.getUniqueId());
        if (a == null) return false;
        if (a.kit.immune().contains(event.getCause())) return true;
        double stretch = healthScale(entity);
        if (stretch > 1) event.setDamage(event.getDamage() / stretch);
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
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && !dealing) a.lastMelee = Bukkit.getCurrentTick();
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
        engage(a, player);
        engine.fire(a, "EliteMobTargetPlayerEvent", player, null);
    }

    private void hurt(Alive a) {
        if (a.model == null) return;
        // an attack in progress is never cut by a flinch (it holds the monster still)
        if (Bukkit.getCurrentTick() < a.holdUntil) return;
        long now = System.currentTimeMillis();
        if (now - a.lastHurt < 500) return;
        a.lastHurt = now;
        // a second reaction, when the model has one, half of the time
        if (ThreadLocalRandom.current().nextBoolean() && a.model.play("hurt_2")) cue(a, "hurt_2");
        else if (a.model.play("hurt")) cue(a, "hurt");
    }

    /** Below the share of life of its next phase, the monster becomes the next one, life share kept. */
    private void checkPhase(Alive a) {
        if (a.switching || !a.entity.isValid() || a.nextPhase >= a.phases.size()) return;
        double ratio = a.entity.getHealth() / Math.max(1, maxHealth(a.entity));
        MobTemplate.Phase phase = a.phases.get(a.nextPhase);
        if (ratio > phase.below()) return;
        MobTemplate next = templates.get(phase.mob());
        if (next == null) {
            a.nextPhase = a.phases.size();
            return;
        }
        a.switching = true;
        if (phase.transition() && a.model != null) {
            transition(a, phase, next);
            return;
        }
        becomeNext(a, next, ratio);
    }

    /** Removes the monster and puts the next one of its phases in its place, with the same share of life. */
    private void becomeNext(Alive a, MobTemplate next, double ratio) {
        Location at = a.entity.getLocation();
        blocks(a, a.template.extra().removeBlocks());
        alive.remove(a.entity.getUniqueId());
        close(a);
        a.entity.remove();
        LivingEntity child = spawnPhase(next, at, a, ratio);
        if (child != null) onSummon.accept(a.entity, child);
    }

    /**
     * Phase switch played as a scene: the monster stops, invulnerable and without gravity (the animation moves the
     * model, not the body), maybe back at its spawn point; the blocks under it change at the tick given
     * ({@code flood}: ice breaking into water); once the animation is over, the next monster takes its place.
     */
    private void transition(Alive a, MobTemplate.Phase phase, MobTemplate next) {
        LivingEntity e = a.entity;
        e.setInvulnerable(true);
        if (e instanceof Mob m) {
            m.setAI(false);
            m.setTarget(null);
        }
        e.setGravity(false);
        e.setVelocity(new Vector());
        if (phase.atSpawn() && e.getLocation().distanceSquared(a.spawn) > 0.25) {
            Location back = a.spawn.clone();
            back.setYaw(e.getLocation().getYaw());
            e.getWorld().spawnParticle(Particle.CLOUD, e.getLocation().add(0, 0.5, 0), 20, 0.6, 0.3, 0.6, 0.02);
            move(e, back);
        }
        a.model.play(phase.animation());
        MobTemplate.Flood flood = phase.flood();
        if (flood != null) {
            int at = Math.max(0, flood.at());
            if (flood.crack() >= 0 && flood.crack() < at) Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (e.isValid()) FloorBreak.crack(plugin, e.getLocation(), flood, at - flood.crack());
            }, flood.crack());
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (e.isValid()) FloorBreak.burst(plugin, e.getLocation(), flood);
            }, at);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!e.isValid() || !alive.containsKey(e.getUniqueId())) return;
            double ratio = e.getHealth() / Math.max(1, maxHealth(e));
            becomeNext(a, next, ratio);
        }, Math.max(1, phase.duration()));
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
                if (!a.entity.isDead()) {
                    close(a);
                    it.remove();
                }
                continue;
            }
            if (a.switching) continue;
            double leash = a.template.extra().leash();
            if (leash > 0 && a.entity.getLocation().distanceSquared(a.spawn) > leash * leash) move(a.entity, a.spawn);
            if (!a.template.extra().scripts().isEmpty()) later.add(() -> engine.pollZones(a));
            if (a.dormant || !a.entity.hasAI()) continue;
            Player target = target(a);
            keepLow(a, target);
            if (target == null) {
                if (a.inCombat && now - a.lastEngaged > COMBAT_TIMEOUT_MS) {
                    a.inCombat = false;
                    a.target = null;
                    calm(a, true);
                    if (a.entity instanceof Mob m) m.setTarget(null);
                    later.add(() -> engine.fire(a, "EliteMobExitCombatEvent", null, null));
                    a.animate("victory");
                }
                continue;
            }
            a.lastEngaged = now;
            double life = 100 * a.entity.getHealth() / Math.max(1, maxHealth(a.entity));
            // one ability at a time, drawn among those ready (the first of the list would always win), cast-gap apart
            if (now < a.nextCast || !castsAbilities(a) || (a.swimmer != null && !a.swimmer.mayCast())) continue;
            List<Integer> ready = new ArrayList<>();
            double reach = target.getLocation().distance(a.entity.getLocation());
            for (int i = 0; i < a.abilities.size(); i++) {
                Ability ab = a.abilities.get(i);
                if (life > ab.below() || a.spent.contains(i) || "counter_shot".equals(ab.type())) continue;
                // wait: seconds after its life share is reached before the first cast (an ultimate kept for later)
                long since = a.allowedSince.computeIfAbsent(i, k -> now);
                if (now - since < ab.number("wait", 0) * 1000) continue;
                // first time seen: its clock starts half way (a fresh monster does not wait a full cooldown), or
                // full for an opening move; stored once, else "now" moved with it and no ability was ever ready
                long last = a.lastCast.computeIfAbsent(i, k -> now - (long) (ab.every() * (ab.flag("opening", false) ? 1000 : 500)) - 1);
                // distance to its target (target-min / target-max, blocks)
                if (reach < ab.number("target-min", 0) || reach > ab.number("target-max", 1000)) continue;
                // ready, and somebody to hit (a ring cast with nobody in it is thrown in the void)
                if (now - last >= ab.every() * 1000 && RockPowers.worthCasting(ab, a.entity)
                        && harvest.worth(ab, a.entity, a.spawn) && copper.worth(ab, a.entity, a.spawn)
                        && mole.worth(ab, a.entity)) ready.add(i);
            }
            if (ready.isEmpty()) continue;
            // drawn by weight (1 by default); an opening move goes first
            int pick = -1;
            for (int i : ready) if (a.abilities.get(i).flag("opening", false)) pick = i;
            if (pick < 0) {
                double total = 0;
                for (int i : ready) total += Math.max(0, a.abilities.get(i).number("weight", 1));
                double roll = ThreadLocalRandom.current().nextDouble(Math.max(1e-9, total));
                for (int i : ready) {
                    roll -= Math.max(0, a.abilities.get(i).number("weight", 1));
                    if (roll <= 0) {
                        pick = i;
                        break;
                    }
                }
                if (pick < 0) pick = ready.get(ready.size() - 1);
            }
            Ability chosen = a.abilities.get(pick);
            if (chosen.flag("once", false) || chosen.flag("opening", false)) a.spent.add(pick);
            a.lastCast.put(pick, now);
            a.nextCast = now + (long) (a.template.extra().castGap() * 1000);
            later.add(() -> cast(a, chosen, target));
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
        Player own = fightingTarget(a);
        if (own != null) {
            if (m.getTarget() != own) m.setTarget(own);
            return own;
        }
        if (m.getTarget() instanceof Player p && fighting(p)) {
            a.target = p.getUniqueId();
            return p;
        }
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
        if (best != null) {
            a.target = best.getUniqueId();
            m.setTarget(best);
        }
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

    private fr.influencraft.fluxdungeons.combat.HealthBars bars;

    /** Life bars above the monsters (name, level, life). */
    public void bars(fr.influencraft.fluxdungeons.combat.HealthBars bars) {
        this.bars = bars;
    }

    private void bar(Alive a) {
        if (bars == null) return;
        int level = Math.max(0, a.template.extra().level());
        String head = a.template.name() + (level > 0 ? " &7Nv. &f" + level : "");
        bars.attach(a.entity, e -> Texts.parse(head), a.template.extra().barOffset());
    }

    /**
     * Teleports a dungeon monster with what rides it (its life bar): a plain teleport refuses to move an entity that
     * has passengers.
     */
    public static void move(Entity e, Location to) {
        e.teleport(to, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    // ---------------------------------------------------------------- melee: pace and longer reach

    /**
     * Whether this monster may land a melee hit now: players take hits without invulnerability, so what limits the
     * hits a player takes is each monster's own pace ({@code attack-speed}).
     */
    public boolean meleeReady(LivingEntity mob) {
        Alive a = alive.get(mob.getUniqueId());
        return a == null || Bukkit.getCurrentTick() - a.lastMelee >= a.template.extra().attackTicks();
    }


    /** Vanilla melee reach of a mob beyond its body (its attack box is its body widened by this, sideways). */
    private static final double VANILLA_REACH = Math.sqrt(2.04) - 0.6;
    /** Blocks added to the melee reach of dungeon monsters: the same bonus as the players' reach. */
    private double reachBonus = 1;

    public void reachBonus(double blocks) {
        reachBonus = Math.max(0, blocks);
    }

    /**
     * Every 2 ticks: a melee monster whose target stands beyond its vanilla reach but within {@code reachBonus} more
     * hits it anyway, at its own pace ({@code attack-speed}; players got the same bonus, monsters must keep up). Ranged monsters and
     * monsters without attack are left to vanilla.
     */
    private void reachTick() {
        if (reachBonus <= 0) return;
        int now = Bukkit.getCurrentTick();
        for (Alive a : alive.values()) {
            if (!a.inCombat || !(a.entity instanceof Mob m) || !m.hasAI() || !m.isValid()) continue;
            if (m instanceof com.destroystokyo.paper.entity.RangedEntity) continue;
            Player target = fightingTarget(a);
            if (target == null) continue;
            if (now - a.lastMelee < a.template.extra().attackTicks()) continue;
            AttributeInstance attack = m.getAttribute(Attribute.ATTACK_DAMAGE);
            if (attack == null || attack.getValue() <= 0) continue;
            org.bukkit.util.BoundingBox body = m.getBoundingBox(), aim = target.getBoundingBox();
            boolean controlled = a.template.extra().controlled();
            if (!controlled && body.clone().expand(VANILLA_REACH, 0, VANILLA_REACH).overlaps(aim)) continue; // vanilla hits it
            if (!body.clone().expand(VANILLA_REACH + reachBonus, 0, VANILLA_REACH + reachBonus).overlaps(aim)) continue;
            if (!m.hasLineOfSight(target)) continue;
            // lastMelee is set by hitPlayer once the hit lands (set here, the hit would be refused as too soon)
            m.swingMainHand();
            a.animate("attack");
            m.attack(target);
        }
    }

    /**
     * What the monster nearest to this place thinks (staff, {@code /fluxdungeons debug mob}): target, combat, AI,
     * each ability and when it is ready.
     */
    public List<String> inspect(Location at) {
        Alive best = null;
        double bestD = 32 * 32;
        for (Alive a : alive.values()) {
            if (!a.entity.isValid() || a.entity.getWorld() != at.getWorld()) continue;
            double d = a.entity.getLocation().distanceSquared(at);
            if (d < bestD) {
                bestD = d;
                best = a;
            }
        }
        List<String> out = new ArrayList<>();
        if (best == null) {
            out.add("No dungeon monster within 32 blocks.");
            return out;
        }
        Alive a = best;
        long now = System.currentTimeMillis();
        Player own = fightingTarget(a);
        AttributeInstance speed = a.entity.getAttribute(Attribute.MOVEMENT_SPEED);
        out.add(a.template.id() + " (" + a.entity.getType() + "), life " + Math.round(a.entity.getHealth() * healthScale(a.entity)) + "/" + Math.round(maxHealth(a.entity) * healthScale(a.entity))
                + ", AI " + a.entity.hasAI() + ", controlled " + a.template.extra().controlled());
        out.add("combat " + a.inCombat + ", target " + (own == null ? "none" : own.getName())
                + ", vanilla target " + (a.entity instanceof Mob m && m.getTarget() != null ? m.getTarget().getName() : "none")
                + ", model " + (a.model != null) + ", speed " + String.format(java.util.Locale.ROOT, "%.3f", speed == null ? 0 : speed.getValue()));
        if (a.abilities.stream().anyMatch(x -> CopperPowers.TYPES.contains(x.type())))
            out.add("overcharge " + Math.round(copper.overcharge(a.entity)) + "/100");
        out.add("next ability in " + Math.max(0, (a.nextCast - now) / 100) / 10.0 + " s, melee ready " + meleeReady(a.entity));
        double life = 100 * a.entity.getHealth() / Math.max(1, maxHealth(a.entity));
        for (int i = 0; i < a.abilities.size(); i++) {
            Ability ab = a.abilities.get(i);
            long last = a.lastCast.computeIfAbsent(i, k -> now - (long) (ab.every() * 500));
            double left = Math.max(0, ab.every() - (now - last) / 1000.0);
            out.add(" - " + ab.type() + (ab.params().get("spell") != null ? " [" + ab.params().get("spell") + "]" : "") + ": every " + ab.every()
                    + " s, " + (life > ab.below() ? "only under " + ab.below() + " %" : left <= 0 ? "ready" : "in " + Math.round(left * 10) / 10.0 + " s")
                    + " " + ab.params().entrySet().stream().filter(en -> !List.of("type", "spell", "every", "title", "subtitle").contains(en.getKey()))
                    .map(en -> en.getKey() + "=" + en.getValue()).toList());
        }
        return out;
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
        strike(a, p, ab, fallback, 1, false);
    }

    /**
     * An ability hit, or {@code share} of it. A {@code combo: {first, hits, every}} on the ability turns one big hit
     * into a series: {@code first} of it lands at once, the rest in {@code hits} small hits {@code every} ticks
     * apart, so the player sees his life going down and can still react (heal, flee). A {@code drain} part is a
     * small hit without knockback, never blocked (ultimates take a share of their hit that way before the impact,
     * {@code lead-in}). Parts always take, after armour, their exact share of what the whole hit would have taken:
     * splitting never weakens an attack (vanilla armour stops a larger part of small hits).
     */
    private void strike(Alive a, Player p, Ability ab, double fallback, double share, boolean drain) {
        double v = ab.number("power", fallback);
        boolean relative = ab.number("relative", 0) > 0 || !a.template.abilities().contains(ab);
        // absolute powers follow the difficulty level of the run (relative ones already do, through the attack)
        double full = relative ? v * hitOn(a, p) : v * scaling.apply(a.run)[1];
        boolean unavoid = ab.flag("unavoidable", false);
        if (drain) {
            bleed(p, afterArmor(p, full) * share, unavoid);
            return;
        }
        double first = share;
        int hits = 0, every = 4;
        if (ab.params().get("combo") instanceof Map<?, ?> combo && share >= 1) {
            first = combo.get("first") instanceof Number n ? Math.max(0.05, Math.min(1, n.doubleValue())) : 0.5;
            hits = combo.get("hits") instanceof Number n ? Math.max(1, n.intValue()) : 5;
            every = combo.get("every") instanceof Number n ? Math.max(1, n.intValue()) : 4;
        }
        // unavoidable (ultimates): neither a shield nor a dodging enchantment (cancel_event of another plugin) stops it
        unavoidable = unavoid;
        try {
            deal(p, a.entity, first >= 1 ? full : rawFor(p, full, first));
        } finally {
            unavoidable = false;
        }
        if (hits > 0 && first < 1) {
            double each = afterArmor(p, full) * (1 - first) / hits;
            for (int i = 1; i <= hits; i++) Bukkit.getScheduler().runTaskLater(plugin, () -> bleed(p, each, unavoid), (long) i * every);
        }
    }

    /** Life a hit of {@code raw} takes through the player's armour (vanilla formula, toughness included). */
    private static double afterArmor(Player p, double raw) {
        AttributeInstance ar = p.getAttribute(Attribute.ARMOR), to = p.getAttribute(Attribute.ARMOR_TOUGHNESS);
        double armor = ar == null ? 0 : ar.getValue(), tough = to == null ? 0 : to.getValue();
        double cut = Math.max(armor * 0.2, Math.min(20, armor - 4 * raw / (tough + 8)));
        return raw * (1 - cut / 25);
    }

    /** The raw hit whose damage through armour is {@code share} of what {@code full} would take. */
    private static double rawFor(Player p, double full, double share) {
        double want = afterArmor(p, full) * share, lo = 0, hi = full;
        for (int i = 0; i < 30; i++) {
            double mid = (lo + hi) / 2;
            if (afterArmor(p, mid) < want) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    /**
     * Takes this much life (already counted through armour) as a small hit: no knockback, not blocked, the hurt
     * flash and sound of the player kept so that he notices it.
     */
    private void bleed(Player p, double amount, boolean unavoid) {
        if (amount <= 0 || !p.isOnline() || !fighting(p)) return;
        p.setNoDamageTicks(0);
        unavoidable = unavoid;
        dealing = true;
        try {
            p.damage(amount, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.MAGIC).build());
        } finally {
            dealing = false;
            unavoidable = false;
        }
    }

    /** True while an unavoidable ability hit is dealt (see CombatRules#onUnavoidable). */
    private boolean unavoidable;

    public boolean unavoidable() {
        return unavoidable;
    }

    // ---------------------------------------------------------------- difficulty levels

    private static final double[] NO_SCALE = {1, 1, 1};
    /** Life and damage multipliers of a run (its difficulty level), then 1 when its monsters cast abilities, 0 when not. */
    private java.util.function.Function<String, double[]> scaling = r -> NO_SCALE;

    /** False in an easy run: its monsters only fight with their weapons. */
    private boolean castsAbilities(Alive a) {
        double[] v = scaling.apply(a.run);
        return v.length < 3 || v[2] > 0;
    }

    public void scaling(java.util.function.Function<String, double[]> scaling) {
        this.scaling = r -> {
            double[] v = r == null ? null : scaling.apply(r);
            return v == null ? NO_SCALE : v;
        };
    }

    // ---------------------------------------------------------------- archers

    /** Ticks between two vanilla bow shots of a monster (normal difficulty, the one of dungeon worlds). */
    private static final int VANILLA_SHOT_TICKS = 40;
    /** True while FluxDungeons shoots an arrow itself (the shoot event must not schedule more). */
    private boolean shooting;

    /**
     * A dungeon monster shot an arrow (vanilla): aimed straight at its target (vanilla misses a lot on normal
     * difficulty), its damage set from the monster's attack (archers hit often and lightly), and {@code ranged-rate}
     * - 1 more shots scheduled before the next vanilla one.
     */
    public void shot(LivingEntity mob, org.bukkit.entity.AbstractArrow arrow) {
        Alive a = alive.get(mob.getUniqueId());
        if (a == null) return;
        LivingEntity target = fightingTarget(a);
        if (target == null && mob instanceof Mob m) target = m.getTarget();
        if (target != null) aim(mob, arrow, target);
        arrow.setDamage(arrowDamage(a));
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        arrow.getPersistentDataContainer().set(shotKey, PersistentDataType.BYTE, (byte) 1);
        if (shooting) return;
        int extra = (int) Math.round(a.template.extra().rangedRate()) - 1;
        long gap = Math.max(4, VANILLA_SHOT_TICKS / (extra + 1));
        for (int i = 1; i <= extra; i++) Bukkit.getScheduler().runTaskLater(plugin, () -> extraShot(a), gap * i);
    }

    private void extraShot(Alive a) {
        LivingEntity e = a.entity;
        if (!e.isValid() || e.isDead() || !e.hasAI() || !(e instanceof Mob m)) return;
        Player target = fightingTarget(a);
        if (target == null) return;
        if (target.getLocation().distanceSquared(e.getLocation()) > 24 * 24 || !e.hasLineOfSight(target)) return;
        shooting = true;
        try {
            org.bukkit.entity.Arrow arrow = e.launchProjectile(org.bukkit.entity.Arrow.class);
            shot(e, arrow);
        } finally {
            shooting = false;
        }
        m.swingMainHand();
        e.getWorld().playSound(e.getLocation(), Sound.ENTITY_SKELETON_SHOOT, 1f, 1f / (0.8f + ThreadLocalRandom.current().nextFloat() * 0.4f));
    }

    /** Vanilla's aim of a skeleton (a little above the target to make up for the fall of the arrow), barely spread. */
    private static void aim(LivingEntity mob, org.bukkit.entity.AbstractArrow arrow, LivingEntity target) {
        Location from = arrow.getLocation();
        double dx = target.getX() - from.getX();
        double dy = target.getY() + target.getHeight() / 3 - from.getY();
        double dz = target.getZ() - from.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        Vector v = new Vector(dx, dy + flat * 0.2, dz).normalize();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        v.add(new Vector(r.nextGaussian() * 0.0075, r.nextGaussian() * 0.0075, r.nextGaussian() * 0.0075));
        arrow.setVelocity(v.multiply(1.6));
    }

    /**
     * Damage of a monster's own melee hit, before the player's armour: its attack and nothing else (no weapon bonus,
     * no enchantment, no difficulty scaling). FluxDungeons sets it on every melee hit, so a monster always hits for
     * what its file says.
     */
    public double meleeDamage(LivingEntity mob) {
        Alive a = alive.get(mob.getUniqueId());
        return a == null ? -1 : a.baseDamage();
    }

    private final NamespacedKey shotKey;
    private final NamespacedKey healthScaleKey;

    /** True for an arrow a dungeon monster shot with its bow (its damage is the monster's attack). */
    public boolean ownShot(org.bukkit.entity.Projectile p) {
        return p.getPersistentDataContainer().has(shotKey, PersistentDataType.BYTE);
    }

    /** Arrows of a frost archer ({@code frost-arrows}) freeze and slow whoever they hit. */
    public boolean frostArrows(LivingEntity mob) {
        Alive a = alive.get(mob.getUniqueId());
        return a != null && a.template.extra().frostArrows();
    }

    /** Base damage of an arrow so that it hits for the monster's attack (an arrow hits for speed × base, rounded up). */
    private static double arrowDamage(Alive a) {
        return Math.max(0.5, a.baseDamage() / 1.6 - 0.01);
    }

    private void cast(Alive a, Ability ab, Player target) {
        LivingEntity e = a.entity;
        if (!e.isValid()) return;
        shield(a, ab);
        // rock and ice abilities (boulder, rockfall, quake, ice_spikes, stone_skin), water abilities (TidePowers)
        RockPowers.Caster caster = new RockPowers.Caster() {
            public LivingEntity entity() {
                return e;
            }

            public void hit(Player p, double fallback) {
                strike(a, p, ab, fallback);
            }

            public void animate(String animation) {
                a.animate(animation);
            }

            public void hold(int ticks) {
                // still during its attack (no walk over the animation, no other ability meanwhile); a later hold
                // extends it (a rolling charge ending in a wall); AI off for another reason is left alone
                if (!(e instanceof Mob m)) return;
                int now = Bukkit.getCurrentTick();
                if (!m.hasAI() && now >= a.holdUntil) return;
                m.setAI(false);
                a.holdUntil = Math.max(a.holdUntil, now + ticks);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (Bukkit.getCurrentTick() >= a.holdUntil && m.isValid() && !a.switching && !a.dormant) m.setAI(true);
                }, ticks);
            }

            public void hitShare(Player p, double fallback, double share) {
                strike(a, p, ab, fallback, share, false);
            }

            public void drain(Player p, double fallback, double share) {
                strike(a, p, ab, fallback, share, true);
            }

            public void release() {
                a.holdUntil = Bukkit.getCurrentTick();
                if (e instanceof Mob m && m.isValid() && !a.switching && !a.dormant) m.setAI(true);
            }

            public void loop(String animation) {
                if (a.model != null) a.model.loop(animation);
            }

            public void stopLoop(String animation) {
                if (a.model != null) a.model.stop(animation);
            }

            public void parts(boolean visible, java.util.Collection<String> bones) {
                if (a.model != null) a.model.parts(visible, bones);
            }

            public void glow(int rgb) {
                if (a.model != null) a.model.glow(rgb);
            }

            public Location home() {
                return a.spawn.clone();
            }
        };
        if (copper.cast(ab, caster, target) || mole.cast(ab, caster, target) || tide.cast(ab, caster, target) || harvest.cast(ab, caster, target)
                || rock.cast(ab, caster, target)) return;
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
                                    List.of(), List.of(), List.of(), Map.of(), "TRASH", 1, java.util.Map.of(), 0, 1, false, false, false, 0, 0, 0, false, Map.of(), Map.of()));
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
                    if (!e.isValid()) return;
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
            case "charge" -> charge(a, ab, target);
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
