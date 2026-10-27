package fr.influencraft.fluxoffering.gui;

import fr.influencraft.flux.api.FluxAction;
import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.ListProvider;
import fr.influencraft.flux.api.list.ListQuery;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.fluxoffering.config.ConfigManager;
import fr.influencraft.fluxoffering.config.GuiSettings;
import fr.influencraft.fluxoffering.config.MessagesManager;
import fr.influencraft.fluxoffering.data.DataManager;
import fr.influencraft.fluxoffering.integration.ItemsAdderHook;
import fr.influencraft.fluxoffering.model.CollectionDefinition;
import fr.influencraft.fluxoffering.model.ItemKey;
import fr.influencraft.fluxoffering.model.PlayerData;
import fr.influencraft.fluxoffering.service.DepositResult;
import fr.influencraft.fluxoffering.service.DepositService;
import fr.influencraft.fluxoffering.service.MoneyDepositResult;
import fr.influencraft.fluxoffering.text.TextUtil;
import fr.influencraft.fluxoffering.util.AmountFormatter;
import fr.influencraft.fluxoffering.util.ProgressCalculator;
import fr.influencraft.fluxoffering.util.SoundUtil;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The offering menu, a Flux menu ({@code menus/offering.yml}) showing the list {@code offering}: the money and items
 * requested by the collection the player opened, with their progress. Clicks go through the {@code [offering]}
 * action:
 * <pre>
 *   [offering] click &lt;entry id&gt; left|right   deposit all / one, wiki link, money (custom amount / all), editor edit / remove
 *   [offering] depositall                     deposit everything possible
 *   [offering] editor                         switch the admin editor mode
 *   [offering] additem                        editor: request the item held in hand
 * </pre>
 * Amounts are typed in dialog windows, never in the chat (a typed number used to be shown publicly by chat
 * formatting plugins). Filters of the list: everything, deposited, not deposited (editor mode shows everything).
 */
public final class OfferingMenu implements ListProvider, FluxAction {

    public static final String MENU = "offering";
    public static final String LIST = "offering";
    private static final String MONEY = "money";

    private final Plugin plugin;
    private final ConfigManager configManager;
    private final MessagesManager messages;
    private final DataManager dataManager;
    private final DepositService depositService;
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();
    private final Set<UUID> editors = ConcurrentHashMap.newKeySet();

    public OfferingMenu(Plugin plugin, ConfigManager configManager, MessagesManager messages, DataManager dataManager,
                        DepositService depositService) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messages = messages;
        this.dataManager = dataManager;
        this.depositService = depositService;
    }

    /** Collection the player looks at, or null. */
    public CollectionDefinition selected(Player player) {
        String id = selected.get(player.getUniqueId());
        return id == null ? null : configManager.getCollection(id);
    }

    public boolean editing(Player player) {
        return editors.contains(player.getUniqueId());
    }

    public void forget(UUID uuid) {
        selected.remove(uuid);
        editors.remove(uuid);
    }

    public void open(Player player, CollectionDefinition collection, boolean editorMode) {
        boolean changed = !collection.id().equals(selected.put(player.getUniqueId(), collection.id()));
        if (editorMode) editors.add(player.getUniqueId());
        else editors.remove(player.getUniqueId());
        // another collection: back to page 1; the filter is kept while the player stays online
        if (changed) Lists.act(player, LIST, "first", 21);
        Lists.invalidate(player, LIST);
        if (FluxApi.open(player, MENU)) SoundUtil.play(player, configManager.gui().openSound());
    }

    // ---------------------------------------------------------------- list "offering"

    @Override
    public List<String> filters() {
        return List.of(messages.raw("gui.filter-label-all"), messages.raw("gui.filter-label-deposited"),
                messages.raw("gui.filter-label-not-deposited"));
    }

    @Override
    public List<ListEntry> entries(ListQuery query) {
        Player player = query.viewer();
        CollectionDefinition collection = selected(player);
        List<ListEntry> out = new ArrayList<>();
        if (collection == null) return out;
        boolean editor = editing(player);
        PlayerData data = dataManager.get(player);
        if (editor || collection.hasMoneyRequirement()) {
            out.add(ListEntry.of(MONEY).item(editor ? editorMoneyIcon(player, collection) : moneyIcon(player, collection)));
        }
        for (Map.Entry<ItemKey, Integer> e : collection.items().entrySet()) {
            boolean complete = data.getDeposited(collection.id(), e.getKey()) >= e.getValue();
            if (!editor && query.filter() == 1 && !complete) continue;
            if (!editor && query.filter() == 2 && complete) continue;
            ItemStack icon = editor ? editorIcon(player, collection, e.getKey(), e.getValue()) : viewIcon(player, collection, e.getKey(), e.getValue());
            out.add(ListEntry.of(e.getKey().raw()).item(icon).set("name", collection.displayNameFor(e.getKey())));
        }
        return out;
    }

    private boolean playerHasAny(Player player, ItemKey key) {
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && key.matches(stack)) return true;
        }
        return false;
    }

    private static int percent(long deposited, long required) {
        return required <= 0 ? 100 : (int) Math.min(100, Math.round(deposited * 100.0 / required));
    }

    private ItemStack viewIcon(Player viewer, CollectionDefinition collection, ItemKey key, int required) {
        int deposited = Math.min(required, dataManager.get(viewer).getDeposited(collection.id(), key));
        boolean complete = deposited >= required;
        ItemStack stack = key.toDisplayStack(required);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        // enchanted glint = requirement complete (an enchanted book already shines by itself)
        if (!key.isEnchantment()) meta.setEnchantmentGlintOverride(complete);
        Map<String, String> ph = new HashMap<>();
        ph.put("name", collection.displayNameFor(key));
        ph.put("deposited", String.valueOf(deposited));
        ph.put("required", String.valueOf(required));
        ph.put("amount_formatted", AmountFormatter.format(messages, key, required));
        ph.put("amount_literal", String.valueOf(required));
        ph.put("percent", String.valueOf(percent(deposited, required)));
        meta.displayName(TextUtil.parseItemText(messages.raw(complete ? "gui.item-name-complete" : "gui.item-name-incomplete", ph), viewer));
        List<String> lore = new ArrayList<>(messages.rawList(key.isEnchantment() ? "gui.enchant-lore" : "gui.item-lore", ph));
        if (complete) {
            lore.addAll(messages.rawList("gui.item-lore-complete-suffix", ph));
        } else if (playerHasAny(viewer, key)) {
            lore.addAll(messages.rawList(key.isEnchantment() ? "gui.enchant-lore-hint" : "gui.item-lore-hint", ph));
        } else if (configManager.wikiLinks().urlFor(key) != null) {
            // nothing to deposit (the player has none): the wiki page tells where to get it
            lore.addAll(messages.rawList("gui.item-lore-wiki-hint", ph));
        }
        meta.lore(fr.influencraft.flux.api.text.Texts.wrapLore(TextUtil.parseItemTextList(lore, viewer)));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack editorIcon(Player viewer, CollectionDefinition collection, ItemKey key, int required) {
        ItemStack stack = key.toDisplayStack(required);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        Map<String, String> ph = Map.of("name", collection.displayNameFor(key),
                "amount_formatted", AmountFormatter.format(messages, key, required), "amount_literal", String.valueOf(required));
        meta.displayName(TextUtil.parseItemText(messages.raw("gui.item-name-editor", ph), viewer));
        meta.lore(fr.influencraft.flux.api.text.Texts.wrapLore(TextUtil.parseItemTextList(messages.rawList("gui.item-lore-editor", ph), viewer)));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack moneyStack() {
        try {
            return ItemKey.parse(configManager.gui().moneyItem()).toDisplayStack(1);
        } catch (IllegalArgumentException ex) {
            return new ItemStack(Material.SUNFLOWER);
        }
    }

    private ItemStack moneyIcon(Player viewer, CollectionDefinition collection) {
        long required = collection.money();
        long deposited = Math.min(required, dataManager.get(viewer).getMoneyDeposited(collection.id()));
        boolean complete = deposited >= required;
        ItemStack stack = moneyStack();
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.setEnchantmentGlintOverride(complete);
        Map<String, String> ph = Map.of("deposited", AmountFormatter.grouped(deposited), "required", AmountFormatter.grouped(required),
                "percent", String.valueOf(percent(deposited, required)));
        meta.displayName(TextUtil.parseItemText(messages.raw(complete ? "gui.money-name-complete" : "gui.money-name", ph), viewer));
        List<String> lore = new ArrayList<>(messages.rawList("gui.money-lore", ph));
        if (complete) lore.addAll(messages.rawList("gui.item-lore-complete-suffix", ph));
        meta.lore(fr.influencraft.flux.api.text.Texts.wrapLore(TextUtil.parseItemTextList(lore, viewer)));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack editorMoneyIcon(Player viewer, CollectionDefinition collection) {
        ItemStack stack = moneyStack();
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        Map<String, String> ph = Map.of("amount", AmountFormatter.grouped(collection.money()));
        meta.displayName(TextUtil.parseItemText(messages.raw(collection.hasMoneyRequirement() ? "gui.money-name-editor"
                : "gui.money-name-editor-empty", ph), viewer));
        meta.lore(fr.influencraft.flux.api.text.Texts.wrapLore(TextUtil.parseItemTextList(messages.rawList("gui.money-lore-editor", ph), viewer)));
        stack.setItemMeta(meta);
        return stack;
    }

    // ---------------------------------------------------------------- [offering] action

    @Override
    public void run(Player player, MenuView view, String argument) {
        CollectionDefinition collection = selected(player);
        if (collection == null) {
            if (view != null) view.close();
            return;
        }
        String[] parts = argument.trim().split("\\s+");
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "click" -> {
                if (parts.length < 2) return;
                boolean right = parts.length > 2 && parts[2].equalsIgnoreCase("right");
                click(player, collection, parts[1], right);
            }
            case "depositall" -> {
                if (!editing(player)) depositAll(player, collection);
            }
            case "editor" -> {
                if (player.hasPermission("offrandes.admin")) {
                    open(player, collection, !editing(player));
                    return;
                }
            }
            case "additem" -> {
                if (editing(player) && player.hasPermission("offrandes.admin")) addHeldItem(player, collection);
                return;
            }
            default -> plugin.getLogger().warning("[offering] unknown action « " + argument + " »");
        }
        refresh(player, view);
    }

    private void refresh(Player player, MenuView view) {
        Lists.invalidate(player, LIST);
        if (view != null) view.refresh();
    }

    private void click(Player player, CollectionDefinition collection, String id, boolean right) {
        boolean editor = editing(player) && player.hasPermission("offrandes.admin");
        if (id.equals(MONEY)) {
            if (editor) {
                if (right) {
                    collection.money(0);
                    configManager.saveCollections();
                    messages.send(player, "editor.money-removed", player);
                } else {
                    askMoneyRequirement(player, collection);
                }
            } else if (right) {
                reportMoney(player, collection, depositService.depositMoney(player, collection, Long.MAX_VALUE));
            } else {
                askMoneyDeposit(player, collection);
            }
            return;
        }
        ItemKey key;
        try {
            key = ItemKey.parse(id);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!collection.items().containsKey(key)) return;
        if (editor) {
            if (right) {
                String name = collection.displayNameFor(key);
                collection.items().remove(key);
                collection.customNames().remove(key);
                configManager.saveCollections();
                messages.send(player, "editor.item-removed", Map.of("name", name), player);
            } else {
                askQuantity(player, collection, key, collection.displayNameFor(key), false);
            }
            return;
        }
        if (!right && sendWiki(player, collection, key)) return;
        deposit(player, collection, key, right ? 1 : Integer.MAX_VALUE);
    }

    /** Left click on a requested item the player has none of: its wiki page instead of a deposit. */
    private boolean sendWiki(Player player, CollectionDefinition collection, ItemKey key) {
        Integer required = collection.items().get(key);
        if (required == null || dataManager.get(player).getDeposited(collection.id(), key) >= required) return false;
        if (playerHasAny(player, key)) return false;
        String url = configManager.wikiLinks().urlFor(key);
        if (url == null) return false;
        String name = collection.displayNameFor(key);
        Map<String, String> ph = new HashMap<>();
        ph.put("name", name);
        // a client-translated tag cannot be upper-cased on the server: kept as is
        ph.put("name_upper", name.startsWith("<lang:") ? name : name.toUpperCase(Locale.FRENCH));
        ph.put("url", url);
        messages.sendList(player, "gui.wiki-link", ph, player);
        SoundUtil.play(player, configManager.gui().wikiSound());
        return true;
    }

    private void deposit(Player player, CollectionDefinition collection, ItemKey key, int max) {
        DepositResult result = depositService.deposit(player, collection, key, max);
        if (result == null) return;
        GuiSettings gui = configManager.gui();
        String name = collection.displayNameFor(key);
        if (result.isNoop()) {
            SoundUtil.play(player, gui.errorSound());
            if (result.newTotal() >= result.required()) {
                messages.send(player, "gui.deposit-item-already-complete", Map.of("name", name), player);
            } else {
                messages.send(player, key.isEnchantment() ? "gui.deposit-enchant-missing" : "gui.deposit-item-missing", Map.of("name", name), player);
            }
            return;
        }
        SoundUtil.play(player, gui.depositSound());
        Map<String, String> ph = Map.of("amount", String.valueOf(result.deposited()), "name", name,
                "deposited", String.valueOf(result.newTotal()), "required", String.valueOf(result.required()),
                "percent", String.valueOf(ProgressCalculator.percent(dataManager.get(player), collection)));
        messages.send(player, key.isEnchantment() ? "gui.deposit-enchant-success" : "gui.deposit-success", ph, player);
        if (result.collectionJustCompleted()) completed(player, collection);
    }

    private void depositAll(Player player, CollectionDefinition collection) {
        long units = 0;
        int types = 0;
        boolean justCompleted = false;
        for (ItemKey key : collection.items().keySet()) {
            DepositResult result = depositService.deposit(player, collection, key);
            if (result != null && result.deposited() > 0) {
                units += result.consumedItems();
                types++;
                justCompleted |= result.collectionJustCompleted();
            }
        }
        GuiSettings gui = configManager.gui();
        if (units == 0) {
            SoundUtil.play(player, gui.errorSound());
            messages.send(player, "gui.deposit-all-nothing", player);
            return;
        }
        SoundUtil.play(player, gui.depositSound());
        messages.send(player, "gui.deposit-all-success", Map.of("amount", String.valueOf(units), "types", String.valueOf(types),
                "percent", String.valueOf(ProgressCalculator.percent(dataManager.get(player), collection))), player);
        if (justCompleted) completed(player, collection);
    }

    private void completed(Player player, CollectionDefinition collection) {
        SoundUtil.play(player, configManager.gui().collectionCompleteSound());
        messages.send(player, "gui.collection-completed", Map.of("collection", collection.displayName()), player);
    }

    /** Custom money amount, typed in a dialog. */
    private void askMoneyDeposit(Player player, CollectionDefinition collection) {
        long current = dataManager.get(player).getMoneyDeposited(collection.id());
        if (current >= collection.money()) {
            SoundUtil.play(player, configManager.gui().errorSound());
            messages.send(player, "gui.money-already-complete", player);
            return;
        }
        String remaining = AmountFormatter.grouped(collection.money() - current);
        Dialogs.form(player, collection.displayName())
                .body(messages.raw("gui.money-dialog-body", Map.of("remaining", remaining)))
                .requiredInput("amount", messages.raw("gui.money-dialog-label"), "", 16)
                .onSubmit((p, answer) -> {
                    long amount = Format.parseAmount(answer.text("amount"));
                    if (amount <= 0) messages.send(p, "gui.money-invalid", p);
                    else reportMoney(p, collection, depositService.depositMoney(p, collection, amount));
                    open(p, collection, false);
                })
                .onCancel(p -> open(p, collection, false))
                .open();
    }

    /** {@code /offrande depot <collection> <amount>}: kept for the former clickable command. */
    public void depositMoneyCommand(Player player, CollectionDefinition collection, long amount) {
        reportMoney(player, collection, depositService.depositMoney(player, collection, amount));
        open(player, collection, false);
    }

    private void reportMoney(Player player, CollectionDefinition collection, MoneyDepositResult result) {
        GuiSettings gui = configManager.gui();
        if (result == null || result.isNoop()) {
            SoundUtil.play(player, gui.errorSound());
            messages.send(player, result != null && result.newTotal() >= result.required()
                    ? "gui.money-already-complete" : "gui.money-deposit-missing", player);
            return;
        }
        SoundUtil.play(player, gui.depositSound());
        messages.send(player, "gui.money-deposit-success", Map.of("amount", AmountFormatter.grouped(result.deposited()),
                "deposited", AmountFormatter.grouped(result.newTotal()), "required", AmountFormatter.grouped(result.required()),
                "percent", String.valueOf(ProgressCalculator.percent(dataManager.get(player), collection))), player);
        if (result.collectionJustCompleted()) completed(player, collection);
    }

    // ---------------------------------------------------------------- editor

    private void askQuantity(Player admin, CollectionDefinition collection, ItemKey key, String name, boolean isNew) {
        Integer current = collection.items().get(key);
        Dialogs.form(admin, collection.displayName())
                .body(messages.raw("editor.dialog-quantity", Map.of("name", name)))
                .requiredInput("amount", messages.raw("editor.dialog-quantity-label"), current == null ? "" : String.valueOf(current), 10)
                .onSubmit((p, answer) -> {
                    long amount = Format.parseAmount(answer.text("amount"));
                    if (amount <= 0 || amount > Integer.MAX_VALUE) {
                        messages.send(p, "editor.invalid-number", p);
                    } else {
                        collection.items().put(key, (int) amount);
                        configManager.saveCollections();
                        messages.send(p, isNew ? "editor.item-added" : "editor.item-quantity-set",
                                Map.of("name", collection.displayNameFor(key), "amount", String.valueOf(amount)), p);
                    }
                    open(p, collection, true);
                })
                .onCancel(p -> open(p, collection, true))
                .open();
    }

    private void askMoneyRequirement(Player admin, CollectionDefinition collection) {
        Dialogs.form(admin, collection.displayName())
                .body(messages.raw("editor.dialog-money"))
                .requiredInput("amount", messages.raw("editor.dialog-quantity-label"),
                        collection.hasMoneyRequirement() ? String.valueOf(collection.money()) : "", 16)
                .onSubmit((p, answer) -> {
                    long amount = Format.parseAmount(answer.text("amount"));
                    if (amount <= 0) {
                        messages.send(p, "editor.invalid-number", p);
                    } else {
                        collection.money(amount);
                        configManager.saveCollections();
                        messages.send(p, "editor.money-set", Map.of("amount", AmountFormatter.grouped(amount)), p);
                    }
                    open(p, collection, true);
                })
                .onCancel(p -> open(p, collection, true))
                .open();
    }

    /** Requests the item held in hand (an enchanted book requests its first enchantment not requested yet). */
    private void addHeldItem(Player admin, CollectionDefinition collection) {
        ItemStack held = admin.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            messages.send(admin, "editor.hold-item", admin);
            return;
        }
        ItemKey key = enchantmentKeyToAdd(collection, held);
        if (key == null) {
            try {
                key = ItemKey.parse(rawKeyOf(held));
            } catch (IllegalArgumentException ex) {
                return;
            }
        }
        if (collection.items().containsKey(key)) {
            messages.send(admin, "editor.item-already-present", Map.of("name", collection.displayNameFor(key)), admin);
            return;
        }
        askQuantity(admin, collection, key, key.fallbackDisplayName(), true);
    }

    private ItemKey enchantmentKeyToAdd(CollectionDefinition collection, ItemStack stack) {
        if (!(stack.getItemMeta() instanceof EnchantmentStorageMeta meta) || !meta.hasStoredEnchants()) return null;
        ItemKey first = null;
        for (Enchantment enchantment : meta.getStoredEnchants().keySet()) {
            ItemKey key = ItemKey.ofEnchantment(enchantment);
            if (!collection.items().containsKey(key)) return key;
            if (first == null) first = key;
        }
        return first;
    }

    private String rawKeyOf(ItemStack stack) {
        String iaId = ItemsAdderHook.getNamespacedId(stack);
        if (iaId != null) return iaId;
        // not tagged by ItemsAdder (e.g. a CustomFishing catch reusing an ItemsAdder model): match by model data
        String byModel = ItemsAdderHook.findNamespacedIdByModelData(stack);
        return byModel != null ? byModel : stack.getType().name();
    }
}
