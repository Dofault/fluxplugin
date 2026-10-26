package io.dofault.supermarket.integration;

import net.md_5.bungee.api.ChatColor;
import net.momirealms.customfishing.api.BukkitCustomFishingPlugin;
import net.momirealms.customfishing.api.mechanic.context.Context;
import net.momirealms.customfishing.api.mechanic.item.ItemManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fine wrapper around the CustomFishing API. Every call is guarded so the plugin keeps
 * working when CustomFishing is absent (it is only a soft dependency).
 * <p>
 * CustomFishing bakes the exact sell price of a caught fish into a "Price" NBT tag on the
 * item at loot-generation time (evaluated from that fish's {@code price: {base, bonus}} via
 * {@code mechanics.market.price-formula} in CustomFishing/config.yml) — reading that tag back
 * is what guarantees Vault Supermarket always sells fish for exactly the same amount as
 * CustomFishing's own market would have, without duplicating their price table anywhere.
 * Deliberately reads the tag directly instead of calling {@code MarketManager.getItemPrice()},
 * which also falls back to a static per-Material price map (plain COD/SALMON/... at a flat
 * price) meant for CustomFishing's own market — that fallback would silently override Vault
 * Supermarket's own (much lower) prices.yml entries for plain vanilla fish.
 * <p>
 * Uses {@code ItemManager.buildInternal(context, id)} to fetch a CustomFishing item by its
 * own config id (looked up in its internal {@code items} registry, e.g. "common_tuna_fish").
 * {@code buildAny(context, item)} is a different, easily-confused method: it treats its
 * string argument as either a bare vanilla {@code Material} name or a {@code "provider:id"}
 * reference (e.g. {@code itemsadder:some_item}) for a THIRD-PARTY plugin item — never as a
 * CustomFishing config id — and silently falls back to a blank {@code ItemStack(PAPER)} when
 * neither resolves (confirmed by decompiling {@code BukkitItemManager} in
 * CustomFishing-2.3.24.jar; no exception, no log line on CustomFishing's side either).
 */
public final class CustomFishingHook {

    private static final long FISH_CACHE_TTL_MILLIS = 5 * 60 * 1000L;
    private static volatile Map<String, ItemStack> cachedFishSamples = null;
    private static volatile long cachedAt = 0L;

    /**
     * Un item représentatif par palier de rareté plutôt que les ~85 entrées réellement
     * enregistrées (17 espèces × 5 paliers) dans CustomFishing/contents/item/influfish.yml :
     * les prix y sont volontairement uniformisés par palier (même {@code price: {base, bonus}}
     * pour toutes les espèces d'un palier donné, voir l'en-tête de ce fichier), donc n'importe
     * quelle espèce affiche le même prix — le thon (premier groupe du fichier) sert juste de
     * vitrine. fabled_rainbow_fish reste hors grille (prix distinct, plus élevé).
     */
    private record FishTier(String itemId, String label, String hexColor) {
        /** Palier = préfixe de l'id CustomFishing ("common_tuna_fish" -> "common"). */
        String tier() {
            return tierOf(itemId);
        }
    }

    private static String tierOf(String itemId) {
        int underscore = itemId.indexOf('_');
        return underscore > 0 ? itemId.substring(0, underscore) : itemId;
    }

    private static final List<FishTier> FISH_TIERS = List.of(
            new FishTier("common_tuna_fish", "Poisson Commun", "AAAAAA"),
            new FishTier("unique_tuna_fish", "Poisson Unique", "37C44A"),
            new FishTier("epic_tuna_fish", "Poisson Épique", "3283A8"),
            new FishTier("mythic_tuna_fish", "Poisson Mythique", "B376E5"),
            new FishTier("legendary_tuna_fish", "Poisson Légendaire", "F0A800"),
            new FishTier("fabled_rainbow_fish", "Poisson Arc-en-Ciel", "FFD94D"));

    private CustomFishingHook() {
    }

    public static boolean isEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("CustomFishing");
    }

    private static BukkitCustomFishingPlugin plugin() {
        return isEnabled() ? BukkitCustomFishingPlugin.getInstance() : null;
    }

    /**
     * @return the total price (already multiplied by the stack's amount) CustomFishing baked
     * into this item at catch time, or 0 if it isn't a priced CustomFishing item.
     */
    public static double getPrice(ItemStack stack) {
        BukkitCustomFishingPlugin cf = plugin();
        if (cf == null || stack == null || stack.getType().isAir()) {
            return 0.0;
        }
        try {
            Object tag = cf.getItemManager().wrap(stack).getTag("Price").orElse(null);
            if (!(tag instanceof Number number)) {
                return 0.0;
            }
            double unitPrice = number.doubleValue();
            return unitPrice > 0 ? unitPrice * stack.getAmount() : 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    public static boolean isCustomFishingItem(ItemStack stack) {
        BukkitCustomFishingPlugin cf = plugin();
        if (cf == null || stack == null) {
            return false;
        }
        try {
            return cf.getItemManager().getCustomFishingItemID(stack) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Les paliers de rareté connus, dans l'ordre croissant ("common" ... "fabled"). */
    public static List<String> getFishTiers() {
        return FISH_TIERS.stream().map(FishTier::tier).toList();
    }

    /**
     * @return le palier de rareté ("common", "unique", ...) de ce poisson CustomFishing, ou
     * {@code null} si ce n'est pas un poisson d'un des {@link #FISH_TIERS} (déchet, objet de
     * default.yml, item non CustomFishing).
     */
    public static String getFishTier(ItemStack stack) {
        BukkitCustomFishingPlugin cf = plugin();
        if (cf == null || stack == null) {
            return null;
        }
        try {
            String id = cf.getItemManager().getCustomFishingItemID(stack);
            if (id == null) {
                return null;
            }
            String tier = tierOf(id);
            return getFishTiers().contains(tier) ? tier : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Un ItemStack par palier de rareté ({@link #FISH_TIERS}), nom d'affichage "Poisson
     * &lt;Rareté&gt;" coloré et prix baké par CustomFishing, pour le catalogue de prix de
     * Vault Supermarket. Cache {@value #FISH_CACHE_TTL_MILLIS}ms.
     */
    public static List<ItemStack> getSellableFishSamples(Player context) {
        return new ArrayList<>(getSellableFishSamplesByTier(context).values());
    }

    /** Comme {@link #getSellableFishSamples}, indexé par palier ({@link #getFishTier}). */
    public static Map<String, ItemStack> getSellableFishSamplesByTier(Player context) {
        BukkitCustomFishingPlugin cf = plugin();
        if (cf == null) {
            return Map.of();
        }
        long now = System.currentTimeMillis();
        Map<String, ItemStack> cached = cachedFishSamples;
        if (cached != null && now - cachedAt < FISH_CACHE_TTL_MILLIS) {
            return cached;
        }
        Map<String, ItemStack> samples = new LinkedHashMap<>();
        try {
            ItemManager itemManager = cf.getItemManager();
            Context<Player> ctx = Context.player(context);
            for (FishTier tier : FISH_TIERS) {
                ItemStack sample;
                try {
                    sample = itemManager.buildInternal(ctx, tier.itemId());
                } catch (Throwable t) {
                    continue;
                }
                if (sample == null || sample.getType().isAir() || getPrice(sample) <= 0) {
                    continue;
                }
                ItemMeta meta = sample.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ChatColor.of("#" + tier.hexColor()) + tier.label());
                    sample.setItemMeta(meta);
                }
                samples.put(tier.tier(), sample);
            }
        } catch (Throwable t) {
            return cached != null ? cached : Map.of();
        }
        cachedFishSamples = samples;
        cachedAt = now;
        return samples;
    }
}
