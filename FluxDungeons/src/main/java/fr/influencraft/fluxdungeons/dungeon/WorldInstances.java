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

    WorldInstances(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Copies the map into a new world and loads it; {@code done} receives it (null on failure) on the server thread. */
    void create(File source, String name, Consumer<World> done) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            File target = dimension(name);
            try {
                deleteTree(target.toPath());
                copyTree(source.toPath(), target.toPath());
            } catch (IOException e) {
                plugin.getLogger().severe("Could not copy map " + source + ": " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> done.accept(null));
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
        w.setGameRule(GameRules.MOB_DROPS, false);
        w.setGameRule(GameRules.BLOCK_DROPS, false);
        w.setDifficulty(Difficulty.HARD);
        w.setAutoSave(false);
    }

    /** Unloads the world (anyone still inside is sent to the main spawn) and deletes its folder. */
    void destroy(World w) {
        if (w == null) return;
        for (Player p : w.getPlayers()) p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        File folder = dimension(w.getName());
        if (!Bukkit.unloadWorld(w, false)) {
            plugin.getLogger().warning("Could not unload " + w.getName() + ", its folder stays until the next start.");
            return;
        }
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    deleteTree(folder.toPath());
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

    /** Folders of run worlds left by a crash: deleted at start. */
    void sweep() {
        File[] left = dimension("x").getParentFile().listFiles((d, n) -> n.startsWith("fd_"));
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
