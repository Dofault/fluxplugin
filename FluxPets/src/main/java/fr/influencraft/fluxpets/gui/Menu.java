package fr.influencraft.fluxpets.gui;

import fr.influencraft.fluxpets.FluxPets;
import fr.influencraft.fluxpets.Texts;
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

    protected final FluxPets plugin;
    private final Map<Integer, BiConsumer<Player, ClickType>> actions = new HashMap<>();
    private Inventory inventory;
    private final int rows;
    private final String title;

    protected Menu(FluxPets plugin, int rows, String title) {
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
            meta.lore(fr.influencraft.flux.api.text.Texts.wrapLore(lines));
            meta.addItemFlags(ItemFlag.values());
        });
        return item;
    }

    protected static ItemStack glow(ItemStack item, boolean glow) {
        item.editMeta(meta -> meta.setEnchantmentGlintOverride(glow ? Boolean.TRUE : null));
        return item;
    }

    protected void back(Menu target) {
        set(rows * 9 - 9, icon("arrow", t("back")), (p, c) -> target.open(p));
    }

    /** Boutons page précédente / suivante en bas du menu. */
    protected void pages(int page, int total, java.util.function.IntConsumer goTo) {
        int last = rows * 9 - 1;
        if (page > 0) {
            set(last - 5, icon("arrow", t("previous-page"),
                    t("page", "%page%", String.valueOf(page), "%pages%", String.valueOf(total))),
                    (p, c) -> goTo.accept(page - 1));
        }
        if (page < total - 1) {
            set(last - 3, icon("arrow", t("next-page"),
                    t("page", "%page%", String.valueOf(page + 2), "%pages%", String.valueOf(total))),
                    (p, c) -> goTo.accept(page + 1));
        }
    }

    protected static int pageCount(int size) {
        return Math.max(1, (size + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
    }

    /** Texte du menu d'édition (lang : editor.<key>). */
    protected String t(String key, String... replacements) {
        return plugin.text("editor." + key, replacements);
    }

    /** Lignes du menu d'édition (lang : editor.<key>). */
    protected List<String> lines(String key, String... replacements) {
        return plugin.texts("editor." + key, replacements);
    }

    /** Ligne d'aide d'action : clic gauche / droit, au format des menus du serveur. */
    protected String left(String action) {
        return t("click.left", "%action%", action);
    }

    protected String right(String action) {
        return t("click.right", "%action%", action);
    }

    protected String shift(String action) {
        return t("click.shift", "%action%", action);
    }

    /** Lignes concaténées (lore construit en plusieurs morceaux). */
    @SafeVarargs
    protected static List<String> join(List<String>... parts) {
        List<String> all = new ArrayList<>();
        for (List<String> part : parts) {
            all.addAll(part);
        }
        return all;
    }

    /** Clic droit : un exemplaire ; Maj + clic droit : une pile de 64. */
    protected static void giveCopies(Player player, ClickType click, ItemStack item) {
        ItemStack copy = item.clone();
        copy.setAmount(click == ClickType.SHIFT_RIGHT ? Math.min(64, copy.getMaxStackSize()) : 1);
        fr.influencraft.fluxpets.RevealManager.giveOrDrop(player, copy);
        fr.influencraft.flux.api.util.Sounds.cue(player, "click");
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
