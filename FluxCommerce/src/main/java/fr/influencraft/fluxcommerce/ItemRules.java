package fr.influencraft.fluxcommerce;

import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.text.Texts;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Items that can never change hands, in any part of FluxCommerce: never traded, never listed at the auction house,
 * never sold or bought by a chest shop. One list, {@code blacklist} of the main config.yml. A rule is a material name,
 * a custom item id ({@code namespace:id}, {@code namespace:prefix*}), or {@code name:}/{@code lore:}/{@code model:}/
 * {@code cmd:} followed by a text found in the custom name, the lore, the item model or the custom model data. An item
 * holding a forbidden item (shulker box, bundle, two levels deep) is forbidden too.
 */
public final class ItemRules {

    private final FluxCommerce host;
    private List<String> rules = List.of();

    ItemRules(FluxCommerce host) {
        this.host = host;
    }

    void load() {
        rules = List.copyOf(host.getConfig().getStringList("blacklist"));
    }

    public List<String> rules() {
        return rules;
    }

    /** Adds a rule and saves the config; false when it was already there. */
    public boolean add(String rule) {
        if (rules.contains(rule)) return false;
        List<String> out = new ArrayList<>(rules);
        out.add(rule);
        save(out);
        return true;
    }

    /** Removes the rule at {@code index} (from 0) and saves the config; the rule removed, or null. */
    public String remove(int index) {
        if (index < 0 || index >= rules.size()) return null;
        List<String> out = new ArrayList<>(rules);
        String removed = out.remove(index);
        save(out);
        return removed;
    }

    private void save(List<String> out) {
        host.getConfig().set("blacklist", out);
        host.saveConfig();
        rules = List.copyOf(out);
    }

    /** Whether this item may never change hands. */
    public boolean forbidden(ItemStack item) {
        return forbidden(item, 0);
    }

    private boolean forbidden(ItemStack item, int depth) {
        if (item == null || item.getType().isAir()) return false;
        if (matches(item)) return true;
        if (depth >= 2) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof Container box) {
            for (ItemStack inside : box.getInventory().getContents()) if (forbidden(inside, depth + 1)) return true;
        }
        if (meta instanceof BundleMeta bundle) {
            for (ItemStack inside : bundle.getItems()) if (forbidden(inside, depth + 1)) return true;
        }
        return false;
    }

    private boolean matches(ItemStack item) {
        if (rules.isEmpty()) return false;
        String id = customId(item);
        ItemMeta meta = item.getItemMeta();
        for (String rule : rules) {
            String r = rule.trim();
            String lower = r.toLowerCase(Locale.ROOT);
            if (lower.startsWith("name:")) {
                String text = r.substring(5).toLowerCase(Locale.ROOT);
                if (meta != null && meta.hasDisplayName() && Texts.plain(meta.displayName()).toLowerCase(Locale.ROOT).contains(text)) return true;
            } else if (lower.startsWith("lore:")) {
                String text = r.substring(5).toLowerCase(Locale.ROOT);
                if (meta != null && meta.lore() != null && meta.lore().stream().anyMatch(c -> Texts.plain(c).toLowerCase(Locale.ROOT).contains(text))) return true;
            } else if (lower.startsWith("model:")) {
                String text = lower.substring(6);
                if (meta != null && meta.hasItemModel() && meta.getItemModel() != null && meta.getItemModel().toString().contains(text)) return true;
            } else if (lower.startsWith("cmd:")) {
                // custom model data (ItemsAdder and other resource pack items): strings, numbers, or the former integer
                String value = r.substring(4).trim();
                if (meta != null && customModelData(meta).contains(value)) return true;
            } else if (r.equalsIgnoreCase(item.getType().name())) {
                return true;
            } else if (id != null && CustomItems.matches(r, id)) {
                return true;
            }
        }
        return false;
    }

    /** The custom id of an item ({@code fluxtools:pioche}), null for a plain item or when Flux cannot tell. */
    public static String customId(ItemStack item) {
        try {
            return CustomItems.id(item);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Every custom model data value of an item, as text (strings, numbers without ".0", the former integer). */
    @SuppressWarnings("deprecation")
    static List<String> customModelData(ItemMeta meta) {
        List<String> out = new ArrayList<>();
        if (meta.hasCustomModelDataComponent()) {
            var c = meta.getCustomModelDataComponent();
            out.addAll(c.getStrings());
            for (Float f : c.getFloats()) out.add(f == Math.floor(f) ? String.valueOf(f.longValue()) : String.valueOf(f));
        }
        if (meta.hasCustomModelData()) out.add(String.valueOf(meta.getCustomModelData()));
        return out;
    }
}
