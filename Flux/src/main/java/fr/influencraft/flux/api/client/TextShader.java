package fr.influencraft.flux.api.client;

import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The text shader of the server's resource pack ({@code core/text.vsh} / {@code text.fsh}). A pack holds one copy of
 * it, and several features draw through it (FluxPortal's windows, the HUD), so Flux alone writes it, in its client
 * pack (Flux's config, {@code client-pack}), from:
 * <ul>
 *   <li>a <b>base</b>: Minecraft's own shader, or the one a plugin replaces it with ({@link #base}: FluxPortal, whose
 *   window is drawn by the world part of the shader). A base keeps the two hooks of Flux's own copy
 *   ({@code resources/pack/shader/text.vsh}): {@code // @FLUX_DECLARATIONS} before {@code main} and
 *   {@code // @FLUX_GUI_VERTEX} at the end of {@code main}, inside {@code #ifdef IS_GUI};</li>
 *   <li><b>parts</b> filling those hooks ({@link #part}): code run for the text of the interface (boss bars, chat,
 *   titles) once the vertex is placed and coloured as the game wants it. The HUD moves its text there.</li>
 * </ul>
 * MC GUI merges its own text shader with the pack's (docs/technique/mcgui.md): it finds Flux's copy in
 * {@code plugins/Flux/pack}. After a change the console asks for {@code /iazip}.
 */
public final class TextShader {

    public static final String DECLARATIONS = "// @FLUX_DECLARATIONS";
    public static final String GUI_VERTEX = "// @FLUX_GUI_VERTEX";

    /** {@code owner} null: the base remembered from the last start, until its plugin gives it again. */
    private record Base(Plugin owner, String name, String vertex, String fragment) {
    }

    private record Part(Plugin owner, String declarations, String guiVertex) {
    }

    private static volatile Base base;
    private static final Map<String, Part> PARTS = new ConcurrentHashMap<>();
    private static volatile Runnable onChange = () -> {
    };

    private TextShader() {
    }

    /** Called by Flux: told when the shader must be written again. */
    public static void bind(Runnable changed) {
        onChange = changed == null ? () -> {
        } : changed;
    }

    /**
     * Replaces Minecraft's shader as the base (one plugin at a time; null texts give it back). The texts must keep
     * the two hooks, else the parts are left out and a warning is logged.
     */
    public static void base(Plugin owner, String vertex, String fragment) {
        Base before = base;
        if (before != null && before.owner() != null && before.owner() != owner && vertex != null) {
            owner.getLogger().warning("The text shader of " + before.owner().getName() + " is replaced by this plugin's.");
        }
        base = vertex == null ? null : new Base(owner, owner.getName(), vertex, fragment);
        onChange.run();
    }

    /**
     * The base written at the last start (Flux's pack cache), used while plugins load: Flux writes the shader before
     * MC GUI merges it, which MC GUI does once, when it starts (pack mode external). Its plugin gives it again later.
     */
    public static void remembered(String name, String vertex, String fragment) {
        if (base == null && vertex != null && fragment != null) base = new Base(null, name, vertex, fragment);
    }

    /**
     * Code of a plugin added to the shader: {@code declarations} (constants, functions) before {@code main}, compiled
     * for the interface's text only, {@code guiVertex} (statements) at the end of {@code main} for the interface's text. Same id again: replaced;
     * null code: removed. Names should start with the plugin's own prefix ({@code flux_hud_...}).
     */
    public static void part(Plugin owner, String id, String declarations, String guiVertex) {
        if (declarations == null && guiVertex == null) PARTS.remove(id);
        else PARTS.put(id, new Part(owner, declarations == null ? "" : declarations, guiVertex == null ? "" : guiVertex));
        onChange.run();
    }

    public static void unregisterAll(Plugin owner) {
        boolean changed = PARTS.values().removeIf(p -> p.owner() == owner);
        Base b = base;
        if (b != null && b.owner() != null && b.owner() == owner) {
            base = null;
            changed = true;
        }
        if (changed) onChange.run();
    }

    /** Owner of the base, "minecraft" for the game's own. */
    public static String baseName() {
        Base b = base;
        return b == null ? "minecraft" : b.name();
    }

    /** The vertex shader as it is written in the pack, or null when the base lacks the hooks. */
    public static String vertex() throws IOException {
        Base b = base;
        String text = b == null ? resource("pack/shader/text.vsh") : b.vertex();
        if (!text.contains(DECLARATIONS) || !text.contains(GUI_VERTEX)) return null;
        StringBuilder declarations = new StringBuilder();
        StringBuilder gui = new StringBuilder();
        for (Map.Entry<String, Part> e : new TreeMap<>(PARTS).entrySet()) {
            Part p = e.getValue();
            if (!p.declarations().isBlank()) declarations.append("// ").append(e.getKey()).append(" (").append(p.owner().getName())
                    .append(")\n").append(p.declarations().strip()).append("\n\n");
            if (!p.guiVertex().isBlank()) gui.append(p.guiVertex().strip()).append('\n');
        }
        // only the interface's text runs the parts: the game compiles this shader once per kind of text (eight of
        // them), the world's and see-through ones skip the parts' tables
        String decl = declarations.isEmpty() ? "" : "#ifdef IS_GUI\n" + declarations.toString().strip() + "\n#endif";
        return text.replace(DECLARATIONS, decl).replace(GUI_VERTEX, gui.toString().strip());
    }

    /** The base's vertex shader without any part. */
    public static String rawVertex() throws IOException {
        Base b = base;
        return b == null ? resource("pack/shader/text.vsh") : b.vertex();
    }

    public static String fragment() throws IOException {
        Base b = base;
        return b == null ? resource("pack/shader/text.fsh") : b.fragment();
    }

    /** True when there is nothing to write: Minecraft's base and no part (the pack keeps the game's shader). */
    public static boolean vanilla() {
        return base == null && PARTS.isEmpty();
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = TextShader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("missing " + path + " in Flux's jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
