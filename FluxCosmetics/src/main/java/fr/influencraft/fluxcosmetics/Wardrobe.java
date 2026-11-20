package fr.influencraft.fluxcosmetics;

import fr.influencraft.flux.api.client.Focus;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The wardrobe ({@code /tenue}): the players in it ({@link WardrobeView}), their tick, and what they may not do
 * meanwhile (his real items never move: the hotbar he sees is drawn by packets). Sneaking, the "leave" slot clicked,
 * {@code /tenue} again, a teleport by somebody else, death, world change or log out end it.
 */
final class Wardrobe implements Listener {

    /** Tag of the mannequins: never saved with the world, removed at once if one is ever found. */
    static final String TAG = "fluxcosmetics_wardrobe";

    private final FluxCosmetics plugin;
    private final Map<UUID, WardrobeView> views = new HashMap<>();
    private BukkitTask task;

    Wardrobe(FluxCosmetics plugin) {
        this.plugin = plugin;
    }

    boolean active(Player player) {
        return views.containsKey(player.getUniqueId());
    }

    void open(Player player) {
        if (active(player)) {
            plugin.messages().send(player, "already");
            return;
        }
        // the camera follows his mouse through packets: no packet adapter, no wardrobe
        if (!fr.influencraft.flux.api.client.Camera.available() || player.isDead() || player.isInsideVehicle() || player.isSleeping() || player.isGliding()
                || Focus.focused(player)) {
            plugin.messages().send(player, "busy");
            return;
        }
        WardrobeView view = new WardrobeView(plugin, player);
        views.put(player.getUniqueId(), view);
        view.open();
        for (Player other : Bukkit.getOnlinePlayers()) if (other != player) other.hidePlayer(plugin, player);
        plugin.messages().send(player, "opened");
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void close(Player player, boolean bringBack) {
        WardrobeView view = views.remove(player.getUniqueId());
        if (view == null || view.closing) return;
        view.close(bringBack);
        for (Player other : Bukkit.getOnlinePlayers()) if (other != player) other.showPlayer(plugin, player);
        if (player.isOnline()) plugin.messages().send(player, "closed");
        if (views.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    void closeAll() {
        for (WardrobeView view : new ArrayList<>(views.values())) close(view.player, true);
    }

    private void tick() {
        for (WardrobeView view : new ArrayList<>(views.values())) {
            if (!view.player.isOnline()) close(view.player, false);
            else view.tick();
        }
    }

    private WardrobeView view(Player player) {
        return views.get(player.getUniqueId());
    }

    // ================================================================== controls

    /** Slot 1 moves the camera, any other holds it still. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        WardrobeView view = view(e.getPlayer());
        if (view != null) view.held(e.getNewSlot());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (e.isSneaking() && active(e.getPlayer())) close(e.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!active(p)) return;
        e.setCancelled(true);
        if (p.getInventory().getHeldItemSlot() == WardrobeView.LEAVE_SLOT) {
            Bukkit.getScheduler().runTask(plugin, () -> close(p, true));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (active(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (active(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (active(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && active(p)) e.setCancelled(true);
    }

    /** Nothing hurts him nor his mannequin while he looks at himself. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && active(p)) e.setCancelled(true);
        else if (e.getEntity().getScoreboardTags().contains(TAG)) e.setCancelled(true);
    }

    // ================================================================== ways out

    /** Taken elsewhere by a command or another plugin: the wardrobe ends where he now is. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        WardrobeView view = view(e.getPlayer());
        // UNKNOWN: the server putting him back after a move it refused (the camera brushing a wall), not a way out
        if (view == null || view.moving || e.getCause() == PlayerTeleportEvent.TeleportCause.UNKNOWN) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (view(p) == view) close(p, false);
        });
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        close(e.getPlayer(), false);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        close(e.getEntity(), false);
    }

    /** He logs out where he stood, not around his mannequin. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        close(e.getPlayer(), true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player joined = e.getPlayer();
        for (WardrobeView view : views.values()) joined.hidePlayer(plugin, view.player);
    }
}
