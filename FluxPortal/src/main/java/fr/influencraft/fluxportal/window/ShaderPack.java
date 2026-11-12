package fr.influencraft.fluxportal.window;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes the window's text shader (the templates in {@code resources/window/}, filled with the map colours) as an
 * overlay of a pack folder: {@code <folder>/pack.mcmeta} and {@code <folder>/<overlay>/assets/minecraft/shaders/core/}.
 * The server's pack takes it from there (a folder its pack plugin merges), and other plugins replacing the text shader
 * (MC GUI) find it in the folder and merge theirs with it.
 */
public final class ShaderPack {

    private ShaderPack() {
    }

    /** True when a file changed (the pack must be rebuilt). */
    public static boolean write(Path folder, String overlay, int minFormat, int maxFormat, MapColours colours) throws IOException {
        String common = fill(read("window/common.glsl"), colours.glsl());
        boolean changed = writeIfChanged(folder.resolve("pack.mcmeta"), mcmeta(overlay, minFormat, maxFormat));
        for (String name : new String[]{"text.vsh", "text.fsh"}) {
            String text = read("window/" + name).replace("@COMMON@", common);
            changed |= writeIfChanged(folder.resolve(overlay + "/assets/minecraft/shaders/core/" + name), text);
        }
        return changed;
    }

    private static String mcmeta(String overlay, int min, int max) {
        return "{\"pack\": {\"pack_format\": " + min + ", \"min_format\": " + min + ", \"max_format\": 9999, \"supported_formats\": [" + min
                + ", 9999], \"description\": \"FluxPortal\"}, \"overlays\": {\"entries\": [{\"min_format\": " + min + ", \"max_format\": " + max
                + ", \"directory\": \"" + overlay + "\", \"formats\": [" + min + ", " + max + "]}]}}\n";
    }

    private static boolean writeIfChanged(Path file, String text) throws IOException {
        if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(text)) return false;
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return true;
    }

    private static String fill(String text, Map<String, String> values) {
        String out = text;
        for (Map.Entry<String, String> e : values.entrySet()) out = out.replace(e.getKey(), e.getValue());
        return out;
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = ShaderPack.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("missing " + resource + " in the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
