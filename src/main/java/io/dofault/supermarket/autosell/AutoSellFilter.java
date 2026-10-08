package io.dofault.supermarket.autosell;

import io.dofault.supermarket.integration.CustomFishingHook;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Décide si un item a le droit d'être vendu par la vente automatique.
 * <p>
 * La vente auto est faite pour les <em>ressources</em> ramassées en farmant (minerais, blocs,
 * cultures). Tout ce qui est un équipement ou un objet rare passe au travers et reste dans
 * l'inventaire : un joueur qui reçoit le stuff d'un copain (armure/outils en netherite, canne
 * custom, poisson rare) ne doit jamais le voir disparaître parce qu'il avait oublié son objet
 * de vente auto activé. Le joueur garde le droit de vendre ces objets, mais manuellement, au
 * bloc de vente du magasin — la vente manuelle n'utilise donc PAS ce filtre.
 * <p>
 * Deux niveaux :
 * <ul>
 * <li><b>Exceptions</b> : une liste de clés (nom de matériau, ou {@code fish:<palier>} pour un
 * palier de rareté de poisson CustomFishing) que le joueur personnalise dans
 * {@link AutoSellMenu}. Tant qu'il n'y a pas touché, il a les exceptions par défaut
 * ({@link #getDefaultExceptions()}) : la liste {@code autosell.exclusions.materials}, tout
 * l'équipement réparable (si {@code damageable: true}) et les paliers de poissons de
 * {@code fish-tiers}.</li>
 * <li><b>Garde-fous</b>, jamais personnalisables : items enchantés et items « façonnés » par
 * un plugin, dont la valeur réelle n'a rien à voir avec le prix de leur matériau ; déchets et
 * objets CustomFishing hors paliers de poissons.</li>
 * </ul>
 */
public final class AutoSellFilter {

    /**
     * Repli si la section de config est absente : rares (ou uniques) et réellement présents
     * dans prices.yml, plus quelques items qui pourraient y être ajoutés un jour et qu'on ne
     * voudrait jamais voir partir en vente auto.
     */
    private static final List<String> DEFAULT_EXCLUDED_MATERIALS = List.of(
            // Netherite : jamais une "ressource ramassée", toujours du stuff
            "NETHERITE_INGOT", "NETHERITE_SCRAP", "NETHERITE_BLOCK", "ANCIENT_DEBRIS",
            "NETHERITE_UPGRADE_SMITHING_TEMPLATE",
            // Poissons : se vendent au magasin, jamais à la volée en pêchant
            "COD", "SALMON", "TROPICAL_FISH", "PUFFERFISH", "COOKED_COD", "COOKED_SALMON",
            "COD_BUCKET", "SALMON_BUCKET", "TROPICAL_FISH_BUCKET", "PUFFERFISH_BUCKET",
            "AXOLOTL_BUCKET", "TADPOLE_BUCKET",
            // Butins exceptionnels d'un monde ressource / d'un boss
            "NETHER_STAR", "TOTEM_OF_UNDYING", "SHULKER_SHELL", "ELYTRA", "DRAGON_EGG",
            "DRAGON_HEAD", "WITHER_SKELETON_SKULL", "HEAVY_CORE", "ECHO_SHARD",
            "ENCHANTED_GOLDEN_APPLE", "HEART_OF_THE_SEA", "NAUTILUS_SHELL", "BEACON",
            "CONDUIT", "END_CRYSTAL", "TRIAL_KEY", "OMINOUS_TRIAL_KEY", "SNIFFER_EGG",
            // Objets uniques d'apprivoisement / de monture
            "SADDLE", "NAME_TAG");

    /** Préfixe des clés d'exception des paliers de poissons CustomFishing. */
    public static final String FISH_KEY_PREFIX = "fish:";

    private final JavaPlugin plugin;

    private boolean excludeEnchanted;
    private boolean excludeCustom;
    private Set<String> defaultExceptions;
    private AutoSellExceptionManager exceptions;

    public AutoSellFilter(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("autosell.exclusions");
        boolean excludeDamageable = section == null || section.getBoolean("damageable", true);
        this.excludeEnchanted = section == null || section.getBoolean("enchanted", true);
        this.excludeCustom = section == null || section.getBoolean("custom-items", true);

        List<String> names = section == null
                ? DEFAULT_EXCLUDED_MATERIALS
                : section.getStringList("materials");
        if (names.isEmpty()) {
            names = DEFAULT_EXCLUDED_MATERIALS;
        }

        Set<String> keys = new LinkedHashSet<>();
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                plugin.getLogger().warning("autosell.exclusions.materials : matériau inconnu « " + name + " », ignoré.");
                continue;
            }
            keys.add(material.name());
        }

        // Armures, outils, armes, arcs/arbalètes, boucliers, élytres, cannes à pêche : de
        // l'équipement, pas une ressource. Exception par défaut, que le joueur peut lever.
        if (excludeDamageable) {
            for (Material material : Material.values()) {
                if (!material.isLegacy() && material.isItem() && material.getMaxDurability() > 0) {
                    keys.add(material.name());
                }
            }
        }

        List<String> tiers = section == null || !section.isList("fish-tiers")
                ? CustomFishingHook.getFishTiers()
                : section.getStringList("fish-tiers");
        for (String tier : tiers) {
            keys.add(FISH_KEY_PREFIX + tier.toLowerCase());
        }

        this.defaultExceptions = Collections.unmodifiableSet(keys);
    }

    /** Branché après construction : le gestionnaire d'exceptions dépend lui-même du filtre. */
    public void setExceptionManager(AutoSellExceptionManager exceptions) {
        this.exceptions = exceptions;
    }

    /** Les exceptions par défaut, celles du serveur. */
    public Set<String> getDefaultExceptions() {
        return defaultExceptions;
    }

    /**
     * @return {@code true} si cet item peut partir en vente automatique pour ce joueur, selon
     * ses exceptions.
     */
    public boolean isAutoSellable(Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        Set<String> playerExceptions = exceptions == null
                ? defaultExceptions
                : exceptions.getExceptions(player.getUniqueId());

        // Un poisson CustomFishing porte son prix en NBT (et un nom/lore custom, qui le ferait
        // tomber dans le garde-fou « façonné » plus bas) : il est donc décidé ici, par palier
        // de rareté. Tout autre objet CustomFishing (déchet...) reste bloqué.
        if (CustomFishingHook.getPrice(stack) > 0 || CustomFishingHook.isCustomFishingItem(stack)) {
            String tier = CustomFishingHook.getFishTier(stack);
            return tier != null && !playerExceptions.contains(FISH_KEY_PREFIX + tier);
        }

        if (playerExceptions.contains(stack.getType().name())) {
            return false;
        }

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return true;
        }

        if (excludeEnchanted
                && (meta.hasEnchants() || (meta instanceof EnchantmentStorageMeta storage && storage.hasStoredEnchants()))) {
            return false;
        }

        // Tout item "façonné" par un plugin (ItemsAdder, AdvancedEnchantments, EcoEnchants,
        // récompense de caisse, item de quête...) porte au moins un de ces marqueurs. Une
        // ressource brute ramassée au sol n'en porte jamais aucun.
        if (excludeCustom
                && (meta.hasCustomModelData() || meta.hasDisplayName() || meta.hasLore()
                        || !meta.getPersistentDataContainer().isEmpty())) {
            return false;
        }

        return true;
    }
}
