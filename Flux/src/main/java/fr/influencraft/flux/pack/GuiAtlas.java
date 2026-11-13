package fr.influencraft.flux.pack;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sprites added to the game's {@code gui} atlas by Flux's client pack ({@code assets/minecraft/atlases/gui.json},
 * sources {@code single}): a texture already in the pack (an item's picture) given a sprite name, so that a text can
 * show it ({@code <sprite:gui:...>}) without a copy of the picture. Used by the HUD for the currencies' icons.
 */
public final class GuiAtlas {

    private static final Map<String, Map<String, String>> SPRITES = new ConcurrentHashMap<>();
    private static volatile Runnable onChange = () -> {
    };

    private GuiAtlas() {
    }

    static void bind(Runnable changed) {
        onChange = changed == null ? () -> {
        } : changed;
    }

    /** The sprites of one user (replaces its former ones): sprite name -> texture resource ({@code ns:path}). */
    public static void set(String user, Map<String, String> sprites) {
        Map<String, String> before = SPRITES.put(user, Map.copyOf(sprites));
        if (!sprites.equals(before)) onChange.run();
    }

    /** The atlas file, or null when there is no sprite. */
    static String json() {
        Map<String, String> all = new TreeMap<>();
        SPRITES.values().forEach(all::putAll);
        if (all.isEmpty()) return null;
        StringBuilder b = new StringBuilder("{\n  \"sources\": [\n");
        int i = 0;
        for (Map.Entry<String, String> e : all.entrySet()) {
            b.append("    {\"type\": \"single\", \"resource\": \"").append(e.getValue()).append("\", \"sprite\": \"")
                    .append(e.getKey()).append("\"}").append(++i < all.size() ? ",\n" : "\n");
        }
        return b.append("  ]\n}\n").toString();
    }
}
