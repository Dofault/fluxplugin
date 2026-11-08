package fr.influencraft.fluxdungeons;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.items.ItemLooks;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.fluxdungeons.mob.Combat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows the level of dungeon gear as the last line of its description. The level itself is the item's data
 * ({@link Combat#ITEM_LEVEL}); this line comes back whenever the item's description is rewritten from the config.
 */
public final class ItemLevels {

    private static FluxPlugin plugin;

    private ItemLevels() {
    }

    static void register(FluxPlugin owner) {
        plugin = owner;
        ItemLooks.register(owner, (id, item) -> id.startsWith("fluxdungeons:") && line(item));
    }

    /** Adds the level line when the item has a level and the line is missing; true when the item changed. */
    public static boolean line(ItemStack item) {
        int level = Combat.storedLevel(item);
        if (level < 0 || plugin == null) return false;
        Component wanted = Texts.parse(plugin.messages().raw("item.level", "%level%", String.valueOf(level)));
        String plain = PlainTextComponentSerializer.plainText().serialize(wanted);
        List<Component> lore = item.getItemMeta().lore();
        List<Component> lines = lore == null ? new ArrayList<>() : new ArrayList<>(lore);
        PlainTextComponentSerializer s = PlainTextComponentSerializer.plainText();
        if (!lines.isEmpty() && s.serialize(lines.get(lines.size() - 1)).equals(plain)) return false;
        // a former level line (the level changed) is replaced
        String prefix = plain.replaceAll("\\d+$", "");
        lines.removeIf(c -> {
            String t = s.serialize(c);
            return t.startsWith(prefix) && t.substring(prefix.length()).matches("\\d+");
        });
        lines.add(wanted);
        item.editMeta(m -> m.lore(lines));
        return true;
    }
}
