package fr.influencraft.flux.session;

import fr.influencraft.flux.Flux;
import fr.influencraft.flux.menu.ClickKind;
import fr.influencraft.flux.menu.InventoryMode;
import fr.influencraft.flux.menu.MenuButton;
import fr.influencraft.flux.menu.Requirement;
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

    private final Flux plugin;
    private final MenuService service;

    public MenuListener(Flux plugin, MenuService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private static MenuSession sessionOf(InventoryEvent event) {
        InventoryView view = event.getView();
        return view.getTopInventory().getHolder(false) instanceof MenuHolder h ? h.session() : null;
    }

    /**
     * Ce clic doit-il être annulé (true pour tout ce qui n'est pas « ranger son inventaire » ou poser / reprendre un
     * objet dans la zone de dépôt). Un maj+clic depuis l'inventaire vers le menu est annulé ici puis rejoué à la main
     * vers la zone de dépôt seulement ({@link #shiftIntoInputs}).
     */
    private static boolean mustCancel(MenuSession s, InventoryClickEvent e) {
        if (s.menu().inventoryMode() != InventoryMode.INTERACTIVE) return true;
        int raw = e.getRawSlot();
        InventoryAction action = e.getAction();
        if (action == InventoryAction.COLLECT_TO_CURSOR || action == InventoryAction.UNKNOWN) return true;
        if (raw >= 0 && raw < s.topSize()) {
            // deposit zone: everything but a double click (which would collect buttons too)
            return !s.menu().isInput(raw);
        }
        return action == InventoryAction.MOVE_TO_OTHER_INVENTORY;
    }

    /** Shift-click from the player's inventory: moves the stack into the deposit zone only. */
    private void shiftIntoInputs(Player player, MenuSession s, InventoryClickEvent e) {
        org.bukkit.inventory.ItemStack moving = e.getCurrentItem();
        if (moving == null || moving.getType().isAir() || s.menu().inputSlots().length == 0) return;
        org.bukkit.inventory.Inventory top = s.inventory();
        int left = moving.getAmount();
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int slot : s.menu().inputSlots()) {
                if (left <= 0) break;
                org.bukkit.inventory.ItemStack there = top.getItem(slot);
                if (pass == 0 && there != null && there.isSimilar(moving) && there.getAmount() < there.getMaxStackSize()) {
                    int add = Math.min(left, there.getMaxStackSize() - there.getAmount());
                    there.setAmount(there.getAmount() + add);
                    left -= add;
                } else if (pass == 1 && (there == null || there.getType().isAir())) {
                    org.bukkit.inventory.ItemStack put = moving.clone();
                    put.setAmount(Math.min(left, moving.getMaxStackSize()));
                    top.setItem(slot, put);
                    left -= put.getAmount();
                }
            }
        }
        if (left == moving.getAmount()) return;
        if (left <= 0) e.setCurrentItem(null);
        else moving.setAmount(left);
        inputsChanged(player, s);
    }

    /** Runs the menu's input-change-actions on the next tick (contents of the deposit zone changed). */
    private void inputsChanged(Player player, MenuSession s) {
        if (s.menu().inputChangeActions().isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && s.isOpen()) plugin.actions().run(player, s, s.menu().inputChangeActions());
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        MenuSession s = sessionOf(e);
        if (s == null) return;
        if (!mustCancel(s, e)) {
            int raw = e.getRawSlot();
            if (raw >= 0 && raw < s.topSize() && e.getWhoClicked() instanceof Player player) inputsChanged(player, s);
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && e.getRawSlot() >= s.topSize()
                && s.menu().inventoryMode() == InventoryMode.INTERACTIVE) {
            shiftIntoInputs(player, s, e);
            return;
        }

        int raw = e.getRawSlot();
        // click in the player's own (visible) inventory: inventory-click-actions, %clicked_slot% = slot of his inventory
        if (raw >= s.topSize() && !s.hidesInventory()) {
            if (!s.menu().inventoryClickActions().isEmpty() && e.getSlot() >= 0 && !s.onCooldown(plugin.clickCooldown())) {
                String slot = String.valueOf(e.getSlot());
                plugin.actions().run(player, s, s.menu().inventoryClickActions().stream()
                        .map(a -> a.replace("%clicked_slot%", slot)).toList());
            }
            return;
        }
        // Hors coffre, les boutons n'existent qu'en mode hidden (zone du joueur réécrite).
        if (raw < 0) return;
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
            player.sendActionBar(fr.influencraft.flux.Text.line(player, button.confirm()));
            fr.influencraft.flux.api.util.Sounds.cue(player, "click");
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
            if (raw < s.topSize() && !s.menu().isInput(raw)) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragDone(InventoryDragEvent e) {
        MenuSession s = sessionOf(e);
        if (s == null || !(e.getWhoClicked() instanceof Player player)) return;
        for (int raw : e.getRawSlots()) {
            if (raw < s.topSize()) {
                inputsChanged(player, s);
                return;
            }
        }
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
        fr.influencraft.flux.api.list.Lists.forget(e.getPlayer());
        fr.influencraft.flux.menu.ListRenderer.forget(e.getPlayer());
        fr.influencraft.flux.api.util.PlayerSettings.forget(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        service.purgeButtons(e.getPlayer());
    }
}
