package fr.influencraft.flux.api.text;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Minecraft's own names (items, blocks, enchantments, potion effects) in the server's language, for texts the server
 * writes once for everybody (a sign, a search index) where a client-side translation cannot be used. Elsewhere, send
 * the name to be translated by each client ({@code <lang:key>}, {@code Component.translatable}).
 * Lists: {@code item_names/<language>.json} of Flux's jar (Minecraft 26.2 language file, item and block keys).
 */
public final class Translations {

    private static volatile Map<String, String> names = Map.of();

    private Translations() {
    }

    /** Called by Flux with the suite's language. */
    public static void load(Plugin flux, String language) {
        try (InputStream in = flux.getResource("item_names/" + language + ".json")) {
            if (in == null) {
                names = Map.of();
                return;
            }
            Map<String, String> read = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() {
                    }.getType());
            names = read == null ? Map.of() : Map.copyOf(read);
        } catch (Exception e) {
            flux.getLogger().warning("item_names/" + language + ".json not readable: " + e.getMessage());
        }
    }

    /** The name of a translation key in the server's language, or null when the list does not have it. */
    public static String server(String translationKey) {
        return translationKey == null ? null : names.get(translationKey);
    }

    /** Plain name of an item for everybody: its custom name, else its Minecraft name in the server's language. */
    public static String itemName(ItemStack item) {
        if (item == null) return "?";
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) return Texts.plain(meta.displayName());
        if (meta != null && meta.hasItemName()) return Texts.plain(meta.itemName());
        String n = server(item.getType().translationKey());
        return n != null ? n : item.getType().getKey().getKey().replace('_', ' ');
    }
}
