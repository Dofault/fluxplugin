package fr.influencraft.flux.session;

import fr.influencraft.flux.Flux;
import fr.influencraft.flux.ItemFactory;
import fr.influencraft.flux.Text;
import fr.influencraft.flux.menu.Menu;
import fr.influencraft.flux.menu.Page;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ouverture / fermeture des menus et registre des sessions (lu par le fil réseau).
 *
 * <p>Ordre à l'ouverture : la session est inscrite AVANT {@code openInventory} pour que les
 * paquets de la nouvelle fenêtre soient déjà réécrits ; si l'ouverture échoue (événement
 * annulé par un autre plugin), on la retire et on renvoie le vrai inventaire au client.
 */
public final class MenuService {

    /** Durée (ticks) après fermeture pendant laquelle les actions du mode créatif sont refusées. */
    private static final int CREATIVE_GUARD_TICKS = 10;

    private final Flux plugin;
    private final ItemFactory items;
    private final Map<UUID, MenuSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> creativeGuard = new ConcurrentHashMap<>();

    public MenuService(Flux plugin, ItemFactory items) {
        this.plugin = plugin;
        this.items = items;
    }

    /** Session dont l'inventaire est masqué, pour le fil réseau. */
    public MenuSession hiddenSession(UUID playerId) {
        MenuSession s = sessions.get(playerId);
        return s != null && s.hidesInventory() ? s : null;
    }

    public MenuSession session(UUID playerId) {
        return sessions.get(playerId);
    }

    public boolean open(Player player, Menu menu) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> open(player, menu));
            return true;
        }
        if (!player.isOnline()) return false;
        if (!menu.permission().isEmpty() && !player.hasPermission(menu.permission())) {
            player.sendMessage(Text.line(player, plugin.prefix() + "&#FF5C5CTu n'as pas accès à ce menu."));
            return false;
        }

        MenuSession session = new MenuSession(player, menu, items);
        session.render(player);
        sessions.put(player.getUniqueId(), session);

        InventoryView view = player.openInventory(session.inventory());
        if (view == null || player.getOpenInventory().getTopInventory() != session.inventory()) {
            // Ouverture refusée (InventoryOpenEvent annulé...) : on n'a rien affiché.
            if (sessions.remove(player.getUniqueId(), session) && session.hidesInventory()) {
                player.updateInventory();
            }
            return false;
        }
        session.markOpen(ContainerIds.of(player, plugin.getLogger()));
        // Les paquets d'ouverture ont pu partir avant markOpen : on renvoie la fenêtre une fois
        // l'identifiant connu, pour que le client ait à coup sûr la zone du bas réécrite.
        if (session.hidesInventory()) player.updateInventory();

        if (menu.updateInterval() > 0) {
            session.setUpdater(Bukkit.getScheduler().runTaskTimer(plugin, () -> refresh(player, session),
                    menu.updateInterval(), menu.updateInterval()));
        }
        if (!menu.loopTitles().isEmpty()) {
            List<String> frames = menu.loopTitles();
            int[] index = {0};
            session.setLooper(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (!session.isOpen() || !player.isOnline()) return;
                showTitle(player, session, frames.get(index[0]++ % frames.size()));
            }, 1L, menu.loopInterval()));
        }
        plugin.actions().run(player, session, menu.openActions());
        // menus that play no sound when opening get the default one (menu-open-sound)
        if (menu.openActions().stream().noneMatch(a -> a.trim().startsWith("[sound]"))) {
            fr.influencraft.flux.api.util.Sounds.play(player, plugin.openSound());
        }
        return true;
    }

    private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer SECTION =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.builder()
                    .character('\u00a7').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    /**
     * Change de page sans rouvrir la fenêtre : les titres de l'animation défilent
     * ({@code InventoryView#setTitle} renvoie la fenêtre au client avec le même identifiant,
     * l'inventaire reste donc masqué), puis le fond et les boutons de la page s'affichent.
     * Retour au menu (target null) : boutons de la page retirés tout de suite, animation à l'envers.
     */
    public void switchPage(Player player, MenuSession session, Page target) {
        if (!session.isOpen() || sessions.get(player.getUniqueId()) != session || session.animating()) return;
        Page current = session.page();
        if (current == target) return;

        // Infobulles coupées dès l'appui, avant même le premier cadre : rien n'est cliquable
        // pendant une animation (cf. doc), autant ne rien laisser survoler non plus. Restaurées
        // au dernier cadre, une fois l'état final (page cible ou menu de base) en place.
        session.setMuteTooltips(true);

        // Page A -> menu : boutons de A retirés tout de suite, animation de A à l'envers.
        // Menu -> page B : animation de B, puis boutons de B.
        // Page A -> page B : A se replie (à l'envers, jusqu'au fond du menu) puis B se déploie.
        List<String> frames = new ArrayList<>();
        int interval;
        if (current != null) {
            List<String> back = new ArrayList<>(current.frames());
            java.util.Collections.reverse(back);
            frames.addAll(back);
            session.setPage(null);
        }
        // Dans tous les cas (y compris menu -> page B, où rien ne change de case tout de suite),
        // un rendu immédiat renvoie les boutons actuellement affichés avec l'infobulle coupée.
        session.render(player);

        if (target != null) {
            if (current != null) frames.add(session.menu().title());
            frames.addAll(target.frames());
            interval = target.frameInterval();
        } else {
            interval = current.frameInterval();
        }

        int[] index = {0};
        session.setAnimation(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!session.isOpen() || sessions.get(player.getUniqueId()) != session || !player.isOnline()) {
                session.stopAnimation();
                session.setMuteTooltips(false);
                return;
            }
            if (index[0] < frames.size()) {
                setTitle(player, session, frames.get(index[0]++));
                return;
            }
            session.stopAnimation();
            session.setMuteTooltips(false);
            if (target != null) session.setPage(target);
            // Boutons de l'état final (page cible ou menu de base), infobulles restaurées.
            session.render(player);
            // Fond final + chemins ; renvoie aussi le contenu, boutons de la page compris.
            String full = session.fullTitle(player);
            setTitle(player, session, full);
            session.setLastTitle(full);
        }, 0L, interval));
    }

    /**
     * Remplace la page affichée sans animation (pages d'un même panneau : précédent / suivant).
     * Depuis le menu sans page ouverte, retombe sur {@link #switchPage} (animation de dépliage).
     */
    public void swapPage(Player player, MenuSession session, Page target) {
        if (!session.isOpen() || sessions.get(player.getUniqueId()) != session || session.animating()) return;
        if (session.page() == null || target == null) {
            switchPage(player, session, target);
            return;
        }
        if (session.page() == target) return;
        long start = System.nanoTime();
        String full;
        Text.beginMemo(player);
        try {
            session.setPage(target);
            session.render(player);
            full = session.fullTitle(player);
        } finally {
            Text.endMemo();
        }
        setTitle(player, session, full); // renvoie aussi le contenu, boutons de la page compris
        session.setLastTitle(full);
        measure(session, start, "changement de page");
    }

    /** Recalcule les boutons (placeholders, conditions) sans toucher au titre affiché. */
    public void renderButtons(Player player, MenuSession session) {
        if (!session.isOpen() || sessions.get(player.getUniqueId()) != session) return;
        if (session.render(player) && session.hidesInventory()) player.updateInventory();
    }

    /** Affiche un titre tout de suite (animations libres : Plinko), sans bloquer les clics. */
    public void showTitle(Player player, MenuSession session, String raw) {
        if (!session.isOpen() || sessions.get(player.getUniqueId()) != session) return;
        setTitle(player, session, raw);
        session.setLastTitle(raw);
    }

    private void setTitle(Player player, MenuSession session, String raw) {
        InventoryView view = player.getOpenInventory();
        if (view.getTopInventory() != session.inventory()) return;
        view.setTitle(SECTION.serialize(Text.line(player, raw)));
    }

    private final java.util.Map<String, Long> slowWarned = new java.util.concurrent.ConcurrentHashMap<>();

    /** Signale une fois par page un affichage de plus de 20 ms (diagnostic de performance). */
    private void measure(MenuSession session, long start, String what) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        if (ms < 20) return;
        String page = session.menu().id() + (session.page() != null ? "/" + session.page().id() : "");
        // at most once every 30 s per page and operation, with where the time went
        long now = System.currentTimeMillis();
        Long last = slowWarned.get(page + what);
        if (last != null && now - last < 30_000) return;
        slowWarned.put(page + what, now);
        plugin.getLogger().warning("Affichage lent de " + page + " (" + what + ") : " + ms + " ms [" + RenderTimings.summary() + "]");
    }

    /** Recalcule un menu ouvert (placeholders, conditions d'affichage). */
    public void refresh(Player player, MenuSession session) {
        if (!session.isOpen() || sessions.get(player.getUniqueId()) != session || !player.isOnline()) {
            session.markClosed();
            return;
        }
        long start = System.nanoTime();
        boolean bottomChanged;
        String full = null;
        Text.beginMemo(player);
        try {
            bottomChanged = session.render(player);
            if (!session.animating()) full = session.fullTitle(player);
        } finally {
            Text.endMemo();
        }
        measure(session, start, "rafraîchissement");
        // Chemins de progression : le titre ne change que si la progression a bougé.
        if (full != null && !full.equals(session.lastTitle())) {
            session.setLastTitle(full);
            setTitle(player, session, full); // renvoie aussi tout le contenu
            return;
        }
        if (bottomChanged && session.hidesInventory()) player.updateInventory();
    }

    /**
     * Fermeture de la fenêtre (InventoryCloseEvent, quelle qu'en soit la cause). La session
     * n'est retirée que si c'est encore la session courante : en passant d'un menu à un autre,
     * la nouvelle est déjà inscrite et ne doit pas être effacée.
     */
    public void closed(Player player, MenuSession session) {
        session.markClosed();
        sessions.remove(player.getUniqueId(), session);
        giveBackInputs(player, session);
        if (session.hidesInventory() && plugin.isEnabled()) {
            creativeGuard.put(player.getUniqueId(), Bukkit.getCurrentTick() + CREATIVE_GUARD_TICKS);
            // Le serveur bascule sur la fenêtre d'inventaire après l'événement : on renvoie le
            // vrai contenu au tick suivant (le client garde les objets factices d'ici là).
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.updateInventory();
                    purgeButtons(player);
                }
            });
        }
        if (player.isOnline() && plugin.isEnabled()) plugin.actions().run(player, null, session.menu().closeActions());
    }

    public void quit(Player player) {
        MenuSession s = sessions.remove(player.getUniqueId());
        if (s != null) {
            s.markClosed();
            giveBackInputs(player, s);
        }
        creativeGuard.remove(player.getUniqueId());
    }

    /** Les actions du mode créatif sont refusées tant qu'un menu masqué est ouvert, et juste après. */
    public boolean creativeLocked(Player player) {
        if (hiddenSession(player.getUniqueId()) != null) return true;
        Integer until = creativeGuard.get(player.getUniqueId());
        if (until == null) return false;
        if (Bukkit.getCurrentTick() <= until) return true;
        creativeGuard.remove(player.getUniqueId());
        return false;
    }

    /** Ferme tous les menus (rechargement, arrêt) et renvoie tout de suite le vrai inventaire. */
    public void closeAll() {
        for (MenuSession s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.playerId());
            if (p != null && p.getOpenInventory().getTopInventory().getHolder(false) instanceof MenuHolder) {
                p.closeInventory();
            }
            sessions.remove(s.playerId(), s);
            s.markClosed();
            if (p != null) giveBackInputs(p, s);
            if (p != null && p.isOnline()) {
                p.updateInventory();
                purgeButtons(p);
            }
        }
        creativeGuard.clear();
    }

    /**
     * Whatever is still in the deposit zone goes back to its owner (closing, leaving, reload): never lost. What does
     * not fit drops at his feet, the last resort.
     */
    private void giveBackInputs(Player player, MenuSession session) {
        if (session.menu().inputSlots().length == 0) return;
        java.util.List<ItemStack> items = session.takeInputs();
        if (items.isEmpty()) return;
        items.removeIf(ItemFactory::isButton);
        fr.influencraft.flux.api.inventory.Inventories.giveOrDrop(player, items);
    }

    /**
     * Filet de sécurité : un objet de bouton ne doit jamais exister dans un vrai inventaire.
     * S'il en apparaît un (bug client, plugin tiers), il est supprimé et signalé.
     */
    public void purgeButtons(Player player) {
        PlayerInventory inv = player.getInventory();
        List<Integer> found = new ArrayList<>();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (ItemFactory.isButton(contents[i])) {
                inv.setItem(i, null);
                found.add(i);
            }
        }
        if (ItemFactory.isButton(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            found.add(-1);
        }
        Inventory ender = player.getEnderChest();
        for (int i = 0; i < ender.getSize(); i++) {
            if (ItemFactory.isButton(ender.getItem(i))) {
                ender.setItem(i, null);
                found.add(1000 + i);
            }
        }
        if (!found.isEmpty()) {
            plugin.getLogger().warning("Objet(s) de bouton retiré(s) de l'inventaire de " + player.getName()
                    + " (cases " + found + ")");
        }
    }
}
