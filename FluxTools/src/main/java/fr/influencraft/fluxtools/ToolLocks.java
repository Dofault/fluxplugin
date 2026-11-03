package fr.influencraft.fluxtools;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The enchantments of a tool evolve with it: no enchanting table, grindstone, smithing table or crafting. At the anvil,
 * only repair with the material (repairable tool) and the books Incassable (repairable tool) and Lié à l'âme pass,
 * never both on the same tool.
 */
final class ToolLocks implements Listener {

    /** Vanilla enchantments: EcoEnchants registers its own in the same {@code minecraft} namespace. */
    private static final Set<Enchantment> VANILLA = new HashSet<>();

    static {
        for (Field f : Enchantment.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == Enchantment.class) {
                try {
                    VANILLA.add((Enchantment) f.get(null));
                } catch (IllegalAccessException ignored) {
                }
            }
        }
    }

    private final FluxTools plugin;

    ToolLocks(FluxTools plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void anvil(PrepareAnvilEvent ev) {
        apply(ev);
    }

    /** Enforced last: EcoEnchants may also write the anvil result. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void anvilLast(PrepareAnvilEvent ev) {
        apply(ev);
    }

    private void apply(PrepareAnvilEvent ev) {
        ItemStack left = ev.getInventory().getFirstItem();
        ItemStack right = ev.getInventory().getSecondItem();
        Tool o = plugin.tool(left);
        if (o == null) {
            // Any other item: vanilla enchantments are free, but no custom enchantment at the anvil (except
            // Incassable and Lié à l'âme), and never a tool as the sacrifice.
            if (plugin.tool(right) != null || forbiddenCustom(left) || forbiddenCustom(right)) ev.setResult(null);
            return;
        }
        if (right == null || right.isEmpty() || plugin.tool(right) != null) {
            ev.setResult(null);
            return;
        }
        if (right.getType() == Material.ENCHANTED_BOOK) {
            ItemStack r = book(o, left, right);
            // other books: mastery enchantments of another plugin (FluxEnchant), when one is registered
            var maitrise = fr.influencraft.fluxtools.api.Tools.mastery();
            if (r == null && maitrise != null && !toolBook(right) && ev.getView().getPlayer() instanceof Player p) {
                r = maitrise.anvil(p, left, right, FluxTools.info(o, ToolState.read(left)));
            }
            ev.setResult(r);
            if (ev.getResult() != null) ev.getView().setRepairCost(10);
            return;
        }
        if (!o.repairable || !left.canRepair(right)) {
            ev.setResult(null);
            return;
        }
        // vanilla repair: the computed result is kept, without renaming or any other enchantment
        ItemStack r = ev.getResult();
        if (r == null || r.isEmpty()) return;
        ToolState e = ToolState.read(r);
        if (e == null) return;
        r.removeEnchantments();
        r.addUnsafeEnchantments(left.getEnchantments());
        plugin.renderer().update(r, o, e, false);
        ev.setResult(r);
    }

    /** Book with a custom enchantment (EcoEnchants) other than Incassable or Lié à l'âme. */
    private static boolean forbiddenCustom(ItemStack i) {
        if (i == null || !(i.getItemMeta() instanceof EnchantmentStorageMeta m)) return false;
        for (Enchantment e : m.getStoredEnchants().keySet()) {
            if (!VANILLA.contains(e) && !ToolRenderer.BOOKS.contains(e.getKey().getKey())) return true;
        }
        return false;
    }

    /** Book Incassable or Lié à l'âme (handled here, never by mastery). */
    private static boolean toolBook(ItemStack book) {
        if (!(book.getItemMeta() instanceof EnchantmentStorageMeta m)) return false;
        for (Enchantment e : m.getStoredEnchants().keySet()) if (ToolRenderer.BOOKS.contains(e.getKey().getKey())) return true;
        return false;
    }

    private ItemStack book(Tool o, ItemStack tool, ItemStack book) {
        if (!(book.getItemMeta() instanceof EnchantmentStorageMeta m) || m.getStoredEnchants().size() != 1) return null;
        Map.Entry<Enchantment, Integer> en = m.getStoredEnchants().entrySet().iterator().next();
        String id = en.getKey().getKey().getKey();
        if (!ToolRenderer.BOOKS.contains(id)) return null;
        if ("incassable".equals(id) && !o.repairable) return null;
        for (Enchantment owned : tool.getEnchantments().keySet()) {
            if (ToolRenderer.BOOKS.contains(owned.getKey().getKey())) return null;
        }
        ItemStack r = tool.clone();
        r.addUnsafeEnchantment(en.getKey(), en.getValue());
        return r;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void table(PrepareItemEnchantEvent ev) {
        if (plugin.tool(ev.getItem()) != null) ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void tableEnchant(EnchantItemEvent ev) {
        if (plugin.tool(ev.getItem()) == null) return;
        ev.setCancelled(true);
        Player p = ev.getEnchanter();
        plugin.messages().send(p, "locked");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void grindstone(PrepareGrindstoneEvent ev) {
        if (plugin.tool(ev.getInventory().getUpperItem()) != null || plugin.tool(ev.getInventory().getLowerItem()) != null) {
            ev.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void smithing(PrepareSmithingEvent ev) {
        for (ItemStack i : ev.getInventory().getContents()) {
            if (plugin.tool(i) != null) {
                ev.setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void craft(PrepareItemCraftEvent ev) {
        for (ItemStack i : ev.getInventory().getMatrix()) {
            if (plugin.tool(i) != null) {
                ev.getInventory().setResult(null);
                return;
            }
        }
    }
}
