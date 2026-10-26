package fr.influencraft.fluxoffering.gui;

import fr.influencraft.fluxoffering.config.GuiSettings;
import fr.influencraft.fluxoffering.config.MessagesManager;
import fr.influencraft.fluxoffering.model.ItemKey;
import fr.influencraft.fluxoffering.text.TextUtil;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Builders for the fixed control buttons (navigation, filter toggle, editor toggle).
 * Their material can point at an ItemsAdder id (namespace:id) or fall back to a plain
 * vanilla material if ItemsAdder isn't installed or the id doesn't resolve.
 */
final class GuiItems {

    private GuiItems() {
    }

    private static ItemStack base(String rawKey) {
        try {
            return ItemKey.parse(rawKey).toDisplayStack(1);
        } catch (IllegalArgumentException ex) {
            return new ItemStack(Material.ARROW);
        }
    }

    private static ItemStack build(String rawKey, MessagesManager messages, String nameKey, String loreKey, OfflinePlayer player) {
        ItemStack stack = base(rawKey);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(TextUtil.parseItemText(messages.raw(nameKey), player));
            meta.lore(TextUtil.parseItemTextList(messages.rawList(loreKey), player));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    static ItemStack navPrev(GuiSettings gui, MessagesManager messages, OfflinePlayer player) {
        return build(gui.navPrevItem(), messages, "gui.nav-prev-name", "gui.nav-prev-lore", player);
    }

    static ItemStack navNext(GuiSettings gui, MessagesManager messages, OfflinePlayer player) {
        return build(gui.navNextItem(), messages, "gui.nav-next-name", "gui.nav-next-lore", player);
    }

    static ItemStack filterToggle(GuiSettings gui, MessagesManager messages, OfflinePlayer player, CollectionMenuHolder.FilterMode mode) {
        String nameKey = switch (mode) {
            case ALL -> "gui.filter-all-name";
            case DEPOSITED -> "gui.filter-deposited-name";
            case NOT_DEPOSITED -> "gui.filter-not-deposited-name";
        };
        String loreKey = switch (mode) {
            case ALL -> "gui.filter-all-lore";
            case DEPOSITED -> "gui.filter-deposited-lore";
            case NOT_DEPOSITED -> "gui.filter-not-deposited-lore";
        };
        return build(gui.filterItem(), messages, nameKey, loreKey, player);
    }

    static ItemStack back(GuiSettings gui, MessagesManager messages, OfflinePlayer player) {
        return build(gui.backItem(), messages, "gui.back-name", "gui.back-lore", player);
    }

    static ItemStack editorToggle(GuiSettings gui, MessagesManager messages, OfflinePlayer player, boolean editorMode) {
        String rawKey = editorMode ? gui.editorCloseItem() : gui.editorOpenItem();
        String nameKey = editorMode ? "gui.editor-exit-name" : "gui.editor-enter-name";
        String loreKey = editorMode ? "gui.editor-exit-lore" : "gui.editor-enter-lore";
        return build(rawKey, messages, nameKey, loreKey, player);
    }

    static ItemStack depositAll(GuiSettings gui, MessagesManager messages, OfflinePlayer player) {
        return build(gui.depositAllItem(), messages, "gui.deposit-all-name", "gui.deposit-all-lore", player);
    }
}
