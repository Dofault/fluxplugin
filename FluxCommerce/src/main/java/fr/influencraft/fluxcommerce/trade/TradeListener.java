package fr.influencraft.fluxcommerce.trade;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sneak + right click on a player sends him a request (or accepts his); leaving or dying ends the trade, the offered
 * items going back to their owner (into his death drops when he dies without keeping his inventory).
 */
final class TradeListener implements Listener {

    /** One request per click: the client sends several interaction packets for one click. */
    private static final long CLICK_COOLDOWN_MS = 500;

    private final TradeModule plugin;
    private final Trades trades;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    TradeListener(TradeModule plugin, Trades trades) {
        this.plugin = plugin;
        this.trades = trades;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        // Paper 26.2 fires only PlayerInteractAtEntityEvent for a right click on an entity (one interaction packet, and
        // the event has no handler list of its own any more): it reaches this handler, never filter it out
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof Player target)) return;
        Player p = e.getPlayer();
        if (!p.isSneaking() || !plugin.getConfig().getBoolean("requests.sneak-click", true)) return;
        // NPCs are Player entities too: only a real player online with that id counts
        if (target.hasMetadata("NPC") || Bukkit.getPlayer(target.getUniqueId()) != target) return;
        if (!p.hasPermission(TradeModule.USE)) return;
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        e.setCancelled(true);
        if (last != null && now - last < CLICK_COOLDOWN_MS) return;
        lastClick.put(p.getUniqueId(), now);
        trades.request(p, target);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        lastClick.remove(p.getUniqueId());
        trades.forgetRequests(p.getUniqueId());
        Trade t = trades.of(p);
        if (t != null) trades.abort(t, "aborted-left", p.getUniqueId(), p, null);
    }

    // early: a graves plugin listening later finds the items among the drops
    @EventHandler(priority = EventPriority.LOW)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        Trade t = trades.of(p);
        if (t == null) return;
        // the death drops are already computed: the items join them (or the inventory, when it is kept)
        trades.abort(t, "aborted-death", p.getUniqueId(), p, e.getKeepInventory() ? null : e.getDrops());
        plugin.messages().send(p, "aborted-death-you");
    }
}
