package fr.influencraft.fluxdungeons.dungeon;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The world dungeons are played in: one empty world, templates on a row at negative z, and runs side by side at z 0,
 * {@code spacing} blocks apart. A run is a copy of its dungeon's template pasted in a free slot, then wiped once
 * over. Block work is spread over ticks ({@code blocks-per-tick}) so a copy never freezes the server.
 */
public final class Arena {

    /** Empty world: no terrain, no structure, no mob. */
    private static final class Void extends ChunkGenerator {
    }

    public static final int BASE_Y = 64;

    private final Plugin plugin;
    private final String worldName;
    private final int spacing;
    private final int blocksPerTick;
    private final File templatesFile;
    private final YamlConfiguration templates;
    private final Deque<Job> jobs = new ArrayDeque<>();
    private World world;

    public Arena(Plugin plugin, String worldName, int spacing, int blocksPerTick) {
        this.plugin = plugin;
        this.worldName = worldName;
        this.spacing = Math.max(128, spacing);
        this.blocksPerTick = Math.max(1000, blocksPerTick);
        this.templatesFile = new File(plugin.getDataFolder(), "templates.yml");
        this.templates = YamlConfiguration.loadConfiguration(templatesFile);
        Bukkit.getScheduler().runTaskTimer(plugin, this::work, 1L, 1L);
    }

    public World world() {
        if (world != null) return world;
        world = Bukkit.getWorld(worldName);
        if (world == null) {
            world = new WorldCreator(worldName).generator(new Void()).generateStructures(false).createWorld();
        }
        if (world != null) {
            world.setGameRule(GameRules.SPAWN_MOBS, false);
            world.setGameRule(GameRules.SPAWN_MONSTERS, false);
            world.setGameRule(GameRules.SPAWN_PATROLS, false);
            world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
            world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
            world.setGameRule(GameRules.ADVANCE_TIME, false);
            world.setGameRule(GameRules.ADVANCE_WEATHER, false);
            world.setGameRule(GameRules.KEEP_INVENTORY, true);
            world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
            world.setGameRule(GameRules.MOB_GRIEFING, false);
            world.setGameRule(GameRules.MOB_DROPS, false);
            world.setGameRule(GameRules.BLOCK_DROPS, false);
            world.setTime(18000);
            world.setStorm(false);
            world.setDifficulty(Difficulty.HARD);
        }
        return world;
    }

    public boolean isArena(World w) {
        return w != null && w.getName().equals(worldName);
    }

    public String worldName() {
        return worldName;
    }

    // ---------------------------------------------------------------- places

    public Location slotOrigin(int slot) {
        return new Location(world(), (double) slot * spacing, BASE_Y, 0);
    }

    /** Where a dungeon's template sits; each dungeon keeps its row place in templates.yml. */
    public Location templateOrigin(String dungeon) {
        int index = templates.getInt("slots." + dungeon, -1);
        if (index < 0) {
            index = templates.getConfigurationSection("slots") == null ? 0 : templates.getConfigurationSection("slots").getKeys(false).size();
            templates.set("slots." + dungeon, index);
            saveTemplates();
        }
        return new Location(world(), (double) index * spacing, BASE_Y, -2.0 * spacing);
    }

    /** Fingerprint of the definition the template was last built from (null: never built). */
    public String builtFrom(String dungeon) {
        return templates.getString("built." + dungeon);
    }

    private void builtFrom(String dungeon, String fingerprint) {
        templates.set("built." + dungeon, fingerprint);
        saveTemplates();
    }

    private void saveTemplates() {
        try {
            templates.save(templatesFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save templates.yml: " + e.getMessage());
        }
    }

    /** Box of a dungeon placed at an origin, in world coordinates. */
    public static BoundingBox box(Location origin, DungeonDefinition.Layout layout) {
        return new BoundingBox(origin.getBlockX(), origin.getBlockY(), origin.getBlockZ(),
                origin.getBlockX() + layout.sizeX(), origin.getBlockY() + layout.sizeY(), origin.getBlockZ() + layout.sizeZ());
    }

    // ---------------------------------------------------------------- block work

    /** Draws the template of a dungeon from its definition. */
    public void build(DungeonDefinition def, String fingerprint, Runnable done) {
        Location origin = templateOrigin(def.id());
        Builder builder = new Builder(def);
        DungeonDefinition.Layout l = def.layout();
        // one block of margin around the template, cleared, so a smaller rebuild leaves nothing behind
        jobs.add(new Job(origin.clone().add(-1, 0, -1), l.sizeX() + 2, l.sizeY() + 1, l.sizeZ() + 2,
                (x, y, z) -> x == 0 || z == 0 || x > l.sizeX() || z > l.sizeZ()
                        ? Material.AIR.createBlockData() : builder.at(x - 1, y, z - 1),
                () -> {
                    builtFrom(def.id(), fingerprint);
                    done.run();
                }));
    }

    /** Copies a dungeon's template into a run slot. */
    public void copy(DungeonDefinition def, Location to, Runnable done) {
        Location from = templateOrigin(def.id());
        DungeonDefinition.Layout l = def.layout();
        World w = world();
        int fx = from.getBlockX(), fy = from.getBlockY(), fz = from.getBlockZ();
        jobs.add(new Job(to, l.sizeX() + 1, l.sizeY() + 1, l.sizeZ() + 1,
                (x, y, z) -> w.getBlockAt(fx + x, fy + y, fz + z).getBlockData(), done));
    }

    /** Wipes a run slot and removes what is left there (monsters, arrows, items). */
    public void clear(DungeonDefinition def, Location origin, Runnable done) {
        DungeonDefinition.Layout l = def.layout();
        BoundingBox box = box(origin, l).expand(2);
        for (Entity e : world().getNearbyEntities(box)) if (!(e instanceof Player)) e.remove();
        BlockData air = Material.AIR.createBlockData();
        jobs.add(new Job(origin, l.sizeX() + 1, l.sizeY() + 1, l.sizeZ() + 1, (x, y, z) -> air, done));
    }

    /** Keeps the chunks of a box loaded and ticking (monsters keep moving with nobody near them). */
    public void hold(BoundingBox box, boolean held) {
        World w = world();
        for (int cx = (int) Math.floor(box.getMinX()) >> 4; cx <= (int) Math.floor(box.getMaxX()) >> 4; cx++) {
            for (int cz = (int) Math.floor(box.getMinZ()) >> 4; cz <= (int) Math.floor(box.getMaxZ()) >> 4; cz++) {
                Chunk c = w.getChunkAt(cx, cz);
                if (held) c.addPluginChunkTicket(plugin);
                else c.removePluginChunkTicket(plugin);
            }
        }
    }

    public boolean busy() {
        return !jobs.isEmpty();
    }

    @FunctionalInterface
    interface Source {
        BlockData at(int x, int y, int z);
    }

    /** A box filled block by block, a few thousand per tick. */
    private final class Job {
        final World w = world();
        final int ox, oy, oz, sx, sy, sz;
        final Source source;
        final Runnable done;
        int x, y, z;

        Job(Location origin, int sx, int sy, int sz, Source source, Runnable done) {
            this.ox = origin.getBlockX();
            this.oy = origin.getBlockY();
            this.oz = origin.getBlockZ();
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.source = source;
            this.done = done;
        }

        /** Places up to {@code budget} blocks; true when the box is complete. */
        boolean step(int budget) {
            while (budget-- > 0) {
                if (x >= sx) return true;
                BlockData data = source.at(x, y, z);
                var block = w.getBlockAt(ox + x, oy + y, oz + z);
                if (!block.getBlockData().equals(data)) block.setBlockData(data, false);
                if (++y >= sy) {
                    y = 0;
                    if (++z >= sz) {
                        z = 0;
                        x++;
                    }
                }
            }
            return x >= sx;
        }
    }

    private void work() {
        Job job = jobs.peek();
        if (job == null) return;
        boolean finished;
        try {
            finished = job.step(blocksPerTick);
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Block work failed: " + e.getMessage());
            finished = true;
        }
        if (finished) {
            jobs.poll();
            try {
                job.done.run();
            } catch (RuntimeException e) {
                plugin.getLogger().severe("After block work: " + e);
            }
        }
    }
}
