package fr.influencraft.fluxdungeons.script;

import fr.influencraft.flux.api.text.Texts;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs the scripts of dungeon monsters. The format is EliteMobs' EliteScript (events, zones, targets, conditions,
 * cooldowns, timed actions), so the dungeons made for EliteMobs keep their boss fights and their doors; the engine is
 * our own and only knows what our dungeons use. Unknown actions are logged once and skipped.
 */
public final class ScriptEngine {

    /** What the engine asks the dungeon: monsters, reinforcements, fighting players. */
    public interface Services {
        /** The script host of a dungeon monster, null for anything else. */
        ScriptHost host(Entity entity);

        /** Spawns a monster of the run (SUMMON_REINFORCEMENT), null when the template is unknown. */
        LivingEntity summon(ScriptHost by, String template, Location at);

        /** A player who can be targeted (alive, not watching). */
        boolean fighting(Player player);

        /** A hit of the monster on a player, {@code multiplier} times its normal hit (normalized combat applied). */
        void damage(ScriptHost by, Player target, double multiplier);
    }

    /** Why a script runs: its event, the entity the event involved, the damage it may change. */
    public static final class Context {
        final String event;
        final Entity direct;
        final EntityDamageEvent damage;
        final Location landing;
        final Resolved inherited;
        final Zone inheritedZone;
        final long started = Bukkit.getCurrentTick();

        public Context(String event, Entity direct, EntityDamageEvent damage) {
            this(event, direct, damage, null, null, null);
        }

        Context(String event, Entity direct, EntityDamageEvent damage, Location landing, Resolved inherited, Zone inheritedZone) {
            this.event = event;
            this.direct = direct;
            this.damage = damage;
            this.landing = landing;
            this.inherited = inherited;
            this.inheritedZone = inheritedZone;
        }

        Context child(Resolved targets, Zone zone, Location landingAt) {
            return new Context(event, direct, damage, landingAt == null ? landing : landingAt, targets, zone);
        }
    }

    /** Targets resolved by a Target block: entities and/or places. */
    static final class Resolved {
        final List<Entity> entities = new ArrayList<>();
        final List<Location> locations = new ArrayList<>();

        List<Location> places() {
            if (!locations.isEmpty()) return locations;
            List<Location> out = new ArrayList<>();
            for (Entity e : entities) out.add(e.getLocation());
            return out;
        }
    }

    private final Plugin plugin;
    private final Services services;
    private final NamespacedKey fallingKey;
    /** Tags of players (monsters keep theirs in their host). */
    private final Map<UUID, Set<String>> playerTags = new HashMap<>();
    /** Players inside each zone-listening script's zone, per host. */
    private final Map<String, Set<UUID>> inZone = new HashMap<>();
    private final Set<String> warned = new HashSet<>();

    public ScriptEngine(Plugin plugin, Services services) {
        this.plugin = plugin;
        this.services = services;
        this.fallingKey = new NamespacedKey(plugin, "falling_block");
    }

    // ---------------------------------------------------------------- events

    /** An event happened to a monster: runs its scripts listening to it (cooldowns and blocking conditions apply). */
    public void fire(ScriptHost host, String event, Entity direct, EntityDamageEvent damage) {
        if (host.scripts().isEmpty()) return;
        long now = Bukkit.getCurrentTick();
        for (Script s : host.scripts().values()) {
            if (!s.listens(event)) continue;
            boolean gated = s.localCooldown > 0 || s.globalCooldown > 0;
            if (gated) {
                if (host.cooldowns().getOrDefault(s.name, 0L) > now) continue;
                if (s.globalCooldown > 0 && host.globalCooldownUntil() > now) continue;
            }
            Context ctx = new Context(event, direct, damage);
            if (s.conditions != null && !conditionsHold(s.conditions, host, ctx, null, s)) continue;
            if (gated) applyCooldowns(host, s, now);
            run(host, s, ctx);
        }
    }

    private void applyCooldowns(ScriptHost host, Script s, long now) {
        if (s.localCooldown > 0) host.cooldowns().merge(s.name, now + s.localCooldown, Math::max);
        if (s.globalCooldown > 0) host.globalCooldownUntil(Math.max(host.globalCooldownUntil(), now + s.globalCooldown));
    }

    /** Players entering or leaving the zones of ZoneEnterEvent / ZoneLeaveEvent scripts (called a few times a second). */
    public void pollZones(ScriptHost host) {
        for (Script s : host.scripts().values()) {
            if (s.zone == null || !(s.listens("ZoneEnterEvent") || s.listens("ZoneLeaveEvent"))) continue;
            String key = host.entity().getUniqueId() + "/" + s.name;
            Set<UUID> before = inZone.computeIfAbsent(key, k -> new HashSet<>());
            Set<UUID> now = new HashSet<>();
            Context probe = new Context("", null, null);
            for (Zone.Placed z : place(s.zone, host, probe)) {
                for (Entity e : z.entities(false, x -> services.host(x) != null)) if (e instanceof Player) now.add(e.getUniqueId());
            }
            for (UUID u : now) if (!before.contains(u) && s.listens("ZoneEnterEvent")) fire(host, s, "ZoneEnterEvent", Bukkit.getPlayer(u));
            for (UUID u : before) if (!now.contains(u) && s.listens("ZoneLeaveEvent")) fire(host, s, "ZoneLeaveEvent", Bukkit.getPlayer(u));
            before.clear();
            before.addAll(now);
        }
    }

    private void fire(ScriptHost host, Script s, String event, Entity direct) {
        long now = Bukkit.getCurrentTick();
        if (host.cooldowns().getOrDefault(s.name, 0L) > now) return;
        Context ctx = new Context(event, direct, null);
        if (s.conditions != null && !conditionsHold(s.conditions, host, ctx, null, s)) return;
        applyCooldowns(host, s, now);
        run(host, s, ctx);
    }

    /** A monster is gone: forget its zone memory. */
    public void forget(ScriptHost host) {
        String prefix = host.entity().getUniqueId() + "/";
        inZone.keySet().removeIf(k -> k.startsWith(prefix));
    }

    // ---------------------------------------------------------------- running

    void run(ScriptHost host, Script script, Context ctx) {
        for (Map<?, ?> action : script.actions) schedule(host, script, action, ctx);
    }

    private void schedule(ScriptHost host, Script script, Map<?, ?> action, Context ctx) {
        long wait = (long) Math.max(0, Script.num(action, "wait", 0));
        long every = (long) Script.num(action, "repeatEvery", 0);
        int times = (int) Script.num(action, "times", -1);
        Map<?, ?> conditions = Script.map(action, "Conditions");
        if (every <= 0) {
            Runnable once = () -> {
                if (!alive(host)) return;
                execute(host, script, action, ctx, conditions);
            };
            if (wait == 0) once.run();
            else Bukkit.getScheduler().runTaskLater(plugin, once, wait);
            return;
        }
        new BukkitRunnable() {
            int count;

            public void run() {
                count++;
                if ((times > 0 && count > times) || !alive(host)) {
                    cancel();
                    return;
                }
                if (!execute(host, script, action, ctx, conditions)) cancel();
            }
        }.runTaskTimer(plugin, wait, every);
    }

    private static boolean alive(ScriptHost host) {
        LivingEntity e = host.entity();
        return e != null && e.getWorld() != null && (e.isValid() || e.isDead());
    }

    /** Runs one action now; false when its blocking conditions failed (a repeating action then stops). */
    private boolean execute(ScriptHost host, Script script, Map<?, ?> action, Context ctx, Map<?, ?> conditions) {
        String type = Script.text(action, "action", "").toUpperCase(Locale.ROOT);
        Map<?, ?> targetSpec = Script.map(action, "Target");
        Resolved targets = resolve(targetSpec, host, ctx, script);
        if (conditions != null) {
            String mode = Script.text(conditions, "conditionType", "BLOCKING").toUpperCase(Locale.ROOT);
            Map<?, ?> condTarget = Script.map(conditions, "Target");
            boolean filtering = mode.equals("FILTERING")
                    && (condTarget == null || "ACTION_TARGET".equalsIgnoreCase(Script.text(condTarget, "targetType", "")));
            if (filtering) {
                targets.entities.removeIf(e -> !checks(conditions, e, e.getLocation(), host, targets.entities.size()));
                targets.locations.removeIf(l -> !checks(conditions, null, l, host, targets.locations.size()));
            } else if (!conditionsHold(conditions, host, ctx, targets, script)) {
                return false;
            }
        }
        try {
            act(type, action, targets, host, script, ctx);
        } catch (RuntimeException e) {
            if (warned.add("err:" + script.name + type)) {
                plugin.getLogger().warning("Script " + script.name + ", action " + type + " failed: " + e);
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- targets

    Resolved resolve(Map<?, ?> spec, ScriptHost host, Context ctx, Script script) {
        Resolved r = new Resolved();
        String type = spec == null ? "SELF" : Script.text(spec, "targetType", "SELF").toUpperCase(Locale.ROOT);
        LivingEntity self = host.entity();
        double range = spec == null ? 20 : Script.num(spec, "range", 20);
        switch (type) {
            case "SELF" -> r.entities.add(self);
            case "SELF_SPAWN" -> r.locations.add(host.spawn().clone());
            case "DIRECT_TARGET" -> {
                if (ctx.direct != null) r.entities.add(ctx.direct);
            }
            case "NEARBY_PLAYERS" -> {
                for (Entity e : self.getWorld().getNearbyEntities(self.getLocation(), range, range, range)) {
                    if (e instanceof Player p && services.fighting(p)) r.entities.add(p);
                }
            }
            case "WORLD_PLAYERS", "ALL_PLAYERS" -> {
                for (Player p : self.getWorld().getPlayers()) if (p.getGameMode() != GameMode.SPECTATOR || type.equals("WORLD_PLAYERS")) r.entities.add(p);
            }
            case "NEARBY_MOBS", "NEARBY_ELITES" -> {
                for (Entity e : self.getWorld().getNearbyEntities(self.getLocation(), range, range, range)) {
                    if (e == self || !(e instanceof LivingEntity) || e instanceof Player) continue;
                    if (type.equals("NEARBY_ELITES") && services.host(e) == null) continue;
                    r.entities.add(e);
                }
            }
            case "LOCATION" -> {
                Location l = location(Script.text(spec, "location", ""), self.getWorld());
                if (l != null) r.locations.add(l);
            }
            case "LOCATIONS" -> {
                for (String s : Script.list(spec, "locations")) {
                    Location l = location(s, self.getWorld());
                    if (l != null) r.locations.add(l);
                }
            }
            case "LANDING_LOCATION" -> {
                if (ctx.landing != null) r.locations.add(ctx.landing.clone());
            }
            case "ZONE_FULL", "ZONE_BORDER", "INHERIT_SCRIPT_ZONE_FULL", "INHERIT_SCRIPT_ZONE_BORDER" -> {
                Zone zone = type.startsWith("INHERIT") ? ctx.inheritedZone : script.zone;
                if (zone != null) {
                    boolean border = type.endsWith("BORDER");
                    for (Zone.Placed z : place(zone, host, ctx)) {
                        r.entities.addAll(z.entities(border, e -> services.host(e) != null));
                        r.locations.addAll(z.points(border));
                    }
                }
            }
            case "INHERIT_SCRIPT_TARGET", "ACTION_TARGET" -> {
                if (ctx.inherited != null) {
                    r.entities.addAll(ctx.inherited.entities);
                    r.locations.addAll(ctx.inherited.locations);
                }
            }
            default -> {
                if (warned.add("target:" + type)) plugin.getLogger().warning("Unknown script target type: " + type);
            }
        }
        if (spec != null) {
            Vector offset = vector(Script.text(spec, "offset", ""));
            if (offset != null) {
                if (r.locations.isEmpty() && !r.entities.isEmpty()) for (Entity e : r.entities) r.locations.add(e.getLocation());
                for (Location l : r.locations) l.add(offset);
            }
            double coverage = Script.num(spec, "coverage", 1);
            if (coverage < 1 && r.locations.size() > 1) {
                Collections.shuffle(r.locations);
                int keep = Math.max(1, (int) Math.round(r.locations.size() * coverage));
                r.locations.subList(keep, r.locations.size()).clear();
            }
        }
        return r;
    }

    /** The zone placed on each place its Target resolves to. */
    List<Zone.Placed> place(Zone zone, ScriptHost host, Context ctx) {
        List<Zone.Placed> out = new ArrayList<>();
        List<Location> origins = resolve(zone.target, host, ctx, null).places();
        Location second = zone.target2 == null ? null : first(resolve(zone.target2, host, ctx, null).places());
        Location finalA = zone.finalTarget == null ? null : first(resolve(zone.finalTarget, host, ctx, null).places());
        Location finalB = zone.finalTarget2 == null ? null : first(resolve(zone.finalTarget2, host, ctx, null).places());
        long elapsed = Bukkit.getCurrentTick() - ctx.started;
        for (Location o : origins) out.add(zone.place(o.clone(), second == null ? null : second.clone(), finalA, finalB, elapsed));
        return out;
    }

    private static Location first(List<Location> list) {
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * A place {@code world,x,y,z[,yaw,pitch]}: the world name is ignored (a run plays in its own copy of the map, the
     * coordinates are those of the original map, {@code same_as_boss} included).
     */
    static Location location(String spec, World world) {
        String[] a = spec.split(",");
        if (a.length < 4) return null;
        try {
            Location l = new Location(world, Double.parseDouble(a[1].trim()), Double.parseDouble(a[2].trim()), Double.parseDouble(a[3].trim()));
            if (a.length >= 6) {
                l.setYaw(Float.parseFloat(a[4].trim()));
                l.setPitch(Float.parseFloat(a[5].trim().split(":")[0]));
            }
            return l;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Vector vector(String s) {
        if (s == null || s.isBlank()) return null;
        String[] a = s.split(",");
        if (a.length < 3) return null;
        try {
            return new Vector(Double.parseDouble(a[0].trim()), Double.parseDouble(a[1].trim()), Double.parseDouble(a[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- conditions

    private boolean conditionsHold(Map<?, ?> c, ScriptHost host, Context ctx, Resolved actionTargets, Script script) {
        Map<?, ?> spec = Script.map(c, "Target");
        Resolved on = spec == null ? null : ("ACTION_TARGET".equalsIgnoreCase(Script.text(spec, "targetType", "")) ? actionTargets : resolve(spec, host, ctx, script));
        if (on == null) {
            on = new Resolved();
            on.entities.add(host.entity());
        }
        int count = on.entities.size() + on.locations.size();
        if (Script.get(c, "targetCountGreaterThan") != null) {
            boolean ok = count > Script.num(c, "targetCountGreaterThan", 0);
            if (ok != Script.bool(c, "runIfTargetCountGreaterThanIs", true)) return false;
        }
        if (Script.get(c, "targetCountLowerThan") != null) {
            boolean ok = count < Script.num(c, "targetCountLowerThan", 0);
            if (ok != Script.bool(c, "runIfTargetCountLowerThanIs", true)) return false;
        }
        if (on.entities.isEmpty() && on.locations.isEmpty()) {
            // nothing to check on: only isAlive can fail (a missing entity is not alive)
            return Script.get(c, "isAlive") == null || !Script.bool(c, "isAlive", true);
        }
        for (Entity e : on.entities) if (!checks(c, e, e.getLocation(), host, count)) return false;
        for (Location l : on.locations) if (!checks(c, null, l, host, count)) return false;
        return true;
    }

    private boolean checks(Map<?, ?> c, Entity e, Location l, ScriptHost host, int count) {
        if (Script.get(c, "isAlive") != null) {
            boolean alive = e == null || e.isValid();
            if ((alive == Script.bool(c, "isAlive", true)) != Script.bool(c, "runIfAliveIs", true)) return false;
        }
        if (Script.get(c, "hasTags") != null) {
            boolean has = tagsOf(e, host).containsAll(Script.list(c, "hasTags"));
            if (has != Script.bool(c, "runIfHasTagIs", true)) return false;
        }
        if (Script.get(c, "doesNotHaveTags") != null) {
            boolean lacks = !tagsOf(e, host).containsAll(Script.list(c, "doesNotHaveTags"));
            if (lacks != Script.bool(c, "runIfDoesNotHaveTagIs", true)) return false;
        }
        if (Script.get(c, "locationIsAir") != null) {
            boolean air = l.getBlock().getType().isAir();
            if ((air == Script.bool(c, "locationIsAir", true)) != Script.bool(c, "runIfLocationIsAirIs", true)) return false;
        }
        if (Script.get(c, "isOnFloor") != null) {
            boolean floor = l.getBlock().isPassable() && l.clone().add(0, -1, 0).getBlock().getType().isSolid();
            if ((floor == Script.bool(c, "isOnFloor", true)) != Script.bool(c, "runIfIsOnFloorIs", true)) return false;
        }
        if (Script.get(c, "isStandingOnMaterial") != null) {
            Material m = Material.matchMaterial(Script.text(c, "isStandingOnMaterial", ""));
            boolean on = m != null && l.clone().add(0, -1, 0).getBlock().getType() == m;
            if (on != Script.bool(c, "runIfIsStandingOnMaterialIs", true)) return false;
        }
        if (Script.get(c, "randomChance") != null) {
            boolean hit = ThreadLocalRandom.current().nextDouble() < Script.num(c, "randomChance", 1);
            if (hit != Script.bool(c, "runIfRandomChanceIs", true)) return false;
        }
        return true;
    }

    private Set<String> tagsOf(Entity e, ScriptHost host) {
        if (e == null) return Set.of();
        if (e instanceof Player p) return playerTags.getOrDefault(p.getUniqueId(), Set.of());
        ScriptHost h = services.host(e);
        return h == null ? Set.of() : h.tags();
    }

    // ---------------------------------------------------------------- actions

    private void act(String type, Map<?, ?> a, Resolved t, ScriptHost host, Script script, Context ctx) {
        LivingEntity self = host.entity();
        World w = self.getWorld();
        switch (type) {
            case "SPAWN_PARTICLE" -> particles(a, t);
            case "PLAY_SOUND" -> {
                String sound = Script.text(a, "sValue", "");
                float volume = (float) Script.num(a, "volume", 1), pitch = (float) Script.num(a, "pitch", 1);
                for (Location l : t.places()) w.playSound(l, sound.toLowerCase(Locale.ROOT), volume, pitch);
            }
            case "MESSAGE" -> {
                String msg = Script.text(a, "sValue", "");
                for (Entity e : t.entities) if (e instanceof Player p) p.sendMessage(Texts.parse(msg));
            }
            case "TITLE_MESSAGE" -> {
                long stay = (long) Script.num(a, "duration", 40), in = (long) Script.num(a, "fadeIn", 1), out = (long) Script.num(a, "fadeOut", 1);
                Title title = Title.title(Texts.parse(Script.text(a, "title", "")), Texts.parse(Script.text(a, "subtitle", "")),
                        Title.Times.times(Duration.ofMillis(in * 50), Duration.ofMillis(stay * 50), Duration.ofMillis(out * 50)));
                for (Entity e : t.entities) if (e instanceof Player p) p.showTitle(title);
            }
            case "POTION_EFFECT" -> {
                PotionEffectType effect = effect(Script.text(a, "potionEffectType", ""));
                if (effect == null) return;
                PotionEffect pe = new PotionEffect(effect, (int) Script.num(a, "duration", 100), (int) Script.num(a, "amplifier", 0));
                for (Entity e : t.entities) if (e instanceof LivingEntity le) le.addPotionEffect(pe);
            }
            case "DAMAGE" -> {
                double multiplier = Script.num(a, "multiplier", 1), amount = Script.num(a, "amount", 1);
                for (Entity e : t.entities) {
                    if (!(e instanceof LivingEntity le) || le == self) continue;
                    if (le instanceof Player p) {
                        if (services.fighting(p)) services.damage(host, p, multiplier);
                    } else {
                        le.damage(amount, self);
                    }
                }
            }
            case "PUSH" -> {
                Map<?, ?> relative = Script.map(a, "RelativeVector");
                Vector fixed = vector(Script.text(a, "vValue", ""));
                boolean add = Script.bool(a, "bValue", false);
                List<Entity> list = new ArrayList<>(t.entities);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    for (Entity e : list) {
                        if (!e.isValid()) continue;
                        Vector v = relative != null ? relativeVector(relative, e, host, ctx, script) : fixed;
                        if (v == null) continue;
                        e.setVelocity(add ? e.getVelocity().add(v) : v);
                    }
                }, 1L);
            }
            case "PLACE_BLOCK" -> {
                Material m = Material.matchMaterial(Script.text(a, "material", "AIR"));
                if (m == null || !m.isBlock()) return;
                int duration = (int) Script.num(a, "duration", 0);
                BlockData data = m.createBlockData();
                for (Location l : t.places()) placeBlock(l.getBlock(), data, duration);
            }
            case "SPAWN_FALLING_BLOCK" -> {
                Material m = Material.matchMaterial(Script.text(a, "material", "STONE"));
                if (m == null || !m.isBlock()) return;
                Vector v = vector(Script.text(a, "vValue", ""));
                List<String> landing = Script.list(a, "landingScripts");
                for (Location l : t.places()) {
                    FallingBlock fb = w.spawn(l, FallingBlock.class, f -> {
                        f.setBlockData(m.createBlockData());
                        f.setDropItem(false);
                        f.setCancelDrop(true);
                        f.setHurtEntities(false);
                        f.getPersistentDataContainer().set(fallingKey, PersistentDataType.STRING, host.entity().getUniqueId().toString());
                    });
                    if (v != null) fb.setVelocity(v);
                    if (!landing.isEmpty()) watchLanding(fb, host, landing, ctx, t);
                }
            }
            case "SUMMON_ENTITY" -> {
                EntityType et = entityType(Script.text(a, "sValue", ""));
                if (et == null || et.getEntityClass() == null) return;
                Vector v = vector(Script.text(a, "vValue", ""));
                int duration = (int) Script.num(a, "duration", 0);
                List<String> landing = Script.list(a, "landingScripts");
                for (Location l : t.places()) {
                    Entity e = w.spawn(l, et.getEntityClass());
                    if (v != null) e.setVelocity(v);
                    if (duration > 0) Bukkit.getScheduler().runTaskLater(plugin, e::remove, duration);
                    if (!landing.isEmpty()) watchLanding(e, host, landing, ctx, t);
                }
            }
            case "SUMMON_REINFORCEMENT" -> {
                String file = Script.text(a, "sValue", "");
                String id = file.endsWith(".yml") ? file.substring(0, file.length() - 4) : file;
                int duration = (int) Script.num(a, "duration", 0);
                Vector v = vector(Script.text(a, "vValue", ""));
                for (Location l : t.places()) {
                    LivingEntity mob = services.summon(host, id, l);
                    if (mob == null) {
                        if (warned.add("summon:" + id)) plugin.getLogger().warning("Unknown reinforcement " + id + " in script " + script.name);
                        continue;
                    }
                    if (v != null) mob.setVelocity(v);
                    if (duration > 0) Bukkit.getScheduler().runTaskLater(plugin, mob::remove, duration);
                }
            }
            case "TELEPORT" -> {
                Map<?, ?> fin = Script.map(a, "FinalTarget");
                Location to = fin == null ? null : first(resolve(fin, host, ctx, script).places());
                if (to == null) return;
                for (Entity e : t.entities) e.teleport(to);
            }
            case "NAVIGATE" -> {
                Map<?, ?> fin = Script.map(a, "FinalTarget");
                Location to = fin == null ? null : first(resolve(fin, host, ctx, script).places());
                if (to == null) return;
                double speed = Script.num(a, "velocity", 1);
                boolean force = Script.bool(a, "bValue", false);
                long duration = (long) Script.num(a, "duration", 100);
                for (Entity e : t.entities) {
                    if (!(e instanceof Mob mob)) continue;
                    mob.getPathfinder().moveTo(to, speed);
                    if (force) Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (mob.isValid() && mob.getLocation().distanceSquared(to) > 4) mob.teleport(to);
                    }, duration);
                }
            }
            case "SET_MOB_AI" -> {
                boolean on = Script.bool(a, "bValue", true);
                int duration = (int) Script.num(a, "duration", 0);
                for (Entity e : t.entities) {
                    if (!(e instanceof LivingEntity le) || e instanceof Player) continue;
                    le.setAI(on);
                    if (duration > 0) Bukkit.getScheduler().runTaskLater(plugin, () -> le.setAI(!on), duration);
                }
            }
            case "SET_MOB_AWARE" -> {
                boolean on = Script.bool(a, "bValue", true);
                int duration = (int) Script.num(a, "duration", 0);
                for (Entity e : t.entities) {
                    if (!(e instanceof Mob m)) continue;
                    m.setAware(on);
                    if (duration > 0) Bukkit.getScheduler().runTaskLater(plugin, () -> m.setAware(!on), duration);
                }
            }
            case "MAKE_INVULNERABLE" -> {
                boolean on = Script.bool(a, "invulnerable", true);
                int duration = (int) Script.num(a, "duration", 0);
                for (Entity e : t.entities) {
                    e.setInvulnerable(on);
                    if (duration > 0) Bukkit.getScheduler().runTaskLater(plugin, () -> e.setInvulnerable(!on), duration);
                }
            }
            case "SET_ON_FIRE" -> {
                int duration = (int) Script.num(a, "duration", 60);
                for (Entity e : t.entities) e.setFireTicks(Math.max(e.getFireTicks(), duration));
            }
            case "VISUAL_FREEZE" -> {
                int amount = (int) Script.num(a, "amount", Script.num(a, "duration", 40));
                for (Entity e : t.entities) e.setFreezeTicks(e.getFreezeTicks() + amount);
            }
            case "STRIKE_LIGHTNING" -> {
                for (Location l : t.places()) {
                    w.strikeLightningEffect(l);
                    for (Entity e : w.getNearbyEntities(l, 1.5, 3, 1.5)) {
                        if (e instanceof Player p && services.fighting(p)) services.damage(host, p, 1);
                    }
                }
            }
            case "TAG", "UNTAG" -> {
                List<String> tags = Script.list(a, "tags");
                int duration = (int) Script.num(a, "duration", 0);
                for (Entity e : t.entities) {
                    Set<String> set = e instanceof Player p ? playerTags.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>())
                            : services.host(e) != null ? services.host(e).tags() : null;
                    if (set == null) continue;
                    if (type.equals("TAG")) set.addAll(tags);
                    else tags.forEach(set::remove);
                    if (duration > 0) Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (type.equals("TAG")) tags.forEach(set::remove);
                        else set.addAll(tags);
                    }, duration);
                }
            }
            case "MODIFY_DAMAGE" -> {
                if (ctx.damage != null) ctx.damage.setDamage(ctx.damage.getDamage() * Script.num(a, "multiplier", 1));
            }
            case "RUN_SCRIPT" -> {
                List<String> names = Script.list(a, "scripts");
                if (Script.bool(a, "onlyRunOneScript", false) && !names.isEmpty()) {
                    names = List.of(names.get(ThreadLocalRandom.current().nextInt(names.size())));
                }
                Context child = ctx.child(t, script.zone, null);
                for (String n : names) {
                    Script s = host.scripts().get(n);
                    if (s == null) continue;
                    if (s.conditions != null && !conditionsHold(s.conditions, host, child, t, s)) continue;
                    applyCooldowns(host, s, Bukkit.getCurrentTick());
                    run(host, s, child);
                }
            }
            case "SET_TIME" -> w.setTime((long) Script.num(a, "time", 6000));
            case "PLAY_ANIMATION" -> host.animate(Script.text(a, "sValue", ""));
            case "HEAL" -> {
                double amount = Script.num(a, "amount", 1);
                for (Entity e : t.entities) if (e instanceof LivingEntity le) le.heal(amount);
            }
            case "SCALE" -> {
                double scale = Script.num(a, "scale", 1);
                for (Entity e : t.entities) {
                    if (!(e instanceof LivingEntity le)) continue;
                    var inst = le.getAttribute(org.bukkit.attribute.Attribute.SCALE);
                    if (inst != null) inst.setBaseValue(scale);
                }
            }
            default -> {
                if (warned.add("action:" + type)) plugin.getLogger().warning("Script action not supported (skipped): " + type);
            }
        }
    }

    private Vector relativeVector(Map<?, ?> rv, Entity target, ScriptHost host, Context ctx, Script script) {
        Map<?, ?> src = Script.map(rv, "SourceTarget"), dst = Script.map(rv, "DestinationTarget");
        Location from = src == null ? host.entity().getLocation() : first(resolve(src, host, ctx, script).places());
        Location to = dst == null ? target.getLocation() : first(resolve(dst, host, ctx, script).places());
        if (from == null || to == null || from.getWorld() != to.getWorld()) return null;
        Vector v = to.toVector().subtract(from.toVector());
        if (Script.bool(rv, "normalize", false) && v.lengthSquared() > 0) v.normalize();
        v.multiply(Script.num(rv, "multiplier", 1));
        Vector offset = vector(Script.text(rv, "offset", ""));
        if (offset != null) v.add(offset);
        return v;
    }

    /** Places a block, for good or for {@code duration} ticks (then the former block comes back if still there). */
    private void placeBlock(Block block, BlockData data, int duration) {
        BlockData before = block.getBlockData();
        block.setBlockData(data, false);
        if (duration > 0) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (block.getBlockData().equals(data)) block.setBlockData(before, false);
        }, duration);
    }

    /** Runs landing scripts once a falling block or a summoned entity touches the ground (6000 ticks at most). */
    private void watchLanding(Entity e, ScriptHost host, List<String> scripts, Context ctx, Resolved parent) {
        new BukkitRunnable() {
            int ticks;
            Location last = e.getLocation();

            public void run() {
                ticks++;
                if (e.isValid()) last = e.getLocation();
                boolean landed = !e.isValid() || e.isOnGround();
                if (!landed && ticks < 6000) return;
                cancel();
                if (e instanceof FallingBlock) e.remove();
                Context child = ctx.child(parent, null, last);
                for (String n : scripts) {
                    Script s = host.scripts().get(n);
                    if (s != null) ScriptEngine.this.run(host, s, child);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Falling blocks of scripts never become blocks. */
    public boolean ownsFallingBlock(Entity e) {
        return e.getPersistentDataContainer().has(fallingKey, PersistentDataType.STRING);
    }

    // ---------------------------------------------------------------- particles, effects, names

    private void particles(Map<?, ?> a, Resolved t) {
        if (!(Script.get(a, "particles") instanceof List<?> list)) return;
        for (Location l : t.places()) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> p)) continue;
                Particle particle = particle(Script.text(p, "particle", "FLAME"));
                if (particle == null) continue;
                int amount = (int) Script.num(p, "amount", 1);
                double x = Script.num(p, "x", 0.01), y = Script.num(p, "y", 0.01), z = Script.num(p, "z", 0.01);
                double speed = Script.num(p, "speed", 0.01);
                try {
                    if (particle.getDataType() == Particle.DustOptions.class) {
                        Color color = Color.fromRGB((int) Script.num(p, "red", 255), (int) Script.num(p, "green", 0), (int) Script.num(p, "blue", 0));
                        l.getWorld().spawnParticle(particle, l, Math.max(1, amount), x, y, z, speed, new Particle.DustOptions(color, 1.2f));
                    } else if (particle.getDataType() == Void.class) {
                        l.getWorld().spawnParticle(particle, l, amount, x, y, z, speed);
                    }
                } catch (RuntimeException ignored) {
                    // a particle needing data we do not give: skipped
                }
            }
        }
    }

    private static final Map<String, String> PARTICLES = Map.ofEntries(
            Map.entry("EXPLOSION_HUGE", "EXPLOSION_EMITTER"), Map.entry("EXPLOSION_LARGE", "EXPLOSION"),
            Map.entry("EXPLOSION_NORMAL", "POOF"), Map.entry("SMOKE_NORMAL", "SMOKE"), Map.entry("SMOKE_LARGE", "LARGE_SMOKE"),
            Map.entry("REDSTONE", "DUST"), Map.entry("SPELL", "EFFECT"), Map.entry("SPELL_INSTANT", "INSTANT_EFFECT"),
            Map.entry("SPELL_WITCH", "WITCH"), Map.entry("SPELL_MOB", "EFFECT"), Map.entry("SPELL_MOB_AMBIENT", "EFFECT"),
            Map.entry("VILLAGER_HAPPY", "HAPPY_VILLAGER"), Map.entry("VILLAGER_ANGRY", "ANGRY_VILLAGER"),
            Map.entry("CRIT_MAGIC", "ENCHANTED_HIT"), Map.entry("DRIP_LAVA", "DRIPPING_LAVA"), Map.entry("DRIP_WATER", "DRIPPING_WATER"),
            Map.entry("FIREWORKS_SPARK", "FIREWORK"), Map.entry("SLIME", "ITEM_SLIME"), Map.entry("SNOWBALL", "ITEM_SNOWBALL"),
            Map.entry("TOTEM", "TOTEM_OF_UNDYING"), Map.entry("SUSPENDED_DEPTH", "UNDERWATER"), Map.entry("TOWN_AURA", "MYCELIUM"),
            Map.entry("ENCHANTMENT_TABLE", "ENCHANT"), Map.entry("WATER_SPLASH", "SPLASH"), Map.entry("WATER_WAKE", "FISHING"),
            Map.entry("WATER_BUBBLE", "BUBBLE"), Map.entry("SNOW_SHOVEL", "ITEM_SNOWBALL"), Map.entry("BLOCK_CRACK", "POOF"),
            Map.entry("BLOCK_DUST", "POOF"), Map.entry("ITEM_CRACK", "POOF"), Map.entry("MOB_APPEARANCE", "ELDER_GUARDIAN"),
            Map.entry("FLASH", "FLASH"));

    static Particle particle(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        n = PARTICLES.getOrDefault(n, n);
        try {
            return Particle.valueOf(n);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final Map<String, String> EFFECTS = Map.ofEntries(
            Map.entry("SLOW", "slowness"), Map.entry("FAST_DIGGING", "haste"), Map.entry("SLOW_DIGGING", "mining_fatigue"),
            Map.entry("INCREASE_DAMAGE", "strength"), Map.entry("HEAL", "instant_health"), Map.entry("HARM", "instant_damage"),
            Map.entry("JUMP", "jump_boost"), Map.entry("CONFUSION", "nausea"), Map.entry("DAMAGE_RESISTANCE", "resistance"));

    public static PotionEffectType effect(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        String key = EFFECTS.getOrDefault(n, n.toLowerCase(Locale.ROOT));
        return Registry.EFFECT.get(NamespacedKey.minecraft(key));
    }

    static EntityType entityType(String name) {
        try {
            return EntityType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
