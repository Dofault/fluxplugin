package fr.influencraft.fluxenchant;

import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.text.Texts;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Development gear, items of the suite ({@code fluxenchant:dev_<item>}, {@code /fluxitems give <player> fluxenchant:dev_*}):
 * netherite when it exists, unbreakable, with every enchantment the item accepts at its maximum level, vanilla and
 * custom, conflicts ignored. A source rather than a catalog entry: the list follows the enchantments of the server.
 */
final class DevGear implements CustomItems.Source {

    private static final Map<String, Material> ITEMS = new LinkedHashMap<>();

    static {
        ITEMS.put("dev_pickaxe", Material.NETHERITE_PICKAXE);
        ITEMS.put("dev_pickaxe_silk", Material.NETHERITE_PICKAXE);
        ITEMS.put("dev_axe", Material.NETHERITE_AXE);
        ITEMS.put("dev_shovel", Material.NETHERITE_SHOVEL);
        ITEMS.put("dev_hoe", Material.NETHERITE_HOE);
        ITEMS.put("dev_shears", Material.SHEARS);
        ITEMS.put("dev_fishing_rod", Material.FISHING_ROD);
        ITEMS.put("dev_sword", Material.NETHERITE_SWORD);
        ITEMS.put("dev_bow", Material.BOW);
        ITEMS.put("dev_crossbow", Material.CROSSBOW);
        ITEMS.put("dev_trident", Material.TRIDENT);
        ITEMS.put("dev_mace", Material.MACE);
        ITEMS.put("dev_helmet", Material.NETHERITE_HELMET);
        ITEMS.put("dev_chestplate", Material.NETHERITE_CHESTPLATE);
        ITEMS.put("dev_leggings", Material.NETHERITE_LEGGINGS);
        ITEMS.put("dev_boots", Material.NETHERITE_BOOTS);
        ITEMS.put("dev_elytra", Material.ELYTRA);
    }

    /** Would cancel what the gear is for: silk touch cancels fortune (see dev_pickaxe_silk), riptide stops a throw. */
    private static final Set<String> LEFT_OUT = Set.of("silk_touch", "riptide");

    private final NamespacedKey key;

    DevGear(Plugin plugin) {
        key = new NamespacedKey(plugin, "dev_item");
    }

    @Override
    public String id(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    @Override
    public ItemStack item(String id) {
        Material material = ITEMS.get(id);
        if (material == null) return null;
        ItemStack item = new ItemStack(material);
        boolean silk = id.endsWith("_silk");
        for (Enchantment e : Registry.ENCHANTMENT) {
            String name = e.getKey().getKey();
            if (e.isCursed() || !e.canEnchantItem(item)) continue;
            if (silk ? name.equals("fortune") : LEFT_OUT.contains(name)) continue;
            if (name.equals("riptide")) continue;
            item.addUnsafeEnchantment(e, e.getMaxLevel());
        }
        ItemMeta meta = item.getItemMeta();
        meta.setUnbreakable(true);
        meta.displayName(Texts.colors("&#FF5C5C&lDEV ").append(Component.translatable(material.translationKey())));
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public Collection<String> ids() {
        return ITEMS.keySet();
    }
}
