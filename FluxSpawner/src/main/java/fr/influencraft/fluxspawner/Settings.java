package fr.influencraft.fluxspawner;

import fr.influencraft.flux.api.mobs.MobStacks;
import fr.influencraft.flux.api.util.Experience;
import fr.influencraft.flux.api.value.Values;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;

/**
 * Everything read from config.yml, types.yml and levels.yml, and every number derived from it. Production, capacity,
 * fuel and goals are worked out from FluxValue ({@link Values}): a level produces a share of optimal play, so every
 * mob type is worth the same at the same level (fewer blazes than zombies, the same money), and changing a weight in
 * FluxValue moves the spawners with the rest of the economy.
 */
final class Settings {

    /** An extra fuel of FluxSpawner itself (essence), worth fixed seconds of running whatever the level. */
    record Essence(String id, Material material, boolean glint, double seconds) {
    }

    private final JavaPlugin plugin;

    // placement
    Set<String> worlds = Set.of();
    Set<String> disabledWorlds = Set.of();
    boolean requireTown;
    int minDistance;
    boolean remoteManagement;
    // production
    int tickSeconds;
    int activationRange;
    int spawnRadius;
    int stockRadius;
    boolean freeze;
    boolean protect;
    int noStackCapacity;
    double fallbackKillPoints;
    double fallbackPointsPerSecond;
    double fallbackMoneyPerSecond;
    // fuel
    boolean fuelEnabled;
    double fuelCostRatio;
    List<String> commonFuels = List.of();
    final Map<String, Essence> essences = new LinkedHashMap<>();
    // limits
    int activeDefault;
    int placedDefault;
    // vanilla spawners
    Set<String> vanillaDisabledWorlds = Set.of();
    boolean vanillaCapture;
    // hologram
    boolean hologram;
    double hologramHeight;
    int hologramSeconds;
    // type change
    boolean changeType;
    // types and levels
    final Map<String, SpawnerType> types = new LinkedHashMap<>();
    final Map<String, TreeMap<Integer, Level>> profiles = new HashMap<>();

    private final Map<EntityType, Double> killPoints = new HashMap<>();
    /** Items already reported without a value (one warning each, not one per kill). */
    private final Set<String> warned = new HashSet<>();

    Settings(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void load() {
        FileConfiguration c = plugin.getConfig();
        worlds = lower(c.getStringList("placement.worlds"));
        disabledWorlds = lower(c.getStringList("placement.disabled-worlds"));
        requireTown = c.getBoolean("placement.require-town", true);
        minDistance = Math.max(0, c.getInt("placement.min-distance", 4));
        remoteManagement = c.getBoolean("remote-management", true);
        tickSeconds = Math.max(1, c.getInt("production.tick-seconds", 1));
        activationRange = Math.max(0, c.getInt("production.activation-range", 32));
        spawnRadius = Math.max(0, c.getInt("production.spawn-radius", 2));
        stockRadius = Math.max(2, c.getInt("production.stock-radius", 6));
        freeze = c.getBoolean("production.freeze", true);
        protect = c.getBoolean("production.protect", true);
        noStackCapacity = Math.max(1, c.getInt("production.no-stack-capacity", 6));
        fallbackKillPoints = Math.max(1, c.getDouble("production.fallback.kill-points", 84.6));
        fallbackPointsPerSecond = Math.max(0.01, c.getDouble("production.fallback.points-per-second", 58.75));
        fallbackMoneyPerSecond = Math.max(0, c.getDouble("production.fallback.money-per-second", 5.875));
        fuelEnabled = c.getBoolean("fuel.enabled", true);
        fuelCostRatio = Math.max(0.0001, c.getDouble("fuel.cost-ratio", 0.25));
        commonFuels = items(c.getStringList("fuel.items"));
        essences.clear();
        ConfigurationSection es = c.getConfigurationSection("fuel.essences");
        if (es != null) {
            for (String id : es.getKeys(false)) {
                ConfigurationSection e = es.getConfigurationSection(id);
                if (e == null) continue;
                Material m = Material.matchMaterial(e.getString("material", "ECHO_SHARD"));
                double seconds = Amount.parse(e.get("fuel", "1h")).asHours() * 3600;
                if (m == null || seconds <= 0) {
                    plugin.getLogger().warning("fuel.essences." + id + ": unknown material or no fuel, skipped");
                    continue;
                }
                essences.put(id.toLowerCase(Locale.ROOT), new Essence(id.toLowerCase(Locale.ROOT), m, e.getBoolean("glint", true), seconds));
            }
        }
        activeDefault = Math.max(0, c.getInt("limits.active-default", 1));
        placedDefault = Math.max(0, c.getInt("limits.placed-default", 3));
        vanillaDisabledWorlds = lower(c.getStringList("vanilla.disabled-worlds"));
        vanillaCapture = c.getBoolean("vanilla.capture", false);
        hologram = c.getBoolean("hologram.enabled", true);
        hologramHeight = c.getDouble("hologram.height", 1.35);
        hologramSeconds = Math.max(1, c.getInt("hologram.refresh-seconds", 2));
        changeType = c.getBoolean("change-type.enabled", true);
        loadLevels(yaml("levels.yml"));
        loadTypes(yaml("types.yml"));
        killPoints.clear();
        warned.clear();
    }

    /** Values changed (FluxValue reloaded): kill points are read again. */
    void valuesChanged() {
        killPoints.clear();
    }

    private YamlConfiguration yaml(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists() && plugin.getResource(name) != null) plugin.saveResource(name, false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        if (plugin.getResource(name) != null && y.getKeys(false).isEmpty()) {
            plugin.getLogger().warning(name + " is empty or unreadable: using the file shipped in the jar");
            y = YamlConfiguration.loadConfiguration(new InputStreamReader(plugin.getResource(name), StandardCharsets.UTF_8));
        }
        return y;
    }

    private void loadLevels(YamlConfiguration y) {
        profiles.clear();
        ConfigurationSection all = y.getConfigurationSection("profiles");
        if (all == null) return;
        for (String profile : all.getKeys(false)) {
            ConfigurationSection p = all.getConfigurationSection(profile);
            if (p == null) continue;
            TreeMap<Integer, Level> levels = new TreeMap<>();
            for (String key : p.getKeys(false)) {
                int n;
                try {
                    n = Integer.parseInt(key.trim());
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("levels.yml " + profile + "." + key + ": not a level number, skipped");
                    continue;
                }
                ConfigurationSection l = p.getConfigurationSection(key);
                if (l == null) continue;
                List<Requirement> requirements = new ArrayList<>();
                for (Map<?, ?> map : l.getMapList("requirements")) {
                    Requirement r = Requirement.read(map);
                    if (r == null) plugin.getLogger().warning("levels.yml " + profile + "." + key + ": unknown goal " + map);
                    else requirements.add(r);
                }
                levels.put(n, new Level(n, Math.max(0, l.getDouble("efficiency", 0.05)),
                        Math.max(0.01, Amount.parse(l.get("capacity", "1h")).asHours()),
                        Math.max(0.01, Amount.parse(l.get("tank", "2h")).asHours()), List.copyOf(requirements)));
            }
            if (!levels.isEmpty() && levels.firstKey() == 0) profiles.put(profile.toLowerCase(Locale.ROOT), levels);
            else plugin.getLogger().warning("levels.yml " + profile + ": no level 0, profile skipped");
        }
        if (profiles.isEmpty()) plugin.getLogger().warning("levels.yml has no usable profile: no spawner can work");
    }

    private void loadTypes(YamlConfiguration y) {
        types.clear();
        ConfigurationSection all = y.getConfigurationSection("types");
        if (all == null) {
            plugin.getLogger().warning("types.yml has no types section: no spawner type");
            return;
        }
        for (String id : all.getKeys(false)) {
            ConfigurationSection t = all.getConfigurationSection(id);
            if (t == null) continue;
            String key = id.toLowerCase(Locale.ROOT);
            EntityType entity = entity(t.getString("entity", key));
            if (entity == null || !entity.isAlive() || !entity.isSpawnable()) {
                plugin.getLogger().warning("types.yml " + id + ": unknown or unspawnable mob, skipped");
                continue;
            }
            Material icon = Material.matchMaterial(t.getString("icon", entity.getKey().getKey() + "_spawn_egg"));
            if (icon == null) icon = Material.SPAWNER;
            String profile = t.getString("profile", "default").toLowerCase(Locale.ROOT);
            if (!profiles.containsKey(profile)) {
                plugin.getLogger().warning("types.yml " + id + ": unknown level profile " + profile + ", skipped");
                continue;
            }
            String upgrade = t.getString("upgrade-item", "");
            if (!upgrade.contains(":")) upgrade = upgrade.toUpperCase(Locale.ROOT);
            String permission = t.getString("permission", "");
            types.put(key, new SpawnerType(key, entity, t.getString("name", null), icon, profile,
                    Math.max(0.01, t.getDouble("rate", 1)), Math.max(0, t.getDouble("cost-multiplier", 1)),
                    t.getBoolean("vanilla-xp", true), t.getBoolean("loot", true), upgrade,
                    Amount.parse(t.get("change-price", "1h")), permission.isBlank() ? null : permission,
                    items(t.getStringList("fuel"))));
        }
        if (types.isEmpty()) plugin.getLogger().warning("types.yml has no usable type");
    }

    private static EntityType entity(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.ENTITY_TYPE.get(key);
    }

    private static Set<String> lower(List<String> list) {
        Set<String> out = new HashSet<>();
        for (String s : list) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    /** Item ids as the suite writes them: material names upper case, namespaced ids as they are. */
    private static List<String> items(List<String> list) {
        List<String> out = new ArrayList<>();
        for (String s : list) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t.contains(":") ? t.toLowerCase(Locale.ROOT) : t.toUpperCase(Locale.ROOT));
        }
        return out;
    }

    // ---------------------------------------------------------------- levels

    SpawnerType type(String id) {
        return id == null ? null : types.get(id.toLowerCase(Locale.ROOT));
    }

    TreeMap<Integer, Level> levels(SpawnerType type) {
        return profiles.getOrDefault(type.profile(), new TreeMap<>());
    }

    int maxLevel(SpawnerType type) {
        TreeMap<Integer, Level> l = levels(type);
        return l.isEmpty() ? 0 : l.lastKey();
    }

    /** The level, or the highest one below it (a profile may skip numbers). */
    Level level(SpawnerType type, int n) {
        TreeMap<Integer, Level> l = levels(type);
        var e = l.floorEntry(Math.max(0, n));
        return e == null ? new Level(0, 0.05, 1, 2, List.of()) : e.getValue();
    }

    /** The level right above {@code n}, null at the top. */
    Level next(SpawnerType type, int n) {
        var e = levels(type).higherEntry(n);
        return e == null ? null : e.getValue();
    }

    // ---------------------------------------------------------------- production

    double pointsPerSecond() {
        double v = Values.available() ? Values.pointsPerSecond() : 0;
        return v > 0 ? v : fallbackPointsPerSecond;
    }

    double moneyPerSecond() {
        double v = Values.available() ? Values.moneyPerSecond() : 0;
        return v > 0 ? v : fallbackMoneyPerSecond;
    }

    /** Points of one kill of this mob in FluxValue (the fallback when it does not know it). */
    double killPoints(EntityType entity) {
        return killPoints.computeIfAbsent(entity, e -> {
            OptionalDouble p = Values.available() ? Values.actionPoints("kill", e.getKey().getKey()) : OptionalDouble.empty();
            if (p.isPresent() && p.getAsDouble() > 0) return p.getAsDouble();
            if (Values.available()) plugin.getLogger().warning("FluxValue gives no value to a kill of " + e.getKey().getKey()
                    + ": using production.fallback.kill-points");
            return fallbackKillPoints;
        });
    }

    /** Points this spawner makes per second of running. */
    double pointsPerSecond(SpawnerType type, int level) {
        return level(type, level).efficiency() * pointsPerSecond() * type.rate();
    }

    double mobsPerHour(SpawnerType type, int level) {
        return pointsPerSecond(type, level) * 3600 / killPoints(type.entity());
    }

    /** Most mobs waiting at the spawner. */
    int capacity(SpawnerType type, int level) {
        if (!MobStacks.available()) return noStackCapacity;
        double mobs = level(type, level).capacityHours() * mobsPerHour(type, level);
        return (int) Math.max(1, Math.min(MobStacks.maxCount(), Math.round(mobs)));
    }

    double tankSeconds(SpawnerType type, int level) {
        return level(type, level).tankHours() * 3600;
    }

    // ---------------------------------------------------------------- fuel

    /** Fuels this type accepts: the common ones, then its own. */
    List<String> fuels(SpawnerType type) {
        List<String> out = new ArrayList<>(commonFuels);
        for (String f : type.fuels()) if (!out.contains(f)) out.add(f);
        return out;
    }

    /**
     * Seconds of running one item gives at this level: an essence its fixed time, any other item its value in
     * FluxValue burnt at {@code fuel.cost-ratio} of what the spawner produces (0 when the item has no value).
     */
    double fuelSeconds(String item, SpawnerType type, int level) {
        Essence essence = essence(item);
        if (essence != null) return essence.seconds();
        OptionalDouble points = Values.available() ? Values.points(item) : OptionalDouble.empty();
        if (points.isEmpty() || points.getAsDouble() <= 0) return 0;
        double burn = fuelCostRatio * pointsPerSecond(type, level);
        return burn <= 0 ? 0 : points.getAsDouble() / burn;
    }

    Essence essence(String item) {
        if (item == null || !item.startsWith("fluxspawner:")) return null;
        return essences.get(item.substring("fluxspawner:".length()));
    }

    // ---------------------------------------------------------------- goals

    /**
     * What a goal asks to leave level {@code from}: kills in hours of the spawner's production at {@code from}, money
     * in hours of the reference income, items in hours of optimal play (times the type's cost multiplier), vanilla
     * experience in points. Rounded to readable numbers.
     */
    long required(Requirement r, SpawnerType type, int from) {
        Amount a = r.amount();
        return switch (r.kind()) {
            case KILLS -> a.timed() ? Amount.nice(a.hours() * mobsPerHour(type, from)) : Math.round(a.fixed());
            case MONEY -> a.timed() ? Amount.nice(a.hours() * 3600 * moneyPerSecond() * type.costMultiplier()) : Math.round(a.fixed());
            case XP -> Experience.forLevel((int) Math.round(a.timed() ? 0 : a.fixed()));
            case CURRENCY -> Math.round(a.fixed());
            case ITEM -> {
                if (!a.timed()) yield Math.round(a.fixed());
                String item = r.resolvedItem(type);
                OptionalDouble points = Values.available() ? Values.points(item) : OptionalDouble.empty();
                if (points.isEmpty() || points.getAsDouble() <= 0) {
                    if (warned.add(item)) plugin.getLogger().warning("FluxValue gives no value to " + item + ": goal skipped");
                    yield 0;
                }
                yield Amount.nice(a.hours() * 3600 * pointsPerSecond() * type.costMultiplier() / points.getAsDouble());
            }
        };
    }

    double changePrice(SpawnerType type) {
        Amount a = type.changePrice();
        return a.timed() ? Amount.nice(a.hours() * 3600 * moneyPerSecond()) : a.fixed();
    }

    // ---------------------------------------------------------------- limits

    /** Most active spawners: {@code fluxspawner.active.<n>} (highest wins), else {@code limits.active-default}. */
    int activeLimit(Player p) {
        return limit(p, "fluxspawner.active.", activeDefault);
    }

    /** Most placed spawners: {@code fluxspawner.placed.<n>}, else {@code limits.placed-default}. */
    int placedLimit(Player p) {
        return limit(p, "fluxspawner.placed.", placedDefault);
    }

    private static int limit(Player p, String prefix, int fallback) {
        if (p.hasPermission(prefix + "unlimited")) return Integer.MAX_VALUE;
        int best = -1;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            String node = info.getPermission().toLowerCase(Locale.ROOT);
            if (!info.getValue() || !node.startsWith(prefix)) continue;
            try {
                best = Math.max(best, Integer.parseInt(node.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // another node of the same family
            }
        }
        return best >= 0 ? best : fallback;
    }

    boolean worldAllowed(String world) {
        String w = world.toLowerCase(Locale.ROOT);
        return (worlds.isEmpty() || worlds.contains(w)) && !disabledWorlds.contains(w);
    }
}
