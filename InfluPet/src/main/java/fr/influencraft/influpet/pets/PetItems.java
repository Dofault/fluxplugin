package fr.influencraft.influpet.pets;

import com.willfp.eco.core.config.interfaces.Config;
import com.willfp.eco.core.items.Items;
import fr.influencraft.influpet.Texts;
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
                meta.lore(lines);
            }
        });
        return item;
    }

    /** Objet d'une section de réglages : clés item, name, lore. */
    public static ItemStack fromConfig(Config c) {
        return build(c.getString("item"), c.has("name") ? c.getString("name") : null,
                c.has("lore") ? c.getStrings("lore") : null);
    }

    /** Icône d'un familier dans le menu (gui.pet-icon), avec l'état actif / à activer. */
    public static ItemStack icon(Player player, Pet pet) {
        Config c = Pets.settings().getSubsection("gui.pet-icon");
        int level = PetData.level(player, pet);
        List<String> lore = new ArrayList<>(PetData.inject(c.getStrings(level >= pet.maxLevel()
                ? "max-level-lore" : "lore"), player, pet, null));
        lore.addAll(c.getStrings(PetData.isActive(player, pet) ? "active-lore" : "not-active-lore"));
        return named(pet.baseItem(), title(c.getString("name"), pet, level), lore);
    }

    /** Familier d'un emplacement occupé en haut du menu (gui.pet-slots.active). */
    public static ItemStack info(Player player, Pet pet) {
        Config c = Pets.settings().getSubsection("gui.pet-slots.active");
        int level = PetData.level(player, pet);
        return named(pet.baseItem(), title(c.getString("name"), pet, level),
                PetData.inject(c.getStrings(level >= pet.maxLevel() ? "max-level-lore" : "lore"), player, pet, null));
    }

    /** Emplacement libre (gui.pet-slots.empty) ou verrouillé (gui.pet-slots.locked) n°slot. */
    public static ItemStack slot(Player player, int slot, boolean locked) {
        Config c = Pets.settings().getSubsection(locked ? "gui.pet-slots.locked" : "gui.pet-slots.empty");
        String grade = c.has("grades." + slot) ? c.getString("grades." + slot) : "";
        List<String> lore = new ArrayList<>();
        for (String line : c.has("lore") ? c.getStrings("lore") : List.<String>of()) {
            if (!line.contains("%grade%") || !grade.isEmpty()) {
                lore.add(papi(player, line.replace("%grade%", grade).replace("%slot%", String.valueOf(slot))));
            }
        }
        String name = c.has("name") ? papi(player, c.getString("name").replace("%slot%", String.valueOf(slot))) : null;
        return build(c.getString("item"), name, lore);
    }

    /** Placeholders PlaceholderAPI (si présent). */
    private static String papi(Player player, String text) {
        return text.indexOf('%') >= 0 && org.bukkit.Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
                ? me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text) : text;
    }

    static String title(String format, Pet pet, int level) {
        return format.replace("%level%", String.valueOf(level)).replace("%pet%", pet.name());
    }

    /** Titre de menu : codes & -> codes § (le titre des menus eco est une chaîne). */
    static String menuTitle(String text) {
        return LegacyComponentSerializer.legacySection().serialize(Texts.of(text));
    }
}
