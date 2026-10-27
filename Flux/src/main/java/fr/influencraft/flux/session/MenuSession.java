package fr.influencraft.flux.session;

import fr.influencraft.flux.ItemFactory;
import fr.influencraft.flux.Text;
import fr.influencraft.flux.menu.InventoryMode;
import fr.influencraft.flux.menu.Menu;
import fr.influencraft.flux.menu.MenuButton;
import fr.influencraft.flux.menu.Page;
import fr.influencraft.flux.menu.Requirement;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Un menu ouvert par un joueur. Tout est fait sur le fil principal, sauf la lecture des
 * objets factices du bas ({@link #fakeBottom()}, {@link #matchesWindow(int)}) par le fil
 * réseau qui réécrit les paquets : ces champs sont volatils et remplacés d'un bloc.
 *
 * <p>Le vrai inventaire du joueur n'est jamais modifié : la zone du bas n'existe que dans
 * les paquets envoyés au client. Rien à restaurer à la fermeture, à la déconnexion ou au crash.
 */
public final class MenuSession {

    private final UUID playerId;
    private final Menu menu;
    private final MenuHolder holder;
    private final Inventory inventory;
    private final ItemFactory items;

    /** Bouton affiché sur chaque case (coffre + zone du joueur), choisi au dernier rendu. */
    private MenuButton[] shown;
    /** Objets de la zone du joueur (36 cases, ordre de la fenêtre), version Bukkit et paquet. */
    private ItemStack[] bottomBukkit = new ItemStack[36];
    private volatile com.github.retrooper.packetevents.protocol.item.ItemStack[] bottomPacket =
            new com.github.retrooper.packetevents.protocol.item.ItemStack[36];
    private volatile int windowId = -1;
    private volatile boolean open;
    private BukkitTask updater;
    /** Animation de titre en boucle (title-loop), arrêtée à la fermeture. */
    private BukkitTask looper;
    /** Page affichée (null = menu de base) et animation en cours. */
    private Page page;
    private BukkitTask animation;
    /** Dernier titre envoyé (fond + chemins), pour ne le renvoyer que s'il change. */
    private String lastTitle;
    private long lastClickTick = Long.MIN_VALUE / 2;
    /** Bouton « confirm » cliqué une première fois, et quand (double clic de confirmation). */
    private MenuButton armed;
    private long armedTick;
    /** Infobulles coupées de force (animation de page en cours) : cf. {@link ItemFactory#build}. */
    private boolean muteTooltips;

    MenuSession(Player player, Menu menu, ItemFactory items) {
        this.playerId = player.getUniqueId();
        this.menu = menu;
        this.items = items;
        this.holder = new MenuHolder();
        this.lastTitle = fullTitle(player);
        this.inventory = Bukkit.createInventory(holder, menu.topSize(), Text.line(player, lastTitle));
        // Une case de menu peut afficher jusqu'à 99 objets (quantité = niveau, etc.) : sans ça,
        // l'inventaire ramène toute quantité à 64.
        this.inventory.setMaxStackSize(99);
        this.holder.bind(this, inventory);
        this.shown = new MenuButton[menu.totalSlots()];
    }

    public Menu menu() {
        return menu;
    }

    public UUID playerId() {
        return playerId;
    }

    public Inventory inventory() {
        return inventory;
    }

    public boolean hidesInventory() {
        // Bedrock clients (Geyser) do not take the rewritten inventory packets: their inventory stays visible
        return menu.inventoryMode() == InventoryMode.HIDDEN && !fr.influencraft.flux.api.client.Clients.bedrock(playerId);
    }

    public boolean isOpen() {
        return open;
    }

    void markOpen(int windowId) {
        this.windowId = windowId;
        this.open = true;
    }

    void markClosed() {
        this.open = false;
        if (updater != null) {
            updater.cancel();
            updater = null;
        }
        if (looper != null) {
            looper.cancel();
            looper = null;
        }
        stopAnimation();
    }

    /** Titre complet : fond du menu ou de la page, chemins de progression, puis calques conditionnels. */
    public String fullTitle(Player player) {
        Text.beginMemo(player);
        try {
            return fullTitleNow(player);
        } finally {
            Text.endMemo();
        }
    }

    private String fullTitleNow(Player player) {
        String base = page != null ? page.title() : menu.title();
        List<fr.influencraft.flux.menu.PathSpec> paths = new ArrayList<>(menu.paths());
        if (page != null) paths.addAll(page.paths());
        StringBuilder full = new StringBuilder(base)
                .append(fr.influencraft.flux.PathRenderer.render(player, base, menu.rows(), paths));
        for (fr.influencraft.flux.menu.TitleLayer layer : menu.titleLayers()) {
            if (fr.influencraft.flux.menu.Requirement.all(layer.requirements(), player)) full.append(layer.text());
        }
        return full.toString();
    }

    String lastTitle() {
        return lastTitle;
    }

    void setLastTitle(String title) {
        this.lastTitle = title;
    }

    public Page page() {
        return page;
    }

    void setPage(Page page) {
        this.page = page;
    }

    /** Une animation de page est en cours : les clics sont ignorés. */
    public boolean animating() {
        return animation != null;
    }

    void setAnimation(BukkitTask task) {
        this.animation = task;
    }

    /** Coupe (ou restaure) l'infobulle de tous les boutons au prochain {@link #render}. */
    void setMuteTooltips(boolean mute) {
        this.muteTooltips = mute;
    }

    void stopAnimation() {
        if (animation != null) {
            animation.cancel();
            animation = null;
        }
    }

    void setUpdater(BukkitTask task) {
        this.updater = task;
    }

    void setLooper(BukkitTask task) {
        this.looper = task;
    }

    /** Fil réseau : cette fenêtre est-elle la nôtre ? (-1 = identifiant inconnu, toute fenêtre non nulle). */
    public boolean matchesWindow(int id) {
        int mine = windowId;
        return mine == -1 ? id > 0 : id == mine;
    }

    /** Fil réseau : objets à afficher dans la zone du joueur (null = case vide). */
    public com.github.retrooper.packetevents.protocol.item.ItemStack[] fakeBottom() {
        return bottomPacket;
    }

    public int topSize() {
        return menu.topSize();
    }

    /** Items in the deposit zone ({@code input-slots}), by slot; empty slots left out. */
    public java.util.Map<Integer, ItemStack> inputs() {
        java.util.Map<Integer, ItemStack> out = new java.util.LinkedHashMap<>();
        for (int slot : menu.inputSlots()) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) out.put(slot, item);
        }
        return out;
    }

    /** Removes the items of the deposit zone and returns them (the caller now owns them). */
    public java.util.List<ItemStack> takeInputs() {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        for (int slot : menu.inputSlots()) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) out.add(item);
            inventory.setItem(slot, null);
        }
        return out;
    }

    /** Bouton affiché sur une case brute de la fenêtre, null si aucun. */
    public MenuButton buttonAt(int rawSlot) {
        return rawSlot >= 0 && rawSlot < shown.length ? shown[rawSlot] : null;
    }

    /**
     * Bouton à double clic (option « confirm ») : true si ce clic confirme (2e clic sur le même
     * bouton dans les 3 secondes), false s'il ne fait qu'armer la confirmation.
     */
    public boolean confirmClick(MenuButton button) {
        long now = Bukkit.getCurrentTick();
        if (armed == button && now - armedTick <= 60) {
            armed = null;
            return true;
        }
        armed = button;
        armedTick = now;
        return false;
    }

    /** Anti double clic : true si le clic doit être ignoré. */
    public boolean onCooldown(int cooldownTicks) {
        long now = Bukkit.getCurrentTick();
        if (now - lastClickTick < cooldownTicks) return true;
        lastClickTick = now;
        return false;
    }

    /**
     * Recalcule boutons et objets (placeholders, conditions). Retourne true si la zone du
     * joueur a changé : il faut alors renvoyer la fenêtre au client.
     */
    boolean render(Player player) {
        Text.beginMemo(player);
        try {
            return renderNow(player);
        } finally {
            Text.endMemo();
        }
    }

    private boolean renderNow(Player player) {
        int total = menu.totalSlots();
        MenuButton[] next = new MenuButton[total];
        // Boutons de la page d'abord : ils passent devant ceux du menu sur une même case.
        // Order on a same slot: lists of the page, page buttons, lists of the menu, menu buttons.
        List<MenuButton> ordered = new ArrayList<>();
        if (page != null) {
            ordered.addAll(fr.influencraft.flux.menu.ListRenderer.buttons(player, menu, page.lists()));
            List<MenuButton> pageButtons = new ArrayList<>(page.buttons());
            pageButtons.sort(Comparator.comparingInt(MenuButton::priority));
            ordered.addAll(pageButtons);
        }
        ordered.addAll(fr.influencraft.flux.menu.ListRenderer.buttons(player, menu, menu.lists()));
        List<MenuButton> base = new ArrayList<>(menu.buttons());
        base.sort(Comparator.comparingInt(MenuButton::priority));
        ordered.addAll(base);
        for (MenuButton b : ordered) {
            // Toutes ses cases déjà prises par une variante prioritaire : inutile d'évaluer ses conditions.
            boolean free = false;
            for (int slot : b.slots()) {
                if (slot < total && next[slot] == null) {
                    free = true;
                    break;
                }
            }
            if (!free || !Requirement.all(b.viewRequirements(), player)) continue;
            for (int slot : b.slots()) {
                if (slot < total && next[slot] == null) next[slot] = b;
            }
        }

        Map<MenuButton, ItemStack> built = new IdentityHashMap<>();
        int top = menu.topSize();
        for (int slot = 0; slot < top; slot++) {
            // deposit zone: real items of the player, never overwritten
            if (menu.isInput(slot)) {
                next[slot] = null;
                continue;
            }
            ItemStack item = item(next[slot], player, built);
            ItemStack current = inventory.getItem(slot);
            if (current != null && current.getType().isAir()) current = null;
            if (!Objects.equals(current, item)) inventory.setItem(slot, item);
        }

        boolean bottomChanged = false;
        if (hidesInventory()) {
            ItemStack[] bukkit = new ItemStack[36];
            for (int i = 0; i < 36; i++) {
                bukkit[i] = item(next[top + i], player, built);
                if (!Objects.equals(bukkit[i], bottomBukkit[i])) bottomChanged = true;
            }
            if (bottomChanged) {
                com.github.retrooper.packetevents.protocol.item.ItemStack[] packet =
                        new com.github.retrooper.packetevents.protocol.item.ItemStack[36];
                for (int i = 0; i < 36; i++) {
                    packet[i] = bukkit[i] == null ? null : SpigotConversionUtil.fromBukkitItemStack(bukkit[i]);
                }
                bottomBukkit = bukkit;
                bottomPacket = packet;
            }
        }
        shown = next;
        return bottomChanged;
    }

    private ItemStack item(MenuButton b, Player player, Map<MenuButton, ItemStack> built) {
        if (b == null) return null;
        if (built.containsKey(b)) return built.get(b);
        long t = System.nanoTime();
        ItemStack item = items.build(b, player, muteTooltips);
        RenderTimings.add(RenderTimings.ITEMS, t);
        built.put(b, item);
        return item;
    }
}
