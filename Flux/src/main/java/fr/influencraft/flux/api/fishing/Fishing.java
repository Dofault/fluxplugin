package fr.influencraft.flux.api.fishing;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom fishing of the server, whatever plugin runs it (CustomFishing today, {@code providers.fishing}): fish
 * prices, rarity tiers (section {@code fishing.tiers} of Flux's config) and custom rod durability. Plugins of the
 * suite never call that plugin directly.
 */
public final class Fishing {

    /** What an adapter provides. */
    public interface Provider {
        String name();

        /** Id of the custom fishing item, or null. */
        String id(ItemStack item);

        /** Unit price written on a caught fish, 0 when none. */
        double unitPrice(ItemStack item);

        /** A new item of this id for the player (null when unknown). */
        ItemStack build(Player player, String id);

        boolean hasCustomDurability(ItemStack item);

        boolean damaged(ItemStack item);

        boolean repair(Player player, ItemStack item);
    }

    /** A rarity tier: its id prefix, a sample item id, the label and colour shown in menus. */
    private record Tier(String id, String sample, String label, String color) {
    }

    private static volatile Provider provider;
    private static volatile List<Tier> tiers = List.of();
    private static final long CACHE_MS = 5 * 60 * 1000L;
    private static volatile Map<String, ItemStack> samples;
    private static volatile long samplesAt;

    private Fishing() {
    }

    /** Called by Flux. */
    public static void use(Provider p, ConfigurationSection tierSection) {
        provider = p;
        List<Tier> list = new ArrayList<>();
        if (tierSection != null) {
            for (String id : tierSection.getKeys(false)) {
                ConfigurationSection t = tierSection.getConfigurationSection(id);
                if (t != null) list.add(new Tier(id, t.getString("sample", ""), t.getString("label", id), t.getString("color", "&f")));
            }
        }
        tiers = List.copyOf(list);
        samples = null;
    }

    public static boolean available() {
        return provider != null;
    }

    public static String providerName() {
        Provider p = provider;
        return p == null ? "none" : p.name();
    }

    /** Total price of a caught fish (unit price x amount), 0 when it has none. */
    public static double price(ItemStack item) {
        Provider p = provider;
        if (p == null || item == null || item.getType().isAir()) return 0;
        try {
            double unit = p.unitPrice(item);
            return unit > 0 ? unit * item.getAmount() : 0;
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    public static boolean isFishingItem(ItemStack item) {
        Provider p = provider;
        try {
            return p != null && item != null && p.id(item) != null;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Ids of the configured tiers, from the most common. */
    public static List<String> tiers() {
        return tiers.stream().map(Tier::id).toList();
    }

    /** Tier of a fish (its id starts with "<tier>_"), or null. */
    public static String tier(ItemStack item) {
        Provider p = provider;
        if (p == null || item == null) return null;
        try {
            String id = p.id(item);
            if (id == null) return null;
            for (Tier t : tiers) if (id.startsWith(t.id() + "_")) return t.id();
        } catch (RuntimeException | LinkageError e) {
            // not readable: no tier
        }
        return null;
    }

    /** One priced sample per tier, named with the tier's label (cached a few minutes). */
    public static Map<String, ItemStack> samplesByTier(Player player) {
        Provider p = provider;
        if (p == null) return Map.of();
        Map<String, ItemStack> cached = samples;
        long now = System.currentTimeMillis();
        if (cached != null && now - samplesAt < CACHE_MS) return cached;
        Map<String, ItemStack> out = new LinkedHashMap<>();
        for (Tier t : tiers) {
            ItemStack sample;
            try {
                sample = p.build(player, t.sample());
            } catch (RuntimeException | LinkageError e) {
                continue;
            }
            if (sample == null || sample.getType().isAir() || price(sample) <= 0) continue;
            ItemMeta meta = sample.getItemMeta();
            if (meta != null) {
                meta.displayName(fr.influencraft.flux.api.text.Texts.colors(t.color() + t.label()));
                sample.setItemMeta(meta);
            }
            out.put(t.id(), sample);
        }
        samples = out;
        samplesAt = now;
        return out;
    }

    public static List<ItemStack> samples(Player player) {
        return new ArrayList<>(samplesByTier(player).values());
    }

    public static boolean hasCustomDurability(ItemStack item) {
        Provider p = provider;
        try {
            return p != null && item != null && p.hasCustomDurability(item);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public static boolean damaged(ItemStack item) {
        Provider p = provider;
        try {
            return p != null && p.damaged(item);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public static boolean repair(Player player, ItemStack item) {
        Provider p = provider;
        try {
            return p != null && p.repair(player, item);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
