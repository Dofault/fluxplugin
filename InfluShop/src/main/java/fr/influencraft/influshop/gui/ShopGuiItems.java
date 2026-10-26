package fr.influencraft.influshop.gui;

import fr.influencraft.influshop.config.ConfirmSettings;
import fr.influencraft.influshop.config.GuiSettings;
import fr.influencraft.influshop.config.MessagesManager;
import fr.influencraft.influshop.integration.ItemsAdderHook;
import fr.influencraft.influshop.model.SortMode;
import fr.influencraft.influshop.text.TextUtil;
import fr.influencraft.influshop.util.AmountFormatter;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;

/**
 * Builders for the fixed control buttons (navigation, sort, editor toggle, balance). Their
 * material can point at an ItemsAdder id ("namespace:id") or fall back to a plain vanilla
 * material if ItemsAdder isn't installed or the id doesn't resolve.
 */
final class ShopGuiItems {

    private ShopGuiItems() {
    }

    private static ItemStack base(String rawKey) {
        if (rawKey != null && rawKey.contains(":")) {
            ItemStack fromItemsAdder = ItemsAdderHook.createItemStack(rawKey);
            if (fromItemsAdder != null) {
                return fromItemsAdder;
            }
        }
        Material material = rawKey != null ? Material.matchMaterial(rawKey.toUpperCase(java.util.Locale.ROOT)) : null;
        return new ItemStack(material != null ? material : Material.ARROW);
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

    static ItemStack back(GuiSettings gui, MessagesManager messages, OfflinePlayer player) {
        return build(gui.backItem(), messages, "gui.back-name", "gui.back-lore", player);
    }

    static ItemStack sortToggle(GuiSettings gui, MessagesManager messages, OfflinePlayer player, SortMode mode) {
        String prefix = switch (mode) {
            case ALL -> "gui.sort-all";
            case PRICE_ASC -> "gui.sort-price-asc";
            case PRICE_DESC -> "gui.sort-price-desc";
        };
        return build(gui.sortItem(), messages, prefix + "-name", prefix + "-lore", player);
    }

    static ItemStack editorToggle(GuiSettings gui, MessagesManager messages, OfflinePlayer player, boolean editorMode) {
        String rawKey = editorMode ? gui.editorCloseItem() : gui.editorOpenItem();
        String nameKey = editorMode ? "gui.editor-exit-name" : "gui.editor-enter-name";
        String loreKey = editorMode ? "gui.editor-exit-lore" : "gui.editor-enter-lore";
        return build(rawKey, messages, nameKey, loreKey, player);
    }

    static ItemStack confirmButton(ConfirmSettings confirm, MessagesManager messages, OfflinePlayer player) {
        return build(confirm.confirmItem(), messages, "gui.confirm-button-name", "gui.confirm-button-lore", player);
    }

    static ItemStack cancelButton(ConfirmSettings confirm, MessagesManager messages, OfflinePlayer player) {
        return build(confirm.cancelItem(), messages, "gui.cancel-button-name", "gui.cancel-button-lore", player);
    }

    static ItemStack balance(GuiSettings gui, MessagesManager messages, OfflinePlayer player, int balance) {
        ItemStack stack = base(gui.balanceItem());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(TextUtil.parseItemText(messages.raw("gui.balance-name"), player));
            meta.lore(TextUtil.parseItemTextList(messages.rawList("gui.balance-lore", Map.of("balance", AmountFormatter.grouped(balance))), player));
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
