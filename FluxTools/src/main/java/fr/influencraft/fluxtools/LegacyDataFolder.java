package fr.influencraft.fluxtools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * First start under the Flux name: copies the data folder of a former plugin name
 * (plugins/&lt;old name&gt;/) into the new one. The old folder is left untouched, so going back
 * to the old jar is always possible.
 */
final class LegacyDataFolder {

    private LegacyDataFolder() {
    }

    /** Call first thing in onLoad(). Former names are tried in order, the first one found wins. */
    static void migrate(JavaPlugin plugin, String... formerNames) {
        File folder = plugin.getDataFolder();
        if (folder.exists()) {
            return;
        }
        for (String name : formerNames) {
            File legacy = new File(folder.getParentFile(), name);
            if (!legacy.isDirectory()) {
                continue;
            }
            Path from = legacy.toPath();
            Path to = folder.toPath();
            try (Stream<Path> paths = Files.walk(from)) {
                for (Path path : (Iterable<Path>) paths::iterator) {
                    Files.copy(path, to.resolve(from.relativize(path).toString()));
                }
                plugin.getLogger().info("Data copied from plugins/" + name + " (former plugin name).");
            } catch (IOException e) {
                plugin.getLogger().warning("Could not copy plugins/" + name + ": " + e);
            }
            return;
        }
    }
}
