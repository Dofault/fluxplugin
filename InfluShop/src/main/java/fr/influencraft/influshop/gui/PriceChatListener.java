package fr.influencraft.influshop.gui;

import fr.influencraft.influshop.config.MessagesManager;
import fr.influencraft.influshop.model.ShopEntry;
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
 * Handles the chat-based "type a price" prompt used to change an EXISTING catalog entry's
 * price (left-click on it in editor mode). Adding a brand new entry goes through the in-game
 * Dialog instead (see ShopMenu#handleEditorAddRequest), which already asks for the price up
 * front alongside the command — this listener only ever edits an entry already in the map.
 */
public final class PriceChatListener implements Listener {

    private final JavaPlugin plugin;
    private final MessagesManager messages;
    private final Map<UUID, ShopEntry> pending = new ConcurrentHashMap<>();
    private ShopMenu shopMenu;

    public PriceChatListener(JavaPlugin plugin, MessagesManager messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    void setShopMenu(ShopMenu shopMenu) {
        this.shopMenu = shopMenu;
    }

    public void beginPrompt(Player admin, ShopEntry entry, String displayName) {
        pending.put(admin.getUniqueId(), entry);
        admin.closeInventory();
        messages.send(admin, "editor.prompt-price", Map.of("name", displayName), admin);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        ShopEntry entry = pending.get(player.getUniqueId());
        if (entry == null) {
            return;
        }
        // Cancel as early as possible: chat-formatting plugins (AdvancedChat) hook this same
        // event and can broadcast their own formatted copy regardless of a later handler.
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        plugin.getServer().getScheduler().runTask(plugin, () -> handleInput(player, entry, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    private void handleInput(Player admin, ShopEntry entry, String text) {
        if (text.equalsIgnoreCase("annuler") || text.equalsIgnoreCase("cancel")) {
            pending.remove(admin.getUniqueId());
            messages.send(admin, "editor.cancelled", admin);
            if (shopMenu != null) {
                shopMenu.reopenEditor(admin);
            }
            return;
        }

        int price;
        try {
            price = Integer.parseInt(text.replace(" ", ""));
        } catch (NumberFormatException ex) {
            messages.send(admin, "editor.invalid-number", admin);
            return; // keep the prompt pending so the admin can retry
        }
        if (price < 0) {
            messages.send(admin, "editor.invalid-number", admin);
            return;
        }

        pending.remove(admin.getUniqueId());
        if (shopMenu != null) {
            shopMenu.handleChatPrice(admin, entry, price);
        }
    }
}
