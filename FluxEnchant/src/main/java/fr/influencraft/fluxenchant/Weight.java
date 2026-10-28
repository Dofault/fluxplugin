package fr.influencraft.fluxenchant;

import fr.influencraft.fluxtools.api.Tools;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Weight of every enchantable item (FluxTools tools are handled by {@link Mastery}): the enchanting table leaves out
 * the heaviest custom enchantments that do not fit, the anvil refuses a result above the capacity.
 */
final class Weight implements Listener {

    private final FluxEnchant plugin;

    Weight(FluxEnchant plugin) {
        this.plugin = plugin;
    }

    int of(Map<Enchantment, Integer> enchants) {
        int w = 0;
        for (Enchantment e : enchants.keySet()) w += plugin.rarities().weight(e);
        return w;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void table(EnchantItemEvent ev) {
        ItemStack item = ev.getItem();
        if (Tools.info(item) != null) return;
        int capacity = plugin.capacity(item);
        int current = of(item.getEnchantments());
        Map<Enchantment, Integer> adding = ev.getEnchantsToAdd();
        List<Enchantment> heaviest = new ArrayList<>(adding.keySet());
        heaviest.sort(Comparator.comparingInt((Enchantment e) -> plugin.rarities().weight(e)).reversed());
        boolean trimmed = false;
        for (Enchantment e : heaviest) {
            if (current + of(adding) <= capacity) break;
            if (plugin.rarities().weight(e) == 0) continue;
            adding.remove(e);
            trimmed = true;
        }
        if (trimmed) {
            plugin.messages().send(ev.getEnchanter(), "table-trimmed", "%weight%", String.valueOf(current + of(adding)),
                    "%capacity%", String.valueOf(capacity));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void anvil(PrepareAnvilEvent ev) {
        ItemStack result = ev.getResult();
        if (result == null || result.isEmpty() || Tools.info(result) != null) return;
        ItemMeta meta = result.getItemMeta();
        if (meta == null) return;
        int weight = of(meta.getEnchants());
        int capacity = plugin.capacity(result);
        if (weight > capacity) {
            ev.setResult(null);
            if (ev.getView().getPlayer() instanceof Player p) {
                plugin.messages().actionBar(p, "refused-weight", "%weight%", String.valueOf(weight), "%capacity%", String.valueOf(capacity));
            }
        }
    }
}
