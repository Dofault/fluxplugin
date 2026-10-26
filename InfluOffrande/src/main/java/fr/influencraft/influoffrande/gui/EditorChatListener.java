package fr.influencraft.influoffrande.gui;

import fr.influencraft.influoffrande.config.ConfigManager;
import fr.influencraft.influoffrande.config.MessagesManager;
import fr.influencraft.influoffrande.model.CollectionDefinition;
import fr.influencraft.influoffrande.model.ItemKey;
import fr.influencraft.influoffrande.util.AmountFormatter;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the chat-based "type a quantity" prompt used by the collection editor to set/add
 * item requirements, since arbitrary quantities (potentially far above 64) don't fit a
 * click-to-increment interaction. No inventory needs to be manually closed: opening chat
 * on the client already closes whatever inventory was open, which fires the normal close event.
 */
public final class EditorChatListener implements Listener {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final MessagesManager messages;
    private final Map<UUID, PendingQuantityInput> pending = new ConcurrentHashMap<>();
    // Separate from `pending`: an admin editing a collection's money requirement rather
    // than an item's quantity (no ItemKey involved). Value is the collection id.
    private final Map<UUID, String> pendingMoney = new ConcurrentHashMap<>();
    private CollectionMenu collectionMenu;

    public EditorChatListener(JavaPlugin plugin, ConfigManager configManager, MessagesManager messages) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.messages = messages;
    }

    /**
     * Set once both objects exist (avoids a constructor cycle with CollectionMenu, which
     * needs this listener to start prompts).
     */
    void setCollectionMenu(CollectionMenu collectionMenu) {
        this.collectionMenu = collectionMenu;
    }

    public void beginPrompt(Player admin, String collectionId, ItemKey key, String prettyName, boolean isNewItem) {
        pending.put(admin.getUniqueId(), new PendingQuantityInput(collectionId, key, isNewItem));
        // Close explicitly rather than relying on the client auto-closing the inventory when
        // chat opens: makes it obvious the quantity has to be typed, and lets the admin freely
        // reopen/browse other inventories while a prompt is pending without it fighting for focus.
        admin.closeInventory();
        messages.send(admin, "editor.prompt-quantity", Map.of("name", prettyName), admin);
    }

    /**
     * Starts the "type the requested BitBlock amount in chat" prompt for the collection
     * editor's money button (left click). Same mechanism as {@link #beginPrompt}, kept in
     * a separate map since there's no ItemKey involved.
     */
    public void beginMoneyPrompt(Player admin, String collectionId) {
        pendingMoney.put(admin.getUniqueId(), collectionId);
        admin.closeInventory();
        messages.send(admin, "editor.prompt-money", admin);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PendingQuantityInput input = pending.get(player.getUniqueId());
        String moneyCollectionId = pendingMoney.get(player.getUniqueId());
        if (input == null && moneyCollectionId == null) {
            return;
        }
        // Must cancel as early as possible: chat-formatting plugins (AdvancedChat) hook
        // this same event and can broadcast their own formatted copy of the message
        // regardless of what a later-priority handler does, so LOW wasn't early enough
        // to reliably beat them to it.
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (moneyCollectionId != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> handleMoneyInput(player, moneyCollectionId, text));
        } else {
            plugin.getServer().getScheduler().runTask(plugin, () -> handleInput(player, input, text));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
        pendingMoney.remove(event.getPlayer().getUniqueId());
    }

    private void handleMoneyInput(Player admin, String collectionId, String text) {
        if (text.equalsIgnoreCase("annuler") || text.equalsIgnoreCase("cancel")) {
            pendingMoney.remove(admin.getUniqueId());
            messages.send(admin, "editor.cancelled", admin);
            reopen(admin, collectionId);
            return;
        }

        long amount;
        try {
            amount = Long.parseLong(text.replace(" ", ""));
        } catch (NumberFormatException ex) {
            messages.send(admin, "editor.invalid-number", admin);
            return; // keep the prompt pending so the admin can retry
        }
        if (amount <= 0) {
            messages.send(admin, "editor.invalid-number", admin);
            return;
        }

        CollectionDefinition collection = configManager.getCollection(collectionId);
        if (collection == null) {
            pendingMoney.remove(admin.getUniqueId());
            return;
        }

        collection.money(amount);
        configManager.saveCollections();
        pendingMoney.remove(admin.getUniqueId());

        messages.send(admin, "editor.money-set", Map.of("amount", AmountFormatter.grouped(amount)), admin);
        reopen(admin, collectionId);
    }

    private void handleInput(Player player, PendingQuantityInput input, String text) {
        if (text.equalsIgnoreCase("annuler") || text.equalsIgnoreCase("cancel")) {
            pending.remove(player.getUniqueId());
            messages.send(player, "editor.cancelled", player);
            reopen(player, input.collectionId());
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(text.replace(" ", ""));
        } catch (NumberFormatException ex) {
            messages.send(player, "editor.invalid-number", player);
            return; // keep the prompt pending so the admin can retry
        }
        if (amount <= 0) {
            messages.send(player, "editor.invalid-number", player);
            return;
        }

        CollectionDefinition collection = configManager.getCollection(input.collectionId());
        if (collection == null) {
            pending.remove(player.getUniqueId());
            return;
        }

        collection.items().put(input.itemKey(), amount);
        configManager.saveCollections();
        pending.remove(player.getUniqueId());

        Map<String, String> ph = Map.of(
                "name", collection.displayNameFor(input.itemKey()),
                "amount", String.valueOf(amount)
        );
        messages.send(player, input.isNewItem() ? "editor.item-added" : "editor.item-quantity-set", ph, player);
        reopen(player, input.collectionId());
    }

    private void reopen(Player player, String collectionId) {
        CollectionDefinition collection = configManager.getCollection(collectionId);
        if (collection == null || collectionMenu == null) {
            return;
        }
        collectionMenu.openFresh(player, collection, 0, CollectionMenuHolder.FilterMode.ALL, true);
    }
}
