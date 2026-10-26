package fr.influencraft.influmarche;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tout ce qui décrit un objet pour le marché : nom français, clé de regroupement (prix moyen,
 * comparaison des offres), catégorie et texte de recherche.
 *
 * <p>Noms français : {@code noms_fr.json} (clés {@code item.minecraft.*}, {@code block.minecraft.*},
 * enchantements, potions), extrait du {@code fr_fr.json} d'InteractiveChat (Minecraft 26.2). Le
 * serveur n'a que l'anglais : sans ce fichier, « diamant » ne trouverait pas le diamant.</p>
 */
final class Objets {

    private static Map<String, String> fr = Map.of();

    private Objets() {
    }

    static void charger(InfluMarche plugin) {
        try (InputStream in = plugin.getResource("noms_fr.json")) {
            if (in == null) return;
            fr = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() {
            }.getType());
        } catch (Exception e) {
            plugin.getLogger().warning("noms_fr.json illisible : recherche en anglais seulement (" + e + ")");
        }
    }

    // ── Noms ─────────────────────────────────────────────────────────────────

    static String texte(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** Nom de l'objet en français, sans couleurs : nom personnalisé, nom d'objet ItemsAdder, sinon nom Minecraft. */
    static String nom(ItemStack item) {
        if (item == null) return "?";
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) return texte(meta.displayName());
        if (meta != null && meta.hasItemName()) return texte(meta.itemName());
        if (meta instanceof PotionMeta pm && pm.getBasePotionType() != null) {
            String p = fr.get(item.getType().translationKey() + ".effect." + baseEffet(pm.getBasePotionType()));
            if (p != null) return p;
        }
        String n = fr.get(item.getType().translationKey());
        return n != null ? n : joli(item.getType());
    }

    /** « DIAMOND_BLOCK » -> « Diamond block » (repli). */
    static String joli(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Type de potion sans préfixe long_/strong_ (les noms français sont donnés pour l'effet de base). */
    private static String baseEffet(PotionType t) {
        String k = t.getKey().getKey();
        return k.replaceFirst("^(long|strong)_", "");
    }

    static String enchantement(Enchantment e) {
        String n = fr.get("enchantment." + e.getKey().getNamespace() + "." + e.getKey().getKey());
        return n != null ? n : e.getKey().getKey().replace('_', ' ');
    }

    // ── Clé de regroupement ─────────────────────────────────────────────────

    /** Identifiant ItemsAdder (« ns:id ») ou null. */
    static String itemsAdder(ItemStack item) {
        if (item == null || !Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return null;
        try {
            dev.lone.itemsadder.api.CustomStack cs = dev.lone.itemsadder.api.CustomStack.byItemStack(item);
            return cs == null ? null : cs.getNamespacedID();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Clé qui regroupe les objets « de même nature » pour le prix moyen et la comparaison :
     * objet ItemsAdder par son identifiant ; livre enchanté par ses enchantements ; potion par son
     * type ; objet enchanté (outil, arme...) à part de sa version sans enchantement ; sinon le
     * matériau. Un nom personnalisé n'en fait pas un autre objet.
     */
    static String cle(ItemStack item) {
        if (item == null) return "air";
        String ia = itemsAdder(item);
        if (ia != null) return "ia:" + ia;
        String base = item.getType().getKey().getKey();
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta es && es.hasStoredEnchants()) {
            StringBuilder sb = new StringBuilder(base);
            for (Map.Entry<Enchantment, Integer> e : new TreeMap<>(cleEnchants(es.getStoredEnchants())).entrySet()) {
                sb.append(':').append(e.getKey().getKey().getKey()).append(e.getValue());
            }
            return sb.toString();
        }
        if (meta instanceof PotionMeta pm && pm.getBasePotionType() != null) return base + ":" + pm.getBasePotionType().getKey().getKey();
        if (meta != null && meta.hasEnchants()) return base + ":enchante";
        return base;
    }

    private static Map<Enchantment, Integer> cleEnchants(Map<Enchantment, Integer> m) {
        Map<Enchantment, Integer> t = new TreeMap<>((a, b) -> a.getKey().toString().compareTo(b.getKey().toString()));
        t.putAll(m);
        return t;
    }

    // ── Recherche ───────────────────────────────────────────────────────────

    /** Minuscules sans accents ni ponctuation superflue : « Bûche de chêne » -> « buche de chene ». */
    static String normaliser(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("[^a-z0-9: ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Texte de recherche d'un objet : nom anglais (DIAMOND), nom français, nom affiché, ItemsAdder, enchantements. */
    static String index(ItemStack item) {
        StringBuilder sb = new StringBuilder();
        sb.append(item.getType().name()).append(' ').append(nom(item)).append(' ');
        String vanilla = fr.get(item.getType().translationKey());
        if (vanilla != null) sb.append(vanilla).append(' ');
        String ia = itemsAdder(item);
        if (ia != null) sb.append(ia).append(' ');
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            for (Enchantment e : meta.getEnchants().keySet()) sb.append(enchantement(e)).append(' ').append(e.getKey().getKey()).append(' ');
            if (meta instanceof EnchantmentStorageMeta es) {
                for (Enchantment e : es.getStoredEnchants().keySet()) sb.append(enchantement(e)).append(' ').append(e.getKey().getKey()).append(' ');
            }
        }
        return normaliser(sb.toString());
    }

    /** Vrai si tous les mots de la recherche apparaissent dans l'index. */
    static boolean correspond(String index, String recherche) {
        if (recherche == null || recherche.isBlank()) return true;
        for (String mot : normaliser(recherche).split(" ")) {
            if (!mot.isEmpty() && !index.contains(mot)) return false;
        }
        return true;
    }

    // ── Catégories ──────────────────────────────────────────────────────────

    enum Categorie {
        TOUT("Tout", "CHEST"),
        CONSTRUCTION("Construction", "BRICKS"),
        RESSOURCES("Ressources", "IRON_INGOT"),
        OUTILS("Outils", "DIAMOND_PICKAXE"),
        COMBAT("Combat et armures", "DIAMOND_SWORD"),
        NOURRITURE("Nourriture et cultures", "BREAD"),
        MAGIE("Alchimie et magie", "ENCHANTED_BOOK"),
        UNIQUES("Objets uniques", "NETHER_STAR"),
        DIVERS("Divers", "BUNDLE");

        final String nom, icone;

        Categorie(String nom, String icone) {
            this.nom = nom;
            this.icone = icone;
        }
    }

    private static final Map<Material, Categorie> CACHE = new HashMap<>();

    static Categorie categorie(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        // Objet personnalisé (ItemsAdder, objet de plugin nommé ou décrit) : rangé dans les uniques.
        if (itemsAdder(item) != null || meta != null && (meta.hasCustomModelDataComponent() || meta.hasLore() && !meta.hasEnchants())) {
            return Categorie.UNIQUES;
        }
        return CACHE.computeIfAbsent(item.getType(), Objets::categorieMateriau);
    }

    private static Categorie categorieMateriau(Material m) {
        String n = m.name();
        if (n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.endsWith("_AXE")
                || n.matches("SHEARS|FISHING_ROD|FLINT_AND_STEEL|BRUSH|SPYGLASS|COMPASS|RECOVERY_COMPASS|CLOCK|LEAD|NAME_TAG|BUCKET|.*_BUCKET|CARROT_ON_A_STICK|WARPED_FUNGUS_ON_A_STICK")) {
            return Categorie.OUTILS;
        }
        if (n.endsWith("_SWORD") || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.endsWith("_HORSE_ARMOR") || n.endsWith("ARROW") || n.matches("BOW|CROSSBOW|TRIDENT|MACE|SHIELD|ELYTRA|TOTEM_OF_UNDYING|TURTLE_HELMET|WOLF_ARMOR")) {
            return Categorie.COMBAT;
        }
        if (n.matches(".*POTION|ENCHANTED_BOOK|EXPERIENCE_BOTTLE|BREWING_STAND|BLAZE_POWDER|BLAZE_ROD|NETHER_WART|GHAST_TEAR|"
                + "FERMENTED_SPIDER_EYE|MAGMA_CREAM|GLISTERING_MELON_SLICE|DRAGON_BREATH|PHANTOM_MEMBRANE|RABBIT_FOOT|ENCHANTING_TABLE|"
                + "ENDER_EYE|BOOK|WRITABLE_BOOK|GLASS_BOTTLE|BREEZE_ROD|OMINOUS_BOTTLE")) {
            return Categorie.MAGIE;
        }
        if (m.isEdible() || n.endsWith("_SEEDS") || n.endsWith("_SAPLING") || n.endsWith("_PROPAGULE")
                || n.matches("WHEAT|SUGAR_CANE|BAMBOO|CACTUS|COCOA_BEANS|PUMPKIN|MELON|NETHER_WART_BLOCK|HAY_BLOCK|EGG|MILK_BUCKET|SUGAR|BONE_MEAL|KELP|SWEET_BERRIES|PITCHER_POD|TORCHFLOWER_SEEDS")) {
            return Categorie.NOURRITURE;
        }
        if (n.endsWith("_INGOT") || n.endsWith("_NUGGET") || n.startsWith("RAW_") || n.endsWith("_ORE") || n.endsWith("_SCRAP")
                || n.matches("DIAMOND|EMERALD|COAL|CHARCOAL|REDSTONE|LAPIS_LAZULI|QUARTZ|AMETHYST_SHARD|STRING|LEATHER|FEATHER|BONE|"
                + "GUNPOWDER|SLIME_BALL|ENDER_PEARL|PRISMARINE_SHARD|PRISMARINE_CRYSTALS|NETHER_STAR|SHULKER_SHELL|HONEYCOMB|INK_SAC|"
                + "GLOW_INK_SAC|CLAY_BALL|FLINT|GLOWSTONE_DUST|ECHO_SHARD|HEART_OF_THE_SEA|NAUTILUS_SHELL|ARMADILLO_SCUTE|TURTLE_SCUTE|"
                + ".*_BLOCK_OF_.*|DIAMOND_BLOCK|IRON_BLOCK|GOLD_BLOCK|EMERALD_BLOCK|NETHERITE_BLOCK|COPPER_BLOCK|COAL_BLOCK|REDSTONE_BLOCK|LAPIS_BLOCK|RAW_.*_BLOCK")) {
            return Categorie.RESSOURCES;
        }
        if (m.isBlock()) return Categorie.CONSTRUCTION;
        return Categorie.DIVERS;
    }
}
