package fr.influencraft.fluxtools.api;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/** Entry point of FluxTools for other plugins. */
public final class Tools {

    private static java.util.function.Function<ItemStack, ToolInfo> reader = item -> null;
    private static java.util.function.Consumer<ItemStack> refresher = item -> { };

    private Tools() {
    }

    /** The tool carried by this item, or null. */
    public static ToolInfo info(ItemStack item) {
        return reader.apply(item);
    }

    /** Rewrites a tool (its enchantments and description), e.g. after a mastery enchantment changed. */
    public static void refresh(ItemStack item) {
        refresher.accept(item);
    }

    /** Set by FluxTools on start. */
    public static void bind(java.util.function.Function<ItemStack, ToolInfo> r, java.util.function.Consumer<ItemStack> refresh) {
        reader = r == null ? item -> null : r;
        refresher = refresh == null ? item -> { } : refresh;
    }

    /** The registered mastery hook, or null. */
    public static EnchantMastery mastery() {
        return Bukkit.getServicesManager().load(EnchantMastery.class);
    }
}
