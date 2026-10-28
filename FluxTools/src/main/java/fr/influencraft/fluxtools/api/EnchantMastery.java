package fr.influencraft.fluxtools.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Enchantments added to a tool beyond its own ones (mastery enchantments), provided by another plugin (FluxEnchant)
 * and registered in Bukkit's services. FluxTools keeps the tool: it calls this hook at the anvil, when the tool gains
 * XP, and each time it rewrites the tool, so these enchantments survive its rewrites. Without a registered hook,
 * FluxTools refuses every other book at the anvil, as before.
 */
public interface EnchantMastery {

    /** Anvil: the tool with the book added, or null when refused (book, tool level, weight...). */
    ItemStack anvil(Player player, ItemStack tool, ItemStack book, ToolInfo info);

    /** The tool gained XP (its own XP, already counted by FluxTools). True when an enchantment level changed. */
    boolean gain(Player player, ItemStack tool, ToolInfo info, double xp);

    /** Puts these enchantments at their current level on the tool, after FluxTools wrote its own ones. */
    void apply(ItemMeta meta);

    /** Lines of the tool's description for these enchantments (colour codes {@code &} / {@code &#RRGGBB}). */
    List<String> lines(ItemMeta meta);
}
