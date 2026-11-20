package fr.influencraft.fluxspawner;

import fr.influencraft.flux.api.mobs.MobStacks;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs the placed spawners. Every tick of {@code production.tick-seconds}, a spawner in a loaded chunk, switched on,
 * with fuel, room for more mobs and a player close enough makes its share of a mob; whole mobs join the stack standing
 * at the spawner (Flux's {@link MobStacks}, FluxStack), so a spawner of level 10 is still one entity. Fuel burns only
 * while it produces. The vanilla spawner block only shows the mob turning inside: its own spawns are cancelled.
 */
final class Production {

    /** Ticks of a vanilla spawner delay we set on our blocks: they never try to spawn by themselves. */
    private static final int NEVER = 20 * 60 * 60 * 6;

    private final FluxSpawner plugin;
    final NamespacedKey mobKey;
    private final NamespacedKey hologramKey;
    private BukkitTask task;
    private int ticks;

    Production(FluxSpawner plugin) {
        this.plugin = plugin;
        this.mobKey = new NamespacedKey(plugin, "spawner");
        this.hologramKey = new NamespacedKey(plugin, "hologram");
    }

    void start() {
        stop();
        long period = 20L * plugin.settings().tickSeconds;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    /** Plugin off: holograms go (they are never saved), the mobs stay where they are with their count. */
    void shutdown() {
        stop();
        for (Spawner s : plugin.spawners().placed()) removeHologram(s);
    }

    // ---------------------------------------------------------------- mobs

    /** Spawner id a mob was made by, null for any other entity. */
    UUID spawnerOf(Entity e) {
        String s = e.getPersistentDataContainer().get(mobKey, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** The spawner's own mobs standing around it (its stack and any single mob). */
    List<LivingEntity> mobs(Spawner s) {
        Location c = s.centre();
        List<LivingEntity> out = new ArrayList<>();
        if (c == null) return out;
        double r = plugin.settings().stockRadius;
        String id = s.id.toString();
        for (Entity e : c.getWorld().getNearbyEntities(c, r, r, r, e -> e instanceof LivingEntity && !(e instanceof Player))) {
            if (id.equals(e.getPersistentDataContainer().get(mobKey, PersistentDataType.STRING)) && e.isValid() && !e.isDead()) {
                out.add((LivingEntity) e);
            }
        }
        return out;
    }

    /** Mobs waiting: the counts of its stacks added up. */
    int waiting(List<LivingEntity> mobs) {
        int n = 0;
        for (LivingEntity e : mobs) n += MobStacks.count(e);
        return n;
    }

    /** Takes every waiting mob away (the spawner is picked up); the number taken. */
    int clear(Spawner s) {
        if (!s.chunkLoaded()) return 0;
        int n = 0;
        for (LivingEntity e : mobs(s)) {
            n += MobStacks.count(e);
            e.remove();
        }
        return n;
    }

    /** What every mob of a spawner keeps: no despawn, no picking up, and standing still when frozen. */
    void tame(LivingEntity e) {
        e.setPersistent(true);
        if (e instanceof Mob m) {
            m.setRemoveWhenFarAway(false);
            m.setCanPickupItems(false);
            if (plugin.settings().freeze && m.isAware()) m.setAware(false);
        }
    }

    /** A new entity of the spawner's mob next to it, standing for {@code count} mobs; null when there is no room. */
    private LivingEntity spawn(Spawner s, SpawnerType type, int count) {
        Location at = spot(s);
        if (at == null) return null;
        Class<? extends Entity> cls = type.entity().getEntityClass();
        if (cls == null) return null;
        String id = s.id.toString();
        Entity e = at.getWorld().spawn(at, cls, CreatureSpawnEvent.SpawnReason.SPAWNER, made -> {
            made.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, id);
            if (made instanceof Ageable a) a.setAdult();
            // one colour: sheep of different colours never stack together
            if (made instanceof org.bukkit.entity.Sheep sheep) sheep.setColor(org.bukkit.DyeColor.WHITE);
            if (made instanceof LivingEntity l) tame(l);
        });
        if (!(e instanceof LivingEntity living) || !e.isValid()) return null;
        // natural spawning may add a rider (spider jockey) or a mount (chicken jockey): a stack is one mob alone
        for (Entity rider : new ArrayList<>(e.getPassengers())) rider.remove();
        Entity vehicle = e.getVehicle();
        if (vehicle != null) {
            vehicle.removePassenger(e);
            vehicle.remove();
        }
        if (count > 1) MobStacks.setCount(living, count);
        return living;
    }

    /**
     * A free spot for a mob: beside the spawner first (its hologram stays clear above it), on the same level, then one
     * below and one above, within {@code spawn-radius}; on top of the spawner last.
     */
    private Location spot(Spawner s) {
        World w = s.bukkitWorld();
        if (w == null) return null;
        int r = Math.max(1, plugin.settings().spawnRadius);
        for (int d = 1; d <= r; d++) {
            for (int dy : new int[]{0, -1, 1}) {
                for (int dx = -d; dx <= d; dx++) {
                    for (int dz = -d; dz <= d; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                        int x = s.x + dx, y = s.y + dy, z = s.z + dz;
                        if (free(w, x, y, z)) return new Location(w, x + 0.5, y, z + 0.5);
                    }
                }
            }
        }
        if (free(w, s.x, s.y + 1, s.z)) return new Location(w, s.x + 0.5, s.y + 1, s.z + 0.5);
        return null;
    }

    private static boolean free(World w, int x, int y, int z) {
        Block feet = w.getBlockAt(x, y, z);
        return feet.isPassable() && !feet.isLiquid() && w.getBlockAt(x, y + 1, z).isPassable()
                && w.getBlockAt(x, y - 1, z).getType().isSolid();
    }

    /** The block shows the mob and never spawns by itself. */
    void dress(Spawner s, SpawnerType type) {
        Block b = s.block();
        if (b == null || b.getType() != Material.SPAWNER || !(b.getState() instanceof CreatureSpawner cs)) return;
        cs.setSpawnedType(type.entity());
        cs.setMinSpawnDelay(0);
        cs.setMaxSpawnDelay(NEVER);
        cs.setMinSpawnDelay(NEVER);
        cs.setDelay(NEVER);
        try {
            cs.setMaxNearbyEntities(0);
        } catch (IllegalArgumentException refused) {
            // older rule of the API: the cancelled spawn events are enough
        }
        cs.update(true, false);
    }

    // ---------------------------------------------------------------- the tick

    private void tick() {
        ticks++;
        double dt = plugin.settings().tickSeconds;
        boolean holograms = plugin.settings().hologram
                && ticks % Math.max(1, plugin.settings().hologramSeconds / plugin.settings().tickSeconds) == 0;
        for (Spawner s : new ArrayList<>(plugin.spawners().placed())) {
            SpawnerType type = plugin.settings().type(s.type);
            if (type == null) continue;
            run(s, type, dt);
            if (holograms) hologram(s, type);
        }
        // fuel moves every second: saved once a minute, and on every discrete change
        if (ticks % Math.max(1, 60 / plugin.settings().tickSeconds) == 0) {
            for (Spawner s : plugin.spawners().all()) if (s.dirty) plugin.store().save(s);
        }
    }

    private void run(Spawner s, SpawnerType type, double dt) {
        if (!s.chunkLoaded()) {
            s.status = Spawner.Status.UNLOADED;
            s.hologram = null;
            return;
        }
        Block b = s.block();
        if (b == null || b.getType() != Material.SPAWNER) {
            plugin.lost(s);
            return;
        }
        List<LivingEntity> mobs = mobs(s);
        for (LivingEntity e : mobs) tame(e);
        s.waiting = waiting(mobs);
        Settings set = plugin.settings();
        int capacity = set.capacity(type, s.level);
        // the mobs it held as an item come back first; what finds no room (no free spot yet, lower capacity) stays in
        // stock for a later tick instead of vanishing
        if (s.stock > 0) {
            int back = Math.min(s.stock, Math.max(0, capacity - s.waiting));
            if (back > 0 && add(s, type, mobs, back)) {
                s.waiting += back;
                s.stock -= back;
                s.dirty = true;
            }
        }
        Spawner.Status before = s.status;
        if (!s.active) {
            s.status = Spawner.Status.PAUSED;
        } else if (set.fuelEnabled && s.fuel <= 0) {
            s.status = Spawner.Status.NO_FUEL;
        } else if (s.waiting >= capacity) {
            s.status = Spawner.Status.FULL;
        } else if (!playerNear(s)) {
            s.status = Spawner.Status.WAITING;
        } else {
            s.pending += set.mobsPerHour(type, s.level) / 3600 * dt;
            int whole = (int) Math.min(Math.floor(s.pending), capacity - s.waiting);
            boolean made = whole <= 0 || add(s, type, mobs, whole);
            if (made) {
                s.status = Spawner.Status.RUNNING;
                if (whole > 0) {
                    s.pending -= whole;
                    s.waiting += whole;
                }
                if (set.fuelEnabled) s.fuel = Math.max(0, s.fuel - dt);
                s.dirty = true;
            } else {
                s.status = Spawner.Status.BLOCKED;
                s.pending = Math.min(s.pending, 1);
            }
        }
        if (s.status != before) plugin.statusChanged(s, before);
    }

    /** Adds {@code n} mobs: to its biggest stack when stacks exist, else as a new entity (one at a time without stacks). */
    private boolean add(Spawner s, SpawnerType type, List<LivingEntity> mobs, int n) {
        if (MobStacks.available()) {
            LivingEntity carrier = null;
            for (LivingEntity e : mobs) if (carrier == null || MobStacks.count(e) > MobStacks.count(carrier)) carrier = e;
            if (carrier != null) {
                int now = MobStacks.count(carrier);
                int set = MobStacks.setCount(carrier, now + n);
                if (set >= now + n) return true;
                n -= Math.max(0, set - now);
            }
            return spawn(s, type, n) != null;
        }
        return spawn(s, type, 1) != null;
    }

    private boolean playerNear(Spawner s) {
        int range = plugin.settings().activationRange;
        if (range <= 0) return true;
        Location c = s.centre();
        if (c == null) return false;
        for (Player p : c.getWorld().getNearbyPlayers(c, range)) {
            if (p.getGameMode() != GameMode.SPECTATOR && !p.isDead()) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- hologram

    /** The text over the spawner: its mob, level and what it is doing. */
    void hologram(Spawner s, SpawnerType type) {
        if (!plugin.settings().hologram || !s.chunkLoaded()) return;
        Location c = s.centre();
        if (c == null) return;
        Location at = c.clone().add(0, plugin.settings().hologramHeight, 0);
        // nobody to read it: no text sent
        if (at.getWorld().getNearbyPlayers(at, 24).isEmpty()) {
            removeHologram(s);
            return;
        }
        String text = String.join("\n", plugin.lines("hologram.lines", plugin.vars(s, type)));
        Entity existing = s.hologram == null ? null : Bukkit.getEntity(s.hologram);
        if (existing instanceof TextDisplay d && d.isValid()) {
            d.text(Texts.parse(text));
            return;
        }
        TextDisplay d = at.getWorld().spawn(at, TextDisplay.class, made -> {
            made.setPersistent(false);
            made.getPersistentDataContainer().set(hologramKey, PersistentDataType.STRING, s.id.toString());
            made.text(Texts.parse(text));
            made.setBillboard(Display.Billboard.CENTER);
            made.setShadowed(true);
            made.setViewRange(0.4f);
        });
        s.hologram = d.getUniqueId();
    }

    void removeHologram(Spawner s) {
        Entity e = s.hologram == null ? null : Bukkit.getEntity(s.hologram);
        if (e != null) e.remove();
        s.hologram = null;
    }

    /** Hours and minutes left of fuel, as menus and holograms show it. */
    static String time(double seconds) {
        return Format.duration(Math.max(0, Math.round(seconds)));
    }
}
