package fr.influencraft.flux.api.inventory;

import fr.influencraft.flux.ItemFactory;
import fr.influencraft.flux.api.text.Texts;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Items described the same way everywhere in the suite (menus, configs, rewards):
 * <ul>
 *   <li>{@code itemsadder:<namespace>:<id>} or {@code itemsadder:<id>} (any ItemsAdder item);</li>
 *   <li>a vanilla material: {@code DIAMOND}, {@code minecraft:diamond};</li>
 *   <li>{@code head:<base64 texture>}, {@code player-head} (the viewer's head), {@code hdb:<id>} (HeadDatabase);</li>
 *   <li>{@code source:<id>:<argument>}: an item provided by a plugin ({@link fr.influencraft.flux.api.FluxApi#registerItemSource}).</li>
 * </ul>
 * An unknown material gives a barrier (with one console warning), never null, except {@code air}.
 */
public final class Items {

    private Items() {
    }

    /** The item of a material spec, or null for {@code air}. {@code viewer} is used by {@code player-head}. */
    public static ItemStack of(String material, Player viewer) {
        if (material != null && material.regionMatches(true, 0, "source:", 0, 7)) {
            String rest = material.substring(7);
            int sep = rest.indexOf(':');
            var source = fr.influencraft.flux.api.FluxApi.itemSource(sep < 0 ? rest : rest.substring(0, sep));
            ItemStack item = source == null ? null : source.item(viewer, sep < 0 ? "" : rest.substring(sep + 1));
            return item == null ? new ItemStack(Material.BARRIER) : item.clone();
        }
        ItemFactory factory = ItemFactory.instance();
        if (factory == null) {
            Material m = Material.matchMaterial(material);
            return m == null ? new ItemStack(Material.BARRIER) : new ItemStack(m);
        }
        return factory.base(material, viewer);
    }

    public static ItemStack of(String material) {
        return of(material, null);
    }

    /** Item with a name and lore through {@link Texts} (placeholders of {@code viewer}, colours, no italics). */
    public static ItemStack of(String material, String name, List<String> lore, Player viewer) {
        ItemStack item = of(material, viewer);
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        if (name != null) meta.displayName(Texts.parse(name, viewer));
        if (lore != null && !lore.isEmpty()) meta.lore(Texts.lore(lore, viewer));
        item.setItemMeta(meta);
        return item;
    }

    /** Namespaced ItemsAdder id of an item ({@code namespace:id}), or null when it is not an ItemsAdder item. */
    public static String itemsAdderId(ItemStack item) {
        if (item == null || !org.bukkit.Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return null;
        dev.lone.itemsadder.api.CustomStack stack = dev.lone.itemsadder.api.CustomStack.byItemStack(item);
        return stack == null ? null : stack.getNamespacedID();
    }

    /** Whether an ItemsAdder item with this namespaced id exists (false without ItemsAdder). */
    public static boolean itemsAdderExists(String namespacedId) {
        return org.bukkit.Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")
                && dev.lone.itemsadder.api.CustomStack.isInRegistry(namespacedId);
    }

    /** Same kind of item (ItemsAdder id when both have one, otherwise material and item data). */
    public static boolean similar(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        String ia = itemsAdderId(a), ib = itemsAdderId(b);
        if (ia != null || ib != null) return ia != null && ia.equals(ib);
        return a.isSimilar(b);
    }
}
