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
 * the files under {@code pack/assets/} of Flux's jar (HUD pictures, its font), the sprites of {@link GuiAtlas} and the text shader
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
        GuiAtlas.bind(this::changed);
        flux.getServer().getScheduler().runTask(flux, () -> {
            started = true;
            write();
        });
    }

    public void stop() {
        TextShader.bind(null);
        GuiAtlas.bind(null);
    }

    private void changed() {
        if (!started || pending || !flux.isEnabled()) return;
        pending = true;
        flux.getServer().getScheduler().runTaskLater(flux, () -> {
            pending = false;
            write();
        }, 2L);
    }

    private Path cache() {
        return flux.getDataFolder().toPath().resolve("pack-cache");
    }

    /**
     * While plugins load, before any is enabled: writes the text shader from the base remembered at the last start
     * and the parts given so far (the HUD's, read from hud.yml by Flux). MC GUI merges the shader it finds when it
     * starts, which is before Flux: without this, a change of the HUD needed two restarts.
     */
    public void early() {
        try {
            Path c = cache();
            // no base remembered yet (first start): writing now could leave out a plugin's base (the portals); the
            // shader of the last start stays on disk, the first full write remembers the base for next time
            if (!Files.isRegularFile(c.resolve("text.vsh")) || !Files.isRegularFile(c.resolve("text.fsh"))) return;
            {
                TextShader.remembered(Files.readString(c.resolve("name.txt"), StandardCharsets.UTF_8).strip(),
                        Files.readString(c.resolve("text.vsh"), StandardCharsets.UTF_8), Files.readString(c.resolve("text.fsh"), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            flux.getLogger().warning("Remembered text shader not read: " + e.getMessage());
        }
        writeShader(false);
    }

    /** Writes every folder now (main thread: small files). */
    public void write() {
        writeShader(true);
    }

    private void writeShader(boolean full) {
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
        if (full) rememberBase();
        boolean shader = !TextShader.vanilla();
        for (Path folder : folders) {
            try {
                boolean changed = false;
                if (full) {
                    changed = copyAssets(folder);
                    changed |= writeIfChanged(folder.resolve("pack.mcmeta"), mcmeta());
                    String atlas = GuiAtlas.json();
                    Path atlasFile = folder.resolve("assets/minecraft/atlases/gui.json");
                    changed |= atlas == null ? Files.deleteIfExists(atlasFile) : writeIfChanged(atlasFile, atlas);
                }
                Path core = folder.resolve(overlay + "/assets/minecraft/shaders/core");
                if (shader) {
                    changed |= writeIfChanged(core.resolve("text.vsh"), vertex);
                    changed |= writeIfChanged(core.resolve("text.fsh"), fragment);
                } else {
                    changed |= Files.deleteIfExists(core.resolve("text.vsh")) | Files.deleteIfExists(core.resolve("text.fsh"));
                }
                if (changed) flux.getLogger().warning("Client pack written in " + folder + " (text shader: " + TextShader.baseName()
                        + "): rebuild the resource pack (/iazip)" + (full ? "; restart first if MC GUI merged the former shader." : ", before MC GUI merges it."));
            } catch (IOException | RuntimeException e) {
                flux.getLogger().severe("Client pack not written in " + folder + ": " + e.getMessage());
            }
        }
    }

    /** Keeps the base for the next start (see early). */
    private void rememberBase() {
        if (TextShader.baseName().equals("minecraft")) return;
        try {
            Path c = cache();
            Files.createDirectories(c);
            writeIfChanged(c.resolve("name.txt"), TextShader.baseName());
            writeIfChanged(c.resolve("text.vsh"), TextShader.rawVertex());
            writeIfChanged(c.resolve("text.fsh"), TextShader.fragment());
        } catch (IOException | RuntimeException e) {
            flux.getLogger().warning("Text shader not remembered: " + e.getMessage());
        }
    }

    private boolean copyAssets(Path folder) throws IOException {
        boolean changed = false;
        try (JarFile in = new JarFile(jar)) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                // pack/assets/ applies to every version; pack/overlay/assets/ only to the formats of the overlay (shaders
                // copied from one Minecraft version, like gui.fsh)
                String name;
                if (e.getName().startsWith("pack/assets/")) name = e.getName().substring("pack/".length());
                else if (e.getName().startsWith("pack/overlay/assets/")) name = overlay + "/" + e.getName().substring("pack/overlay/".length());
                else continue;
                byte[] bytes;
                try (InputStream s = in.getInputStream(e)) {
                    bytes = s.readAllBytes();
                }
                Path target = folder.resolve(name);
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
