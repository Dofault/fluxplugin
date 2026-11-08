package fr.influencraft.fluxdungeons.dungeon;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.value.Values;
import fr.influencraft.fluxdungeons.Loadouts;
import fr.influencraft.fluxdungeons.hero.Abilities;
import fr.influencraft.fluxdungeons.hero.Heroes;
import fr.influencraft.fluxdungeons.mob.MobTemplate;
import fr.influencraft.fluxdungeons.mob.Mobs;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Every run of every dungeon: starting (copy of the template, players swapped to their dungeon inventory and
 * teleported), rooms (a room wakes up when a player enters it, its gate opens once its waves are dead), deaths
 * (lives shared by the party, then watching), victory (money, class experience, personal loot), defeat, leaving, and
 * the wiping of the copy.
 */
public final class Runs {

    /** Why a run could not start. */
    public enum Refusal { NONE, NO_CLASS, TOO_FEW, TOO_MANY, BUSY_PLAYER, NO_SLOT, NOT_LEADER, EDITING, UNKNOWN }

    private final FluxPlugin plugin;
    private final Arena arena;
    private final Mobs mobs;
    private final Heroes heroes;
    private final Loadouts loadouts;
    private final WorldInstances instances;
    private final Editor editor;
    private final Map<String, DungeonDefinition> dungeons = new LinkedHashMap<>();
    private final Map<String, Run> runs = new HashMap<>();
    private final Map<UUID, Run> byPlayer = new HashMap<>();
    /** Players this plugin is teleporting (the guard against leaving the dungeon world lets them through). */
    private final Set<UUID> teleporting = new HashSet<>();
    private int maxRuns = 8;
    private int returnDelay = 10;
    private double extraMobsPerPlayer = 0.5, bossHealthPerPlayer = 0.6;
    private double fallbackMoneyPerMinute = 50;
    private double spawnDistance = 40;
    private String origin = "victory";
    private int ticks;

    public Runs(FluxPlugin plugin, Arena arena, Mobs mobs, Heroes heroes, Loadouts loadouts) {
        this.plugin = plugin;
        this.arena = arena;
        this.mobs = mobs;
        this.heroes = heroes;
        this.loadouts = loadouts;
        this.instances = new WorldInstances(plugin);
        instances.sweep();
        this.editor = new Editor(plugin, instances, this::dungeon, mobs);
        mobs.onSummon((parent, child) -> {
            Run run = runs.get(mobs.runOf(child));
            if (run != null) run.mobs.add(child.getUniqueId());
        });
        mobs.onDeath(this::mobKilled);
        mobs.runLevel(id -> {
            Run r = runs.get(id);
            return r == null ? 0 : r.level;
        }, id -> {
            Run r = runs.get(id);
            return r == null ? 1 : r.partySize;
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    /** Monsters give their loot when killed; off: only the end of the dungeon rewards. */
    private boolean mobLoot;
    private final Map<String, Double> classLootChance = new HashMap<>();
    private double relevantChance = 0.8;

    public void settings(org.bukkit.configuration.ConfigurationSection c) {
        maxRuns = Math.max(1, c.getInt("max-runs", 8));
        returnDelay = Math.max(1, c.getInt("return-delay", 10));
        extraMobsPerPlayer = c.getDouble("scaling.extra-mobs-per-player", 0.5);
        bossHealthPerPlayer = c.getDouble("scaling.boss-health-per-player", 0.6);
        fallbackMoneyPerMinute = c.getDouble("reward.money-per-minute-without-fluxvalue", 50);
        spawnDistance = Math.max(8, c.getDouble("spawn-distance", 40));
        WorldInstances.viewDistance = Math.max(2, c.getInt("view-distance", 8));
        mobLoot = c.getBoolean("mob-loot", true);
        classLootChance.clear();
        classLootChance.put("TRASH", c.getDouble("class-loot-chance.trash", 0.05));
        classLootChance.put("MINIBOSS", c.getDouble("class-loot-chance.miniboss", 0.25));
        classLootChance.put("BOSS", c.getDouble("class-loot-chance.boss", 1.0));
        relevantChance = c.getDouble("class-loot-relevant-chance", 0.8);
    }

    public void load(Map<String, DungeonDefinition> definitions) {
        dungeons.clear();
        dungeons.putAll(definitions);
    }

    public Collection<DungeonDefinition> dungeons() {
        return dungeons.values();
    }

    public DungeonDefinition dungeon(String id) {
        return dungeons.get(id);
    }

    public Run runOf(Player player) {
        return byPlayer.get(player.getUniqueId());
    }

    public Run runOf(UUID uuid) {
        return byPlayer.get(uuid);
    }

    public boolean teleporting(Player player) {
        return teleporting.contains(player.getUniqueId());
    }

    public Collection<Run> all() {
        return List.copyOf(runs.values());
    }

    // ---------------------------------------------------------------- templates

    /** Builds the template of every dungeon whose definition changed since it was last built. */
    public void buildChanged() {
        for (DungeonDefinition def : dungeons.values()) {
            if (def.map() == null && !def.fingerprint().equals(arena.builtFrom(def.id()))) build(def, null);
        }
    }

    public void build(DungeonDefinition def, Runnable done) {
        plugin.getLogger().info("Building the template of dungeon " + def.id() + "...");
        arena.build(def, def.fingerprint(), () -> {
            plugin.getLogger().info("Template of dungeon " + def.id() + " built.");
            if (done != null) done.run();
        });
    }

    public Location templateOrigin(DungeonDefinition def) {
        return arena.templateOrigin(def.id());
    }

    // ---------------------------------------------------------------- starting

    /** Checks that these players can play this dungeon together. */
    public Refusal check(DungeonDefinition def, List<Player> players) {
        if (editor.locked(def.id())) return Refusal.EDITING;
        if (players.size() < def.minPlayers()) return Refusal.TOO_FEW;
        if (players.size() > def.maxPlayers()) return Refusal.TOO_MANY;
        for (Player p : players) {
            if (byPlayer.containsKey(p.getUniqueId())) return Refusal.BUSY_PLAYER;
            if (heroes.classOf(p.getUniqueId()) == null) return Refusal.NO_CLASS;
        }
        if (def.map() != null ? instances.freeName(maxRuns) == null : freeSlot() < 0) return Refusal.NO_SLOT;
        return Refusal.NONE;
    }

    /** The first player who blocks {@link #check} for a refusal about one player (no class, already playing). */
    public Player blocking(List<Player> players) {
        for (Player p : players) {
            if (byPlayer.containsKey(p.getUniqueId()) || heroes.classOf(p.getUniqueId()) == null) return p;
        }
        return null;
    }

    /** Starts a run for these players (checked before). The copy is made, then they are taken in. */
    public Run start(DungeonDefinition def, List<Player> players) {
        if (def.map() != null) return startMap(def, players);
        int slot = freeSlot();
        if (slot < 0) return null;
        Run run = new Run(UUID.randomUUID().toString().substring(0, 8), def, slot, arena.slotOrigin(slot));
        runs.put(run.id, run);
        for (Player p : players) {
            run.expected.add(p.getUniqueId());
            byPlayer.put(p.getUniqueId(), run);
            plugin.messages().send(p, "run.preparing", "%dungeon%", def.name());
        }
        Runnable copy = () -> arena.copy(def, run.origin, () -> begin(run));
        if (!def.fingerprint().equals(arena.builtFrom(def.id()))) build(def, copy);
        else copy.run();
        return run;
    }

    /** An imported map: its world is copied for this run, its monsters placed, then the players come in. */
    private Run startMap(DungeonDefinition def, List<Player> players) {
        Run run = new Run(UUID.randomUUID().toString().substring(0, 8), def, -1, null);
        run.level = partyLevel(players);
        run.partySize = Math.max(1, players.size());
        runs.put(run.id, run);
        for (Player p : players) {
            run.expected.add(p.getUniqueId());
            byPlayer.put(p.getUniqueId(), run);
            plugin.messages().send(p, "run.preparing", "%dungeon%", def.name());
        }
        java.io.File folder = new java.io.File(plugin.getDataFolder(), def.map().folder());
        if (!new java.io.File(folder, "region").isDirectory()) {
            plugin.getLogger().severe("Map folder of dungeon " + def.id() + " not found: " + folder);
            for (Player p : players) plugin.messages().send(p, "start.failed");
            for (UUID u : run.expected) byPlayer.remove(u);
            runs.remove(run.id);
            return null;
        }
        String worldName = instances.freeName(maxRuns);
        if (worldName == null) {
            for (Player p : players) plugin.messages().send(p, "start.no-slot");
            for (UUID u : run.expected) byPlayer.remove(u);
            runs.remove(run.id);
            return null;
        }
        instances.create(folder, worldName, world -> {
            if (world == null) {
                for (UUID u : run.expected) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) plugin.messages().send(p, "start.failed");
                    byPlayer.remove(u);
                }
                runs.remove(run.id);
                return;
            }
            run.world = world;
            double[] s = def.map().start();
            run.origin = new Location(world, s[0], s[1], s[2], (float) s[3], (float) s[4]);
            run.checkpoint = run.origin.clone();
            if (!runs.containsKey(run.id)) {
                instances.destroy(world);
                return;
            }
            for (DungeonDefinition.Spawn sp : def.map().spawns()) {
                if (mobs.template(sp.mob()) == null) {
                    plugin.getLogger().warning("Unknown monster " + sp.mob() + " in dungeon " + def.id());
                    continue;
                }
                run.pending.add(sp);
                if (def.map().objectives().contains(sp.mob())) run.objectivesLeft.add(sp.mob());
            }
            run.objectivesTotal = run.objectivesLeft.size();
            if (run.objectivesTotal == 0) plugin.getLogger().info("Dungeon " + def.id() + " has no objective: won once every monster is dead.");
            enter(run);
        });
        return run;
    }

    /** Monsters of an imported map appear once a player is within {@code spawn-distance} blocks of their place. */
    private void spawnNear(Run run) {
        if (run.pending.isEmpty()) return;
        List<Player> near = fighters(run);
        if (near.isEmpty()) return;
        double d2 = spawnDistance * spawnDistance;
        java.util.Iterator<DungeonDefinition.Spawn> it = run.pending.iterator();
        int budget = 25; // at most this many per half second
        while (it.hasNext() && budget > 0) {
            DungeonDefinition.Spawn sp = it.next();
            Location at = new Location(run.world, sp.x(), sp.y(), sp.z(), sp.yaw(), 0);
            boolean close = false;
            for (Player p : near) if (p.getWorld() == run.world && p.getLocation().distanceSquared(at) <= d2) close = true;
            if (!close) continue;
            it.remove();
            budget--;
            MobTemplate t = mobs.template(sp.mob());
            LivingEntity mob = t == null ? null : mobs.spawn(t, at, run.id);
            if (mob == null) continue;
            run.mobs.add(mob.getUniqueId());
            if (run.def.map().objectives().contains(sp.mob())) mobs.objective(mob, sp.mob());
        }
    }

    /** A dungeon monster loaded back with its chunk: tracked again; false when its run is over. */
    public boolean adopt(LivingEntity le) {
        Run run = runs.get(mobs.runOf(le));
        if (run == null || run.state == Run.State.ENDING || run.world == null && run.imported()) return false;
        if (!mobs.adopt(le)) return false;
        run.mobs.add(le.getUniqueId());
        return true;
    }

    private int freeSlot() {
        Set<Integer> used = new HashSet<>();
        for (Run r : runs.values()) used.add(r.slot);
        for (int i = 0; i < maxRuns; i++) if (!used.contains(i)) return i;
        return -1;
    }

    /** The copy is ready: gates closed, players in. */
    private void begin(Run run) {
        if (!runs.containsKey(run.id)) return;
        DungeonDefinition.Layout l = run.def.layout();
        BlockData gate = run.def.material("gate", Material.IRON_BARS).createBlockData();
        for (int i = 0; i < l.gates().size(); i++) setGate(run, i, gate);
        arena.hold(run.box(), true);
        run.checkpoint = run.roomCenter(0).add(0, 0, -Math.max(0, run.def.rooms().get(0).length() / 2 - 2));
        enter(run);
    }

    /** The dungeon is ready: the expected players swap to their dungeon inventory and come in. */
    /**
     * Runs the console commands of a run event for a player ({@code commands.<event>} of the dungeon file), e.g.
     * {@code typewriter cinematic start <page> %player%}. Placeholders: %player%, %dungeon% (id), %objective%.
     */
    private void hook(Run run, String event, Player p, String objective) {
        for (String line : run.def.commands(event)) {
            String cmd = line.replace("%player%", p.getName()).replace("%dungeon%", run.def.id())
                    .replace("%objective%", objective == null ? "" : objective);
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Command of " + run.def.id() + " (" + event + ") failed: " + cmd + ": " + e);
            }
        }
    }

    private void enter(Run run) {
        run.state = Run.State.RUNNING;
        run.started = System.currentTimeMillis();
        for (UUID uuid : List.copyOf(run.expected)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                byPlayer.remove(uuid);
                continue;
            }
            if (!loadouts.enter(p)) {
                byPlayer.remove(uuid);
                plugin.messages().send(p, "run.save-failed");
                continue;
            }
            run.players.add(uuid);
            heroes.activate(p);
            teleport(p, run.checkpoint);
            p.showTitle(Title.title(Texts.parse(plugin.messages().raw("run.title", "%dungeon%", run.def.name())),
                    Texts.parse(plugin.messages().raw("run.subtitle", "%lives%", String.valueOf(run.livesLeft))),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
            p.playSound(p.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.6f);
            // a second later, once the player stands in the dungeon (cinematics start from there)
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && byPlayer.get(p.getUniqueId()) == run) hook(run, "enter", p, null);
            }, 20L);
        }
        run.expected.clear();
        if (run.players.isEmpty()) cleanup(run);
    }

    // ---------------------------------------------------------------- leaving

    /** The player leaves his run (command, disconnect, end): his things given back, sent where he was. */
    public void leave(Player player) {
        Run run = byPlayer.remove(player.getUniqueId());
        if (run == null) return;
        run.expected.remove(player.getUniqueId());
        boolean inside = run.players.remove(player.getUniqueId());
        run.watching.remove(player.getUniqueId());
        if (run.bossBar != null) player.hideBossBar(run.bossBar);
        if (inside) {
            hook(run, "leave", player, null);
            heroes.deactivate(player);
            Location back = loadouts.leave(player);
            teleport(player, back != null ? back : Bukkit.getWorlds().get(0).getSpawnLocation());
        }
        if (run.players.isEmpty() && run.expected.isEmpty() && run.state != Run.State.PREPARING) cleanup(run);
    }

    /** Everybody out, nothing given (server stop, staff). */
    public void stopAll() {
        for (Run run : List.copyOf(runs.values())) stop(run);
        editor.closeAll();
        for (UUID u : List.copyOf(visitReturn.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) endVisit(p);
        }
        for (World w : List.copyOf(visits.values())) instances.destroy(w);
        visits.clear();
    }

    // ---------------------------------------------------------------- staff visits

    /** Copies of maps opened by staff to look around: no monsters, no run, deleted once empty. */
    private final Map<String, World> visits = new HashMap<>();
    private final Map<UUID, Location> visitReturn = new HashMap<>();

    /**
     * Diagnostics: opens a working copy of a map, places a block, saves it back into a test folder
     * ({@code debug-save/}) and reports how many region files were written. The real map is not touched.
     */
    public void debugSave(DungeonDefinition def, java.util.function.Consumer<String> report) {
        java.io.File folder = new java.io.File(plugin.getDataFolder(), def.map().folder());
        java.io.File target = new java.io.File(plugin.getDataFolder(), "debug-save");
        instances.create(folder, "fde_debug", world -> {
            if (world == null) {
                report.accept("could not open the copy");
                return;
            }
            double[] st = def.map().start();
            world.getBlockAt((int) Math.floor(st[0]), (int) Math.floor(st[1]) + 3, (int) Math.floor(st[2])).setType(org.bukkit.Material.GOLD_BLOCK);
            instances.saveBack(world, target, files -> {
                java.io.File[] out = new java.io.File(target, "region").listFiles((d, n) -> n.endsWith(".mca"));
                report.accept("saved: " + files + " region files in the copy, " + (out == null ? 0 : out.length) + " in " + target);
            });
        });
    }

    /** Diagnostics: opens and closes {@code n} copies of a map one after the other, without players. */
    public void debugCycle(DungeonDefinition def, int n, Runnable done) {
        if (n <= 0) {
            done.run();
            return;
        }
        String name = instances.freeName(maxRuns);
        if (name == null) {
            done.run();
            return;
        }
        instances.create(new java.io.File(plugin.getDataFolder(), def.map().folder()), name, world -> {
            if (world == null) {
                done.run();
                return;
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                instances.destroy(world);
                Bukkit.getScheduler().runTaskLater(plugin, () -> debugCycle(def, n - 1, done), 40L);
            }, 40L);
        });
    }

    /** Copies a loaded world into a new map folder (new dungeon made from a build). */
    public void copyWorld(World w, java.io.File target, java.util.function.Consumer<Boolean> done) {
        instances.copyWorld(w, target, done);
    }

    public Editor editor() {
        return editor;
    }

    /** True when the player is visiting a map copy. */
    public boolean visiting(Player p) {
        return visitReturn.containsKey(p.getUniqueId());
    }

    /**
     * Opens a copy of an imported map (or the template of a generated dungeon) and brings the player to its start, to
     * look around. Nothing is spawned and nothing done there is kept.
     */
    public void visit(Player p, DungeonDefinition def) {
        if (byPlayer.containsKey(p.getUniqueId())) {
            plugin.messages().send(p, "admin.visit-in-run");
            return;
        }
        if (def.map() == null) {
            visitReturn.putIfAbsent(p.getUniqueId(), p.getLocation());
            p.teleport(templateOrigin(def).add(def.layout().centerX() + 0.5, 1, 3.5), PlayerTeleportEvent.TeleportCause.PLUGIN);
            plugin.messages().send(p, "admin.template", "%dungeon%", def.name());
            return;
        }
        java.io.File folder = new java.io.File(plugin.getDataFolder(), def.map().folder());
        String name = new java.io.File(folder, "region").isDirectory() ? instances.freeName(maxRuns) : null;
        if (name == null) {
            plugin.messages().send(p, "start.failed");
            return;
        }
        plugin.messages().send(p, "admin.visit-loading", "%dungeon%", def.name());
        instances.create(folder, name, world -> {
            if (world == null || !p.isOnline()) {
                if (world != null) instances.destroy(world);
                else plugin.messages().send(p, "start.failed");
                return;
            }
            visits.put(world.getName(), world);
            visitReturn.putIfAbsent(p.getUniqueId(), p.getLocation());
            double[] s = def.map().start();
            p.teleport(new Location(world, s[0], s[1], s[2], (float) s[3], (float) s[4]), PlayerTeleportEvent.TeleportCause.PLUGIN);
            plugin.messages().send(p, "admin.visit", "%dungeon%", def.name());
        });
    }

    /** Brings a visiting player back where he was; false when he was not visiting. */
    public boolean endVisit(Player p) {
        Location back = visitReturn.remove(p.getUniqueId());
        if (back == null) return false;
        if (back.getWorld() == null || visits.containsKey(back.getWorld().getName())) back = Bukkit.getWorlds().get(0).getSpawnLocation();
        p.teleport(back, PlayerTeleportEvent.TeleportCause.PLUGIN);
        plugin.messages().send(p, "admin.visit-left");
        return true;
    }

    /** Visit copies nobody is in any more are deleted. */
    private void sweepVisits() {
        visitReturn.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
        for (java.util.Iterator<World> it = visits.values().iterator(); it.hasNext(); ) {
            World w = it.next();
            if (!w.getPlayers().isEmpty()) continue;
            it.remove();
            instances.destroy(w);
        }
    }

    public void stop(Run run) {
        for (UUID uuid : List.copyOf(run.players)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) leave(p);
        }
        for (UUID uuid : List.copyOf(run.expected)) byPlayer.remove(uuid);
        run.expected.clear();
        cleanup(run);
    }

    private void cleanup(Run run) {
        if (runs.remove(run.id) == null) return;
        for (UUID uuid : run.players) byPlayer.remove(uuid);
        mobs.removeRun(run.id);
        if (run.imported()) {
            instances.destroy(run.world);
            return;
        }
        arena.hold(run.box(), false);
        if (plugin.isEnabled()) arena.clear(run.def, run.origin, () -> {
        });
    }

    private void teleport(Player p, Location to) {
        teleporting.add(p.getUniqueId());
        try {
            p.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            teleporting.remove(p.getUniqueId());
        }
    }

    // ---------------------------------------------------------------- the game

    private void tick() {
        ticks++;
        if (!visits.isEmpty()) sweepVisits();
        if (ticks % 4 == 0) editor.tick();
        for (Run run : List.copyOf(runs.values())) {
            if (run.state != Run.State.RUNNING) continue;
            long elapsed = (System.currentTimeMillis() - run.started) / 1000;
            long left = run.def.timeLimit() - elapsed;
            if (run.def.timeLimit() > 0) {
                int minutes = (int) (left / 60);
                if (left <= 0) {
                    fail(run, "run.time-up");
                    continue;
                }
                if ((minutes == 5 || minutes == 1) && left % 60 >= 55 && run.warnedMinutes != minutes) {
                    run.warnedMinutes = minutes;
                    broadcast(run, "run.time-left", "%time%", Format.duration(left));
                }
            }
            for (Player p : online(run)) {
                double floor = run.imported() ? run.world.getMinHeight() + 4 : run.origin.getY() - 8;
                if (p.getLocation().getY() < floor) {
                    teleport(p, run.checkpoint);
                    p.setFallDistance(0);
                }
                if (ticks % 2 == 0 && !run.watching.contains(p.getUniqueId())) heroes.tick(p);
            }
            if (!run.imported()) rooms(run);
            else {
                run.mobs.removeIf(uuid -> {
                    Entity e = Bukkit.getEntity(uuid);
                    return e == null || !e.isValid();
                });
                spawnNear(run);
            }
            if (run.bossBar != null) updateBossBar(run);
        }
    }

    private void rooms(Run run) {
        int index = run.currentRoom();
        if (index >= run.rooms.length) return;
        DungeonDefinition.Room room = run.def.rooms().get(index);
        switch (run.rooms[index]) {
            case WAITING -> {
                if (!room.fight()) {
                    clear(run, index);
                    return;
                }
                BoundingBox box = run.room(index);
                for (Player p : fighters(run)) {
                    if (box.contains(p.getLocation().toVector())) {
                        startRoom(run, index);
                        return;
                    }
                }
            }
            case FIGHTING -> {
                run.mobs.removeIf(uuid -> {
                    Entity e = Bukkit.getEntity(uuid);
                    return e == null || !e.isValid() || e.isDead();
                });
                leash(run, index);
                if (!run.mobs.isEmpty()) return;
                if (room.boss() == null && run.wave + 1 < room.waves().size()) {
                    run.wave++;
                    spawnWave(run, index);
                } else {
                    clear(run, index);
                }
            }
            default -> {
            }
        }
    }

    private void startRoom(Run run, int index) {
        DungeonDefinition.Room room = run.def.rooms().get(index);
        run.rooms[index] = Run.RoomState.FIGHTING;
        run.wave = 0;
        BoundingBox box = run.room(index);
        for (Player p : fighters(run)) {
            if (!box.contains(p.getLocation().toVector())) teleport(p, run.roomEntrance(index));
        }
        String title = room.title() == null ? "" : room.title();
        for (Player p : online(run)) {
            p.showTitle(Title.title(Texts.parse(title), Texts.parse(plugin.messages().raw(room.boss() != null ? "room.boss" : "room.fight")),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(400))));
            p.playSound(p.getLocation(), room.boss() != null ? Sound.ENTITY_WITHER_SPAWN : Sound.EVENT_RAID_HORN, 0.7f, 1f);
        }
        if (room.boss() != null) spawnBoss(run, index, room.boss());
        else spawnWave(run, index);
    }

    private void spawnWave(Run run, int index) {
        DungeonDefinition.Room room = run.def.rooms().get(index);
        Map<String, Integer> wave = room.waves().get(run.wave);
        int players = Math.max(1, fighters(run).size());
        if (room.waves().size() > 1) broadcast(run, "room.wave", "%wave%", String.valueOf(run.wave + 1), "%waves%", String.valueOf(room.waves().size()));
        for (Map.Entry<String, Integer> e : wave.entrySet()) {
            MobTemplate t = mobs.template(e.getKey());
            if (t == null) {
                plugin.getLogger().warning("Unknown monster " + e.getKey() + " in dungeon " + run.def.id());
                continue;
            }
            int count = (int) Math.round(e.getValue() * (1 + extraMobsPerPlayer * (players - 1)));
            for (int i = 0; i < count; i++) {
                Location at = spawnSpot(run, index);
                at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 0.5, 0), 8, 0.3, 0.4, 0.3, 0.01);
                LivingEntity mob = mobs.spawn(t, at, run.id);
                if (mob != null) run.mobs.add(mob.getUniqueId());
            }
        }
    }

    private void spawnBoss(Run run, int index, String id) {
        MobTemplate t = mobs.template(id);
        if (t == null) {
            plugin.getLogger().warning("Unknown boss " + id + " in dungeon " + run.def.id());
            return;
        }
        Location at = run.roomCenter(index);
        at.setYaw(180);
        LivingEntity boss = mobs.spawn(t, at, run.id);
        if (boss == null) return;
        int players = Math.max(1, fighters(run).size());
        AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) {
            max.setBaseValue(t.health() * (1 + bossHealthPerPlayer * (players - 1)));
            boss.setHealth(max.getValue());
        }
        run.mobs.add(boss.getUniqueId());
        run.boss = boss.getUniqueId();
        run.bossBar = BossBar.bossBar(Texts.parse(t.name()), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        for (Player p : online(run)) p.showBossBar(run.bossBar);
    }

    private void updateBossBar(Run run) {
        Entity e = run.boss == null ? null : Bukkit.getEntity(run.boss);
        if (!(e instanceof LivingEntity boss) || !boss.isValid()) return;
        AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
        double ratio = max == null ? 0 : boss.getHealth() / max.getValue();
        run.bossBar.progress((float) Math.max(0, Math.min(1, ratio)));
    }

    /** Monsters pulled away from their room go back to its middle. */
    private void leash(Run run, int index) {
        BoundingBox box = run.room(index).expand(3);
        for (UUID uuid : run.mobs) {
            Entity e = Bukkit.getEntity(uuid);
            if (e != null && !box.contains(e.getLocation().toVector())) e.teleport(run.roomCenter(index));
        }
    }

    /** A free floor spot of the room, away from the players. */
    private Location spawnSpot(Run run, int index) {
        BoundingBox box = run.room(index);
        World w = run.origin.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location fallback = run.roomCenter(index);
        for (int attempt = 0; attempt < 30; attempt++) {
            int x = (int) (box.getMinX() + 1 + r.nextInt(Math.max(1, (int) box.getWidthX() - 2)));
            int z = (int) (box.getMinZ() + 1 + r.nextInt(Math.max(1, (int) box.getWidthZ() - 2)));
            int y = (int) box.getMinY();
            Block feet = w.getBlockAt(x, y, z);
            if (!feet.isPassable() || !feet.getRelative(0, 1, 0).isPassable()) continue;
            Location l = feet.getLocation().add(0.5, 0, 0.5);
            boolean near = false;
            for (Player p : fighters(run)) if (p.getLocation().distanceSquared(l) < 16) near = true;
            if (!near) return l;
        }
        return fallback;
    }

    private void clear(Run run, int index) {
        run.rooms[index] = Run.RoomState.CLEARED;
        run.checkpoint = run.roomCenter(index);
        if (index < run.def.layout().gates().size()) {
            setGate(run, index, Material.AIR.createBlockData());
            Location g = gateCenter(run, index);
            g.getWorld().playSound(g, Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.6f);
            g.getWorld().spawnParticle(Particle.CLOUD, g, 20, 1, 1, 0.2, 0.02);
            if (run.def.rooms().get(index).fight()) broadcast(run, "room.cleared");
        }
        if (index == run.rooms.length - 1) victory(run);
    }

    private void setGate(Run run, int index, BlockData data) {
        BoundingBox g = run.def.layout().gates().get(index);
        World w = run.origin.getWorld();
        for (int x = (int) g.getMinX(); x <= (int) g.getMaxX(); x++) {
            for (int y = (int) g.getMinY(); y <= (int) g.getMaxY(); y++) {
                for (int z = (int) g.getMinZ(); z <= (int) g.getMaxZ(); z++) {
                    w.getBlockAt(run.origin.getBlockX() + x, run.origin.getBlockY() + y, run.origin.getBlockZ() + z).setBlockData(data, true);
                }
            }
        }
    }

    private Location gateCenter(Run run, int index) {
        BoundingBox g = run.def.layout().gates().get(index);
        return run.origin.clone().add(g.getCenterX() + 0.5, g.getCenterY(), g.getCenterZ() + 0.5);
    }

    // ---------------------------------------------------------------- monsters and players

    /** Can this entity be hurt by the abilities of this player? */
    public Abilities.Context context(Player player) {
        return new Abilities.Context() {
            public boolean enemy(Entity entity) {
                Run run = byPlayer.get(player.getUniqueId());
                return run != null && entity instanceof LivingEntity && !(entity instanceof Player)
                        && run.id.equals(mobs.runOf(entity));
            }

            public Collection<Player> allies(Player caster) {
                Run run = byPlayer.get(caster.getUniqueId());
                return run == null ? List.of(caster) : fighters(run);
            }
        };
    }

    /**
     * A dungeon monster is gone for good (vanilla death, end of its death animation, not a phase change): experience
     * for the party, its loot rolled for each player, its objective done.
     */
    public void mobKilled(LivingEntity mob, Player killer, MobTemplate t, String objective, Set<UUID> contributors) {
        Run run = runs.get(mobs.runOf(mob));
        if (run == null || run.state != Run.State.RUNNING) return;
        run.mobs.remove(mob.getUniqueId());
        List<Player> party = fighters(run);
        if (t != null && t.xp() > 0) for (Player p : party) heroes.giveXp(p, t.xp());
        if (t != null && mobLoot) {
            for (Player p : online(run)) {
                if (!contributors.contains(p.getUniqueId())) continue;
                for (MobTemplate.Loot loot : rolls(t.extra().loot(), t.extra().rank(), p)) {
                    ItemStack item = CustomItems.item(loot.item(), p);
                    if (item == null) continue;
                    item.setAmount(Math.max(1, loot.amount()));
                    levelItem(item, run.level, p);
                    p.getInventory().addItem(item).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
                    String shown = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                            ? Texts.ampersand(item.getItemMeta().displayName()) : "<lang:" + item.translationKey() + ">";
                    plugin.messages().send(p, "run.loot", "%items%", shown);
                    p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
                }
            }
        }
        if (objective != null && run.objectivesLeft.remove(objective)) {
            for (Player p : online(run)) hook(run, "objective", p, objective);
            broadcast(run, "objective.done", "%done%", String.valueOf(run.objectivesDone()), "%total%", String.valueOf(run.objectivesTotal));
            if (run.objectivesLeft.isEmpty()) victory(run);
        }
        // a dungeon without objective is won once every monster is dead (those not met yet included)
        if (run.imported() && run.objectivesTotal == 0 && run.state == Run.State.RUNNING && run.pending.isEmpty() && run.mobs.isEmpty()) {
            victory(run);
        }
    }

    /**
     * Loot a player receives, as EliteMobs: each plain entry rolled on its own chance; per group (class loot), one
     * roll on the monster's rank ({@code class-loot-chance}: trash 5 %, miniboss 25 %, boss 100 %), then an item of
     * his class 80 % of the time, of another class otherwise.
     */
    private List<MobTemplate.Loot> rolls(List<MobTemplate.Loot> table, String rank, Player p) {
        List<MobTemplate.Loot> out = new ArrayList<>();
        Map<String, List<MobTemplate.Loot>> groups = new LinkedHashMap<>();
        var heroClass = heroes.classOf(p.getUniqueId());
        String cls = heroClass == null ? "" : heroClass.id();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (MobTemplate.Loot l : table) {
            if (l.group() == null) {
                if (r.nextDouble() < l.chance()) out.add(l);
            } else {
                groups.computeIfAbsent(l.group(), k -> new ArrayList<>()).add(l);
            }
        }
        double chance = classLootChance.getOrDefault(rank, classLootChance.getOrDefault("TRASH", 0.05));
        for (List<MobTemplate.Loot> g : groups.values()) {
            if (r.nextDouble() >= chance) continue;
            List<MobTemplate.Loot> mine = g.stream().filter(l -> l.classes().isEmpty() || l.classes().contains(cls)).toList();
            List<MobTemplate.Loot> other = g.stream().filter(l -> !mine.contains(l)).toList();
            List<MobTemplate.Loot> pool = mine.isEmpty() ? other : other.isEmpty() || r.nextDouble() < relevantChance ? mine : other;
            if (!pool.isEmpty()) out.add(pool.get(r.nextInt(pool.size())));
        }
        return out;
    }

    /** Average class level of the players starting a run, at least 1. */
    private int partyLevel(List<Player> players) {
        if (players.isEmpty()) return 1;
        double sum = 0;
        for (Player p : players) sum += heroes.levelOf(p);
        return Math.max(1, (int) Math.round(sum / players.size()));
    }

    /** A dropped weapon or armour piece takes the level of the run (EliteMobs' scalable items). */
    private void levelItem(ItemStack item, int level, Player p) {
        if (item.getType().getMaxDurability() <= 0 || level <= 0) return;
        item.editMeta(m -> m.getPersistentDataContainer().set(fr.influencraft.fluxdungeons.mob.Combat.ITEM_LEVEL,
                org.bukkit.persistence.PersistentDataType.INTEGER, level));
        fr.influencraft.fluxdungeons.ItemLevels.line(item);
    }

    /** A player died in his run: a shared life is spent, or he watches the others. */
    public void died(Player player) {
        Run run = byPlayer.get(player.getUniqueId());
        if (run == null || run.state != Run.State.RUNNING) return;
        if (run.livesLeft > 0) {
            run.livesLeft--;
            broadcast(run, "run.died", "%player%", player.getName(), "%lives%", String.valueOf(run.livesLeft));
            return;
        }
        run.watching.add(player.getUniqueId());
        broadcast(run, "run.out", "%player%", player.getName());
        if (fighters(run).isEmpty()) Bukkit.getScheduler().runTask(plugin, () -> fail(run, "run.wiped"));
    }

    /** Where a player of a run comes back after dying; watching players come back as spectators. */
    public Location respawn(Player player) {
        Run run = byPlayer.get(player.getUniqueId());
        if (run == null) return null;
        if (run.watching.contains(player.getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) player.setGameMode(GameMode.SPECTATOR);
            });
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Heroes h = heroes;
                if (player.isOnline() && h.hero(player) != null) {
                    AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
                    if (max != null) player.setHealth(max.getValue());
                }
            });
        }
        return run.checkpoint;
    }

    /** The shared arena or the world of a running map. */
    public boolean isDungeonWorld(org.bukkit.World world) {
        if (world == null) return false;
        if (arena.isArena(world) || visits.containsKey(world.getName())) return true;
        for (Run run : runs.values()) if (world.equals(run.world)) return true;
        return false;
    }

    public boolean sameRun(Player a, Player b) {
        Run run = byPlayer.get(a.getUniqueId());
        return run != null && run == byPlayer.get(b.getUniqueId());
    }

    // ---------------------------------------------------------------- end

    private void victory(Run run) {
        if (run.state != Run.State.RUNNING) return;
        run.state = Run.State.ENDING;
        hideBossBar(run);
        long seconds = (System.currentTimeMillis() - run.started) / 1000;
        double perSecond = Values.moneyPerSecond();
        double money = Economy.round(run.def.rewardMinutes() * 60 * (perSecond > 0 ? perSecond : fallbackMoneyPerMinute / 60));
        for (Player p : online(run)) {
            p.setGameMode(GameMode.ADVENTURE);
            p.showTitle(Title.title(Texts.parse(plugin.messages().raw("run.victory")),
                    Texts.parse(plugin.messages().raw("run.victory-sub", "%time%", Format.duration(seconds))),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(800))));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            hook(run, "victory", p, null);
            if (money > 0) Economy.main().give(p, money, Origin.of(plugin, origin).detail(run.def.name()).notifyPlayer());
            heroes.giveXp(p, run.def.rewardXp());
            List<String> found = new ArrayList<>();
            for (DungeonDefinition.Loot loot : run.def.loot()) {
                if (ThreadLocalRandom.current().nextDouble() >= loot.chance()) continue;
                ItemStack item = CustomItems.item(loot.item(), p);
                if (item == null) {
                    plugin.getLogger().warning("Unknown loot item " + loot.item() + " in dungeon " + run.def.id());
                    continue;
                }
                item.setAmount(Math.max(1, loot.amount()));
                p.getInventory().addItem(item).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
                found.add("<lang:" + item.translationKey() + ">");
                if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                    found.set(found.size() - 1, Texts.ampersand(item.getItemMeta().displayName()));
                }
            }
            plugin.messages().send(p, found.isEmpty() ? "run.loot-none" : "run.loot", "%items%", String.join("&7, ", found));
            plugin.messages().send(p, "run.leaving", "%seconds%", String.valueOf(returnDelay));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> stop(run), returnDelay * 20L);
    }

    private void fail(Run run, String reason) {
        if (run.state != Run.State.RUNNING) return;
        run.state = Run.State.ENDING;
        hideBossBar(run);
        for (Player p : online(run)) {
            p.showTitle(Title.title(Texts.parse(plugin.messages().raw("run.defeat")), Texts.parse(plugin.messages().raw(reason)),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.5f, 1.2f);
            hook(run, "defeat", p, null);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> stop(run), 100L);
    }

    private void hideBossBar(Run run) {
        if (run.bossBar == null) return;
        for (Player p : online(run)) p.hideBossBar(run.bossBar);
        run.bossBar = null;
    }

    // ---------------------------------------------------------------- placeholders (Typewriter scoreboard)

    /**
     * {@code %fluxdungeons_<params>%}: {@code in} (true/false), {@code dungeon}, {@code objective} (what to do now),
     * {@code lives}, {@code time} (left), {@code mobs} (left in the room or the map), {@code done}/{@code total}
     * (objectives of a map), {@code boss} (life of the boss in %), {@code class}, {@code level}.
     */
    public String placeholder(Player p, String params) {
        if (p == null) return "";
        Run run = byPlayer.get(p.getUniqueId());
        switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "in" -> {
                return String.valueOf(run != null && run.state != Run.State.PREPARING);
            }
            case "class" -> {
                var c = heroes.classOf(p.getUniqueId());
                return c == null ? "" : c.name();
            }
            case "level" -> {
                var c = heroes.classOf(p.getUniqueId());
                return c == null ? "" : String.valueOf(heroes.level(p.getUniqueId(), c.id()));
            }
            default -> {
            }
        }
        if (run == null) return "";
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "dungeon" -> run.def.name();
            case "lives" -> String.valueOf(run.livesLeft);
            case "time" -> run.secondsLeft() < 0 ? "" : Format.duration(run.secondsLeft());
            case "mobs" -> String.valueOf(run.mobs.size());
            case "done" -> String.valueOf(run.objectivesDone());
            case "total" -> String.valueOf(run.objectivesTotal);
            case "boss" -> {
                Entity e = run.boss == null ? null : Bukkit.getEntity(run.boss);
                if (!(e instanceof LivingEntity b) || !b.isValid()) yield "";
                AttributeInstance max = b.getAttribute(Attribute.MAX_HEALTH);
                yield max == null ? "" : String.valueOf(Math.round(100 * b.getHealth() / max.getValue()));
            }
            case "objective" -> objective(run);
            default -> null;
        };
    }

    private String objective(Run run) {
        if (run.state == Run.State.PREPARING) return plugin.messages().raw("objective.preparing");
        if (run.state == Run.State.ENDING) return plugin.messages().raw("objective.ending");
        if (run.imported()) {
            if (run.objectivesTotal == 0) {
                return plugin.messages().raw("objective.clear", "%mobs%", String.valueOf(run.mobs.size() + run.pending.size()));
            }
            return plugin.messages().raw("objective.map", "%done%", String.valueOf(run.objectivesDone()),
                    "%total%", String.valueOf(run.objectivesTotal));
        }
        int index = run.currentRoom();
        if (index >= run.rooms.length) return plugin.messages().raw("objective.ending");
        DungeonDefinition.Room room = run.def.rooms().get(index);
        String title = room.title() == null ? room.id() : room.title();
        if (run.rooms[index] == Run.RoomState.WAITING) return plugin.messages().raw("objective.go", "%room%", title);
        if (room.boss() != null) {
            MobTemplate t = mobs.template(room.boss());
            return plugin.messages().raw("objective.boss", "%boss%", t == null ? room.boss() : t.name());
        }
        return plugin.messages().raw("objective.room", "%room%", title, "%mobs%", String.valueOf(run.mobs.size()),
                "%wave%", String.valueOf(run.wave + 1), "%waves%", String.valueOf(room.waves().size()));
    }

    // ---------------------------------------------------------------- helpers

    private List<Player> online(Run run) {
        List<Player> out = new ArrayList<>();
        for (UUID uuid : run.players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) out.add(p);
        }
        return out;
    }

    /** Players of the run still fighting (not out of lives). */
    private List<Player> fighters(Run run) {
        List<Player> out = online(run);
        out.removeIf(p -> run.watching.contains(p.getUniqueId()) || p.isDead());
        return out;
    }

    private void broadcast(Run run, String key, String... vars) {
        for (Player p : online(run)) plugin.messages().send(p, key, vars);
    }
}
