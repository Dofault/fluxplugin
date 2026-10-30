package fr.influencraft.fluxtools;

import fr.influencraft.flux.api.items.CustomItems;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.kyori.adventure.key.Key;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 3D looks given to items of other plugins (EcoArmor pieces...), from {@code apparences.yml}: icon (item_model
 * component, also the model worn on the head) and worn appearance (equippable component). An item is recognised by its
 * Flux id ({@link CustomItems#id}, e.g. {@code ecoarmor:phenomene_chestplate}); Flux applies the look at the right
 * moments ({@code ItemLooks}).
 */
final class Apparences {

    /**
     * {@code item}: id or prefix ending with {@code *}. {@code equip}: false = worn appearance left as it is;
     * {@code equipment} null with {@code equip} = none (the item's model is worn on the head).
     * {@code parure}: 3D model worn on the torso by {@link Parures}, or null.
     */
    private record Rule(String item, NamespacedKey model, boolean equip, NamespacedKey equipment, NamespacedKey parure) {
    }

    private final FluxTools plugin;
    private final List<Rule> rules = new ArrayList<>();
    private ConfigurationSection parureSettings;

    Apparences(FluxTools plugin) {
        this.plugin = plugin;
    }

    void charger() {
        rules.clear();
        parureSettings = null;
        File f = new File(plugin.getDataFolder(), "apparences.yml");
        if (!f.isFile()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        parureSettings = yml.getConfigurationSection("parures");
        ConfigurationSection root = yml.getConfigurationSection("apparences");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection c = root.getConfigurationSection(id);
            String item = c == null ? "" : c.getString("objet", "");
            if (item.isBlank() || !item.contains(":")) {
                plugin.getLogger().warning("Appearance " + id + ": missing or invalid item id (objet), skipped.");
                continue;
            }
            String eq = c.getString("equipement");
            rules.add(new Rule(item, key(c.getString("modele")), eq != null,
                    eq == null || eq.equalsIgnoreCase("aucun") ? null : key(eq), key(c.getString("parure"))));
        }
        if (!rules.isEmpty()) plugin.getLogger().info(rules.size() + " appearances loaded.");
        fr.influencraft.flux.api.items.ItemLooks.refreshOnline();
    }

    /** Section {@code parures} of apparences.yml (placement of the torso models), or null. */
    ConfigurationSection reglagesParures() {
        return parureSettings;
    }

    /** Parure model of the rule recognising the item, or null. */
    NamespacedKey parure(ItemStack item) {
        Rule r = item == null || item.isEmpty() ? null : rule(CustomItems.id(item));
        return r == null ? null : r.parure();
    }

    private Rule rule(String id) {
        if (id == null) return null;
        for (Rule r : rules) if (CustomItems.matches(r.item(), id)) return r;
        return null;
    }

    private static NamespacedKey key(String s) {
        return s == null || s.isBlank() ? null : NamespacedKey.fromString(s);
    }

    /**
     * Look of the rule recognising the item (ItemLooks); true when the item changed. Components read through Paper's
     * API, which returns the real value (the material's one when the item writes none): ItemMeta#getEquippable returns
     * an empty component (head, no appearance) instead.
     */
    boolean apply(String id, ItemStack item) {
        Rule r = rule(id);
        if (r == null) return false;
        boolean changed = false;
        if (r.model() != null && !same(item.getData(DataComponentTypes.ITEM_MODEL), r.model())) {
            item.setData(DataComponentTypes.ITEM_MODEL, r.model());
            changed = true;
        }
        Equippable eq = item.getData(DataComponentTypes.EQUIPPABLE);
        if (r.equip() && eq != null && !same(eq.assetId(), r.equipment())) {
            item.setData(DataComponentTypes.EQUIPPABLE, eq.toBuilder().assetId(r.equipment()));
            changed = true;
        }
        return changed;
    }

    private static boolean same(Key a, Key b) {
        return Objects.equals(a == null ? null : a.asString(), b == null ? null : b.asString());
    }
}
