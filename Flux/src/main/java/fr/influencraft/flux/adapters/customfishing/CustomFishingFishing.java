package fr.influencraft.flux.adapters.customfishing;

import fr.influencraft.flux.api.fishing.Fishing;
import net.momirealms.customfishing.api.BukkitCustomFishingPlugin;
import net.momirealms.customfishing.api.mechanic.context.Context;
import net.momirealms.customfishing.api.mechanic.item.ItemManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/** CustomFishing behind {@link Fishing}: the only class of the suite that talks to CustomFishing. */
public final class CustomFishingFishing implements Fishing.Provider {

    private static ItemManager items() {
        BukkitCustomFishingPlugin plugin = BukkitCustomFishingPlugin.getInstance();
        return plugin == null ? null : plugin.getItemManager();
    }

    @Override
    public String name() {
        return "CustomFishing";
    }

    @Override
    public String id(ItemStack item) {
        ItemManager m = items();
        return m == null ? null : m.getCustomFishingItemID(item);
    }

    @Override
    public double unitPrice(ItemStack item) {
        ItemManager m = items();
        if (m == null) return 0;
        Object tag = m.wrap(item).getTag("Price").orElse(null);
        return tag instanceof Number n ? n.doubleValue() : 0;
    }

    @Override
    public ItemStack build(Player player, String id) {
        ItemManager m = items();
        return m == null ? null : m.buildInternal(Context.player(player), id);
    }

    @Override
    public boolean hasCustomDurability(ItemStack item) {
        ItemManager m = items();
        return m != null && m.hasCustomMaxDamage(item);
    }

    @Override
    public boolean damaged(ItemStack item) {
        ItemManager m = items();
        if (m == null) return false;
        int max = m.getMaxDamage(item);
        Optional<Object> current = m.wrap(item).getTag("CustomFishing", "cur_dur");
        return current.isPresent() && current.get() instanceof Number n && n.intValue() < max;
    }

    @Override
    public boolean repair(Player player, ItemStack item) {
        ItemManager m = items();
        if (m == null) return false;
        m.setDamage(player, item, 0);
        return true;
    }
}
