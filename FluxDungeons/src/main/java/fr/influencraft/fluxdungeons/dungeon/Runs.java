package fr.influencraft.fluxdungeons.dungeon;

import fr.influencraft.flux.api.music.Music;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.value.Values;
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
 * Every run of every dungeon, played in the rpg universe with the rpg profile: the launch screen ({@link Lobby}),
 * starting (copy of the map), rooms (a room wakes up when a player enters it, its gate opens once its waves are dead),
 * deaths (nothing is lost, he watches the others), victory (money, rpg experience, loot), defeat, leaving (back to
 * the rpg lobby), and the wiping of the copy.
 */
public final class Runs {

    /** Why a run could not start. */
    public enum Refusal { NONE, NO_CLASS, TOO_FEW, TOO_MANY, BUSY_PLAYER, NO_SLOT, NOT_LEADER, EDITING, NO_KEY, UNKNOWN }

    private final FluxPlugin plugin;
    private final Arena arena;
    private final Mobs mobs;
    /** Lairs of digging bosses (dungeons with a burrows section). */
    private final Burrows burrows;
    private final WorldInstances instances;
    private final Lobby lobby;
    private final Editor editor;
    private final Map<String, DungeonDefinition> dungeons = new LinkedHashMap<>();
    private final Map<String, Run> runs = new HashMap<>();
    private final Map<UUID, Run> byPlayer = new HashMap<>();
    /** Players this plugin is teleporting (the guard against leaving the dungeon world lets them through). */
    private final Set<UUID> teleporting = new HashSet<>();
    private int maxRuns = 8;
    private double extraMobsPerPlayer = 0.5, bossHealthPerPlayer = 0.6;
    private double fallbackMoneyPerMinute = 50;
    private double spawnDistance = 40;
    private String origin = "victory";
    private int ticks;

    /** The rpg universe the dungeons are played in (null only when the rpg module failed: nobody may play). */
    private RpgBridge rpg;

    /** Players of a running run (fighting or watching, not those still arriving), 0 when unknown. */
    public int partySize(String runId) {
        Run run = runId == null ? null : runs.get(runId);
        return run == null ? 0 : run.players.size() + run.expected.size();
    }

    /** Difficulty level of a running run (its monsters' life and damage multipliers), null when unknown. */
    public DungeonDefinition.Level levelOf(String runId) {
        Run run = runId == null ? null : runs.get(runId);
        return run == null ? null : run.levelSpec();
    }

    public void rpg(RpgBridge bridge) {
        this.rpg = bridge;
        lobby.classLine(bridge == null ? null : u -> {
            Player p = Bukkit.getPlayer(u);
            return p == null ? null : bridge.classLine(p);
        });
    }

    private boolean noClass(Player p) {
        return rpg == null || !rpg.mayPlay(p);
    }

    private void giveXp(Player p, double xp) {
        if (rpg != null) rpg.giveXp(p, xp);
    }

    /** Keys spent to launch, tokens given by monsters (reward package), null until wired. */
    private fr.influencraft.fluxdungeons.reward.DungeonKeys keys;
    private fr.influencraft.fluxdungeons.reward.DungeonTokens tokens;
    private fr.influencraft.fluxdungeons.reward.Victory victories;

    /** Keys, tokens and the end of a won run (reward package). */
    public void rewards(fr.influencraft.fluxdungeons.reward.DungeonKeys keys, fr.influencraft.fluxdungeons.reward.DungeonTokens tokens,
                        fr.influencraft.fluxdungeons.reward.Victory victories) {
        this.keys = keys;
        this.tokens = tokens;
        this.victories = victories;
        lobby.keyInfo((leader, def) -> keys == null || !keys.needs(leader, def) ? -1 : keys.count(leader, def.job()));
    }

    public Runs(FluxPlugin plugin, Arena arena, Mobs mobs) {
        this.plugin = plugin;
        this.arena = arena;
        this.mobs = mobs;
        this.burrows = new Burrows(plugin, this, mobs);
        this.lobby = new Lobby(plugin);
        lobby.launcher(this::launch);
        this.instances = new WorldInstances(plugin);
        instances.sweep();
        this.editor = new Editor(plugin, instances, this::dungeon, mobs);
        mobs.onSummon((parent, child) -> {
            Run run = runs.get(mobs.runOf(child));
            if (run == null) return;
            run.mobs.add(child.getUniqueId());
            // a monster changing phase (the former body is already gone) stays in its room; summoned helpers do not count
            String room = parent == null ? null : run.mobRoom.get(parent.getUniqueId());
            if (room != null && !parent.isValid()) run.mobRoom.put(child.getUniqueId(), run.mobRoom.remove(parent.getUniqueId()));
        });
        mobs.onDeath(this::mobKilled);
        // every monster of a run follows its difficulty level
        mobs.scaling(id -> {
            Run r = runs.get(id);
            if (r == null) return null;
            DungeonDefinition.Level l = r.levelSpec();
            // 4th value: the level's rank (1 = easiest), abilities with a higher from-level stay off
            return new double[]{l.health(), l.damage(), l.spells() ? 1 : 0, r.level + 1};
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    public void settings(org.bukkit.configuration.ConfigurationSection c) {
        maxRuns = Math.max(1, c.getInt("max-runs", 8));
        extraMobsPerPlayer = c.getDouble("scaling.extra-mobs-per-player", 0.5);
        bossHealthPerPlayer = c.getDouble("scaling.boss-health-per-player", 0.6);
        fallbackMoneyPerMinute = c.getDouble("reward.money-per-minute-without-fluxvalue", 50);
        spawnDistance = Math.max(8, c.getDouble("spawn-distance", 40));
        WorldInstances.viewDistance = Math.max(2, c.getInt("view-distance", 8));
    }

    private long shakeGap = 1000;

    /** Minimum gap between two shakes of a player's screen while in a run. */
    public void shakeGap(long millis) {
        shakeGap = millis;
    }

    public Lobby lobby() {
        return lobby;
    }

    /** Where a player out of a run goes: the rpg lobby (the main world's spawn if the rpg module is off). */
    public Location lobbyLocation() {
        return rpg != null ? rpg.lobby() : Bukkit.getWorlds().get(0).getSpawnLocation();
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
        return check(def, players, false);
    }

    /** {@code free}: launched by the staff or Typewriter, no key needed. */
    public Refusal check(DungeonDefinition def, List<Player> players, boolean free) {
        Refusal refusal = checkPlayers(def, players);
        if (refusal == Refusal.NONE && !free && keys != null && !players.isEmpty()) {
            Player leader = players.get(0);
            if (keys.needs(leader, def) && keys.count(leader, def.job()) <= 0) return Refusal.NO_KEY;
        }
        return refusal;
    }

    private Refusal checkPlayers(DungeonDefinition def, List<Player> players) {
        if (editor.locked(def.id())) return Refusal.EDITING;
        if (players.size() < def.minPlayers()) return Refusal.TOO_FEW;
        if (players.size() > def.maxPlayers()) return Refusal.TOO_MANY;
        for (Player p : players) {
            if (byPlayer.containsKey(p.getUniqueId()) || lobby.waiting(p)) return Refusal.BUSY_PLAYER;
            if (noClass(p)) return Refusal.NO_CLASS;
        }
        if (def.map() != null ? instances.freeName(maxRuns) == null : freeSlot() < 0) return Refusal.NO_SLOT;
        return Refusal.NONE;
    }

    /** The first player who blocks {@link #check} for a refusal about one player (no class, already playing). */
    public Player blocking(List<Player> players) {
        for (Player p : players) {
            if (byPlayer.containsKey(p.getUniqueId()) || lobby.waiting(p) || noClass(p)) return p;
        }
        return null;
    }

    /** Opens the launch screen for these players (checked before): the run starts once they are all ready. */
    /** Opens the launch screen at this difficulty level (-1: the default one). */
    public void prepare(DungeonDefinition def, List<Player> players, int level, boolean free) {
        lobby.open(def, players, level, free);
    }

    /** Everybody is ready on the launch screen: checked again, the leader's key spent, then started. */
    private void launch(DungeonDefinition def, List<Player> players, int level, boolean free) {
        // the file as it is now: a launch screen opened before /fluxdungeons reload still held the former one
        DungeonDefinition current = dungeons.get(def.id());
        if (current != null) def = current;
        Refusal refusal = check(def, players, free);
        if (refusal != Refusal.NONE) {
            for (Player p : players) {
                plugin.messages().send(p, refusal == Refusal.NO_KEY ? "lobby.refused-key" : "lobby.refused",
                        "%job%", keys == null ? def.job() : keys.jobName(def.job()));
            }
            return;
        }
        if (!free && keys != null && !players.isEmpty() && !keys.spend(players.get(0), def)) {
            for (Player p : players) plugin.messages().send(p, "lobby.refused-key", "%job%", keys.jobName(def.job()));
            return;
        }
        if (!free && keys != null && !players.isEmpty() && keys.needs(players.get(0), def)) {
            for (Player p : players) {
                plugin.messages().send(p, "keys.spent", "%player%", players.get(0).getName(), "%job%", keys.jobName(def.job()));
            }
        }
        start(def, players, level);
    }

    /** Starts a run for these players (checked before). The copy is made, then they are taken in. */
    private Run start(DungeonDefinition def, List<Player> players, int level) {
        if (def.map() != null) return startMap(def, players, level);
        int slot = freeSlot();
        if (slot < 0) return null;
        Run run = new Run(UUID.randomUUID().toString().substring(0, 8), def, slot, arena.slotOrigin(slot));
        run.level = level;
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
    private Run startMap(DungeonDefinition def, List<Player> players, int level) {
        Run run = new Run(UUID.randomUUID().toString().substring(0, 8), def, -1, null);
        run.level = level;
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
            for (DungeonDefinition.Door d : def.map().doors()) if (d.type() != DungeonDefinition.DoorType.PROXIMITY) doorLabel(run, d);
            // a digging boss' lair: arena and galleries built in the run's world first (the boss is its objective)
            if (def.burrows() != null) {
                burrows.prepare(run, () -> {
                    if (runs.containsKey(run.id)) enter(run);
                });
                return;
            }
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
            if (!sp.room().isEmpty()) run.mobRoom.put(mob.getUniqueId(), sp.room());
            if (run.def.map().objectives().contains(sp.mob())) mobs.objective(mob, sp.mob());
        }
    }

    // ---------------------------------------------------------------- doors

    /** Kills needed to open a door: its own number, or every monster placed in its room. */
    private static int needed(Run run, DungeonDefinition.Door d) {
        return d.kills() > 0 ? d.kills() : run.def.map().roomSize(d.room());
    }

    /** Twice a second and at each kill: doors whose condition is met open. */
    private void doors(Run run) {
        if (run.world == null || run.state != Run.State.RUNNING) return;
        for (DungeonDefinition.Door d : run.def.map().doors()) {
            if (run.openDoors.contains(d.id())) continue;
            boolean open;
            if (d.type() == DungeonDefinition.DoorType.OFFERING) {
                // opened by its altar (offer); a few motes over the altar show where to bring the plants
                Offering o = d.offering();
                run.world.spawnParticle(Particle.COMPOSTER, o.ax() + 0.5, o.ay() + 1.2, o.az() + 0.5, 3, 0.25, 0.2, 0.25, 0);
                continue;
            } else if (d.type() == DungeonDefinition.DoorType.KILLS) {
                int done = run.roomKills.getOrDefault(d.room(), 0), need = needed(run, d);
                open = done >= need;
                org.bukkit.entity.TextDisplay label = run.doorLabels.get(d.id());
                if (label != null && label.isValid() && !open) {
                    label.text(Texts.parse(plugin.messages().raw("door.kills", "%done%", String.valueOf(done), "%needed%", String.valueOf(need))));
                }
            } else {
                BoundingBox near = d.box().expand(d.radius());
                open = false;
                for (Player p : fighters(run)) if (p.getWorld() == run.world && near.contains(p.getLocation().toVector())) open = true;
            }
            if (open) openDoor(run, d);
        }
    }

    /** The counter above a door that opens with kills, or the list of what an offering door's altar asks. */
    private void doorLabel(Run run, DungeonDefinition.Door d) {
        BoundingBox box = d.box();
        boolean offering = d.type() == DungeonDefinition.DoorType.OFFERING;
        Location at = offering ? new Location(run.world, d.offering().ax() + 0.5, d.offering().ay() + 1.6, d.offering().az() + 0.5)
                : new Location(run.world, box.getCenterX(), box.getMaxY() + 0.5, box.getCenterZ());
        org.bukkit.entity.TextDisplay label = run.world.spawn(at, org.bukkit.entity.TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            t.setShadowed(true);
            t.setViewRange(0.6f);
            t.setLineWidth(260);
            t.text(Texts.parse(offering ? offeringText(run, d) : plugin.messages().raw("door.kills", "%done%", "0", "%needed%", String.valueOf(needed(run, d)))));
        });
        run.doorLabels.put(d.id(), label);
    }

    /**
     * Opens a door: each of its blocks becomes a moving copy that sinks into the ground while the real block is taken
     * away, with the sound of a heavy wooden door. The door never closes again.
     */
    private void openDoor(Run run, DungeonDefinition.Door d) {
        run.openDoors.add(d.id());
        org.bukkit.entity.TextDisplay label = run.doorLabels.remove(d.id());
        if (label != null) label.remove();
        BoundingBox box = d.box();
        int height = (int) box.getHeight();
        List<org.bukkit.entity.BlockDisplay> moving = new ArrayList<>();
        for (int x = (int) box.getMinX(); x < box.getMaxX(); x++) {
            for (int y = (int) box.getMinY(); y < box.getMaxY(); y++) {
                for (int z = (int) box.getMinZ(); z < box.getMaxZ(); z++) {
                    Block b = run.world.getBlockAt(x, y, z);
                    if (b.getType().isAir()) continue;
                    BlockData data = b.getBlockData();
                    moving.add(run.world.spawn(b.getLocation(), org.bukkit.entity.BlockDisplay.class, bd -> {
                        bd.setBlock(data);
                        bd.setPersistent(false);
                    }));
                    b.setType(Material.AIR, false);
                }
            }
        }
        Location center = new Location(run.world, box.getCenterX(), box.getMinY() + 1, box.getCenterZ());
        run.world.playSound(center, Sound.BLOCK_WOODEN_DOOR_OPEN, 1.5f, 0.5f);
        run.world.playSound(center, Sound.BLOCK_PISTON_CONTRACT, 1.2f, 0.5f);
        run.world.playSound(center, Sound.BLOCK_GRINDSTONE_USE, 0.8f, 0.5f);
        run.world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, center, 12, box.getWidthX() / 3, 0.3, box.getWidthZ() / 3, 0.01);
        int ticks = 30;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (org.bukkit.entity.BlockDisplay bd : moving) {
                if (!bd.isValid()) continue;
                bd.setInterpolationDelay(0);
                bd.setInterpolationDuration(ticks);
                bd.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(0, -height - 0.1f, 0),
                        new org.joml.AxisAngle4f(), new org.joml.Vector3f(1, 1, 1), new org.joml.AxisAngle4f()));
            }
        }, 2L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (org.bukkit.entity.BlockDisplay bd : moving) if (bd.isValid()) bd.remove();
            run.world.playSound(center, Sound.BLOCK_WOOD_PLACE, 1f, 0.6f);
        }, ticks + 3L);
        broadcast(run, d.type() == DungeonDefinition.DoorType.KILLS ? "door.opened-kills"
                : d.type() == DungeonDefinition.DoorType.OFFERING ? "door.opened-offering" : "door.opened");
    }

    // ---------------------------------------------------------------- offering doors (herbalist altars)

    /** What the altar of an offering door asks, line by line (hint, count; the next one marked when ordered). */
    private String offeringText(Run run, DungeonDefinition.Door d) {
        Offering o = d.offering();
        int[] p = run.offerings.computeIfAbsent(d.id(), k -> o.fresh());
        StringBuilder sb = new StringBuilder(plugin.messages().raw("offering.title", "%title%", o.title().isEmpty() ? "Autel" : o.title()));
        boolean nextShown = false;
        for (int i = 0; i < o.entries().size(); i++) {
            Offering.Entry e = o.entries().get(i);
            String key;
            if (p[i] >= e.amount()) key = "offering.line-done";
            else if (o.ordered() && !nextShown) {
                key = "offering.line-next";
                nextShown = true;
            } else key = o.ordered() ? "offering.line-later" : "offering.line";
            sb.append('\n').append(plugin.messages().raw(key, "%hint%", e.hint(), "%done%", String.valueOf(p[i]), "%amount%", String.valueOf(e.amount())));
        }
        return sb.toString();
    }

    /**
     * A player right-clicks a block: when it is the altar of a closed offering door of his run, what he holds is put on
     * it (as many as it still wants when he sneaks, else one), the door opens once everything is in. True when the
     * click was for an altar (the click is then cancelled: a composter must not eat the plants).
     */
    public boolean offer(Player p, Block b, ItemStack item) {
        Run run = runOf(p);
        if (run == null || run.world == null || b.getWorld() != run.world || run.state != Run.State.RUNNING) return false;
        for (DungeonDefinition.Door d : run.def.map().doors()) {
            Offering o = d.offering();
            if (d.type() != DungeonDefinition.DoorType.OFFERING || o == null || run.openDoors.contains(d.id())) continue;
            if (b.getX() != o.ax() || b.getY() != o.ay() || b.getZ() != o.az()) continue;
            int[] progress = run.offerings.computeIfAbsent(d.id(), k -> o.fresh());
            if (item == null || item.getType().isAir()) {
                plugin.messages().send(p, "offering.empty-hand");
                return true;
            }
            int[] taken = new int[1];
            Offering.Result res = o.offer(progress, item.getType(), p.isSneaking() ? item.getAmount() : 1, taken);
            Location at = b.getLocation().add(0.5, 1.0, 0.5);
            switch (res) {
                case WRONG, NOT_YET, ENOUGH -> {
                    plugin.messages().send(p, res == Offering.Result.WRONG ? "offering.wrong" : res == Offering.Result.NOT_YET ? "offering.not-yet" : "offering.enough");
                    run.world.playSound(at, Sound.BLOCK_COMPOSTER_EMPTY, 1f, 0.6f);
                    run.world.spawnParticle(Particle.SMOKE, at, 6, 0.2, 0.2, 0.2, 0.01);
                    return true;
                }
                default -> {
                }
            }
            item.setAmount(item.getAmount() - taken[0]);
            run.world.playSound(at, Sound.BLOCK_COMPOSTER_FILL_SUCCESS, 1f, 1f);
            run.world.playSound(at, Sound.BLOCK_BREWING_STAND_BREW, 0.6f, 1.4f);
            run.world.spawnParticle(Particle.HAPPY_VILLAGER, at, 8, 0.3, 0.3, 0.3, 0);
            broadcast(run, "offering.given", "%player%", p.getName(), "%count%", String.valueOf(taken[0]),
                    "%item%", "<lang:" + item.getType().translationKey() + ">");
            org.bukkit.entity.TextDisplay label = run.doorLabels.get(d.id());
            if (label != null && label.isValid()) label.text(Texts.parse(offeringText(run, d)));
            if (res == Offering.Result.COMPLETE) {
                run.world.playSound(at, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.4f);
                run.world.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 30, 0.4, 0.6, 0.4, 0.2);
                if (!o.grant().isEmpty()) {
                    run.grants.add(o.grant());
                    for (Player o2 : online(run)) {
                        o2.addScoreboardTag(o.grant());
                        if (!o.grantMessage().isEmpty()) o2.sendMessage(Texts.parse(o.grantMessage()));
                    }
                }
                openDoor(run, d);
            }
            return true;
        }
        return false;
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
            // he plays with his rpg profile (the universe swap brings it as he arrives), nothing is set aside
            run.players.add(uuid);
            // no vanilla invulnerability in a run: hits are limited per source (DungeonListener), as for monsters
            p.setMaximumNoDamageTicks(0);
            teleport(p, run.checkpoint);
            // the pets of the semi-rp world never come into a dungeon
            fr.influencraft.flux.api.pets.Companions.suppress(uuid, true);
            // many small hits: his screen shakes at most once a second (hit-reaction.screen-shake-ms)
            fr.influencraft.flux.api.client.HurtShake.limit(uuid, shakeGap);
            p.showTitle(Title.title(Texts.parse(plugin.messages().raw("run.title", "%dungeon%", run.def.name())),
                    Texts.parse(plugin.messages().raw("run.subtitle")),
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

    /** The player leaves his run (command, disconnect, end): back to the rpg lobby. */
    public void leave(Player player) {
        Run run = byPlayer.remove(player.getUniqueId());
        if (run == null) return;
        run.expected.remove(player.getUniqueId());
        boolean inside = run.players.remove(player.getUniqueId());
        run.watching.remove(player.getUniqueId());
        for (String tag : run.grants) player.removeScoreboardTag(tag);
        if (run.bossBar != null) player.hideBossBar(run.bossBar);
        stopMusic(run, player);
        fr.influencraft.flux.api.pets.Companions.suppress(player.getUniqueId(), false);
        fr.influencraft.flux.api.client.HurtShake.clear(player.getUniqueId());
        if (inside) {
            hook(run, "leave", player, null);
            player.setMaximumNoDamageTicks(20);
            // back to the rpg lobby, his rpg things never left him
            if (player.isDead()) player.spigot().respawn();
            player.setGameMode(GameMode.ADVENTURE);
            teleport(player, lobbyLocation());
        }
        if (run.players.isEmpty() && run.expected.isEmpty() && run.state != Run.State.PREPARING) cleanup(run);
    }

    /** Everybody out, nothing given (server stop, staff). */
    public void stopAll() {
        lobby.closeAll();
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

    /** A map kept as support (maps/maps.yml): its folder and where to stand in it. */
    public record MapEntry(String name, java.io.File folder, double[] start) {
    }

    /** Maps of {@code maps/maps.yml}, by name: visited or copied to start a new dungeon. */
    public Map<String, MapEntry> maps() {
        Map<String, MapEntry> out = new LinkedHashMap<>();
        java.io.File index = new java.io.File(plugin.getDataFolder(), "maps/maps.yml");
        if (!index.isFile()) return out;
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(index);
        org.bukkit.configuration.ConfigurationSection c = y.getConfigurationSection("maps");
        if (c == null) return out;
        for (String name : c.getKeys(false)) {
            java.io.File folder = new java.io.File(plugin.getDataFolder(), c.getString(name + ".folder", ""));
            if (!new java.io.File(folder, "region").isDirectory()) continue;
            List<Double> st = c.getDoubleList(name + ".start");
            double[] start = {0.5, 64, 0.5, 0, 0};
            for (int i = 0; i < Math.min(5, st.size()); i++) start[i] = st.get(i);
            out.put(name, new MapEntry(name, folder, start));
        }
        return out;
    }

    /** Opens a copy of a map kept as support (no dungeon on it) to look around, as {@link #visit}. */
    public void visitMap(Player p, MapEntry map) {
        if (byPlayer.containsKey(p.getUniqueId())) {
            plugin.messages().send(p, "admin.visit-in-run");
            return;
        }
        String name = instances.freeName(maxRuns);
        if (name == null) {
            plugin.messages().send(p, "start.failed");
            return;
        }
        plugin.messages().send(p, "admin.visit-loading", "%dungeon%", map.name());
        instances.create(map.folder(), name, world -> {
            if (world == null || !p.isOnline()) {
                if (world != null) instances.destroy(world);
                else plugin.messages().send(p, "start.failed");
                return;
            }
            visits.put(world.getName(), world);
            visitReturn.putIfAbsent(p.getUniqueId(), p.getLocation());
            double[] s = map.start();
            p.teleport(new Location(world, s[0], s[1], s[2], (float) s[3], (float) s[4]), PlayerTeleportEvent.TeleportCause.PLUGIN);
            plugin.messages().send(p, "admin.visit", "%dungeon%", map.name());
        });
    }

    /** Copies a map folder into a new one (new dungeon made from a map kept as support). */
    public void copyMap(java.io.File source, java.io.File target, java.util.function.Consumer<Boolean> done) {
        instances.copyFolder(source, target, done);
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
        burrows.forget(run);
        if (run.imported()) {
            instances.destroy(run.world);
            return;
        }
        arena.hold(run.box(), false);
        if (plugin.isEnabled()) arena.clear(run.def, run.origin, () -> {
        });
    }

    void teleport(Player p, Location to) {
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
            }
            if (!run.imported()) rooms(run);
            else {
                run.mobs.removeIf(uuid -> {
                    Entity e = Bukkit.getEntity(uuid);
                    return e == null || !e.isValid();
                });
                spawnNear(run);
                doors(run);
                if (run.def.burrows() != null) burrows.tick(run);
                ambience(run);
            }
            if (run.bossBar != null) updateBossBar(run);
            bossMusic(run);
        }
    }

    // ---------------------------------------------------------------- ambience

    /** Sounds and particles placed in the map, near the players only (every 10 ticks, see Ambience). */
    private void ambience(Run run) {
        List<Ambience.Emitter> emitters = run.def.map().ambience();
        if (emitters.isEmpty() || run.world == null) return;
        if (run.ambience == null) run.ambience = new long[emitters.size()];
        Ambience.tick(run.world, emitters, run.ambience, online(run), Bukkit.getCurrentTick(), 10);
    }

    // ---------------------------------------------------------------- music

    /** The boss music of the dungeon starts for everyone once a player comes near the boss (or an objective). */
    private void bossMusic(Run run) {
        String song = run.def.bossMusic();
        if (run.music != null || song.isBlank() || run.state != Run.State.RUNNING) return;
        double r2 = run.def.bossMusicRadius() * run.def.bossMusicRadius();
        List<Player> players = online(run);
        for (UUID id : run.mobs) {
            if (!(Bukkit.getEntity(id) instanceof LivingEntity boss) || !boss.isValid()) continue;
            if (!id.equals(run.boss) && !mobs.isObjective(boss)) continue;
            for (Player p : players) {
                if (p.getWorld() != boss.getWorld() || p.getLocation().distanceSquared(boss.getLocation()) > r2) continue;
                run.music = song;
                for (Player q : players) Music.loop(q, song);
                return;
            }
        }
    }

    /** Stops the boss music this player hears (only the run's own). */
    private static void stopMusic(Run run, Player p) {
        if (run.music != null && run.music.equalsIgnoreCase(Music.playing(p))) Music.stop(p);
    }

    /** End of the run: the boss music stops, the victory or defeat jingle plays (else the plain sound). */
    private void endMusic(Run run, Player p, boolean won) {
        stopMusic(run, p);
        String jingle = plugin.getConfig().getString(won ? "music.victory" : "music.defeat", won ? "victoire_boss" : "defaite");
        if (Music.jingle(p, jingle)) return;
        if (won) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        else p.playSound(p.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.5f, 1.2f);
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
        run.mobs.add(boss.getUniqueId());
        bossOf(run, boss);
    }

    /**
     * Makes a monster the boss of the run: its life follows the difficulty level and the number of players, and its
     * bar is shown to everyone.
     */
    void bossOf(Run run, LivingEntity boss) {
        MobTemplate t = mobs.templateOfEntity(boss);
        if (t == null) return;
        int players = Math.max(1, fighters(run).size());
        AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) {
            // its life follows the difficulty level too (this line used to drop it), beyond 1024 if need be
            double level = run.levelSpec().health();
            mobs.maxHealth(boss, t.health() * level * (1 + bossHealthPerPlayer * (players - 1)));
            boss.setHealth(max.getValue());
        }
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
            if (e != null && !box.contains(e.getLocation().toVector())) Mobs.move(e, run.roomCenter(index));
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

    /**
     * A dungeon monster is gone for good (vanilla death, end of its death animation, not a phase change): experience
     * for the party, its loot rolled for each player, its objective done.
     */
    public void mobKilled(LivingEntity mob, Player killer, MobTemplate t, String objective, Set<UUID> contributors) {
        Run run = runs.get(mobs.runOf(mob));
        if (run == null || run.state != Run.State.RUNNING) return;
        run.mobs.remove(mob.getUniqueId());
        String room = run.mobRoom.remove(mob.getUniqueId());
        if (room != null) {
            run.roomKills.merge(room, 1, Integer::sum);
            doors(run);
        }
        List<Player> party = fighters(run);
        double xp = rpg == null || t == null ? 0 : rpg.killXp(mob, t);
        if (xp > 0) for (Player p : party) giveXp(p, xp);
        // dungeon tokens spring out of it, for every fighter of the party
        if (tokens != null && t != null) {
            tokens.award(mob.getLocation(), party, tokens.amount(t.boss() ? "BOSS" : t.extra() == null ? "TRASH" : t.extra().rank(), run.levelSpec().reward()), run.tokens);
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

    /** A player died in his run: nothing is lost (his rpg profile keeps everything), he watches the others. */
    public void died(Player player) {
        Run run = byPlayer.get(player.getUniqueId());
        if (run == null || run.state != Run.State.RUNNING) return;
        run.watching.add(player.getUniqueId());
        broadcast(run, "run.out", "%player%", player.getName());
        if (fighters(run).isEmpty()) Bukkit.getScheduler().runTask(plugin, () -> fail(run, "run.wiped"));
    }

    /**
     * Gives items straight into the inventory; what does not fit is kept in {@code /recompenses} (never dropped:
     * nothing lies on the ground in a dungeon world, it would be lost).
     */
    private void deliver(Player p, List<ItemStack> items) {
        if (!fr.influencraft.flux.api.reward.Rewards.deliver(p, "dungeons", plugin.messages().raw("run.loot-overflow-label"), items).isEmpty()) {
            plugin.messages().send(p, "run.loot-overflow");
        }
    }

    /** Where a player of a run comes back after dying; watching players come back as spectators. */
    public Location respawn(Player player) {
        Run run = byPlayer.get(player.getUniqueId());
        if (run == null) return null;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && byPlayer.get(player.getUniqueId()) == run) player.setGameMode(GameMode.SPECTATOR);
        });
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

    /**
     * The boss is dead: money, rpg experience, loot, boosters of the dungeon's job, then everybody is taken back to
     * the rpg lobby at once (next tick: the monster's death is still being handled), where {@link
     * fr.influencraft.fluxdungeons.reward.Victory} congratulates them (title, fireworks, summary, boss pet).
     */
    /** Money of a victory of this dungeon at this difficulty level (index). */
    public double victoryMoney(DungeonDefinition def, int level) {
        double perSecond = Values.moneyPerSecond();
        List<DungeonDefinition.Level> levels = def.levels();
        double reward = levels.get(Math.max(0, Math.min(level, levels.size() - 1))).reward();
        return Economy.round(def.rewardMinutes() * 60 * (perSecond > 0 ? perSecond : fallbackMoneyPerMinute / 60) * reward);
    }

    private void victory(Run run) {
        if (run.state != Run.State.RUNNING) return;
        run.state = Run.State.ENDING;
        hideBossBar(run);
        long seconds = (System.currentTimeMillis() - run.started) / 1000;
        double money = victoryMoney(run.def, run.level);
        List<Player> winners = online(run);
        Map<UUID, List<String>> loot = new HashMap<>();
        for (Player p : winners) {
            endMusic(run, p, true);
            hook(run, "victory", p, null);
            if (money > 0) Economy.main().give(p, money, Origin.of(plugin, origin).detail(run.def.name()).notifyPlayer());
            if (rpg != null) giveXp(p, rpg.victoryXp(run.world == null ? p.getWorld() : run.world) * run.levelSpec().reward());
            List<String> found = new ArrayList<>();
            for (DungeonDefinition.Loot drop : run.def.loot()) {
                if (ThreadLocalRandom.current().nextDouble() >= drop.chance()) continue;
                ItemStack item = CustomItems.item(drop.item(), p);
                if (item == null) {
                    plugin.getLogger().warning("Unknown loot item " + drop.item() + " in dungeon " + run.def.id());
                    continue;
                }
                item.setAmount(Math.max(1, drop.amount()));
                deliver(p, List.of(item));
                found.add("<lang:" + item.translationKey() + ">");
                if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                    found.set(found.size() - 1, Texts.ampersand(item.getItemMeta().displayName()));
                }
            }
            loot.put(p.getUniqueId(), found);
        }
        Map<UUID, Integer> earned = new HashMap<>(run.tokens);
        Bukkit.getScheduler().runTask(plugin, () -> {
            stop(run);
            if (victories != null) victories.celebrate(run.def, run.level, seconds, winners, loot, earned);
        });
    }

    private void fail(Run run, String reason) {
        if (run.state != Run.State.RUNNING) return;
        run.state = Run.State.ENDING;
        hideBossBar(run);
        for (Player p : online(run)) {
            p.showTitle(Title.title(Texts.parse(plugin.messages().raw("run.defeat")), Texts.parse(plugin.messages().raw(reason)),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
            endMusic(run, p, false);
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
     * {@code time} (left), {@code mobs} (left in the room or the map), {@code done}/{@code total}
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
                String line = rpg == null ? null : rpg.classLine(p);
                return line == null ? "" : line.substring(0, line.lastIndexOf(' '));
            }
            case "level" -> {
                String line = rpg == null ? null : rpg.classLine(p);
                return line == null ? "" : line.substring(line.lastIndexOf(' ') + 1);
            }
            default -> {
            }
        }
        if (run == null) return "";
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "dungeon" -> run.def.name();
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

    List<Player> online(Run run) {
        List<Player> out = new ArrayList<>();
        for (UUID uuid : run.players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) out.add(p);
        }
        return out;
    }

    /** Players of the run still fighting (not out of lives). */
    List<Player> fighters(Run run) {
        List<Player> out = online(run);
        out.removeIf(p -> run.watching.contains(p.getUniqueId()) || p.isDead());
        return out;
    }

    private void broadcast(Run run, String key, String... vars) {
        for (Player p : online(run)) plugin.messages().send(p, key, vars);
    }
}
