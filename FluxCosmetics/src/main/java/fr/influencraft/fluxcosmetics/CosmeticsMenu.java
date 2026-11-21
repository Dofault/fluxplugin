package fr.influencraft.fluxcosmetics;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.ListProvider;
import fr.influencraft.flux.api.list.ListQuery;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.util.Sounds;
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
 *   [cosmetics] category &lt;n&gt;  shows a category of his universe (the camera frames it), refused otherwise
 *   [cosmetics] frame         the camera frames the category now shown
 *   [cosmetics] tags          the wardrobe on the tags, from anywhere (FluxTags' /tags)
 *   [cosmetics] rpg &lt;key&gt;     chooses his rpg outfit ({@link RpgOutfits})
 *   [cosmetics] rpg-here &lt;key&gt; an admin wears an rpg outfit outside the rpg universe (again: off)
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
    private static final String[] ICONS = {"LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS", "NAME_TAG",
            "ARMOR_STAND", "NETHERITE_CHESTPLATE"};
    static final int TAGS = 4, PRESETS = 5, RPG = 6;

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
        // his saved outfits are vanilla ones: in the rpg universe, his rpg outfits instead
        int category = plugin.outfits().rpg().in(player) ? RPG : PRESETS;
        Lists.act(player, LIST, "filter " + category, 14);
        WardrobeView view = plugin.wardrobe().view(player);
        if (view != null) view.frame(category);
    }

    /** Opens the menu on the category and page he last looked at. */
    void open(Player player) {
        int shown = Lists.state(player, LIST).filter();
        if (!allowed(player, shown)) Lists.act(player, LIST, "filter " + (plugin.outfits().rpg().in(player) ? RPG : 0), 14);
        Lists.invalidate(player, LIST);
        FluxApi.open(player, MENU);
    }

    /**
     * Every category is edited from either universe (2026-11-20): what is chosen for the other one is only tried on
     * the mannequin, for his eyes alone ({@link WardrobeView#redress}); the others still see his looks of the universe
     * he is in.
     */
    boolean allowed(Player player, int category) {
        return category >= 0 && category <= RPG;
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
        if (parts[0].equals("tags")) {
            Lists.act(player, LIST, "filter " + TAGS, 14);
            if (view != null) {
                view.frame(TAGS);
                open(player);
            } else {
                // from a menu (the profile) or a command: the wardrobe opens on that category
                player.closeInventory();
                plugin.wardrobe().open(player);
            }
            return;
        }
        if (parts[0].startsWith("preset-")) {
            presets.act(player, parts[0], arg);
            return;
        }
        if (parts[0].equals("rpg-here")) {
            // admins only: an rpg outfit worn outside the rpg universe, seen by everybody
            RpgOutfits.Outfit o = plugin.outfits().rpg().get(arg);
            if (o == null || !fr.influencraft.flux.api.staff.Staff.isAdmin(player) || plugin.outfits().rpg().in(player)) {
                Sounds.cue(player, "error");
                return;
            }
            plugin.outfits().toggleRpgHere(player, o);
            Sounds.cue(player, "toggle-on");
            Lists.invalidate(player, LIST);
            if (view != null) view.redress();
            return;
        }
        if (parts[0].equals("rpg")) {
            RpgOutfits rpg = plugin.outfits().rpg();
            RpgOutfits.Outfit o = rpg.get(arg);
            if (o == null || !rpg.owns(player, o)) {
                Sounds.cue(player, "error");
                return;
            }
            // his class's outfit chosen: back to "my class's", which follows a class change
            plugin.outfits().chooseRpg(player, o.equals(rpg.classOutfit(player)) ? null : o.key());
            Sounds.cue(player, "toggle-on");
            Lists.invalidate(player, LIST);
            if (view != null) view.redress();
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
            case "category" -> {
                int category;
                try {
                    category = Integer.parseInt(arg);
                } catch (NumberFormatException e) {
                    return;
                }
                // a category of the other universe: refused, the list stays
                if (!allowed(player, category)) {
                    Sounds.cue(player, "error");
                    return;
                }
                Sounds.cue(player, "page");
                Lists.act(player, LIST, "filter " + category, 14);
                if (view != null) view.frame(category);
                var open = FluxApi.view(player);
                if (open != null) open.refresh();
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
        List<ListEntry> out = !allowed(viewer, category) ? new ArrayList<>()
                : category == RPG ? rpgEntries(viewer)
                : category == PRESETS ? presets.entries(viewer) : category == TAGS ? tags(viewer) : pieces(viewer, category);
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

    /** His rpg outfits: his class's first, then the others of his class, then the rest, the worn one glowing. */
    private List<ListEntry> rpgEntries(Player viewer) {
        RpgOutfits rpg = plugin.outfits().rpg();
        RpgOutfits.Outfit mine = rpg.classOutfit(viewer);
        RpgOutfits.Outfit worn = rpg.active(viewer, plugin.outfits().rpgChoice(viewer));
        boolean admin = fr.influencraft.flux.api.staff.Staff.isAdmin(viewer) && !rpg.in(viewer);
        String cls = rpg.classOf(viewer);
        List<RpgOutfits.Outfit> all = new ArrayList<>(rpg.all());
        // stable sort: rarity order kept inside each group
        all.sort(java.util.Comparator.comparingInt(o -> o.equals(mine) ? 0 : !cls.isEmpty() && cls.equals(o.heroClass()) ? 1 : 2));
        List<ListEntry> out = new ArrayList<>();
        for (RpgOutfits.Outfit o : all) {
            boolean wearing = o.equals(worn), owned = rpg.owns(viewer, o);
            Lore lore = Lore.create();
            if (o.equals(mine)) lore.description(plugin.messages().raw("menu.rpg-class-outfit"));
            lore.kv(plugin.messages().raw("menu.rarity"), "&" + o.rarity().colour() + plugin.messages().raw("rarities." + o.rarity().id()));
            String className = o.heroClass().isEmpty() ? "" : rpg.className(viewer, o.heroClass());
            if (!className.isEmpty()) lore.kv(plugin.messages().raw("menu.rpg-class"), className);
            if (o.level() > 0) lore.kv(plugin.messages().raw("menu.rpg-level"), String.valueOf(o.level()));
            if (wearing) lore.ok(plugin.messages().raw("menu.worn"));
            if ((admin && plugin.outfits().wornHere(viewer, o))) lore.ok(plugin.messages().raw("menu.rpg-here-worn"));
            if (!owned) lore.ko(lockReason(viewer, rpg, o, className));
            else if (!wearing) lore.left(plugin.messages().raw("menu.wear"));
            if (admin) lore.right(plugin.messages().raw((admin && plugin.outfits().wornHere(viewer, o)) ? "menu.rpg-here-off" : "menu.rpg-here"));
            ItemStack piece = rpg.item(o, EquipmentSlot.CHEST);
            if (piece == null) piece = rpg.item(o, EquipmentSlot.HEAD);
            ItemStack item = Items.dress(icon(piece != null ? piece : new ItemStack(org.bukkit.Material.ARMOR_STAND)), o.name(), lore.build(), viewer);
            if (item == null) continue;
            item.setData(io.papermc.paper.datacomponent.DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, wearing);
            ListEntry entry = ListEntry.of(o.key()).item(item)
                    .set("clic", owned && !wearing ? "[cosmetics] rpg " + o.key() : "[sound] @error");
            if (admin) entry.set("clic_droit", "[cosmetics] rpg-here " + o.key());
            out.add(entry);
        }
        return out;
    }

    /** What keeps an rpg outfit from him: the class to embody first, then the level to reach in it. */
    private String lockReason(Player viewer, RpgOutfits rpg, RpgOutfits.Outfit o, String className) {
        if (!o.heroClass().isEmpty() && !o.heroClass().equals(rpg.classOf(viewer)))
            return plugin.messages().raw("menu.rpg-needs-class", "%class%", className);
        if (o.level() > 0 && rpg.levelOf(viewer) < o.level())
            return plugin.messages().raw("menu.rpg-needs-level", "%level%", String.valueOf(o.level()), "%class%", className);
        return plugin.messages().raw("menu.locked");
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
            // pieces of rpg outfits: shown to admins only
            if (c.adminOnly() && !owned) continue;
            Lore lore = Lore.create();
            if (c.adminOnly()) lore.description(plugin.messages().raw("menu.rpg-piece"));
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
