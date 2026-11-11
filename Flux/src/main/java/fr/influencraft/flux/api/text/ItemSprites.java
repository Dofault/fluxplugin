package fr.influencraft.flux.api.text;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Inline icon of a vanilla item in any text (chat, sidebar, lore, titles): a sprite of the client's block or item
 * atlas, nothing in the resource pack. MiniMessage tag {@code <sprite:block/stone>} or
 * {@code <sprite:items:item/diamond>}, given wrapped in a translation {@code %s}
 * ({@code <lang:'%s':'<sprite:...>'>}): the client shows the same icon, and tools that keep only text and translation
 * components drop a bare sprite silently (Typewriter's sidebar splits its lines that way).
 * Table: {@code item_sprites.json} of Flux's jar, generated from the client jar by
 * {@code Flux/assets/vanilla/gen_item_sprites.py} (rerun after a Minecraft update).
 */
public final class ItemSprites {

    private static volatile Map<String, String> sprites = Map.of();

    private ItemSprites() {
    }

    /** Called by Flux at start-up. */
    public static void load(Plugin flux) {
        try (InputStream in = flux.getResource("item_sprites.json")) {
            if (in == null) return;
            Map<String, String> read = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() {
                    }.getType());
            sprites = read == null ? Map.of() : Map.copyOf(read);
        } catch (Exception e) {
            flux.getLogger().warning("item_sprites.json not readable: " + e.getMessage());
        }
    }

    /** MiniMessage tag of the item's icon ({@code oak_log}, {@code minecraft:oak_log}), or "" when it has none. */
    public static String tag(String item) {
        if (item == null) return "";
        String sprite = sprites.get(item.toLowerCase(Locale.ROOT).replace("minecraft:", "").strip());
        return sprite == null ? "" : "<lang:'%s':'<sprite:" + sprite + ">'>";
    }

    public static String tag(Material material) {
        return material == null ? "" : tag(material.getKey().getKey());
    }
}
