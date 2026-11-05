package fr.influencraft.fluxenchant;

import fr.influencraft.flux.api.text.Texts;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Development gear ({@code /fluxcheat}): netherite items, unbreakable, with every enchantment their kind accepts at its
 * maximum level, vanilla and custom, conflicts ignored. Needs {@code fluxenchant.cheat}, which even an operator does not
 * have by default: this is never meant for players.
 */
final class CheatGear {

    /** Gear sets by name, in the order items are given. */
    static final Map<String, List<Material>> SETS = new LinkedHashMap<>();

    static {
        SETS.put("tools", List.of(Material.NETHERITE_PICKAXE, Material.NETHERITE_AXE, Material.NETHERITE_SHOVEL,
                Material.NETHERITE_HOE, Material.SHEARS, Material.FISHING_ROD));
        SETS.put("weapons", List.of(Material.NETHERITE_SWORD, Material.BOW, Material.CROSSBOW, Material.TRIDENT, Material.MACE));
        SETS.put("armor", List.of(Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS,
                Material.NETHERITE_BOOTS, Material.ELYTRA));
    }

    /**
     * A conflict that a level cannot settle: the first enchantment cancels the second one in play, so only one of them
     * goes on an item. Silk touch would cancel fortune, riptide stops a trident being thrown. Curses are left out too.
     */
    private static final Set<String> LEFT_OUT = Set.of("silk_touch", "riptide");

    private CheatGear() {
    }

    static List<String> names() {
        List<String> out = new ArrayList<>(SETS.keySet());
        out.add("all");
        out.add("silk");
        return out;
    }

    /** Materials of a set, or the single item named; empty when unknown. */
    static List<Material> resolve(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (key.equals("all")) return SETS.values().stream().flatMap(List::stream).toList();
        if (key.equals("silk")) return List.of(Material.NETHERITE_PICKAXE);
        if (SETS.containsKey(key)) return SETS.get(key);
        Material single = Material.matchMaterial(key);
        return single == null || !single.isItem() ? List.of() : List.of(single);
    }

    /** Gives the items, what does not fit falls at the player's feet. Returns the number of enchantments put on. */
    static int give(Player player, List<Material> items, boolean silk) {
        int total = 0;
        for (Material material : items) {
            ItemStack item = new ItemStack(material);
            total += enchant(item, silk);
            ItemMeta meta = item.getItemMeta();
            meta.setUnbreakable(true);
            meta.displayName(Texts.colors("&#FF5C5C&lDEV ").append(net.kyori.adventure.text.Component.translatable(material.translationKey())));
            item.setItemMeta(meta);
            for (ItemStack left : player.getInventory().addItem(item).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
        return total;
    }

    private static int enchant(ItemStack item, boolean silk) {
        int count = 0;
        for (Enchantment e : Registry.ENCHANTMENT) {
            String id = e.getKey().getKey();
            if (e.isCursed() || !e.canEnchantItem(item)) continue;
            if (id.equals("fortune") && silk) continue;
            if (LEFT_OUT.contains(id) && !(silk && id.equals("silk_touch"))) continue;
            item.addUnsafeEnchantment(e, e.getMaxLevel());
            count++;
        }
        return count;
    }
}
