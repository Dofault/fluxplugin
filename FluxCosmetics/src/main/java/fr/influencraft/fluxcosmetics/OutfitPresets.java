package fr.influencraft.fluxcosmetics;

import fr.influencraft.flux.api.FluxAction;
import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The outfits a player saves (category "Mes tenues" of the cosmetics menu): his cosmetic of each armour slot and his
 * tag, under a name he chooses in a Minecraft dialog. {@code presets} slots each (config, 5 to start).
 *
 * <ul>
 *   <li>A saved outfit: left click puts it on (what he does not own any more is skipped), right click opens its
 *   dialog: save what he wears now over it, rename it, or delete it.</li>
 *   <li>An empty slot: left click saves what he wears now, the dialog asking its name.</li>
 * </ul>
 * The tag goes through FluxTags' own action ({@code [tags] <id> quiet}, read through Flux: no dependency).
 */
final class OutfitPresets {

    /** Longest name, in characters. */
    private static final int NAME_LENGTH = 24;

    private final FluxCosmetics plugin;

    OutfitPresets(FluxCosmetics plugin) {
        this.plugin = plugin;
    }

    private int count() {
        return Math.clamp(plugin.getConfig().getInt("presets", 7), 1, 28);
    }

    // ================================================================== list

    List<ListEntry> entries(Player viewer) {
        Map<Integer, Outfits.Preset> saved = plugin.outfits().presets(viewer);
        String tag = plugin.menu().currentTag(viewer);
        List<ListEntry> out = new ArrayList<>();
        for (int n = 1; n <= count(); n++) {
            Outfits.Preset p = saved.get(n);
            out.add(p == null ? empty(viewer, n) : filled(viewer, n, p, tag));
        }
        return out;
    }

    private ListEntry empty(Player viewer, int n) {
        Lore lore = Lore.create().description(plugin.messages().raw("presets.empty-description"))
                .left(plugin.messages().raw("presets.save"));
        ItemStack item = Items.of("itemsadder:fluxui:btn_add_green", plugin.messages().raw("presets.empty-name", "%n%", String.valueOf(n)),
                lore.build(), viewer);
        return ListEntry.of("preset_" + n).item(item).set("clic", "[cosmetics] preset-new " + n);
    }

    private ListEntry filled(Player viewer, int n, Outfits.Preset p, String tagNow) {
        Outfits outfits = plugin.outfits();
        Lore lore = Lore.create();
        List<String> slotNames = plugin.messages().rawList("presets.slots");
        for (int i = 0; i < Outfits.ARMOUR.length; i++) {
            Outfits.Cosmetic c = outfits.get(p.pieces().get(Outfits.ARMOUR[i]));
            String label = i < slotNames.size() ? slotNames.get(i) : Outfits.ARMOUR[i].name();
            lore.kv(label, c == null ? plugin.messages().raw("presets.nothing") : c.name());
        }
        if (p.tag() != null) {
            String image = plugin.menu().tagImage(viewer, p.tag());
            lore.kv(plugin.messages().raw("presets.tag"), image == null ? p.tag() : "&f:" + image + ":");
        }
        boolean worn = wornNow(viewer, p, tagNow);
        if (worn) lore.ok(plugin.messages().raw("presets.worn"));
        else lore.left(plugin.messages().raw("presets.wear"));
        lore.right(plugin.messages().raw("presets.manage"));
        ItemStack item = Items.of("ARMOR_STAND", p.name(), lore.build(), viewer);
        if (item != null) item.setData(io.papermc.paper.datacomponent.DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, worn);
        return ListEntry.of("preset_" + n).item(item)
                .set("clic", worn ? "[sound] @error" : "[cosmetics] preset-wear " + n)
                .set("clic_droit", "[cosmetics] preset-manage " + n);
    }

    /** Whether he wears exactly this outfit now. */
    private boolean wornNow(Player player, Outfits.Preset p, String tagNow) {
        for (EquipmentSlot slot : Outfits.ARMOUR) {
            if (!Objects.equals(p.pieces().get(slot), plugin.outfits().worn(player, slot))) return false;
        }
        return p.tag() == null || p.tag().equals(tagNow);
    }

    // ================================================================== actions

    /** {@code [cosmetics] preset-wear|preset-new|preset-manage <n>}. */
    void act(Player player, String verb, String arg) {
        int n;
        try {
            n = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            return;
        }
        if (n < 1 || n > count()) return;
        switch (verb) {
            case "preset-wear" -> wear(player, n);
            case "preset-new" -> ask(player, n, null);
            case "preset-manage" -> {
                Outfits.Preset p = plugin.outfits().presets(player).get(n);
                if (p == null) ask(player, n, null);
                else ask(player, n, p);
            }
            default -> {
            }
        }
    }

    /** Puts on every piece and the tag of an outfit; what he no longer owns is left as it is. */
    private void wear(Player player, int n) {
        Outfits.Preset p = plugin.outfits().presets(player).get(n);
        if (p == null) return;
        boolean refused = false;
        for (EquipmentSlot slot : Outfits.ARMOUR) {
            String key = p.pieces().get(slot);
            if (key == null) plugin.outfits().takeOff(player, slot);
            else refused |= !plugin.outfits().wear(player, key);
        }
        if (p.tag() != null && !p.tag().equals(plugin.menu().currentTag(player))) {
            FluxAction tags = FluxApi.action("tags");
            if (tags != null) tags.run(player, null, p.tag() + " quiet");
        }
        Sounds.cue(player, refused ? "error" : "toggle-on");
        changed(player);
    }

    /**
     * The dialog of an outfit slot: its name, then save what he wears now (an empty slot asks nothing else), rename
     * or delete. The menu comes back after it, the camera kept on its shot.
     */
    private void ask(Player player, int n, Outfits.Preset p) {
        WardrobeView view = plugin.wardrobe().view(player);
        if (view != null) view.holdShot(true);
        String title = plugin.messages().raw("presets.dialog-title", "%n%", String.valueOf(n));
        String initial = p != null ? p.name() : plugin.messages().raw("presets.default-name", "%n%", String.valueOf(n));
        Dialogs.Form form = Dialogs.form(player, title)
                .body(plugin.messages().raw(p == null ? "presets.dialog-new" : "presets.dialog-saved"))
                .input("name", plugin.messages().raw("presets.name-label"), initial, NAME_LENGTH)
                .onCancel(this::back);
        if (p == null) {
            form.buttons(plugin.messages().raw("presets.button-save"), plugin.messages().raw("presets.button-cancel"))
                    .onSubmit((pl, a) -> {
                        save(pl, n, a.text("name"));
                        back(pl);
                    });
        } else {
            form.columns(2)
                    .action("save", plugin.messages().raw("presets.button-overwrite"), plugin.messages().raw("presets.button-overwrite-hover"))
                    .action("rename", plugin.messages().raw("presets.button-rename"))
                    .action("delete", plugin.messages().raw("presets.button-delete"))
                    .action("cancel", plugin.messages().raw("presets.button-cancel"))
                    .onSubmit((pl, a) -> {
                        switch (a.button()) {
                            case "save" -> save(pl, n, a.text("name"));
                            case "rename" -> {
                                plugin.outfits().savePreset(pl, n, new Outfits.Preset(name(a.text("name"), n), p.pieces(), p.tag()));
                                Sounds.cue(pl, "success");
                            }
                            case "delete" -> {
                                plugin.outfits().deletePreset(pl, n);
                                Sounds.cue(pl, "remove");
                            }
                            default -> {
                            }
                        }
                        back(pl);
                    });
        }
        form.open();
    }

    private void save(Player player, int n, String typed) {
        plugin.outfits().savePreset(player, n, plugin.outfits().current(player, name(typed, n), plugin.menu().currentTag(player)));
        Sounds.cue(player, "success");
    }

    /** Back to the menu after the dialog. */
    private void back(Player player) {
        if (!player.isOnline()) return;
        WardrobeView view = plugin.wardrobe().view(player);
        if (view != null) view.holdShot(false);
        changed(player);
        if (view != null) plugin.menu().open(player);
    }

    private void changed(Player player) {
        Lists.invalidate(player, CosmeticsMenu.LIST);
        WardrobeView view = plugin.wardrobe().view(player);
        if (view != null) view.redress();
        var open = FluxApi.view(player);
        if (open != null) open.refresh();
    }

    /**
     * A typed name made safe to show: letters, digits, spaces and a few signs only (no colour code, MiniMessage tag,
     * placeholder or picture code can get through), {@link #NAME_LENGTH} characters at most; the default name when
     * nothing is left.
     */
    private String name(String typed, int n) {
        String clean = typed == null ? "" : typed.replaceAll("[^\\p{L}\\p{N} '\\-_.!?,()]", "").replaceAll("\\s+", " ").trim();
        if (clean.length() > NAME_LENGTH) clean = clean.substring(0, NAME_LENGTH).trim();
        return clean.isEmpty() ? plugin.messages().raw("presets.default-name", "%n%", String.valueOf(n)) : clean;
    }
}
