package fr.influencraft.fluxcosmetics;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.ListProvider;
import fr.influencraft.flux.api.list.ListQuery;
import fr.influencraft.flux.api.list.Lists;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The choice of cosmetics (menu {@code cosmetiques}), opened from the wardrobe. The whole menu is drawn in the
 * player's inventory rows, the chest above stays empty and transparent: the mannequin is seen over it and wears what
 * is clicked at once.
 *
 * <p>One Flux list, {@code cosmetics}, filtered by category: the four armour slots ({@link Outfits}) and the tags,
 * whose entries are those of FluxTags' own list (read through Flux, no dependency). Flux keeps the category and the
 * page of each player, so the menu opens again where he left it.</p>
 *
 * <pre>
 *   [cosmetics] wear &lt;id&gt;     put a cosmetic on or take it off
 *   [cosmetics] changed       something was worn by another plugin's action (a tag): entries are read again
 *   [cosmetics] turn &lt;deg&gt;    turn the wardrobe's camera around the mannequin
 *   [cosmetics] frame         the camera frames the category now shown (after [list] cosmetics filter &lt;n&gt;)
 *   [cosmetics] opened|closed the menu shows or not (the camera makes room for it)
 *   [cosmetics] leave         leave the wardrobe
 * </pre>
 */
final class CosmeticsMenu {

    static final String MENU = "cosmetiques", LIST = "cosmetics", TAGS_LIST = "tags";

    /** The categories in filter order; the last one is the tags. */
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final String[] ICONS = {"LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS", "NAME_TAG"};

    private final FluxCosmetics plugin;

    CosmeticsMenu(FluxCosmetics plugin) {
        this.plugin = plugin;
    }

    void register() {
        Lists.register(plugin, LIST, new ListProvider() {
            @Override
            public List<ListEntry> entries(ListQuery query) {
                return CosmeticsMenu.this.entries(query.viewer(), query.filter());
            }

            @Override
            public List<String> filters() {
                return categories();
            }
        });
        for (int i = 0; i < ICONS.length; i++) {
            int category = i;
            Lists.registerItem(plugin, LIST + "_cat_" + i, player -> categoryButton(player, category));
        }
        FluxApi.registerAction(plugin, "cosmetics", (player, view, argument) -> act(player, argument.trim()));
    }

    /** Opens the menu on the category and page he last looked at. */
    void open(Player player) {
        Lists.invalidate(player, LIST);
        FluxApi.open(player, MENU);
    }

    private List<String> categories() {
        // one name per category, in filter order (lang file)
        List<String> names = new ArrayList<>(plugin.messages().rawList("menu.categories"));
        while (names.size() < ICONS.length) names.add(ICONS[names.size()]);
        return names.subList(0, ICONS.length);
    }

    private void act(Player player, String argument) {
        String[] parts = argument.split("\\s+", 2);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        WardrobeView view = plugin.wardrobe().view(player);
        switch (parts[0]) {
            case "wear" -> {
                plugin.outfits().toggle(player, arg, true);
                Lists.invalidate(player, LIST);
                if (view != null) view.redress();
            }
            case "changed" -> Lists.invalidate(player, LIST);
            case "turn" -> {
                if (view == null) return;
                try {
                    view.turn(Float.parseFloat(arg));
                } catch (NumberFormatException ignored) {
                    // a menu file's mistake: nothing turns
                }
            }
            case "opened" -> {
                if (view == null) return;
                view.menuShown(true);
                view.frame(Lists.state(player, LIST).filter());
            }
            case "frame" -> {
                if (view != null) view.frame(Lists.state(player, LIST).filter());
            }
            case "closed" -> {
                if (view != null) view.menuShown(false);
            }
            case "leave" -> {
                player.closeInventory();
                plugin.wardrobe().close(player, true);
            }
            default -> {
            }
        }
    }

    // ================================================================== what the menu shows

    private List<ListEntry> entries(Player viewer, int category) {
        if (category >= SLOTS.length) {
            // the tags: FluxTags' own entries (item, click action), as its menu shows them
            ListProvider tags = Lists.provider(TAGS_LIST);
            return tags == null ? List.of() : tags.entries(new ListQuery(viewer, TAGS_LIST, 0, 0, ""));
        }
        EquipmentSlot slot = SLOTS[Math.max(0, category)];
        Outfits outfits = plugin.outfits();
        String worn = outfits.worn(viewer, slot);
        List<ListEntry> out = new ArrayList<>();
        for (Outfits.Cosmetic c : outfits.all()) {
            if (c.slot() != slot) continue;
            boolean wearing = c.key().equals(worn);
            Lore lore = Lore.create();
            if (wearing) lore.ok(plugin.messages().raw("menu.worn"));
            if (!c.unlocked()) lore.ko(plugin.messages().raw("menu.locked"));
            else lore.left(plugin.messages().raw(wearing ? "menu.take-off" : "menu.wear"));
            ItemStack item = Items.dress(outfits.item(c, viewer), c.name(), lore.build(), viewer);
            if (item == null) continue;
            if (wearing) glint(item);
            out.add(ListEntry.of(c.key()).item(item)
                    .set("clic", c.unlocked() ? "[cosmetics] wear " + c.key() : "[sound] @error"));
        }
        return out;
    }

    private ItemStack categoryButton(Player player, int category) {
        List<String> names = categories();
        boolean selected = Lists.state(player, LIST).filter() == category;
        Lore lore = Lore.create().description(plugin.messages().rawList("menu.category-descriptions").stream()
                .skip(category).findFirst().orElse(""));
        if (selected) lore.ok(plugin.messages().raw("menu.selected"));
        else lore.left(plugin.messages().raw("menu.see"));
        ItemStack item = Items.of(ICONS[category], names.get(category), lore.build(), player);
        if (item != null && selected) glint(item);
        return item;
    }

    private static void glint(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
    }
}
