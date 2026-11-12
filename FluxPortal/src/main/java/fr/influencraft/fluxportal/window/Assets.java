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
 * Read only from the main thread or one worker at a time (the zip files are shared).
 */
public final class Assets {

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final List<ZipFile> sources = new ArrayList<>();
    /** Texture names a pack's atlas gives ("ia:129") -> the real one ("rpgblocs:block/stone_andesite"). */
    private final Map<String, String> sprites = new HashMap<>();
    private volatile boolean ready;

    /**
     * Opens the packs (paths from the server folder, missing ones skipped) and the client jar, downloading it in the
     * background the first time; {@code whenReady} runs on the main thread once textures can be read.
     */
    public void load(Plugin plugin, List<String> packs, boolean download, Runnable whenReady) {
        close();
        for (String path : packs) {
            File f = new File(path);
            if (!f.isFile()) {
                plugin.getLogger().info("Pack " + path + " not found: skipped for the windows' textures.");
                continue;
            }
            open(plugin, f).ifPresent(z -> {
                sources.add(z);
                readAtlases(z);
            });
        }
        Matcher m = Pattern.compile("^\\d+(?:\\.\\d+)*").matcher(Bukkit.getBukkitVersion());
        String version = m.find() ? m.group() : Bukkit.getBukkitVersion();
        File jar = new File(plugin.getDataFolder(), "cache/client-" + version + ".jar");
        Optional<ZipFile> client = jar.isFile() ? open(plugin, jar) : Optional.empty();
        if (client.isPresent()) {
            sources.add(client.get());
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
                    open(plugin, jar).ifPresent(sources::add);
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

    public void close() {
        for (ZipFile z : sources) {
            try {
                z.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
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

    private Optional<InputStream> find(String path) throws IOException {
        for (ZipFile z : sources) {
            ZipEntry e = z.getEntry(path);
            if (e != null) return Optional.of(z.getInputStream(e));
        }
        return Optional.empty();
    }

    /** A json under assets/<namespace>/ of the first source that has it ({@code blockstates}, {@code models}). */
    public Optional<JsonObject> json(String id, String folder) {
        try {
            Optional<InputStream> in = find(path(id(id), folder, ".json"));
            if (in.isEmpty()) return Optional.empty();
            try (InputStream s = in.get()) {
                return Optional.of(JsonParser.parseReader(new InputStreamReader(s, StandardCharsets.UTF_8)).getAsJsonObject());
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** A texture by its name in a model ("block/stone", "rpgblocs:block/x", "ia:129"). */
    public Optional<BufferedImage> texture(String ref) {
        String id = id(ref);
        id = sprites.getOrDefault(id, id);
        return image(path(id, "textures", ".png"));
    }

    /** Any picture of the packs, by its full path in them ("assets/minecraft/textures/environment/clouds.png"). */
    public Optional<BufferedImage> image(String path) {
        try {
            Optional<InputStream> in = find(path);
            if (in.isEmpty()) return Optional.empty();
            try (InputStream s = in.get()) {
                return Optional.ofNullable(ImageIO.read(s));
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
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
