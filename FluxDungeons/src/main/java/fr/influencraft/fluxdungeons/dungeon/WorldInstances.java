package fr.influencraft.fluxdungeons.dungeon;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Worlds of imported maps: for each run, the map's world folder is copied (off the server thread) into a new world
 * {@code fd_<run>}, loaded, played, then unloaded and deleted. Chunks never saved in the map stay empty.
 */
final class WorldInstances {

    /** Empty generator for chunks the map does not have. */
    private static final class Void extends ChunkGenerator {
    }

    /** Paper 26 keeps every extra world as a dimension of the main one; a dimension has no level.dat. */
    private static final Set<String> SKIPPED = Set.of("uid.dat", "session.lock", "level.dat", "level.dat_old");

    /** Folder of a world's dimension: {@code <main world>/dimensions/minecraft/<name>}. */
    static File dimension(String name) {
        File main = Bukkit.getWorlds().get(0).getWorldFolder().getAbsoluteFile();
        String path = main.getPath();
        int cut = path.indexOf(File.separator + "dimensions" + File.separator);
        if (cut >= 0) main = new File(path.substring(0, cut));
        File dims = new File(main, "dimensions/minecraft");
        return new File(dims, name.toLowerCase(java.util.Locale.ROOT));
    }

    private final Plugin plugin;
    /**
     * Copies and deletions run one at a time, in order (as EliteMobs' world operation queue): several big copies at
     * once would fight for the disk and slow the whole server.
     */
    private final java.util.concurrent.ExecutorService disk = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FluxDungeons-worlds");
        t.setDaemon(true);
        return t;
    });
    /** World names in use: loaded, or unloaded and still being deleted. */
    private final java.util.Set<String> busy = java.util.concurrent.ConcurrentHashMap.newKeySet();

    WorldInstances(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * A free run world name among {@code fd_0} to {@code fd_<max - 1>}, null when all are taken. Names are fixed so
     * other plugins can be told about them once (LevelledMobs leaves the monsters of these worlds alone).
     */
    String freeName(int max) {
        for (int i = 0; i < max; i++) {
            String name = "fd_" + i;
            if (!busy.contains(name) && Bukkit.getWorld(name) == null) return name;
        }
        return null;
    }

    /** Copies the map into a new world and loads it; {@code done} receives it (null on failure) on the server thread. */
    void create(File source, String name, Consumer<World> done) {
        busy.add(name);
        disk.execute(() -> {
            File target = dimension(name);
            try {
                deleteTree(target.toPath());
                copyTree(source.toPath(), target.toPath());
            } catch (IOException e) {
                plugin.getLogger().severe("Could not copy map " + source + ": " + e.getMessage());
                busy.remove(name);
                if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> done.accept(null));
                return;
            }
            if (!plugin.isEnabled()) {
                busy.remove(name);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                World w;
                try {
                    w = new WorldCreator(name).generator(new Void()).generateStructures(false).keepSpawnLoaded(net.kyori.adventure.util.TriState.FALSE).createWorld();
                } catch (RuntimeException e) {
                    plugin.getLogger().severe("Could not load the map world " + name + ": " + e);
                    w = null;
                }
                if (w != null) rules(w);
                else busy.remove(name);
                done.accept(w);
            });
        });
    }

    static void rules(World w) {
        w.setGameRule(GameRules.SPAWN_MOBS, false);
        w.setGameRule(GameRules.SPAWN_MONSTERS, false);
        w.setGameRule(GameRules.SPAWN_PATROLS, false);
        w.setGameRule(GameRules.SPAWN_PHANTOMS, false);
        w.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        w.setGameRule(GameRules.ADVANCE_WEATHER, false);
        w.setGameRule(GameRules.KEEP_INVENTORY, true);
        w.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        w.setGameRule(GameRules.MOB_GRIEFING, false);
        // a map is a set: lava never sets the wood near it on fire, nothing grows, decays or spreads
        w.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        w.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
        w.setGameRule(GameRules.MOB_DROPS, false);
        w.setGameRule(GameRules.BLOCK_DROPS, false);
        // monster hits are normalized by FluxDungeons; the difficulty only changes vanilla extras (hunger, fire)
        w.setDifficulty(Difficulty.NORMAL);
        w.setAutoSave(false);
        // a dungeon is a small map: the server's view distance (32 on Labs) would load all of it for each player
        w.setViewDistance(Math.max(2, Math.min(viewDistance, Bukkit.getViewDistance())));
        w.setSimulationDistance(Math.max(2, Math.min(viewDistance, Bukkit.getSimulationDistance())));
    }

    /** View and simulation distance of run worlds, in chunks. */
    static int viewDistance = 8;

    /** Unloads the world (anyone still inside is sent to the main spawn) and deletes its folder. */
    void destroy(World w) {
        if (w == null) return;
        for (Player p : w.getPlayers()) p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        File folder = dimension(w.getName());
        if (!Bukkit.unloadWorld(w, false)) {
            plugin.getLogger().warning("Could not unload " + w.getName() + ", its folder stays until the next start.");
            return;
        }
        String name = w.getName();
        if (plugin.isEnabled()) {
            disk.execute(() -> {
                try {
                    deleteTree(folder.toPath());
                    busy.remove(name);
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not delete " + folder + ": " + e.getMessage());
                }
            });
        } else {
            try {
                deleteTree(folder.toPath());
            } catch (IOException ignored) {
                // removed at the next start (sweep)
            }
        }
    }

    /**
     * Saves an edit world and writes its blocks and entities back into the map folder (region, entities, poi), then
     * deletes the working copy; {@code done} runs on the server thread.
     */
    void saveBack(World w, File target, Consumer<Integer> done) {
        String name = w.getName();
        File folder = dimension(name);
        w.save();
        if (!Bukkit.unloadWorld(w, true)) {
            plugin.getLogger().warning("Could not unload " + name + ": the map was not written back.");
            done.accept(0);
            return;
        }
        disk.execute(() -> {
            int files = 0;
            try {
                File[] regions = new File(folder, "region").listFiles((d, n) -> n.endsWith(".mca"));
                files = regions == null ? 0 : regions.length;
                for (String sub : new String[]{"region", "entities", "poi"}) {
                    Path from = folder.toPath().resolve(sub);
                    if (Files.isDirectory(from)) copyTree(from, target.toPath().resolve(sub));
                }
                deleteTree(folder.toPath());
            } catch (IOException e) {
                plugin.getLogger().severe("Could not write the map back into " + target + ": " + e.getMessage());
            }
            busy.remove(name);
            int written = files;
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> done.accept(written));
        });
    }

    /**
     * Copies a loaded world (saved first) into a new map folder: its blocks, entities and points of interest.
     * {@code done} receives true on success, on the server thread.
     */
    void copyWorld(World w, File target, Consumer<Boolean> done) {
        w.save();
        File source = new File(w.getWorldFolder(), "region").isDirectory() ? w.getWorldFolder() : dimension(w.getName());
        disk.execute(() -> {
            boolean ok = true;
            try {
                Files.createDirectories(target.toPath().resolve("region"));
                for (String sub : new String[]{"region", "entities", "poi"}) {
                    Path from = source.toPath().resolve(sub);
                    if (Files.isDirectory(from)) copyTree(from, target.toPath().resolve(sub));
                }
            } catch (IOException e) {
                plugin.getLogger().severe("Could not copy " + w.getName() + " into " + target + ": " + e.getMessage());
                ok = false;
            }
            boolean result = ok;
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> done.accept(result));
        });
    }

    /** Copies a map folder (not loaded) into a new one, off the server thread; {@code done} on the server thread. */
    void copyFolder(File source, File target, Consumer<Boolean> done) {
        disk.execute(() -> {
            boolean ok = true;
            try {
                copyTree(source.toPath(), target.toPath());
            } catch (IOException e) {
                plugin.getLogger().severe("Could not copy " + source + " into " + target + ": " + e.getMessage());
                ok = false;
            }
            boolean result = ok;
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> done.accept(result));
        });
    }

    /** Folders of run worlds left by a crash: deleted at start. */
    void sweep() {
        File[] left = dimension("x").getParentFile().listFiles((d, n) -> n.startsWith("fd_") || n.startsWith("fde_"));
        if (left == null) return;
        for (File f : left) {
            if (Bukkit.getWorld(f.getName()) != null) continue;
            try {
                deleteTree(f.toPath());
            } catch (IOException e) {
                plugin.getLogger().warning("Could not delete the old run world " + f + ": " + e.getMessage());
            }
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!SKIPPED.contains(file.getFileName().toString())) {
                    Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path x : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }
}
