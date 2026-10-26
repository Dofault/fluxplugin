package fr.influencraft.influoffrande.model;

import fr.influencraft.influoffrande.integration.ItemsAdderHook;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Identifies a required item in a collection. Either a vanilla {@link Material},
 * an ItemsAdder namespaced id ("namespace:id"), or a vanilla enchantment ("enchant:sharpness").
 * <p>
 * Config syntax: a plain material name ("DIAMOND") is vanilla; a key starting with
 * {@value #ENCHANT_PREFIX} is an enchantment requirement, counted in levels (a Sharpness V
 * enchanted book adds 5); any other key containing a colon ("influitems:mana_crystal") is
 * treated as an ItemsAdder item.
 */
public final class ItemKey {

    public static final String ENCHANT_PREFIX = "enchant:";

    private final boolean itemsAdder;
    private final Material material;
    private final String namespacedId;
    private final Enchantment enchantment;
    private final String raw;

    private ItemKey(boolean itemsAdder, Material material, String namespacedId, Enchantment enchantment, String raw) {
        this.itemsAdder = itemsAdder;
        this.material = material;
        this.namespacedId = namespacedId;
        this.enchantment = enchantment;
        this.raw = raw;
    }

    public static ItemKey ofEnchantment(Enchantment enchantment) {
        NamespacedKey nsKey = enchantment.getKey();
        String id = NamespacedKey.MINECRAFT.equals(nsKey.getNamespace()) ? nsKey.getKey() : nsKey.toString();
        return new ItemKey(false, Material.ENCHANTED_BOOK, null, enchantment, ENCHANT_PREFIX + id);
    }

    /**
     * @throws IllegalArgumentException if the key is neither a valid Material nor a
     *                                  parsable "namespace:id" string.
     */
    public static ItemKey parse(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Empty item key");
        }
        String trimmed = key.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith(ENCHANT_PREFIX)) {
            String enchantId = trimmed.substring(ENCHANT_PREFIX.length()).toLowerCase(Locale.ROOT);
            NamespacedKey nsKey = NamespacedKey.fromString(enchantId);
            Enchantment enchant = nsKey == null ? null
                    : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(nsKey);
            if (enchant == null) {
                throw new IllegalArgumentException("Unknown enchantment: " + enchantId);
            }
            return ofEnchantment(enchant);
        }
        if (trimmed.contains(":")) {
            String normalized = trimmed.toLowerCase(Locale.ROOT);
            return new ItemKey(true, null, normalized, null, normalized);
        }
        Material material = Material.matchMaterial(trimmed.toUpperCase(Locale.ROOT));
        if (material == null || material.isAir()) {
            throw new IllegalArgumentException("Unknown material: " + trimmed);
        }
        return new ItemKey(false, material, null, null, material.name());
    }

    public boolean isItemsAdder() {
        return itemsAdder;
    }

    /**
     * @return true if this requirement is counted in enchantment levels, deposited as
     * enchanted books, rather than in item units.
     */
    public boolean isEnchantment() {
        return enchantment != null;
    }

    public Enchantment enchantment() {
        return enchantment;
    }

    /**
     * How many units one stack contributes toward this requirement: the level of the
     * requested enchantment stored in an enchanted book (per book), or 1 per item otherwise.
     * Only meaningful for a stack that {@link #matches}.
     */
    public int unitsPerItem(ItemStack stack) {
        if (enchantment == null) {
            return 1;
        }
        return stack.getItemMeta() instanceof EnchantmentStorageMeta meta ? meta.getStoredEnchantLevel(enchantment) : 0;
    }

    public Material material() {
        return material;
    }

    public String namespacedId() {
        return namespacedId;
    }

    /**
     * The exact string that should be written back to collections.yml / data files.
     */
    public String raw() {
        return raw;
    }

    public boolean matches(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (enchantment != null) {
            return stack.getType() == Material.ENCHANTED_BOOK
                    && stack.getItemMeta() instanceof EnchantmentStorageMeta meta
                    && meta.hasStoredEnchant(enchantment);
        }
        if (itemsAdder) {
            String id = ItemsAdderHook.getNamespacedId(stack);
            if (namespacedId.equalsIgnoreCase(id)) {
                return hasReferenceEnchantments(stack);
            }
            // Some plugins (e.g. CustomFishing) build their own ItemStack that visually reuses
            // an ItemsAdder model (same Material + CustomModelData, for the resource pack look)
            // without going through ItemsAdder's own item factory — CustomStack.byItemStack()
            // then can't identify it by tag, since the ItemsAdder-internal marker was never
            // written. Fall back to comparing Material + CustomModelData against the configured
            // item's reference stack. This deliberately ignores every other per-instance detail
            // (name, lore, a fish's size, durability...) — same visual item, different catch.
            return matchesByModelData(stack) && hasReferenceEnchantments(stack);
        }
        // A plain vanilla material must not accidentally match a re-skinned ItemsAdder
        // item that happens to share the same base material.
        if (ItemsAdderHook.isEnabled() && ItemsAdderHook.getNamespacedId(stack) != null) {
            return false;
        }
        return stack.getType() == material && hasReferenceEnchantments(stack);
    }

    /**
     * The deposited item must be the very item the offrande asks for, enchantments included:
     * a plain material expects none, an ItemsAdder item exactly those of its definition.
     * Without this, a Diamond Pickaxe requirement swallowed the player's fully enchanted
     * pickaxe (first match in inventory order). EcoEnchants registers its enchantments in the
     * vanilla registry, so {@link ItemStack#getEnchantments()} covers both kinds.
     */
    private boolean hasReferenceEnchantments(ItemStack stack) {
        Map<Enchantment, Integer> expected = Map.of();
        if (itemsAdder) {
            ItemStack reference = ItemsAdderHook.createItemStack(namespacedId);
            if (reference != null) {
                expected = reference.getEnchantments();
            }
        }
        return stack.getEnchantments().equals(expected);
    }

    private boolean matchesByModelData(ItemStack stack) {
        ItemStack reference = ItemsAdderHook.createItemStack(namespacedId);
        if (reference == null || reference.getType() != stack.getType()) {
            return false;
        }
        ItemMeta referenceMeta = reference.getItemMeta();
        ItemMeta stackMeta = stack.getItemMeta();
        if (referenceMeta == null || stackMeta == null
                || !referenceMeta.hasCustomModelData() || !stackMeta.hasCustomModelData()) {
            return false;
        }
        return referenceMeta.getCustomModelData() == stackMeta.getCustomModelData();
    }

    public int maxStackSize() {
        if (itemsAdder) {
            ItemStack sample = ItemsAdderHook.createItemStack(namespacedId);
            return sample != null ? sample.getMaxStackSize() : 64;
        }
        return material.getMaxStackSize();
    }

    /**
     * Builds a representative ItemStack for GUI display, amount clamped to the item's max stack size.
     */
    public ItemStack toDisplayStack(int amount) {
        if (enchantment != null) {
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            if (book.getItemMeta() instanceof EnchantmentStorageMeta meta) {
                meta.addStoredEnchant(enchantment, enchantment.getMaxLevel(), true);
                // The requirement is described in our own name/lore; the vanilla
                // "Sharpness V" tooltip line would only suggest a specific level is expected.
                meta.addItemFlags(ItemFlag.HIDE_STORED_ENCHANTS);
                book.setItemMeta(meta);
            }
            return book;
        }
        int clamped = Math.max(1, Math.min(amount, maxStackSize()));
        if (itemsAdder) {
            ItemStack stack = ItemsAdderHook.createItemStack(namespacedId);
            if (stack == null) {
                stack = new ItemStack(Material.BARRIER);
            } else {
                stack = stack.clone();
            }
            stack.setAmount(clamped);
            return stack;
        }
        return new ItemStack(material, clamped);
    }

    /**
     * Default display name (used when no custom "names:" override exists): a client-translated
     * tag for vanilla items and enchantments, the ItemsAdder display name otherwise.
     */
    public String fallbackDisplayName() {
        if (enchantment != null) {
            return translatableTag(enchantment.translationKey());
        }
        if (itemsAdder) {
            String name = ItemsAdderHook.getDisplayName(namespacedId);
            // ItemsAdder's own configured name commonly carries its own color/format codes.
            // Strip them to plain text: our GUI templates apply their own color around {name},
            // and splicing raw markup in unmodified risks breaking or double-coloring that
            // template (this is what caused literal "&#FFD94DCancel" to show up unparsed).
            return name != null ? fr.influencraft.influoffrande.text.TextUtil.plain(name, null) : namespacedId;
        }
        return translatableTag(material.translationKey());
    }

    /**
     * MiniMessage tag resolved by each player's client in its own language (and resource pack),
     * so a vanilla item/enchantment name follows the player's game settings.
     */
    private static String translatableTag(String translationKey) {
        return "<lang:" + translationKey + ">";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemKey other)) return false;
        return itemsAdder == other.itemsAdder
                && material == other.material
                && Objects.equals(namespacedId, other.namespacedId)
                && Objects.equals(enchantment, other.enchantment);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemsAdder, material, namespacedId, enchantment);
    }

    @Override
    public String toString() {
        return raw;
    }
}
