package fr.influencraft.fluxdungeons.mob;

import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.fluxdungeons.mob.MobTemplate.Ability;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Monsters of {@code mobs.yml}: spawned with their numbers and equipment, marked with their template and run, driven
 * every half second (target, abilities). A boss wearing a model plays its animations: {@code attack} before a slam
 * (damage at the impact tick of its fiche), {@code hurt} when hit, {@code death} instead of dying at once.
 */
public final class Mobs {

    /** A monster alive in a run. */
    private static final class Alive {
        final LivingEntity entity;
        final MobTemplate template;
        final String run;
        final Map<Integer, Long> lastCast = new HashMap<>();
        final ModelBridge.Handle model;
        long lastHurt;
        boolean dying;

        Alive(LivingEntity entity, MobTemplate template, String run, ModelBridge.Handle model) {
            this.entity = entity;
            this.template = template;
            this.run = run;
            this.model = model;
        }
    }

    private final Plugin plugin;
    private final NamespacedKey mobKey, runKey;
    private final Map<String, MobTemplate> templates = new LinkedHashMap<>();
    private final Map<UUID, Alive> alive = new HashMap<>();
    private final boolean models;
    /** A monster summoned by another: the run must count it. */
    private BiConsumer<LivingEntity, LivingEntity> onSummon = (parent, child) -> {
    };
    /** A monster finished dying (after its death animation): the run counts the kill. */
    private BiConsumer<LivingEntity, Player> onDeath = (mob, killer) -> {
    };

    public Mobs(Plugin plugin) {
        this.plugin = plugin;
        this.mobKey = new NamespacedKey(plugin, "mob");
        this.runKey = new NamespacedKey(plugin, "run");
        this.models = Bukkit.getPluginManager().isPluginEnabled("BetterModel");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    public void load(ConfigurationSection file) {
        templates.clear();
        if (file == null) return;
        for (String id : file.getKeys(false)) {
            ConfigurationSection c = file.getConfigurationSection(id);
            if (c != null) templates.put(id, MobTemplate.read(id, c));
        }
    }

    public MobTemplate template(String id) {
        return templates.get(id);
    }

    public void onSummon(BiConsumer<LivingEntity, LivingEntity> handler) {
        this.onSummon = handler;
    }

    public void onDeath(BiConsumer<LivingEntity, Player> handler) {
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

    // ---------------------------------------------------------------- spawning

    public LivingEntity spawn(MobTemplate t, Location at, String run) {
        Class<? extends Entity> type = t.type().getEntityClass();
        if (type == null || !LivingEntity.class.isAssignableFrom(type)) return null;
        Entity spawned = at.getWorld().spawn(at, type, CreatureSpawnEvent.SpawnReason.CUSTOM, e -> {
            LivingEntity le = (LivingEntity) e;
            le.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, t.id());
            le.getPersistentDataContainer().set(runKey, PersistentDataType.STRING, run);
            le.customName(Texts.parse(t.name()));
            le.setCustomNameVisible(!t.boss());
            le.setRemoveWhenFarAway(false);
            le.setPersistent(true);
            le.setCanPickupItems(false);
            base(le, Attribute.MAX_HEALTH, t.health());
            base(le, Attribute.ATTACK_DAMAGE, t.damage());
            base(le, Attribute.MOVEMENT_SPEED, t.speed());
            base(le, Attribute.ARMOR, t.armor());
            base(le, Attribute.SCALE, t.scale());
            base(le, Attribute.FOLLOW_RANGE, 32);
            if (t.boss()) base(le, Attribute.KNOCKBACK_RESISTANCE, 1);
            le.setHealth(t.health());
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
            if (le instanceof Zombie z) {
                z.setShouldBurnInDay(false);
                z.setAdult();
            }
            if (le instanceof org.bukkit.entity.AbstractSkeleton s) s.setShouldBurnInDay(false);
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
        alive.put(le.getUniqueId(), new Alive(le, t, run, model));
        return le;
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
            if (a.model != null) a.model.close();
            a.entity.remove();
            it.remove();
        }
    }

    public void removeAll() {
        for (Alive a : alive.values()) {
            if (a.model != null) a.model.close();
            a.entity.remove();
        }
        alive.clear();
    }

    // ---------------------------------------------------------------- damage and death

    /** A dungeon monster was hit: hurt animation of its model (at most twice a second). */
    public void hurt(LivingEntity entity) {
        Alive a = alive.get(entity.getUniqueId());
        if (a == null || a.model == null || a.dying) return;
        long now = System.currentTimeMillis();
        if (now - a.lastHurt < 500) return;
        a.lastHurt = now;
        a.model.play("hurt");
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
            alive.remove(entity.getUniqueId());
            a.model.close();
            entity.remove();
            onDeath.accept(entity, killer);
        }, 36L);
        return true;
    }

    /** A dungeon monster died the vanilla way. */
    public void died(LivingEntity entity) {
        Alive a = alive.remove(entity.getUniqueId());
        if (a != null && a.model != null) a.model.close();
    }

    // ---------------------------------------------------------------- behaviour

    private void tick() {
        long now = System.currentTimeMillis();
        // abilities run after the loop: a summon adds monsters to the map being walked
        java.util.List<Runnable> casts = new java.util.ArrayList<>();
        Iterator<Alive> it = alive.values().iterator();
        while (it.hasNext()) {
            Alive a = it.next();
            if (!a.entity.isValid()) {
                if (a.model != null && !a.dying) a.model.close();
                if (!a.dying) it.remove();
                continue;
            }
            if (a.dying) continue;
            Player target = target(a);
            if (target == null) continue;
            double life = 100 * a.entity.getHealth() / Math.max(1, maxHealth(a.entity));
            for (int i = 0; i < a.template.abilities().size(); i++) {
                Ability ab = a.template.abilities().get(i);
                if (life > ab.below()) continue;
                long last = a.lastCast.getOrDefault(i, now - (long) (ab.every() * 500)); // first cast after half a period
                if (now - last < ab.every() * 1000) continue;
                a.lastCast.put(i, now);
                casts.add(() -> cast(a, ab, target));
                break;
            }
        }
        casts.forEach(Runnable::run);
    }

    /** Current target, or the nearest player who can fight within 24 blocks. */
    private static Player target(Alive a) {
        if (a.entity instanceof Mob m) {
            if (m.getTarget() instanceof Player p && fighting(p)) return p;
            Player best = null;
            double bestD = 24 * 24;
            for (Player p : a.entity.getWorld().getPlayers()) {
                if (!fighting(p)) continue;
                double d = p.getLocation().distanceSquared(a.entity.getLocation());
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            if (best != null) m.setTarget(best);
            return best;
        }
        return null;
    }

    private static boolean fighting(Player p) {
        return !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE);
    }

    private static double maxHealth(LivingEntity e) {
        AttributeInstance inst = e.getAttribute(Attribute.MAX_HEALTH);
        return inst == null ? 20 : inst.getValue();
    }

    private void cast(Alive a, Ability ab, Player target) {
        LivingEntity e = a.entity;
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
            case "slam" -> {
                String animation = ab.text("animation", "attack");
                long impact = (long) ab.number("impact", a.model == null ? 10 : 16);
                if (a.model != null) a.model.play(animation);
                if (e instanceof Mob m) m.setAI(false);
                w.playSound(at, Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.6f);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (e instanceof Mob m) m.setAI(true);
                    if (!e.isValid() || a.dying) return;
                    double radius = ab.number("radius", 4);
                    // impact in front of the monster, where the hammer lands
                    Location hit = e.getLocation().add(e.getLocation().getDirection().setY(0).normalize().multiply(radius * 0.5));
                    w.spawnParticle(Particle.EXPLOSION, hit, 2, 0.5, 0.1, 0.5, 0);
                    w.spawnParticle(Particle.LAVA, hit, 12, radius / 2, 0.2, radius / 2, 0);
                    w.playSound(hit, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 0.7f);
                    for (Player p : w.getPlayers()) {
                        if (!fighting(p) || p.getLocation().distanceSquared(hit) > radius * radius) continue;
                        p.damage(ab.number("power", 8), e);
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
