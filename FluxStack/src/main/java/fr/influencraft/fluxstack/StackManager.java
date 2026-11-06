package fr.influencraft.fluxstack;

import fr.influencraft.flux.api.text.Texts;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Display;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Merges identical mobs, splits a stack when one of its mobs dies or a player interacts with it. Two mobs are identical
 * when they share their type, age, variant (sheep colour, slime size) and base attributes: mob levels change health,
 * damage and speed, so two mobs of different levels never merge, without knowing the level plugin.
 */
final class StackManager implements Listener {

    private final FluxStack plugin;
    private final NamespacedKey countKey;
    /** Marks our floating "×12" texts: never saved, rebuilt by the merge pass when missing. */
    private final NamespacedKey labelKey;
    private BukkitTask task;
    private double radiusSquared;
    private double verticalRadius;
    private int maxStack;
    private boolean stackAnimals;
    private Set<String> worlds = Set.of();
    private Set<String> disabledWorlds = Set.of();
    private Set<String> excluded = Set.of();
    private String labelFormat;

    StackManager(FluxStack plugin) {
        this.plugin = plugin;
        this.countKey = new NamespacedKey(plugin, "count");
        this.labelKey = new NamespacedKey(plugin, "label");
    }

    void start() {
        stop();
        FileConfiguration c = plugin.getConfig();
        double radius = Math.max(1, c.getDouble("radius", 20));
        radiusSquared = radius * radius;
        verticalRadius = Math.max(1, c.getDouble("vertical-radius", 8));
        maxStack = Math.max(2, c.getInt("max-stack", 50));
        stackAnimals = c.getBoolean("stack-animals", true);
        worlds = lower(c.getStringList("worlds"));
        disabledWorlds = lower(c.getStringList("disabled-worlds"));
        excluded = lower(c.getStringList("exclude-types"));
        labelFormat = c.getString("label", "&f×{count}");
        long period = Math.max(20, Math.round(c.getDouble("interval-seconds", 2) * 20));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::mergeAll, period, period);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    /** Plugin off: labels go, frozen stacks move again (their count is kept and comes back at the next start). */
    void shutdown() {
        stop();
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity e : world.getLivingEntities()) {
                if (count(e) > 1 && e instanceof Mob m) m.setAware(true);
                removeLabels(e);
            }
        }
    }

    private static Set<String> lower(List<String> list) {
        Set<String> out = new HashSet<>();
        for (String s : list) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    // ---------------------------------------------------------------- count

    int count(Entity e) {
        Integer n = e.getPersistentDataContainer().get(countKey, PersistentDataType.INTEGER);
        return n == null ? 1 : Math.max(1, n);
    }

    /** Count, frozen state and floating label of a stack (n = 1: a normal mob again). */
    private void setCount(LivingEntity e, int n) {
        if (n <= 1) {
            e.getPersistentDataContainer().remove(countKey);
            if (e instanceof Mob m) m.setAware(true);
            removeLabels(e);
            return;
        }
        e.getPersistentDataContainer().set(countKey, PersistentDataType.INTEGER, n);
        if (e instanceof Mob m) m.setAware(false); // the mob that carries a stack stays put (no AI, gravity kept)
        label(e, n);
    }

    private boolean isLabel(Entity e) {
        return e instanceof TextDisplay && e.getPersistentDataContainer().has(labelKey);
    }

    private void removeLabels(Entity host) {
        for (Entity p : host.getPassengers()) if (isLabel(p)) p.remove();
    }

    /** The "×12" text riding the stack, created when missing, updated when the count changed. */
    private void label(LivingEntity host, int n) {
        Component text = Texts.colors(labelFormat.replace("{count}", String.valueOf(n)));
        for (Entity p : host.getPassengers()) {
            if (isLabel(p)) {
                ((TextDisplay) p).text(text);
                return;
            }
        }
        TextDisplay label = host.getWorld().spawn(host.getLocation(), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(labelKey, PersistentDataType.BYTE, (byte) 1);
            d.text(text);
            d.setBillboard(Display.Billboard.CENTER);
            d.setShadowed(true);
            d.setTransformation(new Transformation(new Vector3f(0, 0.35f, 0), new AxisAngle4f(), new Vector3f(1, 1, 1),
                    new AxisAngle4f()));
        });
        host.addPassenger(label);
    }

    // ---------------------------------------------------------------- merging

    private boolean worldAllowed(World w) {
        String name = w.getName().toLowerCase(Locale.ROOT);
        return (worlds.isEmpty() || worlds.contains(name)) && !disabledWorlds.contains(name);
    }

    /** Whether this mob may be part of a stack (never a named, tamed, leashed, ridden or busy one). */
    private boolean stackable(LivingEntity e) {
        if (!(e instanceof Mob) || !e.isValid() || e.isDead()) return false;
        if (excluded.contains(e.getType().getKey().getKey())) return false;
        if (!stackAnimals && !(e instanceof Enemy)) return false;
        if (e.isLeashed() || e.isInsideVehicle() || e.isInvisible()) return false;
        for (Entity p : e.getPassengers()) if (!isLabel(p)) return false;
        if (e instanceof Tameable t && t.isTamed()) return false;
        if (e instanceof Animals a && a.isLoveMode()) return false;
        // COMPAT 2026-11-06: stacks of the first version carry "Zombie ×12" as their name - remove when Labs restarted twice
        if (e.customName() != null && e.getPersistentDataContainer().has(countKey)) e.customName(null);
        // a named mob: a name tag, a boss, a model of another plugin
        if (e.customName() != null) return false;
        return e.getScoreboardTags().isEmpty();
    }

    private static String signature(LivingEntity e) {
        StringBuilder sb = new StringBuilder(e.getType().getKey().getKey());
        if (e instanceof Ageable a) sb.append(a.isAdult() ? "|adult" : "|baby");
        if (e instanceof Sheep s) sb.append('|').append(s.getColor()).append(s.isSheared());
        if (e instanceof Slime s) sb.append("|size").append(s.getSize());
        for (Attribute attribute : List.of(Attribute.MAX_HEALTH, Attribute.ATTACK_DAMAGE, Attribute.MOVEMENT_SPEED)) {
            AttributeInstance a = e.getAttribute(attribute);
            if (a != null) sb.append('|').append(Math.round(a.getValue() * 1000));
        }
        return sb.toString();
    }

    private void mergeAll() {
        for (World world : Bukkit.getWorlds()) {
            if (!worldAllowed(world)) continue;
            Map<String, List<LivingEntity>> groups = new HashMap<>();
            for (LivingEntity e : world.getLivingEntities()) {
                if (stackable(e)) groups.computeIfAbsent(signature(e), k -> new ArrayList<>()).add(e);
            }
            for (List<LivingEntity> group : groups.values()) {
                if (group.size() > 1) merge(group);
                // labels are never saved: a stack loaded from disk gets its label back, frozen again
                for (LivingEntity e : group) if (e.isValid() && count(e) > 1) setCount(e, count(e));
            }
        }
    }

    /** Close enough to merge: within the radius around, and not too far above or below (no merging through a cave roof). */
    private boolean near(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz <= radiusSquared && Math.abs(a.getY() - b.getY()) <= verticalRadius;
    }

    /**
     * Mobs linked by neighbours (A near B, B near C) form one cluster, even when A and C are far apart; each cluster
     * goes into its biggest stack, then into the next biggest once the maximum is reached.
     */
    private void merge(List<LivingEntity> group) {
        Set<LivingEntity> seen = new HashSet<>();
        for (LivingEntity start : group) {
            if (!seen.add(start)) continue;
            List<LivingEntity> cluster = new ArrayList<>(List.of(start));
            for (int i = 0; i < cluster.size(); i++) {
                Location at = cluster.get(i).getLocation();
                for (LivingEntity other : group) {
                    if (!seen.contains(other) && near(at, other.getLocation())) {
                        seen.add(other);
                        cluster.add(other);
                    }
                }
            }
            if (cluster.size() > 1) absorb(cluster);
        }
    }

    private void absorb(List<LivingEntity> cluster) {
        cluster.sort(Comparator.comparingInt(this::count).reversed());
        Set<LivingEntity> gone = new HashSet<>();
        for (LivingEntity host : cluster) {
            if (gone.contains(host)) continue;
            int total = count(host);
            for (LivingEntity other : cluster) {
                if (total >= maxStack) break;
                if (other == host || gone.contains(other)) continue;
                int n = count(other);
                if (total + n > maxStack) continue;
                total += n;
                gone.add(other);
                removeLabels(other);
                other.remove();
            }
            if (total != count(host)) setCount(host, total);
        }
    }

    // ---------------------------------------------------------------- splitting

    /** The next mob of the stack takes the place of the one that died: every death stays a real death. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        int n = count(dead);
        if (n <= 1) return;
        removeLabels(dead);
        Entity next = respawn(dead, dead.getLocation());
        if (next instanceof LivingEntity living) setCount(living, n - 1);
    }

    /** A player feeding, shearing or naming a stacked mob gets one mob of it, the rest stays a stack. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof LivingEntity clicked)) return;
        int n = count(clicked);
        if (n <= 1) return;
        Entity rest = respawn(clicked, clicked.getLocation());
        if (rest instanceof LivingEntity living) setCount(living, n - 1);
        setCount(clicked, 1);
    }

    /** A stack leaving the world (despawn, unload, removal): its label goes with it, never left floating. */
    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent event) {
        removeLabels(event.getEntity());
    }

    /** A copy of the mob (level, equipment, data), full health. */
    private Entity respawn(LivingEntity from, Location at) {
        EntitySnapshot snapshot = from.createSnapshot();
        if (snapshot == null) return null;
        Entity copy = snapshot.createEntity(at);
        removeLabels(copy);
        if (copy instanceof LivingEntity living) {
            AttributeInstance max = living.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) living.setHealth(max.getValue());
            living.setFireTicks(0);
            living.setNoDamageTicks(0);
        }
        return copy;
    }
}
