package fr.influencraft.fluxportal.window;

import fr.influencraft.flux.api.client.TextShader;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The window's text shader (the templates in {@code resources/window/}, filled with the map colours), handed to Flux
 * as the base of the pack's text shader ({@link TextShader}): Flux writes it into its client pack with the other
 * parts of the suite (the HUD), where MC GUI finds it to merge its own.
 */
public final class ShaderPack {

    private ShaderPack() {
    }

    public static void register(Plugin plugin, MapColours colours) throws IOException {
        String common = fill(read("window/common.glsl"), colours.glsl());
        String vertex = read("window/text.vsh").replace("@COMMON@", common);
        String fragment = read("window/text.fsh").replace("@COMMON@", common);
        TextShader.base(plugin, vertex, fragment);
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
