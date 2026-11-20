package fr.influencraft.flux.adapters.packetevents;

import fr.influencraft.flux.api.client.Appearance;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the looks of {@link Appearance} right as the wearer's state changes, and never lets a drawn look become an item:
 * <ul>
 *   <li>invisibility and game mode read again on their changes (looks hidden over empty slots while invisible, his own
 *   slots never rewritten in creative);</li>
 *   <li>his own slots sent again the tick after anything his client may have predicted from the items it was shown:
 *   a click or drag in his inventory (shift click, number keys, drop key...), an armour piece put on with a right
 *   click, any change of his armour;</li>
 *   <li>a creative click carrying a marked item (a look drawn on his screen, never a real item) is refused.</li>
 * </ul>
 */
public final class AppearanceWatch implements Listener {

    private final Plugin flux;
    /** Wearers whose slots are sent again at the next tick (once per tick, whatever happened). */
    private final Set<UUID> pending = new HashSet<>();

    public AppearanceWatch(Plugin flux) {
        this.flux = flux;
    }

    /** His own slots sent again next tick (they are rewritten on their way). */
    private void resend(Player player) {
        if (!Appearance.wearing(player) || !pending.add(player.getUniqueId())) return;
        Bukkit.getScheduler().runTask(flux, () -> {
            pending.remove(player.getUniqueId());
            if (player.isOnline()) player.updateInventory();
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEffect(EntityPotionEffectEvent e) {
        if (e.getEntity() instanceof Player p && Appearance.wearing(p)) Bukkit.getScheduler().runTask(flux, () -> {
            if (p.isOnline()) Appearance.watch(p);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        if (Appearance.wearing(p)) Bukkit.getScheduler().runTask(flux, () -> {
            if (p.isOnline()) Appearance.watch(p);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmour(PlayerArmorChangeEvent e) {
        resend(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && e.getView().getTopInventory().getType() == InventoryType.CRAFTING) resend(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && e.getView().getTopInventory().getType() == InventoryType.CRAFTING) resend(p);
    }

    /** A right click with an armour piece in hand puts it on: his client swapped it with what it was shown. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction().isRightClick() && e.getItem() != null
                && e.getItem().getData(io.papermc.paper.datacomponent.DataComponentTypes.EQUIPPABLE) != null) resend(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        Appearance.endPreviews(e.getPlayer());
    }

        /** A creative client sends the items it shows: a drawn look never becomes an item. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCreative(InventoryCreativeEvent e) {
        if (!Appearance.marked(e.getCursor()) && !Appearance.marked(e.getCurrentItem())) return;
        e.setCancelled(true);
        if (e.getWhoClicked() instanceof Player p) Bukkit.getScheduler().runTask(flux, () -> {
            if (p.isOnline()) p.updateInventory();
        });
    }
}
