package fr.influencraft.flux.api.util;

import fr.influencraft.flux.api.FluxApi;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Files a plugin of the suite ships in its jar. */
public final class Resources {

    private Resources() {
    }

    /**
     * Copies the jar's {@code menus/} files that are missing on disk (the translation under
     * {@code i18n/<language>/menus/} when present) and hands {@code plugins/<Plugin>/menus/} to Flux: the menus of a
     * plugin live in its own folder, never in Flux's. The folder is handed even when the jar ships no menu (menus
     * written for the server).
     */
    public static void shipMenus(JavaPlugin plugin, String language) {
        List<String> shipped = new ArrayList<>();
        try (JarFile jar = new JarFile(new File(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()))) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("menus/") && name.endsWith(".yml")) shipped.add(name);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not list the menus of the jar: " + e.getMessage());
        }
        File folder = new File(plugin.getDataFolder(), "menus");
        if (shipped.isEmpty()) {
            if (folder.isDirectory()) FluxApi.registerMenuFolder(plugin, folder);
            return;
        }
        String translated = "i18n/" + language + "/";
        for (String name : shipped) {
            File target = new File(plugin.getDataFolder(), name);
            if (target.exists()) continue;
            String source = plugin.getResource(translated + name) != null ? translated + name : name;
            try (InputStream in = plugin.getResource(source)) {
                if (in == null) continue;
                target.getParentFile().mkdirs();
                Files.copy(in, target.toPath());
            } catch (IOException e) {
                plugin.getLogger().warning("Could not write " + name + ": " + e.getMessage());
            }
        }
        FluxApi.registerMenuFolder(plugin, folder);
    }
}
