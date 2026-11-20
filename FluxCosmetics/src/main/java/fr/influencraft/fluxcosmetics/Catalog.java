package fr.influencraft.fluxcosmetics;

import fr.influencraft.flux.api.client.Appearance;
import fr.influencraft.flux.api.client.RecipeBook;
import fr.influencraft.flux.api.items.CustomItems;
import org.bukkit.inventory.EquipmentSlot;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The cosmetics catalog, shown in the recipe book of the inventory ({@link RecipeBook}): the green book button opens
 * the cosmetics instead of the recipes. The tab icons of the book are drawn by the client (nothing to do with
 * cosmetics), so everything sits in one tab and the book is browsed like folders: first one entry per slot (helmets,
 * chestplates...), a click opens it (its cosmetics, a "back" entry first), closing the inventory comes back to the
 * folders. A cosmetic is drawn craftable when the player has it; a click wears it (or takes it off), shift + click
 * shows it on him in the wardrobe.
 *
 * <p>A worn cosmetic is an item drawn in one equipment slot over his real one, for the others
 * ({@link Appearance}) and on the wardrobe's mannequin; his inventory and real armour never change.</p>
 *
 * <p>First version (2026-11-20): the cosmetics are those of the config, "unlocked" for everybody or nobody, and what
 * a player wears is kept in memory only (Flux's database later).</p>
 */
final class Catalog implements RecipeBook.Catalog, Listener {

    /** {@code item}: what is worn (a Flux item id or a material), {@code icon}: its picture in the book (null: the item). */
    record Cosmetic(String key, String name, String description, String item, String icon, EquipmentSlot slot,
                    boolean unlocked) {
    }

    /** The one tab everything sits in (axe and sword): the search tab shows it too. */
    private static final RecipeBook.Tab TAB = RecipeBook.Tab.EQUIPMENT;
    /** The folders, in this order. */
    private static final EquipmentSlot[] FOLDERS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    /**
     * Entry ids: the client lists the book by id, so "back" comes first, then the folders, then the cosmetics
     * ({@link #COSMETIC_ID} + their index).
     */
    private static final int BACK_ID = 0, FOLDER_ID = 1, COSMETIC_ID = 100;

    private final FluxCosmetics plugin;
    private final List<Cosmetic> cosmetics = new ArrayList<>();
    /** What each player wears, one cosmetic per slot (memory only, first version). */
    private final Map<UUID, Map<EquipmentSlot, String>> worn = new HashMap<>();
    /** The folder each player has open, none for the folders themselves. */
    private final Map<UUID, EquipmentSlot> open = new HashMap<>();

    Catalog(FluxCosmetics plugin) {
        this.plugin = plugin;
    }

    void load(ConfigurationSection section) {
        cosmetics.clear();
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection c = section.getConfigurationSection(key);
            if (c == null) continue;
            EquipmentSlot slot = switch (c.getString("slot", "head").toLowerCase(Locale.ROOT)) {
                case "chest" -> EquipmentSlot.CHEST;
                case "legs" -> EquipmentSlot.LEGS;
                case "feet" -> EquipmentSlot.FEET;
                default -> EquipmentSlot.HEAD;
            };
            String item = c.getString("item");
            if (item == null || item.isBlank()) {
                plugin.getLogger().warning("Cosmetic " + key + ": no item, left out");
                continue;
            }
            cosmetics.add(new Cosmetic(key, c.getString("name", key), c.getString("description", ""), item,
                    c.getString("icon"), slot, c.getBoolean("unlocked", true)));
        }
        if (cosmetics.isEmpty()) plugin.getLogger().warning("No cosmetics in config.yml: the recipe book will be empty");
    }

    @Override
    public List<RecipeBook.Entry> entries(Player player) {
        EquipmentSlot folder = open.get(player.getUniqueId());
        List<RecipeBook.Entry> out = new ArrayList<>();
        if (folder == null) {
            for (EquipmentSlot slot : FOLDERS) {
                List<Cosmetic> in = in(slot);
                if (!in.isEmpty()) out.add(new RecipeBook.Entry(FOLDER_ID + slot.ordinal(), folderIcon(player, slot, in), TAB, true, false));
            }
            return out;
        }
        var m = plugin.messages();
        out.add(new RecipeBook.Entry(BACK_ID, Items.dress(Items.of(m.raw("catalog.back-icon"), player), m.raw("catalog.back"),
                Lore.create().left(m.raw("catalog.back-click")).build(), player), TAB, true, false));
        Map<EquipmentSlot, String> mine = worn.getOrDefault(player.getUniqueId(), Map.of());
        for (int i = 0; i < cosmetics.size(); i++) {
            Cosmetic c = cosmetics.get(i);
            if (c.slot() != folder) continue;
            boolean wearing = c.key().equals(mine.get(c.slot()));
            out.add(new RecipeBook.Entry(COSMETIC_ID + i, icon(player, c, wearing), TAB, c.unlocked(), false));
        }
        return out;
    }

    private List<Cosmetic> in(EquipmentSlot slot) {
        return cosmetics.stream().filter(c -> c.slot() == slot).toList();
    }

    /** A folder: the picture of its first cosmetic, its name, how many it holds and which one he wears. */
    private ItemStack folderIcon(Player player, EquipmentSlot slot, List<Cosmetic> in) {
        var m = plugin.messages();
        String key = slot.name().toLowerCase(Locale.ROOT);
        Lore lore = Lore.create().kv(m.raw("catalog.folder-count"), in.size());
        String wearing = worn.getOrDefault(player.getUniqueId(), Map.of()).get(slot);
        in.stream().filter(c -> c.key().equals(wearing)).findFirst().ifPresent(c -> lore.kv(m.raw("catalog.folder-worn"), c.name()));
        lore.left(m.raw("catalog.folder-open"));
        Cosmetic first = in.getFirst();
        ItemStack base = first.icon() == null ? worn(first, player) : Items.of(first.icon(), player);
        return Items.dress(base, m.raw("catalog.folders." + key), lore.build(), player);
    }

    private ItemStack icon(Player player, Cosmetic c, boolean wearing) {
        var m = plugin.messages();
        Lore lore = Lore.create();
        if (!c.description().isBlank()) lore.description(c.description());
        if (wearing) lore.ok(m.raw("catalog.worn")).left(m.raw("catalog.take-off"));
        else if (c.unlocked()) lore.left(m.raw("catalog.wear"));
        else lore.ko(m.raw("catalog.locked"));
        lore.shiftLeft(m.raw("catalog.preview"));
        ItemStack base = c.icon() == null ? worn(c, player) : Items.of(c.icon(), player);
        ItemStack item = Items.dress(base, c.name(), lore.build(), player);
        if (wearing) item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return item;
    }

    @Override
    public void clicked(Player player, int id, boolean shift) {
        if (id == BACK_ID) {
            open.remove(player.getUniqueId());
            Sounds.cue(player, "page");
            RecipeBook.refresh(player);
            return;
        }
        if (id < COSMETIC_ID) {
            int ordinal = id - FOLDER_ID;
            for (EquipmentSlot slot : FOLDERS) if (slot.ordinal() == ordinal) open.put(player.getUniqueId(), slot);
            Sounds.cue(player, "page");
            RecipeBook.refresh(player);
            return;
        }
        id -= COSMETIC_ID;
        if (id < 0 || id >= cosmetics.size()) return;
        Cosmetic c = cosmetics.get(id);
        if (shift) {
            player.closeInventory();
            if (!plugin.wardrobe().active(player)) plugin.wardrobe().open(player);
            return;
        }
        if (!c.unlocked()) {
            Sounds.cue(player, "error");
            plugin.messages().send(player, "catalog.locked-message", "%name%", c.name());
            return;
        }
        Map<EquipmentSlot, String> mine = worn.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        if (c.key().equals(mine.get(c.slot()))) {
            mine.remove(c.slot());
            Appearance.set(player, c.slot(), null);
            Sounds.cue(player, "toggle-off");
            plugin.messages().send(player, "catalog.taken-off", "%name%", c.name());
        } else {
            mine.put(c.slot(), c.key());
            Appearance.set(player, c.slot(), worn(c, player));
            Sounds.cue(player, "toggle-on");
            plugin.messages().send(player, "catalog.wearing", "%name%", c.name());
        }
        RecipeBook.refresh(player);
    }

    /** The item worn for a cosmetic: a Flux item ({@code namespace:id}) or a material. */
    private static ItemStack worn(Cosmetic c, Player player) {
        String spec = c.item();
        boolean fluxId = spec.indexOf(':') > 0 && !spec.startsWith("itemsadder:") && !spec.startsWith("source:");
        ItemStack item = fluxId ? CustomItems.item(spec) : null;
        return item != null ? item : Items.of(spec, player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> RecipeBook.refresh(p), 2L);
    }

    /** His inventory (or a crafting table) closed: the book opens on the folders next time. */
    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p) || open.remove(p.getUniqueId()) == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> RecipeBook.refresh(p));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        open.remove(e.getPlayer().getUniqueId());
        worn.remove(e.getPlayer().getUniqueId());
        Appearance.clear(e.getPlayer());
    }
}
