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
 * <p>One Flux list, {@code cosmetics}, filtered by category: the four armour slots ({@link Outfits}), the tags,
 * whose entries are those of FluxTags' own list (read through Flux, no dependency), and the saved outfits
 * ({@link OutfitPresets}). Flux keeps the category and the
 * page of each player, so the menu opens again where he left it.</p>
 *
 * <pre>
 *   [cosmetics] wear &lt;id&gt;     put a cosmetic on or take it off
 *   [cosmetics] none &lt;slot&gt;   take off the cosmetic worn in that slot (HEAD, CHEST, LEGS, FEET)
 *   [cosmetics] changed       something was worn by another plugin's action (a tag): entries are read again
 *   [cosmetics] turn &lt;deg&gt;    turn the wardrobe's camera around the mannequin
 *   [cosmetics] frame         the camera frames the category now shown (after [list] cosmetics filter &lt;n&gt;)
 *   [cosmetics] opened|closed the menu shows or not (the camera makes room for it)
 *   [cosmetics] leave         leave the wardrobe
 *   [cosmetics] main          leave the wardrobe and open the main menu (main-menu of the config)
 *   [cosmetics] preset-wear|preset-new|preset-manage &lt;n&gt;   saved outfits ({@link OutfitPresets})
 * </pre>
 */
final class CosmeticsMenu {

    static final String MENU = "cosmetiques", LIST = "cosmetics", TAGS_LIST = "tags";
    /** Ids of FluxTags' "no tag" and rank tag entries. */
    private static final String NO_TAG = "aucun", RANK_TAG = "rang";

    /** The categories in filter order: the four armour slots, the tags, the saved outfits. */
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final String[] ICONS = {"LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS", "NAME_TAG", "ARMOR_STAND"};
    static final int TAGS = 4, PRESETS = 5;

    private final FluxCosmetics plugin;
    private final OutfitPresets presets;

    CosmeticsMenu(FluxCosmetics plugin) {
        this.plugin = plugin;
        this.presets = new OutfitPresets(plugin);
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

    /** The next opening of the menu shows his saved outfits (/tenue). */
    void showOutfits(Player player) {
        Lists.act(player, LIST, "filter " + PRESETS, 14);
        WardrobeView view = plugin.wardrobe().view(player);
        if (view != null) view.frame(PRESETS);
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
        if (parts[0].startsWith("preset-")) {
            presets.act(player, parts[0], arg);
            return;
        }
        switch (parts[0]) {
            case "wear" -> {
                plugin.outfits().toggle(player, arg, true);
                Lists.invalidate(player, LIST);
                if (view != null) view.redress();
            }
            case "none" -> {
                try {
                    plugin.outfits().takeOff(player, EquipmentSlot.valueOf(arg));
                } catch (IllegalArgumentException ignored) {
                    return;
                }
                Lists.invalidate(player, LIST);
                if (view != null) view.redress();
            }
            case "changed" -> {
                Lists.invalidate(player, LIST);
                // a tag picked: his nametag changed, over the mannequin too
                if (view != null) view.redress();
            }
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
            case "main" -> {
                // out of the wardrobe, back in his place, then the main menu
                player.closeInventory();
                plugin.wardrobe().close(player, true);
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) FluxApi.open(player, plugin.getConfig().getString("main-menu", "main"));
                }, 2L);
            }
            default -> {
            }
        }
    }

    // ================================================================== what the menu shows

    private List<ListEntry> entries(Player viewer, int category) {
        List<ListEntry> out = category == PRESETS ? presets.entries(viewer) : category == TAGS ? tags(viewer) : pieces(viewer, category);
        // only the saved outfits answer a right click
        for (ListEntry e : out) if (!e.values().containsKey("clic_droit")) e.set("clic_droit", "");
        return out;
    }

    /** FluxTags' entries as its own menu shows them, read through Flux; null when FluxTags is not there. */
    private List<ListEntry> tagEntries(Player viewer) {
        ListProvider tags = Lists.provider(TAGS_LIST);
        return tags == null ? List.of() : tags.entries(new ListQuery(viewer, TAGS_LIST, 0, 0, ""));
    }

    private static boolean glinting(ListEntry e) {
        var meta = e.item() == null ? null : e.item().getItemMeta();
        return meta != null && meta.hasEnchantmentGlintOverride() && meta.getEnchantmentGlintOverride();
    }

    /** Id of the tag he shows now (FluxTags makes it glint in its list), null when unknown. */
    String currentTag(Player player) {
        for (ListEntry e : tagEntries(player)) if (glinting(e)) return e.id();
        return null;
    }

    /** Picture of a tag for him (the {@code image} of FluxTags' entry), null when unknown. */
    String tagImage(Player player, String tag) {
        for (ListEntry e : tagEntries(player)) if (e.id().equals(tag)) return e.values().get("image");
        return null;
    }

    private List<ListEntry> tags(Player viewer) {
        // the tags: FluxTags' own entries (item, click action), as its menu shows them
        List<ListEntry> entries = tagEntries(viewer);
        if (entries.isEmpty()) return new ArrayList<>();
        // picked without a chat line: the mannequin shows the tag
        List<ListEntry> out = new ArrayList<>();
        boolean rankShown = false;
        for (ListEntry e : entries) {
            // "no tag at all" left out: taking a tag off brings the rank's back (the barrier below)
            if (e.id().equals(NO_TAG)) continue;
            String click = e.values().get("clic");
            boolean shown = glinting(e);
            if (e.id().equals(RANK_TAG)) rankShown = shown;
            // the worn tag clicked again comes off: back to the rank's tag, never to no tag at all
            if (shown && !e.id().equals(RANK_TAG)) e.set("clic", "[tags] " + RANK_TAG + " quiet");
            else if (shown) e.set("clic", "[sound] @error");
            else if (click != null && click.startsWith("[tags] ")) e.set("clic", click + " quiet");
            out.add(e);
        }
        out.addFirst(noTag(viewer, rankShown));
        return out;
    }

    private List<ListEntry> pieces(Player viewer, int category) {
        EquipmentSlot slot = SLOTS[Math.clamp(category, 0, SLOTS.length - 1)];
        Outfits outfits = plugin.outfits();
        String worn = outfits.worn(viewer, slot);
        List<ListEntry> out = new ArrayList<>();
        out.add(nothing(viewer, slot, worn == null));
        for (Outfits.Cosmetic c : outfits.all()) {
            if (c.slot() != slot) continue;
            boolean wearing = c.key().equals(worn);
            boolean owned = outfits.owns(viewer, c);
            Lore lore = Lore.create();
            lore.kv(plugin.messages().raw("menu.rarity"), "&" + c.rarity().colour() + plugin.messages().raw("rarities." + c.rarity().id()));
            if (wearing) lore.ok(plugin.messages().raw("menu.worn"));
            if (!owned) lore.ko(plugin.messages().raw("menu.locked"));
            else lore.left(plugin.messages().raw(wearing ? "menu.take-off" : "menu.wear"));
            ItemStack item = Items.dress(icon(outfits.item(c, viewer)), c.name(), lore.build(), viewer);
            if (item == null) continue;
            item.setData(io.papermc.paper.datacomponent.DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, wearing);
            out.add(ListEntry.of(c.key()).item(item)
                    .set("clic", owned ? "[cosmetics] wear " + c.key() : "[sound] @error"));
        }
        return out;
    }

    /**
     * A cosmetic's item as the menu shows it: only its look. Its stats, enchantments, trim and colour lines are hidden
     * (a FluxArmor piece worn as a cosmetic gives none of them), its glint is the "worn" mark.
     */
    private static ItemStack icon(ItemStack item) {
        if (item == null) return null;
        item.setData(io.papermc.paper.datacomponent.DataComponentTypes.TOOLTIP_DISPLAY,
                io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay()
                        .addHiddenComponents(io.papermc.paper.datacomponent.DataComponentTypes.ATTRIBUTE_MODIFIERS,
                                io.papermc.paper.datacomponent.DataComponentTypes.ENCHANTMENTS,
                                io.papermc.paper.datacomponent.DataComponentTypes.TRIM,
                                io.papermc.paper.datacomponent.DataComponentTypes.DYED_COLOR,
                                io.papermc.paper.datacomponent.DataComponentTypes.UNBREAKABLE,
                                io.papermc.paper.datacomponent.DataComponentTypes.DAMAGE)
                        .build());
        item.unsetData(io.papermc.paper.datacomponent.DataComponentTypes.MAX_DAMAGE);
        return item;
    }

    /** First choice of an armour category: a barrier that takes off the cosmetic worn in that slot. */
    private ListEntry nothing(Player viewer, EquipmentSlot slot, boolean bare) {
        Lore lore = Lore.create().description(plugin.messages().raw("menu.none-description"));
        if (bare) lore.ok(plugin.messages().raw("menu.none-current"));
        else lore.left(plugin.messages().raw("menu.take-off"));
        ItemStack item = Items.of("BARRIER", plugin.messages().raw("menu.none-name"), lore.build(), viewer);
        if (bare && item != null) glint(item);
        return ListEntry.of("none").item(item)
                .set("clic", bare ? "[sound] @error" : "[cosmetics] none " + slot.name());
    }

    /** First choice of the tags: a barrier that takes the worn tag off, the rank's tag showing again. */
    private ListEntry noTag(Player viewer, boolean rankShown) {
        Lore lore = Lore.create().description(plugin.messages().raw("menu.no-tag-description"));
        if (rankShown) lore.ok(plugin.messages().raw("menu.no-tag-current"));
        else lore.left(plugin.messages().raw("menu.take-off"));
        ItemStack item = Items.of("BARRIER", plugin.messages().raw("menu.none-name"), lore.build(), viewer);
        if (rankShown && item != null) glint(item);
        return ListEntry.of("none").item(item)
                .set("clic", rankShown ? "[sound] @error" : "[tags] " + RANK_TAG + " quiet");
    }

    private ItemStack categoryButton(Player player, int category) {
        List<String> names = categories();
        boolean selected = Lists.state(player, LIST).filter() == category;
        Lore lore = Lore.create().description(plugin.messages().rawList("menu.category-descriptions").stream()
                .skip(category).findFirst().orElse(""));
        if (selected) lore.ok(plugin.messages().raw("menu.selected"));
        else lore.left(plugin.messages().raw("menu.see"));
        ItemStack item = Items.of(ICONS[category], names.get(category), lore.build(), player);
        if (item == null) return null;
        dye(item, plugin.getConfig().getStringList("menu-colours"), category);
        if (selected) glint(item);
        return item;
    }

    /** Leather icons dyed with the colour of their category ({@code menu-colours} of the config, #RRGGBB). */
    private static void dye(ItemStack item, List<String> colours, int category) {
        if (category >= colours.size() || !(item.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta meta)) return;
        try {
            meta.setColor(org.bukkit.Color.fromRGB(Integer.parseInt(colours.get(category).replace("#", "").trim(), 16)));
        } catch (IllegalArgumentException ignored) {
            return;
        }
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_DYE);
        item.setItemMeta(meta);
    }

    private static void glint(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
    }
}
