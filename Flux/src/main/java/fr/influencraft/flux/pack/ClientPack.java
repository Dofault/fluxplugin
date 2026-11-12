package fr.influencraft.flux.pack;

import fr.influencraft.flux.api.client.TextShader;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Flux's part of the server's resource pack, written into each folder of {@code client-pack.folders} (the pack plugin
 * merges them: ItemsAdder's {@code contents/<name>/resourcepack}; {@code plugins/Flux/pack} is read by MC GUI):
 * the files under {@code pack/assets/} of Flux's jar (HUD pictures, its font) and the text shader
 * ({@link TextShader}) in an overlay for the pack formats it is written for. Written once every plugin has started,
 * then whenever the shader changes; the console asks for a pack rebuild when a file changed.
 */
public final class ClientPack {

    private final JavaPlugin flux;
    private final File jar;
    private List<Path> folders = List.of();
    private String overlay = "flux_text_v88";
    private int minFormat = 88;
    private int maxFormat = 88;
    private boolean started;
    private boolean pending;

    public ClientPack(JavaPlugin flux, File jar) {
        this.flux = flux;
        this.jar = jar;
    }

    public void configure(ConfigurationSection section) {
        if (section == null) {
            folders = List.of();
            return;
        }
        folders = section.getStringList("folders").stream().map(Path::of).toList();
        overlay = section.getString("overlay", overlay);
        minFormat = section.getInt("min-format", minFormat);
        maxFormat = section.getInt("max-format", maxFormat);
    }

    /** First write, on the first tick: every plugin has given its part by then. */
    public void start() {
        TextShader.bind(this::changed);
        flux.getServer().getScheduler().runTask(flux, () -> {
            started = true;
            write();
        });
    }

    public void stop() {
        TextShader.bind(null);
    }

    private void changed() {
        if (!started || pending || !flux.isEnabled()) return;
        pending = true;
        flux.getServer().getScheduler().runTaskLater(flux, () -> {
            pending = false;
            write();
        }, 2L);
    }

    /** Writes every folder now (main thread: small files). */
    public void write() {
        if (folders.isEmpty()) return;
        String vertex;
        String fragment;
        try {
            vertex = TextShader.vertex();
            fragment = TextShader.fragment();
        } catch (IOException e) {
            flux.getLogger().severe("Text shader not built: " + e.getMessage());
            return;
        }
        if (vertex == null) {
            flux.getLogger().severe("The text shader of " + TextShader.baseName() + " lacks Flux's hooks ("
                    + TextShader.DECLARATIONS + ", " + TextShader.GUI_VERTEX + "): written without the parts of the suite.");
            try {
                vertex = TextShader.rawVertex();
            } catch (IOException e) {
                return;
            }
        }
        boolean shader = !TextShader.vanilla();
        for (Path folder : folders) {
            try {
                boolean changed = copyAssets(folder);
                changed |= writeIfChanged(folder.resolve("pack.mcmeta"), mcmeta());
                Path core = folder.resolve(overlay + "/assets/minecraft/shaders/core");
                if (shader) {
                    changed |= writeIfChanged(core.resolve("text.vsh"), vertex);
                    changed |= writeIfChanged(core.resolve("text.fsh"), fragment);
                } else {
                    changed |= Files.deleteIfExists(core.resolve("text.vsh")) | Files.deleteIfExists(core.resolve("text.fsh"));
                }
                if (changed) flux.getLogger().warning("Client pack written in " + folder + " (text shader: " + TextShader.baseName()
                        + "): rebuild the resource pack (/iazip).");
            } catch (IOException | RuntimeException e) {
                flux.getLogger().severe("Client pack not written in " + folder + ": " + e.getMessage());
            }
        }
    }

    private boolean copyAssets(Path folder) throws IOException {
        boolean changed = false;
        try (JarFile in = new JarFile(jar)) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory() || !e.getName().startsWith("pack/assets/")) continue;
                byte[] bytes;
                try (InputStream s = in.getInputStream(e)) {
                    bytes = s.readAllBytes();
                }
                Path target = folder.resolve(e.getName().substring("pack/".length()));
                if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) continue;
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
                changed = true;
            }
        }
        return changed;
    }

    private String mcmeta() {
        return "{\"pack\": {\"pack_format\": " + minFormat + ", \"min_format\": " + minFormat + ", \"max_format\": 9999, \"supported_formats\": ["
                + minFormat + ", 9999], \"description\": \"Flux\"}, \"overlays\": {\"entries\": [{\"min_format\": " + minFormat
                + ", \"max_format\": " + maxFormat + ", \"directory\": \"" + overlay + "\", \"formats\": [" + minFormat + ", " + maxFormat + "]}]}}\n";
    }

    private static boolean writeIfChanged(Path file, String text) throws IOException {
        if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(text)) return false;
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return true;
    }
}
