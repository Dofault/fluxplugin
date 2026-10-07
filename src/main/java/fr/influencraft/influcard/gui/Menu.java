package fr.influencraft.influcard.gui;

import fr.influencraft.influcard.InfluCard;
import fr.influencraft.influcard.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** Menu d'édition : inventaire dont chaque case peut porter une action au clic. */
public abstract class Menu implements InventoryHolder {

    /** Cases de contenu d'une page (4 lignes de 7 au centre d'un menu de 6 lignes). */
    protected static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    protected final InfluCard plugin;
    private final Map<Integer, BiConsumer<Player, ClickType>> actions = new HashMap<>();
    private Inventory inventory;
    private final int rows;
    private final String title;

    protected Menu(InfluCard plugin, int rows, String title) {
        this.plugin = plugin;
        this.rows = rows;
        this.title = title;
    }

    /** Remplit le menu (appelé à chaque ouverture et rafraîchissement). */
    protected abstract void build(Player player);

    public void open(Player player) {
        inventory = Bukkit.createInventory(this, rows * 9, Texts.of(title));
        actions.clear();
        build(player);
        player.openInventory(inventory);
    }

    /** Reconstruit le menu en place (après une modification). */
    public void refresh(Player player) {
        open(player);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    void click(Player player, int slot, ClickType type) {
        BiConsumer<Player, ClickType> action = actions.get(slot);
        if (action != null) {
            action.accept(player, type);
        }
    }

    protected void set(int slot, ItemStack item, BiConsumer<Player, ClickType> action) {
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    // ---------------------------------------------------------------- fabrication d'icônes

    /** Icône : texture ItemsAdder (si id avec ':') ou matériau vanilla. */
    protected ItemStack icon(String material, String name, List<String> lore) {
        ItemStack item;
        if (material.contains(":")) {
            item = plugin.registry().base(material, Material.PAPER);
        } else {
            Material m = Material.matchMaterial(material);
            item = new ItemStack(m == null ? Material.PAPER : m);
        }
        return decorate(item, name, lore);
    }

    protected ItemStack icon(String material, String name, String... lore) {
        return icon(material, name, List.of(lore));
    }

    protected static ItemStack decorate(ItemStack item, String name, List<String> lore) {
        item.editMeta(meta -> {
            meta.displayName(Texts.of(name));
            List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
            for (String l : lore) {
                lines.add(Texts.of(l));
            }
            meta.lore(lines);
            meta.addItemFlags(ItemFlag.values());
        });
        return item;
    }

    protected static ItemStack glow(ItemStack item, boolean glow) {
        item.editMeta(meta -> meta.setEnchantmentGlintOverride(glow ? Boolean.TRUE : null));
        return item;
    }

    protected void back(Menu target) {
        set(rows * 9 - 9, icon("influitems:btn_back", "&r&7%img_left_click% &#FF7A59Retour"),
                (p, c) -> target.open(p));
    }

    /** Boutons page précédente / suivante en bas du menu. */
    protected void pages(int page, int total, java.util.function.IntConsumer goTo) {
        int last = rows * 9 - 1;
        if (page > 0) {
            set(last - 5, icon("influitems:btn_prev", "&r&7%img_left_click% &#FF7A59Page précédente",
                    "&#6B7A8FPage " + page + "/" + total), (p, c) -> goTo.accept(page - 1));
        }
        if (page < total - 1) {
            set(last - 3, icon("influitems:btn_next", "&r&7%img_left_click% &#FF7A59Page suivante",
                    "&#6B7A8FPage " + (page + 2) + "/" + total), (p, c) -> goTo.accept(page + 1));
        }
    }

    protected static int pageCount(int size) {
        return Math.max(1, (size + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
    }

    /** Ligne d'aide d'action : clic gauche / droit, au format des menus du serveur. */
    protected static String left(String action) {
        return "&r&7%img_left_click% &#FF7A59" + action;
    }

    protected static String right(String action) {
        return "&r&7%img_right_click% &#FF7A59" + action;
    }

    protected static String shift(String action) {
        return "&#6B7A8FMaj + clic &#FF7A59" + action;
    }

    /** Clic droit : un exemplaire ; Maj + clic droit : une pile de 64. */
    protected static void giveCopies(Player player, ClickType click, ItemStack item) {
        ItemStack copy = item.clone();
        copy.setAmount(click == ClickType.SHIFT_RIGHT ? Math.min(64, copy.getMaxStackSize()) : 1);
        fr.influencraft.influcard.RevealManager.giveOrDrop(player, copy);
        player.playSound(player, "entity.item.pickup", 0.6f, 1.2f);
    }

    /** Demande une saisie dans le tchat puis rouvre ce menu. */
    protected void ask(Player player, String question, java.util.function.Consumer<String> onAnswer) {
        player.closeInventory();
        plugin.prompts().ask(player, question, answer -> {
            onAnswer.accept(answer);
            refresh(player);
        }, () -> refresh(player));
    }
}
