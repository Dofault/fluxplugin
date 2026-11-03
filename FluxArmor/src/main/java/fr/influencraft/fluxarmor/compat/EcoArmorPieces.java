// COMPAT 2026-11-03: pieces made by EcoArmor (data key ecoarmor:set) already handed out in production - remove when no
// such item is left in the game. Each piece becomes fluxarmor:<set>_<piece> the first time Flux refreshes it, keeping
// its enchantments and damage; it is recognised without EcoArmor, which is gone by then.
package fr.influencraft.fluxarmor.compat;

import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.items.ItemCatalog;
import fr.influencraft.flux.api.items.ItemLooks;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class EcoArmorPieces {

    private static final String NAMESPACE = "ecoarmor";
    private static final NamespacedKey SET = new NamespacedKey(NAMESPACE, "set");

    private EcoArmorPieces() {
    }

    public static void register(Plugin plugin, ItemCatalog catalog) {
        CustomItems.register(plugin, NAMESPACE, new CustomItems.Source() {
            @Override
            public String id(ItemStack item) {
                String set = item.getPersistentDataContainer().get(SET, PersistentDataType.STRING);
                return set == null ? null : set + "_" + piece(item);
            }

            @Override
            public ItemStack item(String id) {
                return null;
            }

            @Override
            public Collection<String> ids() {
                return List.of();
            }
        });
        ItemLooks.register(plugin, (id, item) -> {
            if (!id.startsWith(NAMESPACE + ":")) return false;
            String piece = id.substring(NAMESPACE.length() + 1);
            if (!catalog.has(piece)) return false; // a set not defined in FluxArmor: the item stays as it is
            item.editMeta(meta -> {
                for (NamespacedKey key : List.copyOf(meta.getPersistentDataContainer().getKeys())) {
                    if (key.getNamespace().equals(NAMESPACE)) meta.getPersistentDataContainer().remove(key);
                }
                meta.setAttributeModifiers(null); // the modifiers of its EcoArmor tier: the material's own come back
            });
            catalog.adopt(item, piece);
            return true;
        });
    }

    private static String piece(ItemStack item) {
        String material = item.getType().name().toLowerCase(Locale.ROOT);
        return material.substring(material.lastIndexOf('_') + 1);
    }
}
