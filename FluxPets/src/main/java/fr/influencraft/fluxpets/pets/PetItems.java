package fr.influencraft.fluxpets.pets;

import com.willfp.eco.core.config.interfaces.Config;
import com.willfp.eco.core.items.Items;
import fr.influencraft.fluxpets.Texts;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Objets des menus de familiers (syntaxe d'objet eco : « arrow name:"..." », « itemsadder:... »). */
public final class PetItems {

    private PetItems() {
    }

    /** Objet eco, nom et description remplacés si fournis (codes & et &#RRGGBB, glyphes %img_...%). */
    public static ItemStack build(String lookup, String name, List<String> lore) {
        ItemStack item = lookup == null || lookup.isEmpty() ? null : Items.lookup(lookup).getItem();
        if (item == null || item.getType().isAir()) {
            item = new ItemStack(Material.STONE);
        }
        return named(item.clone(), name, lore);
    }

    public static ItemStack named(ItemStack item, String name, List<String> lore) {
        item.editMeta(meta -> {
            if (name != null) {
                meta.displayName(Texts.of(name));
            }
            if (lore != null) {
                List<Component> lines = new ArrayList<>();
                lore.forEach(l -> lines.add(Texts.of(l)));
                meta.lore(fr.influencraft.flux.api.text.Texts.wrapLore(lines));
            }
        });
        return item;
    }

    /** Objet d'une section de réglages : clés item, name, lore. */
    public static ItemStack fromConfig(Config c) {
        return build(c.getString("item"), c.has("name") ? c.getString("name") : null,
                c.has("lore") ? c.getStrings("lore") : null);
    }

    static String title(String format, Pet pet, int level) {
        return format.replace("%level%", String.valueOf(level)).replace("%pet%", pet.name());
    }
}
