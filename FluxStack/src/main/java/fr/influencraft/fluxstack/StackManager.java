package fr.influencraft.fluxstack;

import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.text.Translations;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

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
    private BukkitTask task;
    private double radiusSquared;
    private int maxStack;
    private boolean stackAnimals;
    private Set<String> worlds = Set.of();
    private Set<String> disabledWorlds = Set.of();
    private Set<String> excluded = Set.of();
    private String nameFormat;

    StackManager(FluxStack plugin) {
        this.plugin = plugin;
        this.countKey = new NamespacedKey(plugin, "count");
    }

    void start() {
        stop();
        FileConfiguration c = plugin.getConfig();
        double radius = Math.max(1, c.getDouble("radius", 6));
        radiusSquared = radius * radius;
        maxStack = Math.max(2, c.getInt("max-stack", 50));
        stackAnimals = c.getBoolean("stack-animals", true);
        worlds = lower(c.getStringList("worlds"));
        disabledWorlds = lower(c.getStringList("disabled-worlds"));
        excluded = lower(c.getStringList("exclude-types"));
        nameFormat = c.getString("name", "{name} &7×{count}");
        long period = Math.max(20, Math.round(c.getDouble("interval-seconds", 2) * 20));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::mergeAll, period, period);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
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

    private void setCount(LivingEntity e, int n) {
        if (n <= 1) {
            if (e.getPersistentDataContainer().has(countKey)) {
                e.getPersistentDataContainer().remove(countKey);
                e.customName(null);
            }
            return;
        }
        e.getPersistentDataContainer().set(countKey, PersistentDataType.INTEGER, n);
        // seen by everybody at once: the server's language (Translations), as a shop sign
        String name = Translations.server(e.getType().translationKey());
        e.customName(Texts.colors(nameFormat.replace("{name}", name).replace("{count}", String.valueOf(n))));
        e.setCustomNameVisible(false); // the level plugin draws the name tag, with this name in it
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
        if (e.isLeashed() || !e.getPassengers().isEmpty() || e.isInsideVehicle() || e.isInvisible()) return false;
        if (e instanceof Tameable t && t.isTamed()) return false;
        if (e instanceof Animals a && a.isLoveMode()) return false;
        // a name not given by us: a name tag, a boss, a model of another plugin
        if (e.customName() != null && !e.getPersistentDataContainer().has(countKey)) return false;
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
            for (List<LivingEntity> group : groups.values()) if (group.size() > 1) merge(group);
        }
    }

    /** Biggest stacks absorb their neighbours first, up to the maximum. */
    private void merge(List<LivingEntity> group) {
        group.sort(Comparator.comparingInt(this::count).reversed());
        Set<LivingEntity> gone = new HashSet<>();
        for (LivingEntity host : group) {
            if (gone.contains(host)) continue;
            int total = count(host);
            if (total >= maxStack) continue;
            Location at = host.getLocation();
            for (LivingEntity other : group) {
                if (other == host || gone.contains(other)) continue;
                if (other.getLocation().distanceSquared(at) > radiusSquared) continue;
                int n = count(other);
                if (total + n > maxStack) continue;
                total += n;
                gone.add(other);
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

    /** A copy of the mob (level, equipment, data), full health. */
    private static Entity respawn(LivingEntity from, Location at) {
        EntitySnapshot snapshot = from.createSnapshot();
        if (snapshot == null) return null;
        Entity copy = snapshot.createEntity(at);
        if (copy instanceof LivingEntity living) {
            AttributeInstance max = living.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) living.setHealth(max.getValue());
            living.setFireTicks(0);
            living.setNoDamageTicks(0);
        }
        return copy;
    }
}
