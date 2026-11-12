package fr.influencraft.fluxportal.window;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Where block models and textures are read: the server's own resource packs first (the pack players get, with the
 * custom blocks of ItemsAdder and the like: a note block state drawn as a rock of the rpg world is seen as that rock
 * through a portal), then the vanilla client, downloaded once from Mojang. A pack may rename its textures through its
 * block atlas ({@code "sprite": "ia:129"} for {@code rpgblocs:block/stone_andesite}): those names are followed.
 * Some packs are "protected" (ItemsAdder's zip): their pictures are damaged on purpose, the game reads them but Java
 * does not. Folders holding the same pictures unharmed (ItemsAdder's {@code contents/<x>/resourcepack}) are read after
 * the packs: a picture that cannot be decoded is looked for in the next source.
 * Read only from the main thread or one worker at a time (the zip files are shared).
 */
public final class Assets {

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /** A place files are read from, by their path in a pack ("assets/minecraft/..."). */
    private interface Source {
        InputStream open(String path) throws IOException;
    }

    private final List<Source> sources = new ArrayList<>();
    private final List<ZipFile> zips = new ArrayList<>();
    /** Texture names a pack's atlas gives ("ia:129") -> the real one ("rpgblocs:block/stone_andesite"). */
    private final Map<String, String> sprites = new HashMap<>();
    private volatile boolean ready;

    /**
     * Opens the packs (paths from the server folder, missing ones skipped) and the client jar, downloading it in the
     * background the first time; {@code whenReady} runs on the main thread once textures can be read.
     */
    public void load(Plugin plugin, List<String> packs, List<String> folders, boolean download, Runnable whenReady) {
        close();
        for (String path : packs) {
            File f = new File(path);
            if (!f.isFile()) {
                plugin.getLogger().info("Pack " + path + " not found: skipped for the windows' textures.");
                continue;
            }
            open(plugin, f).ifPresent(z -> {
                add(z);
                readAtlases(z);
            });
        }
        for (String pattern : folders) {
            for (File dir : expand(pattern)) {
                java.nio.file.Path root = dir.toPath();
                sources.add(path -> {
                    java.nio.file.Path file = root.resolve(path);
                    return Files.isRegularFile(file) ? Files.newInputStream(file) : null;
                });
            }
        }
        Matcher m = Pattern.compile("^\\d+(?:\\.\\d+)*").matcher(Bukkit.getBukkitVersion());
        String version = m.find() ? m.group() : Bukkit.getBukkitVersion();
        File jar = new File(plugin.getDataFolder(), "cache/client-" + version + ".jar");
        Optional<ZipFile> client = jar.isFile() ? open(plugin, jar) : Optional.empty();
        if (client.isPresent()) {
            add(client.get());
            ready = true;
            whenReady.run();
            return;
        }
        if (!download) {
            plugin.getLogger().info("No client jar (textures.download: false): vanilla blocks are drawn in their map colours.");
            ready = !sources.isEmpty();
            whenReady.run();
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getLogger().info("Downloading the Minecraft " + version + " client from Mojang for its block textures (once)...");
                fetch(version, jar);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    open(plugin, jar).ifPresent(this::add);
                    ready = true;
                    whenReady.run();
                });
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Client jar not downloaded: vanilla blocks are drawn in their map colours.", e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ready = !sources.isEmpty();
                    whenReady.run();
                });
            }
        });
    }

    private void add(ZipFile z) {
        zips.add(z);
        sources.add(path -> {
            ZipEntry e = z.getEntry(path);
            return e == null ? null : z.getInputStream(e);
        });
    }

    /** "a/STAR/b" (a star between two slashes): every folder b under a folder of a; a plain path: itself. */
    private static List<File> expand(String pattern) {
        List<File> out = new ArrayList<>();
        int star = pattern.indexOf("/*/");
        if (star < 0) {
            File f = new File(pattern);
            if (f.isDirectory()) out.add(f);
            return out;
        }
        File[] children = new File(pattern.substring(0, star)).listFiles(File::isDirectory);
        if (children == null) return out;
        java.util.Arrays.sort(children);
        for (File c : children) {
            File f = new File(c, pattern.substring(star + 3));
            if (f.isDirectory()) out.add(f);
        }
        return out;
    }

    public void close() {
        for (ZipFile z : zips) {
            try {
                z.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        zips.clear();
        sources.clear();
        sprites.clear();
        ready = false;
    }

    public boolean ready() {
        return ready;
    }

    private static Optional<ZipFile> open(Plugin plugin, File f) {
        try {
            return Optional.of(new ZipFile(f));
        } catch (IOException e) {
            plugin.getLogger().warning(f.getPath() + " unreadable (" + e.getMessage() + ")");
            return Optional.empty();
        }
    }

    /** Every {@code atlases/blocks.json} of a pack (overlays too): its single sprites renamed. */
    private void readAtlases(ZipFile z) {
        z.stream().filter(e -> e.getName().endsWith("assets/minecraft/atlases/blocks.json")).forEach(e -> {
            try (InputStream in = z.getInputStream(e)) {
                JsonObject atlas = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                if (!atlas.has("sources")) return;
                for (JsonElement s : atlas.getAsJsonArray("sources")) {
                    JsonObject o = s.getAsJsonObject();
                    if (o.has("type") && o.get("type").getAsString().endsWith("single") && o.has("sprite") && o.has("resource")) {
                        sprites.putIfAbsent(id(o.get("sprite").getAsString()), id(o.get("resource").getAsString()));
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // a broken atlas renames nothing
            }
        });
    }

    /** "block/stone" -> "minecraft:block/stone". */
    static String id(String ref) {
        return ref.indexOf(':') < 0 ? "minecraft:" + ref : ref;
    }

    private static String path(String id, String folder, String extension) {
        int colon = id.indexOf(':');
        return "assets/" + id.substring(0, colon) + "/" + folder + "/" + id.substring(colon + 1) + extension;
    }

    /** A json under assets/<namespace>/ of the first source that has it ({@code blockstates}, {@code models}). */
    public Optional<JsonObject> json(String id, String folder) {
        String path = path(id(id), folder, ".json");
        for (Source src : sources) {
            try (InputStream in = src.open(path)) {
                if (in == null) continue;
                return Optional.of(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (IOException | RuntimeException e) {
                // unreadable here: the next source
            }
        }
        return Optional.empty();
    }

    /** A texture by its name in a model ("block/stone", "rpgblocs:block/x", "ia:129"). */
    public Optional<BufferedImage> texture(String ref) {
        String id = id(ref);
        id = sprites.getOrDefault(id, id);
        return image(path(id, "textures", ".png"));
    }

    /** Any picture of the sources, by its full path in them, from the first one that can decode it. */
    public Optional<BufferedImage> image(String path) {
        for (Source src : sources) {
            try (InputStream in = src.open(path)) {
                if (in == null) continue;
                BufferedImage img = ImageIO.read(in);
                if (img != null) return Optional.of(img);
            } catch (IOException | RuntimeException e) {
                // a damaged copy: the next source
            }
        }
        return Optional.empty();
    }

    private static void fetch(String version, File to) throws IOException {
        String url = null;
        for (JsonElement v : json(MANIFEST).getAsJsonArray("versions")) {
            JsonObject o = v.getAsJsonObject();
            if (o.get("id").getAsString().equals(version)) {
                url = o.get("url").getAsString();
                break;
            }
        }
        if (url == null) throw new IOException("Mojang's manifest has no version " + version);
        String client = json(url).getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString();
        to.getParentFile().mkdirs();
        File part = new File(to.getPath() + ".part");
        try (InputStream in = URI.create(client).toURL().openStream()) {
            Files.copy(in, part.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(part.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static JsonObject json(String url) throws IOException {
        try (InputStream in = URI.create(url).toURL().openStream()) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
