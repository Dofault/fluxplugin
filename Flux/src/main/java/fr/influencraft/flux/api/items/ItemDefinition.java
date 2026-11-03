package fr.influencraft.flux.api.items;

import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.text.Texts;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * An item described in a config file, the same way in every plugin of the suite:
 *
 * <pre>
 *   material: netherite_helmet      # any spec of Items.of
 *   model: mypack:my_helmet         # item_model component (icon, and the model worn on the head)
 *   equipment: mypack:my_armor      # worn appearance; none = no texture, the model is worn on the head
 *   name: "&amp;6My helmet"
 *   lore: ["&amp;7A line."]
 *   enchantments: {protection: 4}   # written when the item is made, never rewritten afterwards
 *   attributes: {armor: 3}          # replace the material's own ones
 *   flags: [HIDE_ENCHANTS]
 *   durability: 500
 *   unbreakable: false
 *   glow: true
 * </pre>
 *
 * Everything but {@code material} is optional. Name, lore, look, attributes and flags follow the config on items
 * already handed out ({@link ItemCatalog}).
 */
public final class ItemDefinition {

    private final String material;
    private final Key model;
    private final boolean equip;
    private final Key equipment;
    private final String name;
    private final List<String> lore;
    private final Map<String, Integer> enchantments = new LinkedHashMap<>();
    private final Map<String, Double> attributes = new LinkedHashMap<>();
    private final List<ItemFlag> flags = new ArrayList<>();
    private final int durability;
    private final boolean unbreakable;
    private final Boolean glow;
    /** Fingerprint of the definition: an item made with another one is brought up to date. */
    final int revision;

    private ItemDefinition(ConfigurationSection c) {
        material = c.getString("material", "PAPER");
        model = key(c.getString("model"));
        String eq = c.getString("equipment");
        equip = eq != null;
        equipment = eq == null || eq.equalsIgnoreCase("none") ? null : key(eq);
        name = c.getString("name");
        lore = c.getStringList("lore");
        ConfigurationSection e = c.getConfigurationSection("enchantments");
        if (e != null) for (String k : e.getKeys(false)) enchantments.put(k.toLowerCase(Locale.ROOT), e.getInt(k));
        ConfigurationSection a = c.getConfigurationSection("attributes");
        if (a != null) for (String k : a.getKeys(false)) attributes.put(k.toLowerCase(Locale.ROOT), a.getDouble(k));
        for (String f : c.getStringList("flags")) {
            try {
                flags.add(ItemFlag.valueOf(f.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // an unknown flag changes nothing
            }
        }
        durability = c.getInt("durability", 0);
        unbreakable = c.getBoolean("unbreakable", false);
        glow = c.contains("glow") ? c.getBoolean("glow") : null;
        revision = Objects.hash(material, model, equip, equipment, name, lore, attributes, flags, durability, unbreakable, glow);
    }

    public static ItemDefinition read(ConfigurationSection section) {
        return new ItemDefinition(section);
    }

    /** A new stack: material, enchantments and everything {@link #applyTo} writes. */
    ItemStack create() {
        ItemStack item = Items.of(material);
        if (item == null) throw new IllegalArgumentException("air cannot be an item: " + material);
        item.editMeta(meta -> enchantments.forEach((id, level) -> {
            Enchantment ench = enchantment(id);
            if (ench != null) meta.addEnchant(ench, level, true);
            else org.bukkit.Bukkit.getLogger().warning("[Flux] Item definition: unknown enchantment " + id);
        }));
        applyTo(item);
        return item;
    }

    /** Writes name, lore, look, attributes and flags on an item; its enchantments, damage and own data are kept. */
    void applyTo(ItemStack item) {
        item.editMeta(this::meta);
        if (model != null) item.setData(DataComponentTypes.ITEM_MODEL, model);
        Equippable eq = item.getData(DataComponentTypes.EQUIPPABLE);
        if (equip && eq != null) item.setData(DataComponentTypes.EQUIPPABLE, eq.toBuilder().assetId(equipment));
        if (!attributes.isEmpty()) writeAttributes(item, eq);
    }

    private void meta(ItemMeta meta) {
        if (name != null) meta.customName(text(name));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) lines.add(text(line));
            meta.lore(Texts.wrapLore(lines));
        }
        if (!flags.isEmpty()) meta.addItemFlags(flags.toArray(ItemFlag[]::new));
        if (durability > 0 && meta instanceof Damageable d) d.setMaxDamage(durability);
        meta.setUnbreakable(unbreakable);
        if (glow != null) meta.setEnchantmentGlintOverride(glow);
    }

    private void writeAttributes(ItemStack item, Equippable eq) {
        EquipmentSlotGroup slot = eq == null ? EquipmentSlotGroup.ANY : eq.slot().getGroup();
        item.editMeta(meta -> {
            meta.setAttributeModifiers(null);
            attributes.forEach((id, value) -> {
                Attribute attribute = RegistryAccess.registryAccess().getRegistry(RegistryKey.ATTRIBUTE).get(NamespacedKey.minecraft(id));
                if (attribute == null) return;
                meta.addAttributeModifier(attribute, new AttributeModifier(new NamespacedKey("flux", "item_" + id + "_" + slot.toString().toLowerCase(Locale.ROOT)),
                        value, AttributeModifier.Operation.ADD_NUMBER, slot));
            });
        });
    }

    private static Component text(String line) {
        return Texts.colors(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static Key key(String s) {
        return s == null || s.isBlank() ? null : Key.key(s);
    }

    /** Vanilla or custom enchantment (EcoEnchants registers its own in the {@code minecraft} namespace). */
    private static Enchantment enchantment(String id) {
        NamespacedKey key = id.contains(":") ? NamespacedKey.fromString(id) : NamespacedKey.minecraft(id);
        return key == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);
    }
}
