package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.util.Format;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;

/**
 * A spell as a menu button (the « spells » line of /donjon, the details of a class): its icon, name, sentence with its
 * numbers in teal, key and mana cost. One look for every menu that lists spells.
 */
public final class SpellItems {

    /** Colour of the numbers in a spell's text (owner, 2026-11-16: the sentence in white, its numbers in teal). */
    public static final String VALUE = "&#4FC3C9";
    /** What ends a number: back to the sentence's white. */
    public static final String VALUE_END = "&f";

    private SpellItems() {
    }

    /** {@code spell} read with {@link #VALUE} / {@link #VALUE_END}; {@code more} adds the button's own lines (actions). */
    public static ItemStack item(FluxPlugin plugin, SpellBook.Spell spell, Player viewer, Consumer<Lore> more) {
        Lore lore = Lore.create().description("&f" + spell.text())
                .kv(raw(plugin, "menu.spell-key"), raw(plugin, "class-pages.keys." + spell.key()));
        if (spell.mana() >= 0) lore.kv(raw(plugin, "menu.spell-cost"), spell.mana() == 0 ? raw(plugin, "class-pages.free")
                : raw(plugin, "class-pages.mana", "%mana%", Format.decimal(spell.mana())));
        if (more != null) more.accept(lore);
        ItemStack item = Items.of("itemsadder:" + spell.icon(), spell.name(), lore.build(), viewer);
        if (item == null) item = Items.of("ENCHANTED_BOOK", spell.name(), lore.build(), viewer);
        return item == null ? new ItemStack(Material.ENCHANTED_BOOK) : item;
    }

    private static String raw(FluxPlugin plugin, String key, String... vars) {
        return plugin.messages().raw(key, vars);
    }
}
