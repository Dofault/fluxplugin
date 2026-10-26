package fr.influencraft.fluxmenu.session;

import fr.influencraft.fluxmenu.FluxMenu;
import fr.influencraft.fluxmenu.menu.ClickKind;
import fr.influencraft.fluxmenu.menu.InventoryMode;
import fr.influencraft.fluxmenu.menu.MenuButton;
import fr.influencraft.fluxmenu.menu.Requirement;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;

import java.util.List;

/**
 * Clics, glisser-déposer et fermeture des menus.
 *
 * <p>Règle de sécurité : le coffre du menu n'est jamais modifiable. Seul le mode
 * {@code interactive} laisse le joueur manipuler SON inventaire (sans rien pouvoir envoyer
 * vers le menu ni en retirer). L'annulation est posée au plus tôt (LOWEST) puis réimposée
 * au plus tard (HIGHEST), au cas où un autre plugin la lèverait entre les deux.
 */
public final class MenuListener implements Listener {

    private final FluxMenu plugin;
    private final MenuService service;

    public MenuListener(FluxMenu plugin, MenuService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private static MenuSession sessionOf(InventoryEvent event) {
        InventoryView view = event.getView();
        return view.getTopInventory().getHolder(false) instanceof MenuHolder h ? h.session() : null;
    }

    /** Ce clic doit-il être annulé (true pour tout ce qui n'est pas « ranger son inventaire »). */
    private static boolean mustCancel(MenuSession s, InventoryClickEvent e) {
        if (s.menu().inventoryMode() != InventoryMode.INTERACTIVE) return true;
        int raw = e.getRawSlot();
        if (raw >= 0 && raw < s.topSize()) return true;
        InventoryAction action = e.getAction();
        return action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.COLLECT_TO_CURSOR
                || action == InventoryAction.UNKNOWN;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        MenuSession s = sessionOf(e);
        if (s == null) return;
        if (!mustCancel(s, e)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;

        int raw = e.getRawSlot();
        // Hors coffre, les boutons n'existent qu'en mode hidden (zone du joueur réécrite).
        if (raw < 0 || (raw >= s.topSize() && !s.hidesInventory())) return;
        // Zone du bas, ou double clic n'importe où (le client regroupe à la souris les objets
        // semblables de toute la fenêtre, zone du bas comprise) : fenêtre renvoyée.
        if (raw >= s.topSize() || e.getClick() == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK) resyncBottom(player, s);
        MenuButton button = s.buttonAt(raw);
        if (button == null || s.animating()) return;
        ClickKind kind = ClickKind.of(e.getClick());
        if (kind == null) return;
        if (s.onCooldown(plugin.clickCooldown())) return;
        // Bouton à confirmer : le 1er clic prévient (barre d'action + son), le 2e agit.
        if (button.confirm() != null && !s.confirmClick(button)) {
            player.sendActionBar(fr.influencraft.fluxmenu.Text.line(player, button.confirm()));
            player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 0.8f);
            return;
        }

        if (!Requirement.all(button.clickRequirements(), player)) {
            plugin.actions().run(player, s, button.denyActions());
            return;
        }
        List<String> actions = button.actionsFor(kind);
        plugin.actions().run(player, s, actions);
    }

    /**
     * Clic dans la zone du joueur (menu masqué) ou double clic : le client a déjà « pris » l'objet factice à la
     * souris et ne le rend que si on lui renvoie la fenêtre. Un rafraîchissement ne le fait que
     * si la zone du bas change (une variante qui brille...) : sinon l'objet cliqué disparaissait
     * jusqu'à la réouverture (menu Marché, 2026-10-18). On renvoie donc la fenêtre après les
     * actions du clic (jouées au tick suivant), le masquage réécrit la zone du bas au passage.
     */
    private void resyncBottom(Player player, MenuSession s) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && s.isOpen()) player.updateInventory();
        }, 2L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClickEnforce(InventoryClickEvent e) {
        MenuSession s = sessionOf(e);
        if (s != null && mustCancel(s, e)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (dragMustCancel(e)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDragEnforce(InventoryDragEvent e) {
        if (dragMustCancel(e)) e.setCancelled(true);
    }

    private static boolean dragMustCancel(InventoryDragEvent e) {
        MenuSession s = sessionOf(e);
        if (s == null) return false;
        if (s.menu().inventoryMode() != InventoryMode.INTERACTIVE) return true;
        for (int raw : e.getRawSlots()) {
            if (raw < s.topSize()) return true;
        }
        return false;
    }

    /**
     * Le client en créatif peut écrire directement dans l'inventaire (paquet « case créative »).
     * Juste après un menu masqué, il croit encore voir les objets factices : on refuse tout
     * pendant le menu et quelques ticks après, le temps que le vrai contenu lui soit renvoyé.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCreative(InventoryCreativeEvent e) {
        if (e.getWhoClicked() instanceof Player p && service.creativeLocked(p)) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) p.updateInventory();
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        MenuSession s = sessionOf(e);
        if (s != null && e.getPlayer() instanceof Player p) service.closed(p, s);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        service.quit(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        service.purgeButtons(e.getPlayer());
    }
}
