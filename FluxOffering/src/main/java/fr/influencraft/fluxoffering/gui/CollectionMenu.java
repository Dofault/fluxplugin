package fr.influencraft.fluxoffering.gui;

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
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders and drives the deposit / editor interface for a single collection
 * (the "/offrande &lt;collection&gt;" screen).
 */
public final class CollectionMenu {

    private final ConfigManager configManager;
    private final MessagesManager messages;
    private final DataManager dataManager;
    private final DepositService depositService;
    private final EditorChatListener editorChatListener;

    public CollectionMenu(ConfigManager configManager, MessagesManager messages, DataManager dataManager,
                           DepositService depositService, EditorChatListener editorChatListener) {
        this.configManager = configManager;
        this.messages = messages;
        this.dataManager = dataManager;
        this.depositService = depositService;
        this.editorChatListener = editorChatListener;
        editorChatListener.setCollectionMenu(this);
    }

    /**
     * Ouvre le menu en restaurant le dernier filtre d'affichage choisi par le joueur
     * (conserve dans son fichier de donnees, donc au-dela de la simple fermeture du menu).
     * Le mode edition, lui, montre toujours tout.
     */
    public void open(Player player, CollectionDefinition collection, boolean editorMode) {
        CollectionMenuHolder.FilterMode filterMode = editorMode
                ? CollectionMenuHolder.FilterMode.ALL
                : CollectionMenuHolder.FilterMode.fromStored(dataManager.get(player).menuFilterMode());
        openFresh(player, collection, 0, filterMode, editorMode);
    }

    /** Fait defiler le filtre d'affichage et memorise le nouveau choix. */
    public void cycleFilter(Player player, Inventory inv, CollectionMenuHolder holder) {
        CollectionMenuHolder.FilterMode next = holder.filterMode().next();
        holder.filterMode(next);
        holder.page(0);
        dataManager.get(player).menuFilterMode(next.name());
        refresh(player, inv, holder);
    }

    public void openFresh(Player player, CollectionDefinition collection, int page,
                           CollectionMenuHolder.FilterMode filterMode, boolean editorMode) {
        GuiSettings gui = configManager.gui();
        CollectionMenuHolder holder = new CollectionMenuHolder(collection.id());
        holder.page(page);
        holder.filterMode(filterMode);
        holder.editorMode(editorMode);

        String template = editorMode ? gui.editorTitleFormat() : gui.collectionTitleFormat();
        String titleRaw = template.replace("{displayname}", collection.displayName());
        Component title = TextUtil.parse(titleRaw, player);

        Inventory inv = Bukkit.createInventory(holder, gui.size(), title);
        holder.setInventory(inv);
        populate(player, inv, holder, collection);
        player.openInventory(inv);
        SoundUtil.play(player, gui.openSound());
    }

    public void refresh(Player player, Inventory inv, CollectionMenuHolder holder) {
        CollectionDefinition collection = configManager.getCollection(holder.collectionId());
        if (collection == null) {
            player.closeInventory();
            return;
        }
        populate(player, inv, holder, collection);
    }

    private void populate(Player player, Inventory inv, CollectionMenuHolder holder, CollectionDefinition collection) {
        GuiSettings gui = configManager.gui();
        List<Map.Entry<ItemKey, Integer>> entries = new ArrayList<>(collection.items().entrySet());

        if (!holder.editorMode() && holder.filterMode() != CollectionMenuHolder.FilterMode.ALL) {
            // Les deux filtres portent sur l'avancement de l'offrande, jamais sur l'inventaire :
            // "Depose" = exigence entierement remplie, "Non depose" = tout le reste.
            PlayerData data = dataManager.get(player);
            boolean keepCompleted = holder.filterMode() == CollectionMenuHolder.FilterMode.DEPOSITED;
            entries.removeIf(e -> (data.getDeposited(collection.id(), e.getKey()) >= e.getValue()) != keepCompleted);
        }

        List<Integer> allSlots = gui.itemSlots();
        // The 1st slot of the grid is reserved for the money button whenever this
        // offrande requests one (view mode), or always in editor mode so the admin has a
        // way to set one on a collection that doesn't have one yet — but only on the 1st
        // page: money behaves like any other single item of the offrande, it never repeats
        // across pages. Later pages use the full grid for item icons.
        boolean reserveMoneySlot = !allSlots.isEmpty() && (holder.editorMode() || collection.hasMoneyRequirement());
        Integer moneySlot = allSlots.isEmpty() ? null : allSlots.get(0);
        int firstPageCapacity = reserveMoneySlot ? allSlots.size() - 1 : allSlots.size();
        int otherPageCapacity = allSlots.size();

        int totalPages = entries.size() <= firstPageCapacity
                ? 1
                : 1 + (int) Math.ceil((entries.size() - firstPageCapacity) / (double) otherPageCapacity);
        int page = Math.max(0, Math.min(holder.page(), totalPages - 1));
        holder.page(page);

        boolean showMoneyThisPage = reserveMoneySlot && page == 0;
        List<Integer> itemDisplaySlots = showMoneyThisPage ? allSlots.subList(1, allSlots.size()) : allSlots;

        for (int slot : allSlots) {
            inv.setItem(slot, null);
        }

        int start = page == 0 ? 0 : firstPageCapacity + (page - 1) * otherPageCapacity;
        int capacity = page == 0 ? firstPageCapacity : otherPageCapacity;
        int end = Math.min(entries.size(), start + capacity);
        List<Map.Entry<ItemKey, Integer>> pageEntries = entries.subList(start, end);
        for (int i = 0; i < pageEntries.size() && i < itemDisplaySlots.size(); i++) {
            Map.Entry<ItemKey, Integer> entry = pageEntries.get(i);
            ItemStack icon = holder.editorMode()
                    ? buildEditorIcon(player, collection, entry.getKey(), entry.getValue())
                    : buildViewIcon(player, collection, entry.getKey(), entry.getValue());
            inv.setItem(itemDisplaySlots.get(i), icon);
        }

        if (moneySlot != null && showMoneyThisPage) {
            inv.setItem(moneySlot, holder.editorMode() ? buildEditorMoneyIcon(player, collection) : buildMoneyIcon(player, collection));
        }

        // Slot "page precedente" : porte le bouton retour (vers le menu principal) tant
        // qu'on est sur la 1ere page, faute de fleche vers la gauche a afficher a sa place.
        inv.setItem(gui.navPreviousSlot(), page > 0 ? GuiItems.navPrev(gui, messages, player) : GuiItems.back(gui, messages, player));
        inv.setItem(gui.navNextSlot(), page < totalPages - 1 ? GuiItems.navNext(gui, messages, player) : null);

        inv.setItem(gui.filterSlot(), holder.editorMode() ? null : GuiItems.filterToggle(gui, messages, player, holder.filterMode()));

        inv.setItem(gui.editorToggleSlot(), player.hasPermission("offrandes.admin")
                ? GuiItems.editorToggle(gui, messages, player, holder.editorMode())
                : null);

        boolean showDepositAll = !holder.editorMode() && !ProgressCalculator.isComplete(dataManager.get(player), collection);
        inv.setItem(gui.depositAllSlot(), showDepositAll ? GuiItems.depositAll(gui, messages, player) : null);
    }

    private boolean playerHasAny(Player player, ItemKey key) {
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && key.matches(stack)) {
                return true;
            }
        }
        return false;
    }

    private ItemStack buildViewIcon(Player viewer, CollectionDefinition collection, ItemKey key, int required) {
        PlayerData data = dataManager.get(viewer);
        int deposited = Math.min(required, data.getDeposited(collection.id(), key));
        boolean complete = deposited >= required;

        ItemStack stack = key.toDisplayStack(required);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        // Brillance enchantee = offrande completee (pas d'effet tant que c'est incomplet).
        // Un livre enchante brille deja nativement : on ne force rien pour lui.
        if (!key.isEnchantment()) {
            meta.setEnchantmentGlintOverride(complete);
        }

        String prettyName = collection.displayNameFor(key);
        String amountFormatted = AmountFormatter.format(messages, key, required);

        Map<String, String> ph = new HashMap<>();
        ph.put("name", prettyName);
        ph.put("deposited", String.valueOf(deposited));
        ph.put("required", String.valueOf(required));
        ph.put("amount_formatted", amountFormatted);
        ph.put("amount_literal", String.valueOf(required));
        ph.put("percent", String.valueOf(required <= 0 ? 100 : (int) Math.min(100, Math.round(deposited * 100.0 / required))));

        String nameTemplate = messages.raw(complete ? "gui.item-name-complete" : "gui.item-name-incomplete", ph);
        meta.displayName(TextUtil.parseItemText(nameTemplate, viewer));

        List<String> lore = new ArrayList<>(messages.rawList(key.isEnchantment() ? "gui.enchant-lore" : "gui.item-lore", ph));
        if (complete) {
            lore.addAll(messages.rawList("gui.item-lore-complete-suffix", ph));
        } else if (playerHasAny(viewer, key)) {
            lore.addAll(messages.rawList(key.isEnchantment() ? "gui.enchant-lore-hint" : "gui.item-lore-hint", ph));
        } else if (configManager.wikiLinks().urlFor(key) != null) {
            // Aucune action de depot possible (le joueur n'a pas l'objet) : on propose a la
            // place le lien vers la page wiki, pour qu'il sache ou et comment l'obtenir.
            lore.addAll(messages.rawList("gui.item-lore-wiki-hint", ph));
        }
        meta.lore(TextUtil.parseItemTextList(lore, viewer));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildEditorIcon(Player viewer, CollectionDefinition collection, ItemKey key, int required) {
        ItemStack stack = key.toDisplayStack(required);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        String prettyName = collection.displayNameFor(key);
        String amountFormatted = AmountFormatter.format(messages, key, required);
        Map<String, String> ph = Map.of(
                "name", prettyName,
                "amount_formatted", amountFormatted,
                "amount_literal", String.valueOf(required)
        );
        meta.displayName(TextUtil.parseItemText(messages.raw("gui.item-name-editor", ph), viewer));
        meta.lore(TextUtil.parseItemTextList(messages.rawList("gui.item-lore-editor", ph), viewer));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack moneyDisplayStack(GuiSettings gui) {
        try {
            return ItemKey.parse(gui.moneyItem()).toDisplayStack(1);
        } catch (IllegalArgumentException ex) {
            return new ItemStack(Material.SUNFLOWER);
        }
    }

    private ItemStack buildMoneyIcon(Player viewer, CollectionDefinition collection) {
        GuiSettings gui = configManager.gui();
        PlayerData data = dataManager.get(viewer);
        long required = collection.money();
        long deposited = Math.min(required, data.getMoneyDeposited(collection.id()));
        boolean complete = deposited >= required;

        ItemStack stack = moneyDisplayStack(gui);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.setEnchantmentGlintOverride(complete);

        Map<String, String> ph = new HashMap<>();
        ph.put("deposited", AmountFormatter.grouped(deposited));
        ph.put("required", AmountFormatter.grouped(required));
        ph.put("percent", String.valueOf(required <= 0 ? 100 : (int) Math.min(100, Math.round(deposited * 100.0 / required))));

        String nameKey = complete ? "gui.money-name-complete" : "gui.money-name";
        meta.displayName(TextUtil.parseItemText(messages.raw(nameKey, ph), viewer));

        List<String> lore = new ArrayList<>(messages.rawList("gui.money-lore", ph));
        if (complete) {
            lore.addAll(messages.rawList("gui.item-lore-complete-suffix", ph));
        }
        meta.lore(TextUtil.parseItemTextList(lore, viewer));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildEditorMoneyIcon(Player viewer, CollectionDefinition collection) {
        GuiSettings gui = configManager.gui();
        ItemStack stack = moneyDisplayStack(gui);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        Map<String, String> ph = Map.of("amount", AmountFormatter.grouped(collection.money()));
        String nameKey = collection.hasMoneyRequirement() ? "gui.money-name-editor" : "gui.money-name-editor-empty";
        meta.displayName(TextUtil.parseItemText(messages.raw(nameKey, ph), viewer));
        meta.lore(TextUtil.parseItemTextList(messages.rawList("gui.money-lore-editor", ph), viewer));
        stack.setItemMeta(meta);
        return stack;
    }

    // ---- interactions, called from GuiListener ----

    /**
     * Left click on a required item the player owns none of: there is nothing to deposit,
     * so the menu hands out the item's wiki page instead, as a clickable chat link built on
     * the same framed template as /discord.
     *
     * @return true if the wiki link was sent, false if the normal deposit path should run
     * (item already complete, player owns some of it, or no wiki page for this item).
     */
    public boolean handleWikiClick(Player player, CollectionDefinition collection, ItemKey key) {
        Integer required = collection.items().get(key);
        if (required == null || dataManager.get(player).getDeposited(collection.id(), key) >= required) {
            return false;
        }
        if (playerHasAny(player, key)) {
            return false;
        }
        String url = configManager.wikiLinks().urlFor(key);
        if (url == null) {
            return false;
        }
        String prettyName = collection.displayNameFor(key);
        Map<String, String> ph = new HashMap<>();
        ph.put("name", prettyName);
        // A client-translated tag can't be upper-cased server-side: keep it as is.
        ph.put("name_upper", prettyName.startsWith("<lang:") ? prettyName : prettyName.toUpperCase(java.util.Locale.FRENCH));
        ph.put("url", url);
        messages.sendList(player, "gui.wiki-link", ph, player);
        SoundUtil.play(player, configManager.gui().wikiSound());
        return true;
    }

    public void handleDepositClick(Player player, Inventory inv, CollectionMenuHolder holder,
                                    CollectionDefinition collection, ItemKey key, int maxAmount) {
        DepositResult result = depositService.deposit(player, collection, key, maxAmount);
        if (result == null) {
            return;
        }
        GuiSettings gui = configManager.gui();
        String prettyName = collection.displayNameFor(key);

        if (result.isNoop()) {
            SoundUtil.play(player, gui.errorSound());
            if (result.newTotal() >= result.required()) {
                messages.send(player, "gui.deposit-item-already-complete", Map.of("name", prettyName), player);
            } else {
                messages.send(player, key.isEnchantment() ? "gui.deposit-enchant-missing" : "gui.deposit-item-missing",
                        Map.of("name", prettyName), player);
            }
        } else {
            SoundUtil.play(player, gui.depositSound());
            PlayerData data = dataManager.get(player);
            int percent = ProgressCalculator.percent(data, collection);
            Map<String, String> ph = Map.of(
                    "amount", String.valueOf(result.deposited()),
                    "name", prettyName,
                    "deposited", String.valueOf(result.newTotal()),
                    "required", String.valueOf(result.required()),
                    "percent", String.valueOf(percent)
            );
            messages.send(player, key.isEnchantment() ? "gui.deposit-enchant-success" : "gui.deposit-success", ph, player);
            if (result.collectionJustCompleted()) {
                SoundUtil.play(player, gui.collectionCompleteSound());
                messages.send(player, "gui.collection-completed", Map.of("collection", collection.displayName()), player);
            }
        }
        refresh(player, inv, holder);
    }

    public void handleDepositAllClick(Player player, Inventory inv, CollectionMenuHolder holder, CollectionDefinition collection) {
        long totalUnits = 0;
        int typesAffected = 0;
        boolean collectionJustCompleted = false;
        for (ItemKey key : collection.items().keySet()) {
            DepositResult result = depositService.deposit(player, collection, key);
            if (result != null && result.deposited() > 0) {
                totalUnits += result.consumedItems();
                typesAffected++;
                collectionJustCompleted |= result.collectionJustCompleted();
            }
        }

        GuiSettings gui = configManager.gui();
        if (totalUnits == 0) {
            SoundUtil.play(player, gui.errorSound());
            messages.send(player, "gui.deposit-all-nothing", player);
        } else {
            SoundUtil.play(player, gui.depositSound());
            PlayerData data = dataManager.get(player);
            Map<String, String> ph = Map.of(
                    "amount", String.valueOf(totalUnits),
                    "types", String.valueOf(typesAffected),
                    "percent", String.valueOf(ProgressCalculator.percent(data, collection))
            );
            messages.send(player, "gui.deposit-all-success", ph, player);
            if (collectionJustCompleted) {
                SoundUtil.play(player, gui.collectionCompleteSound());
                messages.send(player, "gui.collection-completed", Map.of("collection", collection.displayName()), player);
            }
        }
        refresh(player, inv, holder);
    }

    public void handleEditorLeftClick(Player admin, CollectionDefinition collection, ItemKey key) {
        editorChatListener.beginPrompt(admin, collection.id(), key, collection.displayNameFor(key), false);
    }

    public void handleEditorRemove(Player admin, Inventory inv, CollectionMenuHolder holder,
                                    CollectionDefinition collection, ItemKey key) {
        String prettyName = collection.displayNameFor(key);
        collection.items().remove(key);
        collection.customNames().remove(key);
        configManager.saveCollections();
        messages.send(admin, "editor.item-removed", Map.of("name", prettyName), admin);
        refresh(admin, inv, holder);
    }

    public void handleEditorAddRequest(Player admin, CollectionDefinition collection, ItemStack clickedStack) {
        ItemKey key = enchantmentKeyToAdd(collection, clickedStack);
        if (key == null) {
            try {
                key = ItemKey.parse(rawKeyOf(clickedStack));
            } catch (IllegalArgumentException ex) {
                return;
            }
        }
        if (collection.items().containsKey(key)) {
            messages.send(admin, "editor.item-already-present", Map.of("name", collection.displayNameFor(key)), admin);
            return;
        }
        editorChatListener.beginPrompt(admin, collection.id(), key, key.fallbackDisplayName(), true);
    }

    public void handleMoneyDepositAllClick(Player player, Inventory inv, CollectionMenuHolder holder, CollectionDefinition collection) {
        MoneyDepositResult result = depositService.depositMoney(player, collection, Long.MAX_VALUE);
        reportMoneyDeposit(player, collection, result);
        refresh(player, inv, holder);
    }

    /**
     * Clic gauche sur la case argent : on ne demande pas le montant dans le tchat (le nombre
     * tape s'affichait alors publiquement, cf. {@link fr.influencraft.fluxoffering.command.OffrandeCommand}),
     * on ferme le menu et on envoie une commande cliquable que le joueur n'a plus qu'a completer
     * par son montant.
     */
    public void handleMoneyDepositCustomPrompt(Player player, CollectionDefinition collection) {
        long current = dataManager.get(player).getMoneyDeposited(collection.id());
        if (current >= collection.money()) {
            SoundUtil.play(player, configManager.gui().errorSound());
            messages.send(player, "gui.money-already-complete", player);
            return;
        }
        player.closeInventory();
        Map<String, String> ph = Map.of(
                "collection", collection.id(),
                "name", collection.displayName(),
                "remaining", AmountFormatter.grouped(collection.money() - current)
        );
        messages.sendList(player, "gui.money-command-prompt", ph, player);
    }

    /**
     * Called by "/offrande depot &lt;collection&gt; &lt;montant&gt;" once the amount parsed.
     */
    public void handleMoneyCommandDeposit(Player player, CollectionDefinition collection, long amount) {
        MoneyDepositResult result = depositService.depositMoney(player, collection, amount);
        reportMoneyDeposit(player, collection, result);
        open(player, collection, false);
    }

    public void handleEditorMoneyLeftClick(Player admin, CollectionDefinition collection) {
        editorChatListener.beginMoneyPrompt(admin, collection.id());
    }

    public void handleEditorMoneyRemove(Player admin, Inventory inv, CollectionMenuHolder holder, CollectionDefinition collection) {
        collection.money(0);
        configManager.saveCollections();
        messages.send(admin, "editor.money-removed", admin);
        refresh(admin, inv, holder);
    }

    private void reportMoneyDeposit(Player player, CollectionDefinition collection, MoneyDepositResult result) {
        GuiSettings gui = configManager.gui();
        if (result == null || result.isNoop()) {
            SoundUtil.play(player, gui.errorSound());
            if (result != null && result.newTotal() >= result.required()) {
                messages.send(player, "gui.money-already-complete", player);
            } else {
                messages.send(player, "gui.money-deposit-missing", player);
            }
            return;
        }
        SoundUtil.play(player, gui.depositSound());
        PlayerData data = dataManager.get(player);
        int percent = ProgressCalculator.percent(data, collection);
        Map<String, String> ph = Map.of(
                "amount", AmountFormatter.grouped(result.deposited()),
                "deposited", AmountFormatter.grouped(result.newTotal()),
                "required", AmountFormatter.grouped(result.required()),
                "percent", String.valueOf(percent)
        );
        messages.send(player, "gui.money-deposit-success", ph, player);
        if (result.collectionJustCompleted()) {
            SoundUtil.play(player, gui.collectionCompleteSound());
            messages.send(player, "gui.collection-completed", Map.of("collection", collection.displayName()), player);
        }
    }

    /**
     * An enchanted book clicked in editor mode registers an enchantment requirement (counted
     * in levels) rather than the ENCHANTED_BOOK material. A book holding several
     * enchantments adds the first one this collection doesn't request yet.
     *
     * @return null if the stack isn't an enchanted book with stored enchantments.
     */
    private ItemKey enchantmentKeyToAdd(CollectionDefinition collection, ItemStack stack) {
        if (!(stack.getItemMeta() instanceof EnchantmentStorageMeta meta) || !meta.hasStoredEnchants()) {
            return null;
        }
        ItemKey first = null;
        for (Enchantment enchantment : meta.getStoredEnchants().keySet()) {
            ItemKey key = ItemKey.ofEnchantment(enchantment);
            if (!collection.items().containsKey(key)) {
                return key;
            }
            if (first == null) {
                first = key;
            }
        }
        return first;
    }

    private String rawKeyOf(ItemStack stack) {
        String iaId = ItemsAdderHook.getNamespacedId(stack);
        if (iaId != null) {
            return iaId;
        }
        // Not ItemsAdder-tagged (e.g. a CustomFishing catch reusing an ItemsAdder model) —
        // fall back to matching by Material + CustomModelData against the whole registry.
        String byModel = ItemsAdderHook.findNamespacedIdByModelData(stack);
        return byModel != null ? byModel : stack.getType().name();
    }

    /**
     * Recovers the ItemKey an icon in one of our own slots represents, straight from the
     * displayed ItemStack (its material, or ItemsAdder namespaced id). Used by the click
     * listener instead of tracking a separate slot-to-key index, so it stays correct
     * regardless of filtering/pagination.
     */
    public ItemKey resolveDisplayedKey(ItemStack displayedStack) {
        // Our enchantment icons are books holding exactly the one requested enchantment.
        if (displayedStack.getItemMeta() instanceof EnchantmentStorageMeta meta && meta.getStoredEnchants().size() == 1) {
            return ItemKey.ofEnchantment(meta.getStoredEnchants().keySet().iterator().next());
        }
        try {
            return ItemKey.parse(rawKeyOf(displayedStack));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
