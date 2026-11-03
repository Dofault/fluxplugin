package fr.influencraft.fluxcommerce.auctionhouse;

import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.text.Translations;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the market knows of an item: its name (for players, translated by each client; for the search index and the
 * history, in the server's language), the key that groups items of the same kind (market price, comparison), its
 * category and its search text.
 */
final class ItemInfo {

    private ItemInfo() {
    }

    // ---------------------------------------------------------------- names

    /**
     * Name of the item for a player: its custom name, else the Minecraft name translated by the player's client
     * ({@code <lang:...>}, kept as is by the suite's text pipeline).
     */
    static String display(ItemStack item) {
        Component custom = customName(item);
        if (custom != null) return MiniMessage.miniMessage().serialize(custom);
        return "<lang:" + translationKey(item) + ">";
    }

    /** The same as a component (chat messages: the item's tooltip on mouse over). */
    static Component component(ItemStack item) {
        Component custom = customName(item);
        Component name = custom != null ? custom : Component.translatable(translationKey(item));
        return name.hoverEvent(item.asHoverEvent());
    }

    private static Component customName(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return null;
        if (meta.hasDisplayName() && meta.displayName() != null) return meta.displayName();
        if (meta.hasItemName()) return meta.itemName();
        return null;
    }

    private static String translationKey(ItemStack item) {
        if (item.getItemMeta() instanceof PotionMeta pm && pm.getBasePotionType() != null) {
            return item.getType().translationKey() + ".effect." + baseEffect(pm);
        }
        return item.getType().translationKey();
    }

    /** Plain name in the server's language (search index, history column, typed search). */
    static String plain(ItemStack item) {
        if (item == null) return "?";
        Component custom = customName(item);
        if (custom != null) return Texts.plain(custom);
        String n = Translations.server(translationKey(item));
        if (n == null) n = Translations.server(item.getType().translationKey());
        return n != null ? n : pretty(item.getType());
    }

    /** "DIAMOND_BLOCK" -> "Diamond block" (fallback). */
    private static String pretty(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Potion type without its long_ / strong_ prefix (names are given for the base effect). */
    private static String baseEffect(PotionMeta pm) {
        return pm.getBasePotionType().getKey().getKey().replaceFirst("^(long|strong)_", "");
    }

    private static String enchantment(Enchantment e) {
        String n = Translations.server("enchantment." + e.getKey().getNamespace() + "." + e.getKey().getKey());
        return n != null ? n : e.getKey().getKey().replace('_', ' ');
    }

    // ---------------------------------------------------------------- grouping key

    /** Custom item id of the server ({@code namespace:id}) or null. */
    static String customId(ItemStack item) {
        try {
            return CustomItems.id(item);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * Key grouping items "of the same kind" for the market price and the comparison: a custom item by its id; an
     * enchanted book by its enchantments; a potion by its type; an enchanted item apart from the bare one; else the
     * material. A custom name does not make another item.
     */
    static String key(ItemStack item) {
        if (item == null) return "air";
        String custom = customId(item);
        if (custom != null) return "ia:" + custom;
        String base = item.getType().getKey().getKey();
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta es && es.hasStoredEnchants()) {
            StringBuilder sb = new StringBuilder(base);
            Map<Enchantment, Integer> sorted = new TreeMap<>((a, b) -> a.getKey().toString().compareTo(b.getKey().toString()));
            sorted.putAll(es.getStoredEnchants());
            for (Map.Entry<Enchantment, Integer> e : sorted.entrySet()) sb.append(':').append(e.getKey().getKey().getKey()).append(e.getValue());
            return sb.toString();
        }
        if (meta instanceof PotionMeta pm && pm.getBasePotionType() != null) return base + ":" + pm.getBasePotionType().getKey().getKey();
        if (meta != null && meta.hasEnchants()) return base + ":enchante";
        return base;
    }

    // ---------------------------------------------------------------- contents (shulker box, bundle)

    /** Items inside a shulker box or a bundle (empty for any other item). */
    static List<ItemStack> contents(ItemStack item) {
        List<ItemStack> out = new ArrayList<>();
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof ShulkerBox box) {
            for (ItemStack s : box.getInventory().getContents()) if (s != null && !s.getType().isAir()) out.add(s.clone());
        } else if (meta instanceof BundleMeta bm) {
            for (ItemStack s : bm.getItems()) if (s != null && !s.getType().isAir()) out.add(s.clone());
        }
        return out;
    }

    // ---------------------------------------------------------------- search

    /** Lower case without accents nor punctuation: "Bûche de chêne" -> "buche de chene". */
    static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("[^a-z0-9: ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Search text of an item: English name (DIAMOND) and name in the server's language, or custom name and id; enchantments, category. */
    static String index(ItemStack item) {
        StringBuilder sb = new StringBuilder();
        String custom = customId(item);
        sb.append(plain(item)).append(' ');
        if (custom != null) {
            // a custom item (ItemsAdder...) is found by its name and id, never by the material it is drawn on (paper...)
            sb.append(custom).append(' ').append(custom.replace(':', ' ')).append(' ');
        } else {
            sb.append(item.getType().name()).append(' ');
            String vanilla = Translations.server(item.getType().translationKey());
            if (vanilla != null) sb.append(vanilla).append(' ');
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            for (Enchantment e : meta.getEnchants().keySet()) sb.append(enchantment(e)).append(' ').append(e.getKey().getKey()).append(' ');
            if (meta instanceof EnchantmentStorageMeta es) {
                for (Enchantment e : es.getStoredEnchants().keySet()) sb.append(enchantment(e)).append(' ').append(e.getKey().getKey()).append(' ');
            }
        }
        // category words ("outils", "nourriture"...): the search replaces the former category filter (2026-11-01)
        sb.append(label(category(item))).append(' ');
        return normalize(sb.toString());
    }

    /** Whether every word of the search is in the index. */
    static boolean matches(String index, String search) {
        if (search == null || search.isBlank()) return true;
        for (String word : normalize(search).split(" ")) {
            if (!word.isEmpty() && !index.contains(word)) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- categories (search words)

    enum Category { BUILDING, RESOURCES, TOOLS, COMBAT, FOOD, MAGIC, UNIQUE, MISC }

    /** Words players search a category with ({@code categories.<name>} of the lang file). */
    private static volatile Map<Category, String> labels = Map.of();

    static void labels(java.util.function.Function<Category, String> label) {
        Map<Category, String> m = new EnumMap<>(Category.class);
        for (Category c : Category.values()) m.put(c, label.apply(c));
        labels = m;
    }

    static String label(Category c) {
        return labels.getOrDefault(c, c.name().toLowerCase(Locale.ROOT));
    }

    private static final Map<Material, Category> CACHE = new EnumMap<>(Material.class);

    static Category category(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        // custom item (ItemsAdder, a plugin's item with a name or a description): one of a kind
        if (customId(item) != null || meta != null && (meta.hasCustomModelDataComponent() || meta.hasLore() && !meta.hasEnchants())) {
            return Category.UNIQUE;
        }
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(item.getType(), ItemInfo::categoryOf);
        }
    }

    private static Category categoryOf(Material m) {
        String n = m.name();
        if (n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.endsWith("_AXE")
                || n.matches("SHEARS|FISHING_ROD|FLINT_AND_STEEL|BRUSH|SPYGLASS|COMPASS|RECOVERY_COMPASS|CLOCK|LEAD|NAME_TAG|BUCKET|.*_BUCKET|CARROT_ON_A_STICK|WARPED_FUNGUS_ON_A_STICK")) {
            return Category.TOOLS;
        }
        if (n.endsWith("_SWORD") || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.endsWith("_HORSE_ARMOR") || n.endsWith("ARROW") || n.matches("BOW|CROSSBOW|TRIDENT|MACE|SHIELD|ELYTRA|TOTEM_OF_UNDYING|TURTLE_HELMET|WOLF_ARMOR")) {
            return Category.COMBAT;
        }
        if (n.matches(".*POTION|ENCHANTED_BOOK|EXPERIENCE_BOTTLE|BREWING_STAND|BLAZE_POWDER|BLAZE_ROD|NETHER_WART|GHAST_TEAR|"
                + "FERMENTED_SPIDER_EYE|MAGMA_CREAM|GLISTERING_MELON_SLICE|DRAGON_BREATH|PHANTOM_MEMBRANE|RABBIT_FOOT|ENCHANTING_TABLE|"
                + "ENDER_EYE|BOOK|WRITABLE_BOOK|GLASS_BOTTLE|BREEZE_ROD|OMINOUS_BOTTLE")) {
            return Category.MAGIC;
        }
        if (m.isEdible() || n.endsWith("_SEEDS") || n.endsWith("_SAPLING") || n.endsWith("_PROPAGULE")
                || n.matches("WHEAT|SUGAR_CANE|BAMBOO|CACTUS|COCOA_BEANS|PUMPKIN|MELON|NETHER_WART_BLOCK|HAY_BLOCK|EGG|MILK_BUCKET|SUGAR|BONE_MEAL|KELP|SWEET_BERRIES|PITCHER_POD|TORCHFLOWER_SEEDS")) {
            return Category.FOOD;
        }
        if (n.endsWith("_INGOT") || n.endsWith("_NUGGET") || n.startsWith("RAW_") || n.endsWith("_ORE") || n.endsWith("_SCRAP")
                || n.matches("DIAMOND|EMERALD|COAL|CHARCOAL|REDSTONE|LAPIS_LAZULI|QUARTZ|AMETHYST_SHARD|STRING|LEATHER|FEATHER|BONE|"
                + "GUNPOWDER|SLIME_BALL|ENDER_PEARL|PRISMARINE_SHARD|PRISMARINE_CRYSTALS|NETHER_STAR|SHULKER_SHELL|HONEYCOMB|INK_SAC|"
                + "GLOW_INK_SAC|CLAY_BALL|FLINT|GLOWSTONE_DUST|ECHO_SHARD|HEART_OF_THE_SEA|NAUTILUS_SHELL|ARMADILLO_SCUTE|TURTLE_SCUTE|"
                + "DIAMOND_BLOCK|IRON_BLOCK|GOLD_BLOCK|EMERALD_BLOCK|NETHERITE_BLOCK|COPPER_BLOCK|COAL_BLOCK|REDSTONE_BLOCK|LAPIS_BLOCK|RAW_.*_BLOCK")) {
            return Category.RESOURCES;
        }
        if (m.isBlock()) return Category.BUILDING;
        return Category.MISC;
    }
}
