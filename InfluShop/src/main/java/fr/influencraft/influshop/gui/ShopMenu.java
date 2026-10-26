package fr.influencraft.influshop.gui;

import fr.influencraft.influshop.config.ConfigManager;
import fr.influencraft.influshop.config.ConfirmSettings;
import fr.influencraft.influshop.config.GuiSettings;
import fr.influencraft.influshop.config.MessagesManager;
import fr.influencraft.influshop.integration.PlayerPointsHook;
import fr.influencraft.influshop.model.CommandRunAs;
import fr.influencraft.influshop.model.ShopEntry;
import fr.influencraft.influshop.model.SortMode;
import fr.influencraft.influshop.text.TextUtil;
import fr.influencraft.influshop.util.AmountFormatter;
import fr.influencraft.influshop.util.SoundUtil;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Renders and drives the "/shop" interface: browsing/buying in view mode, add/price/remove
 * in editor mode (mirrors InfluOffrande's CollectionMenu — same GUI conventions, same grid).
 * <p>
 * Adding a catalog entry needs no per-source hook: whatever real item the admin drops in has
 * its material/model-data/name/lore captured once as a fixed snapshot (works for a vanilla
 * item, an ItemsAdder item, anything), then a small in-game Dialog (Paper's 1.21.6+ dialog
 * screens, {@link Dialog}) asks for the command to run at purchase and the price.
 */
public final class ShopMenu {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final MessagesManager messages;
    private final PriceChatListener priceChatListener;

    public ShopMenu(JavaPlugin plugin, ConfigManager configManager, MessagesManager messages, PriceChatListener priceChatListener) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messages = messages;
        this.priceChatListener = priceChatListener;
        priceChatListener.setShopMenu(this);
    }

    public void openFresh(Player player, int page, SortMode sortMode, boolean editorMode) {
        GuiSettings gui = configManager.gui();
        ShopMenuHolder holder = new ShopMenuHolder();
        holder.page(page);
        holder.sortMode(sortMode);
        holder.editorMode(editorMode);

        String template = editorMode ? gui.editorTitleFormat() : gui.shopTitleFormat();
        Component title = TextUtil.parse(template, player);

        Inventory inv = Bukkit.createInventory(holder, gui.size(), title);
        holder.setInventory(inv);
        populate(player, inv, holder);
        player.openInventory(inv);
        SoundUtil.play(player, gui.openSound());
    }

    public void refresh(Player player, Inventory inv, ShopMenuHolder holder) {
        populate(player, inv, holder);
    }

    private List<ShopEntry> sortedEntries(SortMode mode) {
        List<ShopEntry> list = new ArrayList<>(configManager.entries().values());
        // ALL keeps shop.yml's own order as-is (no comparator) — there's deliberately no
        // "by plugin/source" grouping: a player has no notion of what plugin an item comes
        // from, so that distinction never surfaces in the UI.
        switch (mode) {
            case PRICE_ASC -> list.sort(Comparator.comparingInt(ShopEntry::price));
            case PRICE_DESC -> list.sort(Comparator.<ShopEntry>comparingInt(ShopEntry::price).reversed());
            case ALL -> { /* natural order */ }
        }
        return list;
    }

    private void populate(Player viewer, Inventory inv, ShopMenuHolder holder) {
        GuiSettings gui = configManager.gui();
        List<ShopEntry> entries = sortedEntries(holder.sortMode());

        List<Integer> itemSlots = gui.itemSlots();
        int capacity = Math.max(1, itemSlots.size());
        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) capacity));
        int page = Math.max(0, Math.min(holder.page(), totalPages - 1));
        holder.page(page);

        for (int slot : itemSlots) {
            inv.setItem(slot, null);
        }
        holder.slotToEntryId().clear();

        int start = page * capacity;
        int end = Math.min(entries.size(), start + capacity);
        List<ShopEntry> pageEntries = entries.subList(start, end);
        for (int i = 0; i < pageEntries.size() && i < itemSlots.size(); i++) {
            ShopEntry entry = pageEntries.get(i);
            int slot = itemSlots.get(i);
            ItemStack icon = holder.editorMode() ? buildEditorIcon(viewer, entry) : buildViewIcon(viewer, entry);
            inv.setItem(slot, icon);
            holder.slotToEntryId().put(slot, entry.id());
        }

        inv.setItem(gui.navPreviousSlot(), page > 0 ? ShopGuiItems.navPrev(gui, messages, viewer) : ShopGuiItems.back(gui, messages, viewer));
        inv.setItem(gui.navNextSlot(), page < totalPages - 1 ? ShopGuiItems.navNext(gui, messages, viewer) : null);
        inv.setItem(gui.sortSlot(), ShopGuiItems.sortToggle(gui, messages, viewer, holder.sortMode()));
        inv.setItem(gui.editorToggleSlot(), viewer.hasPermission("influshop.admin")
                ? ShopGuiItems.editorToggle(gui, messages, viewer, holder.editorMode())
                : null);
        inv.setItem(gui.balanceSlot(), holder.editorMode() ? null : ShopGuiItems.balance(gui, messages, viewer, PlayerPointsHook.balance(viewer.getUniqueId())));
    }

    // ---- item icon building — always freshly constructed from the entry's own stored
    // snapshot, never cached: it's just string parsing + an ItemStack, cheap enough. ----

    private ItemStack buildBaseStack(ShopEntry entry) {
        Material material = entry.displayMaterial() != null ? entry.displayMaterial() : Material.BARRIER;
        ItemStack stack = new ItemStack(material);
        if (entry.displayModelData() > 0) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setCustomModelData(entry.displayModelData());
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    private String plainName(ShopEntry entry, Player viewer) {
        return PlainTextComponentSerializer.plainText().serialize(TextUtil.parseMiniMessageItemText(entry.displayName(), viewer));
    }

    private ItemStack decorate(Player viewer, ShopEntry entry, List<String> extraLoreLines) {
        ItemStack stack = buildBaseStack(entry);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        Component prefix = TextUtil.parseItemText(messages.raw("gui.price-prefix", Map.of("price", AmountFormatter.grouped(entry.price()))), viewer);
        // entry.displayName()/displayLore() are captured snapshots already serialized as
        // MiniMessage (see ShopMenu#handleEditorAddRequest) — parseMiniMessageItemText, not
        // parseItemText, which would mangle the hex tags already in there (see its javadoc).
        meta.displayName(prefix.append(TextUtil.parseMiniMessageItemText(entry.displayName(), viewer)));

        List<Component> lore = new ArrayList<>(TextUtil.parseMiniMessageItemTextList(entry.displayLore(), viewer));
        lore.addAll(TextUtil.parseItemTextList(extraLoreLines, viewer));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildViewIcon(Player viewer, ShopEntry entry) {
        boolean canAfford = PlayerPointsHook.balance(viewer.getUniqueId()) >= entry.price();
        List<String> hint = messages.rawList(canAfford ? "gui.buy-hint" : "gui.buy-hint-insufficient");
        return decorate(viewer, entry, hint);
    }

    private ItemStack buildEditorIcon(Player viewer, ShopEntry entry) {
        return decorate(viewer, entry, messages.rawList("gui.editor-hint"));
    }

    // ---- interactions, called from GuiListener ----

    /**
     * First step of a purchase: click on a catalog item only pre-checks funds/inventory space
     * and, if both pass, opens the confirmation menu — nothing is charged or given yet. Both
     * checks are re-run in {@link #handleConfirm} since time passes between the two clicks
     * (balance or inventory space can change in the meantime).
     */
    public void handlePurchaseClick(Player player, ShopMenuHolder holder, ShopEntry entry) {
        GuiSettings gui = configManager.gui();
        String name = plainName(entry, player);

        if (PlayerPointsHook.balance(player.getUniqueId()) < entry.price()) {
            SoundUtil.play(player, gui.errorSound());
            messages.send(player, "shop.purchase-insufficient-funds", Map.of("name", name, "price", AmountFormatter.grouped(entry.price())), player);
            return;
        }
        if (!hasFreeSlot(player)) {
            SoundUtil.play(player, gui.errorSound());
            messages.send(player, "shop.purchase-inventory-full", Map.of("name", name), player);
            return;
        }

        openConfirmMenu(player, entry, holder.page(), holder.sortMode());
    }

    private boolean hasFreeSlot(Player player) {
        return player.getInventory().firstEmpty() != -1;
    }

    private void openConfirmMenu(Player player, ShopEntry entry, int returnPage, SortMode returnSortMode) {
        ConfirmSettings confirm = configManager.confirm();
        ConfirmMenuHolder holder = new ConfirmMenuHolder(entry, returnPage, returnSortMode);

        Component title = TextUtil.parse(confirm.titleFormat(), player);
        Inventory inv = Bukkit.createInventory(holder, confirm.size(), title);
        holder.setInventory(inv);

        inv.setItem(confirm.itemSlot(), decorate(player, entry, List.of()));
        inv.setItem(confirm.confirmSlot(), ShopGuiItems.confirmButton(confirm, messages, player));
        inv.setItem(confirm.cancelSlot(), ShopGuiItems.cancelButton(confirm, messages, player));

        player.openInventory(inv);
        SoundUtil.play(player, configManager.gui().openSound());
    }

    public void handleConfirm(Player player, ConfirmMenuHolder confirmHolder) {
        ShopEntry entry = confirmHolder.entry();
        GuiSettings gui = configManager.gui();
        String name = plainName(entry, player);
        int price = entry.price();

        if (PlayerPointsHook.balance(player.getUniqueId()) < price) {
            SoundUtil.play(player, gui.errorSound());
            messages.send(player, "shop.purchase-insufficient-funds", Map.of("name", name, "price", AmountFormatter.grouped(price)), player);
            reopenShop(player, confirmHolder);
            return;
        }
        if (!hasFreeSlot(player)) {
            SoundUtil.play(player, gui.errorSound());
            messages.send(player, "shop.purchase-inventory-full", Map.of("name", name), player);
            reopenShop(player, confirmHolder);
            return;
        }
        if (!PlayerPointsHook.take(player.getUniqueId(), price)) {
            SoundUtil.play(player, gui.errorSound());
            reopenShop(player, confirmHolder);
            return;
        }

        String dispatched = entry.command().replace("%player%", player.getName());
        if (entry.runAs() == CommandRunAs.PLAYER) {
            Bukkit.dispatchCommand(player, dispatched);
        } else {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), dispatched);
        }
        SoundUtil.play(player, gui.purchaseSound());
        messages.send(player, "shop.purchase-success", Map.of("name", name, "price", AmountFormatter.grouped(price)), player);
        reopenShop(player, confirmHolder);
    }

    public void handleCancel(Player player, ConfirmMenuHolder confirmHolder) {
        messages.send(player, "shop.purchase-cancelled", player);
        reopenShop(player, confirmHolder);
    }

    private void reopenShop(Player player, ConfirmMenuHolder confirmHolder) {
        openFresh(player, confirmHolder.returnPage(), confirmHolder.returnSortMode(), false);
    }

    public void handleEditorLeftClick(Player admin, ShopEntry entry) {
        priceChatListener.beginPrompt(admin, entry, plainName(entry, admin));
    }

    public void handleEditorRemove(Player admin, Inventory inv, ShopMenuHolder holder, ShopEntry entry) {
        String name = plainName(entry, admin);
        configManager.entries().remove(entry.id());
        configManager.saveShop();
        messages.send(admin, "editor.item-removed", Map.of("name", name), admin);
        refresh(admin, inv, holder);
    }

    /**
     * Called when an admin drops a real item into the shop while in editor mode. Captures
     * material/model-data/name/lore straight off that ItemStack (works for anything — vanilla,
     * ItemsAdder, another plugin's custom item — no per-source hook needed) and opens the
     * command/price dialog.
     */
    public void handleEditorAddRequest(Player admin, ItemStack dropped) {
        Material material = dropped.getType();
        ItemMeta meta = dropped.getItemMeta();
        int modelData = (meta != null && meta.hasCustomModelData()) ? meta.getCustomModelData() : 0;

        MiniMessage mm = MiniMessage.miniMessage();
        String nameMini = (meta != null && meta.hasDisplayName())
                ? mm.serialize(meta.displayName())
                : mm.serialize(Component.translatable(material.translationKey()));
        List<String> loreMini = new ArrayList<>();
        if (meta != null && meta.hasLore() && meta.lore() != null) {
            for (Component line : meta.lore()) {
                loreMini.add(mm.serialize(line));
            }
        }

        openAddDialog(admin, material, modelData, nameMini, loreMini);
    }

    private void openAddDialog(Player admin, Material material, int modelData, String nameMini, List<String> loreMini) {
        ActionButton confirm = ActionButton.builder(Component.text("Valider"))
                .action(DialogAction.customClick((view, audience) -> Bukkit.getScheduler().runTask(plugin, () ->
                        handleDialogSubmit(admin, material, modelData, nameMini, loreMini, view.getText("command"), view.getText("price"))
                ), ClickCallback.Options.builder().build()))
                .build();

        DialogBase base = DialogBase.builder(Component.text("Ajouter au shop"))
                .inputs(List.of(
                        DialogInput.text("command", Component.text("Commande a l'achat (sans /, %player% = joueur)"))
                                .initial("")
                                .width(300)
                                .maxLength(1000)
                                .build(),
                        DialogInput.text("price", Component.text("Prix en Influence"))
                                .initial("0")
                                .width(150)
                                .maxLength(10)
                                .build()
                ))
                .build();

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.multiAction(List.of(confirm), null, 1)));

        admin.showDialog(dialog);
    }

    private void handleDialogSubmit(Player admin, Material material, int modelData, String nameMini, List<String> loreMini,
                                     String rawCommand, String rawPrice) {
        if (rawCommand == null || rawCommand.isBlank()) {
            messages.send(admin, "editor.invalid-command", admin);
            reopenEditor(admin);
            return;
        }
        int price;
        try {
            price = Integer.parseInt(rawPrice == null ? "" : rawPrice.trim().replace(" ", ""));
        } catch (NumberFormatException ex) {
            price = -1;
        }
        if (price < 0) {
            messages.send(admin, "editor.invalid-number", admin);
            reopenEditor(admin);
            return;
        }

        String plainNameForId = PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(nameMini));
        String id = configManager.freshEntryId(plainNameForId);
        ShopEntry entry = new ShopEntry(id, rawCommand.trim(), CommandRunAs.CONSOLE, material, modelData, nameMini, loreMini, price);
        configManager.entries().put(id, entry);
        configManager.saveShop();

        messages.send(admin, "editor.item-added", Map.of("name", plainName(entry, admin), "price", AmountFormatter.grouped(price)), admin);
        reopenEditor(admin);
    }

    /**
     * Called by {@link PriceChatListener} once a valid price has been typed in chat, editing
     * an EXISTING entry (adding a new one goes through the dialog above instead, which already
     * asks for the price up front).
     */
    public void handleChatPrice(Player admin, ShopEntry entry, int price) {
        entry.price(price);
        configManager.saveShop();
        messages.send(admin, "editor.price-set", Map.of("name", plainName(entry, admin), "price", AmountFormatter.grouped(price)), admin);
        reopenEditor(admin);
    }

    void reopenEditor(Player admin) {
        openFresh(admin, 0, SortMode.ALL, true);
    }
}
