package fr.influencraft.flux.adapters.itemsadder;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The texture an ItemsAdder item is drawn with, read from ItemsAdder's contents ({@code <folder>/config.yml} and
 * {@code configs/**.yml}: {@code info.namespace}, {@code items.<id>.resource.textures[0]}), as a resource id of the
 * pack ({@code influitems:bitblock} for {@code assets/influitems/textures/bitblock.png}). Lets Flux show the very
 * picture of an item elsewhere (the HUD shows the currencies' icons), never a copy of it.
 */
public final class ItemsAdderTextures {

    private ItemsAdderTextures() {
    }

    /** {@code namespace:path} of the item's first texture, or null (unknown item, model without texture list). */
    public static String texture(File contents, String namespace, String id) {
        if (contents == null || !contents.isDirectory()) return null;
        try (Stream<Path> files = Files.walk(contents.toPath(), 4)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".yml"))
                    .filter(f -> !f.toString().contains(File.separator + "resourcepack" + File.separator))::iterator) {
                String found = read(file.toFile(), namespace, id);
                if (found != null) return found;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private static String read(File file, String namespace, String id) {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        if (!namespace.equals(c.getString("info.namespace"))) return null;
        ConfigurationSection item = c.getConfigurationSection("items." + id + ".resource");
        if (item == null) return null;
        List<String> textures = item.getStringList("textures");
        if (textures.isEmpty()) return null;
        String path = textures.getFirst();
        if (path.endsWith(".png")) path = path.substring(0, path.length() - 4);
        return namespace + ":" + path;
    }
}
